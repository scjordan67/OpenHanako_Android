package com.hanaagent.core.llm

import com.hanaagent.core.mood.MoodEvent
import com.hanaagent.core.mood.MoodParser
import io.ktor.client.HttpClient
import io.ktor.client.request.headers
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 一次对话回合 —— 把散落的几块拼成产品真正跑的那条链路。
 *
 * ```
 * ChatPayload.build ─► HTTP ─► 字节流 ─► Utf8StreamDecoder ─► SseFramer
 *                                              ─► LlmStream.mapFrame ─► MoodParser ─► Event
 * ```
 *
 * 每一环都已经单独测过，但**串起来**才有几条只在接缝处出现的性质，这个类负责保证：
 *
 * - 内省块一个字都不能漏进正文。模型被人格要求先写内心独白，那段内容是给界面
 *   单独排版的，混进正文就等于把 Agent 的思考直接说了出来。
 * - 思考通道（Anthropic thinking / DeepSeek reasoning_content）同样不进正文。
 * - [Outcome.raw] 保留模型的**原始**输出（含内省标签），因为它要写进 JSONL；
 *   下次加载时重放一遍必须还原出同样的切分。存切好的正文会让内省块永久丢失。
 * - HTTP 层面的失败要变成人能看懂的一句话。默认情况下 401 只会表现为"没有回复"，
 *   用户完全无从判断是网络、是 key、还是模型名写错了。
 *
 * 这个类不碰磁盘也不碰界面：调用方拿到 [Outcome] 之后自己决定怎么落盘、怎么渲染。
 */
class ChatTurn(
    private val http: HttpClient,
    private val endpoint: Endpoint,
) {

    /**
     * 一个可对话的模型端点。
     *
     * @param baseUrl 不含路径的服务地址，例如 `https://api.anthropic.com`。
     *   具体路径由 [ChatPayload.path] 按 API 形态决定。
     */
    data class Endpoint(
        val api: ChatPayload.Api,
        val baseUrl: String,
        val model: String,
        val apiKey: String,
        val maxTokens: Int? = null,
        val temperature: Double? = null,
    )

    /** 送给界面的事件。正文与内省块是**两条**流，不要合并渲染。 */
    sealed interface Event {
        /** 正文增量 —— 已经剔除内省块与思考通道。 */
        data class Body(val text: String) : Event

        data object MoodStart : Event
        data class MoodText(val text: String) : Event
        data object MoodEnd : Event

        /** 思考通道增量。界面通常折叠显示，或者干脆不显示。 */
        data class Thinking(val text: String) : Event

        /** 失败。[message] 已尽量翻成人话。 */
        data class Failed(val message: String) : Event
    }

    /**
     * 回合结束后的完整结果。
     *
     * @param raw 模型的原始输出（含内省标签）。写进 JSONL 的是这一份。
     * @param body 剔除内省块后的正文。
     * @param mood 内省块的内容（不含标签）。
     * @param thinking 思考通道的内容。
     * @param error 非 null 表示这一回合没能正常完成。
     */
    data class Outcome(
        val raw: String,
        val body: String,
        val mood: String,
        val thinking: String,
        val error: String? = null,
    ) {
        val succeeded: Boolean get() = error == null
    }

    /**
     * 跑完一个回合。
     *
     * [emit] 会在流式过程中被同步调用多次；函数返回时流已经结束。
     * 失败不抛异常 —— 会先发一个 [Event.Failed]，再在 [Outcome.error] 里带回原因。
     * 抛异常的话调用方很容易漏掉「已经吐了半句正文」这个中间状态。
     */
    suspend fun run(
        systemPrompt: String,
        messages: List<ChatPayload.ChatMessage>,
        emit: (Event) -> Unit = {},
    ): Outcome {
        val payload = ChatPayload.build(
            api = endpoint.api,
            model = endpoint.model,
            systemPrompt = systemPrompt,
            messages = messages,
            maxTokens = endpoint.maxTokens,
            temperature = endpoint.temperature,
            stream = true,
        )

        val collector = Collector(endpoint.api, emit)

        try {
            http.preparePost(endpoint.baseUrl.trimEnd('/') + ChatPayload.path(endpoint.api)) {
                contentType(ContentType.Application.Json)
                headers {
                    for ((name, value) in ChatPayload.headers(endpoint.api, endpoint.apiKey)) {
                        // content-type 已经由 contentType() 设过，重复设会变成两个头
                        if (!name.equals("content-type", ignoreCase = true)) append(name, value)
                    }
                }
                setBody(payload.toString())
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    val body = runCatching { response.bodyAsText() }.getOrDefault("")
                    return@execute collector.fail(
                        describeHttpFailure(response.status.value, body),
                    )
                }

                val channel = response.bodyAsChannel()
                val buffer = ByteArray(READ_BUFFER_BYTES)
                while (!channel.isClosedForRead) {
                    val read = channel.readAvailable(buffer, 0, buffer.size)
                    if (read < 0) break
                    if (read == 0) continue
                    collector.onBytes(buffer, read)
                }
                collector.onStreamEnd()
            }
        } catch (failure: Throwable) {
            // 网络断开、超时、DNS —— 到这里都还没有可读的原因，翻译一下
            collector.fail(describeTransportFailure(failure))
        }

        return collector.finish()
    }

    /**
     * 逐字节推进的状态机。
     *
     * 单独抽出来是为了让 [run] 只剩「发请求、读字节」这两件事，也让这段纯逻辑
     * 可以脱离 HTTP 单独测（见 ChatTurnTest 里直接喂字节的用例）。
     */
    internal class Collector(
        private val api: ChatPayload.Api,
        private val emit: (Event) -> Unit,
    ) {
        private val decoder = Utf8StreamDecoder()
        private val framer = LlmStream.SseFramer()
        private val mood = MoodParser()

        private val raw = StringBuilder()
        private val body = StringBuilder()
        private val moodText = StringBuilder()
        private val thinking = StringBuilder()

        private var error: String? = null
        private var done = false

        fun onBytes(bytes: ByteArray, length: Int) {
            val text = decoder.decode(bytes, length)
            if (text.isEmpty()) return
            for (frame in framer.feed(text)) consume(frame)
        }

        fun onStreamEnd() {
            decoder.flush().takeIf { it.isNotEmpty() }?.let { tail ->
                for (frame in framer.feed(tail)) consume(frame)
            }
            for (frame in framer.flush()) consume(frame)
            // 流可能在内省块中途断掉；flush 会补上 MoodEnd，
            // 否则界面会永远停在"内省中"
            mood.flush(::onMoodEvent)
        }

        fun fail(message: String) {
            if (error == null) error = message
            emit(Event.Failed(message))
        }

        fun finish(): Outcome {
            if (!done) mood.flush(::onMoodEvent)
            return Outcome(
                raw = raw.toString(),
                body = body.toString(),
                mood = moodText.toString(),
                thinking = thinking.toString(),
                error = error,
            )
        }

        private fun consume(frame: String) {
            for (event in LlmStream.mapFrame(api, frame)) {
                when (event) {
                    is LlmStream.Event.TextDelta -> {
                        raw.append(event.text)
                        mood.feed(event.text, ::onMoodEvent)
                    }

                    is LlmStream.Event.ThinkingDelta -> {
                        thinking.append(event.text)
                        emit(Event.Thinking(event.text))
                    }

                    LlmStream.Event.Done -> {
                        if (!done) {
                            done = true
                            mood.flush(::onMoodEvent)
                        }
                    }

                    is LlmStream.Event.Error -> fail(event.message)
                }
            }
        }

        private fun onMoodEvent(event: MoodEvent) {
            when (event) {
                is MoodEvent.Text -> {
                    body.append(event.data)
                    emit(Event.Body(event.data))
                }

                MoodEvent.MoodStart -> emit(Event.MoodStart)

                is MoodEvent.MoodText -> {
                    moodText.append(event.data)
                    emit(Event.MoodText(event.data))
                }

                MoodEvent.MoodEnd -> emit(Event.MoodEnd)
            }
        }
    }

    companion object {
        /** 读缓冲大小。中文正文 3 字节一个字，这个大小几乎必然切在字符中间 —— 由 [Utf8StreamDecoder] 兜住。 */
        const val READ_BUFFER_BYTES = 8 * 1024

        private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

        /**
         * 把 HTTP 状态码翻成用户能据以行动的一句话。
         *
         * 默认表现（"没有回复"）对用户毫无信息量：分不清是网络、是 key 写错、
         * 还是模型名不对。这几种的处理方式完全不同。
         */
        internal fun describeHttpFailure(status: Int, body: String): String {
            val detail = extractErrorMessage(body)
            val hint = when (status) {
                401, 403 -> "API key 无效或没有权限"
                404 -> "接口地址或模型名不存在"
                413 -> "请求太大 —— 多半是图片或历史消息超了上限"
                429 -> "被限流了，等一会儿再试"
                in 500..599 -> "模型服务端出错，通常重试即可"
                else -> "请求失败"
            }
            return if (detail.isNullOrBlank()) "$hint（HTTP $status）" else "$hint（HTTP $status）：$detail"
        }

        internal fun describeTransportFailure(failure: Throwable): String {
            val name = failure::class.simpleName ?: "未知错误"
            val message = failure.message?.takeIf { it.isNotBlank() }
            return if (message == null) "连接失败：$name" else "连接失败：$message"
        }

        /** 从错误响应体里挖出人话。两家都是 `{"error":{"message":...}}` 形状。 */
        internal fun extractErrorMessage(body: String): String? {
            if (body.isBlank()) return null
            val obj = runCatching { lenientJson.parseToJsonElement(body).jsonObject }.getOrNull()
                ?: return body.take(200)
            val error = obj["error"]
            val message = when {
                error is JsonObject -> error["message"]?.jsonPrimitive?.contentOrNull()
                error != null -> error.jsonPrimitive.contentOrNull()
                else -> obj["message"]?.jsonPrimitive?.contentOrNull()
            }
            return message ?: body.take(200)
        }

        private fun kotlinx.serialization.json.JsonPrimitive.contentOrNull(): String? =
            if (isString) content else null
    }
}
