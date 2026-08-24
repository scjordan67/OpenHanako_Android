package com.hanaagent.core.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 流式响应解析 —— 把两家不同的 SSE 事件流归一成同一套事件。
 *
 * 上层（MoodParser、界面、会话存储）不应该知道当前接的是哪家模型，
 * 所以差异全部收口在这里。
 */
object LlmStream {

    private val json = Json { ignoreUnknownKeys = true }

    /** 归一后的流式事件。 */
    sealed interface Event {
        /** 正文增量。 */
        data class TextDelta(val text: String) : Event

        /** 思考增量（Anthropic 的 thinking / 各家的 reasoning 通道）。 */
        data class ThinkingDelta(val text: String) : Event

        /** 流正常结束。 */
        data object Done : Event

        /** 对端报错。[message] 已尽量提取成人话，原始载荷在 [raw]。 */
        data class Error(val message: String, val raw: String) : Event
    }

    // ── SSE 分帧 ─────────────────────────────────────────────

    /**
     * 增量式 SSE 分帧器。
     *
     * SSE 的帧以空行分隔，一帧里可能有多行 `data:`，要按换行拼起来。
     * 网络会在任意位置切断，所以必须是增量式的：喂进多少处理多少，
     * 剩下的半帧留在 buffer 里等下一块。
     *
     * 这与 [com.hanaagent.core.mood.MoodParser] 是同一类问题，
     * 也同样用「任意分片结果一致」来测。
     */
    class SseFramer {
        private var buffer = StringBuilder()

        /** 喂入一段字节解码后的文本，返回本次能凑齐的完整帧数据（已去掉 `data:` 前缀）。 */
        fun feed(chunk: String): List<String> {
            buffer.append(chunk)
            val frames = mutableListOf<String>()
            while (true) {
                val separator = findFrameSeparator(buffer) ?: break
                val frame = buffer.substring(0, separator.first)
                buffer = StringBuilder(buffer.substring(separator.second))
                extractData(frame)?.let { frames += it }
            }
            return frames
        }

        /** 流结束时调用：把最后一帧（可能没有以空行收尾）吐出来。 */
        fun flush(): List<String> {
            val rest = buffer.toString()
            buffer = StringBuilder()
            if (rest.isBlank()) return emptyList()
            return listOfNotNull(extractData(rest))
        }

        /** @return 帧结束位置到下一帧起始位置；找不到返回 null。 */
        private fun findFrameSeparator(text: CharSequence): Pair<Int, Int>? {
            for (index in 0 until text.length - 1) {
                if (text[index] == '\n' && text[index + 1] == '\n') return index to index + 2
                if (index < text.length - 3 &&
                    text[index] == '\r' && text[index + 1] == '\n' &&
                    text[index + 2] == '\r' && text[index + 3] == '\n'
                ) {
                    return index to index + 4
                }
            }
            return null
        }

        private fun extractData(frame: String): String? {
            val data = frame.lineSequence()
                .filter { it.startsWith("data:") }
                .map { it.removePrefix("data:").trimStart() }
                .joinToString("\n")
                .trim()
            return data.takeIf { it.isNotEmpty() }
        }
    }

    // ── 事件映射 ─────────────────────────────────────────────

    /**
     * 把一帧 SSE 数据映射成归一事件。
     *
     * @return 该帧对应的事件；这一帧不含可用信息时返回空列表（心跳、ping、
     *   以及各家自有的元数据帧都会走到这里）。
     */
    fun mapFrame(api: ChatPayload.Api, data: String): List<Event> {
        // OpenAI 用 [DONE] 显式收尾；Anthropic 没有这个哨兵
        if (data == "[DONE]") return listOf(Event.Done)

        val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull()
            ?: return emptyList()

        // 两家的错误载荷都是 { "error": { "message": ... } } 形状
        (obj["error"] as? JsonObject)?.let { error ->
            val message = error["message"]?.jsonPrimitive?.contentOrNull()
                ?: error.toString()
            return listOf(Event.Error(message, data))
        }

        return when (api) {
            ChatPayload.Api.ANTHROPIC_MESSAGES -> mapAnthropic(obj, data)
            ChatPayload.Api.OPENAI_COMPLETIONS -> mapOpenAi(obj)
        }
    }

    private fun mapAnthropic(obj: JsonObject, raw: String): List<Event> {
        return when (obj["type"]?.jsonPrimitive?.contentOrNull()) {
            "content_block_delta" -> {
                val delta = obj["delta"] as? JsonObject ?: return emptyList()
                when (delta["type"]?.jsonPrimitive?.contentOrNull()) {
                    "text_delta" -> delta["text"]?.jsonPrimitive?.contentOrNull()
                        ?.let { listOf(Event.TextDelta(it)) } ?: emptyList()

                    "thinking_delta" -> delta["thinking"]?.jsonPrimitive?.contentOrNull()
                        ?.let { listOf(Event.ThinkingDelta(it)) } ?: emptyList()

                    else -> emptyList()
                }
            }

            "message_stop" -> listOf(Event.Done)

            // 流中途出错时 Anthropic 会发一个 error 事件；上面的通用分支已覆盖，
            // 这里兜住 type=error 但载荷形状不同的情况
            "error" -> listOf(Event.Error("模型流中断", raw))

            else -> emptyList()
        }
    }

    private fun mapOpenAi(obj: JsonObject): List<Event> {
        val choices = (obj["choices"] as? kotlinx.serialization.json.JsonArray) ?: return emptyList()
        val events = mutableListOf<Event>()
        for (choice in choices) {
            val delta = (choice as? JsonObject)?.get("delta") as? JsonObject ?: continue
            delta["content"]?.jsonPrimitive?.contentOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?.let { events += Event.TextDelta(it) }
            // 推理内容通道：DeepSeek / Kimi 等把思考放在这里
            delta["reasoning_content"]?.jsonPrimitive?.contentOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?.let { events += Event.ThinkingDelta(it) }
        }
        return events
    }

    private fun JsonPrimitive.contentOrNull(): String? = if (isString) content else null

    /**
     * 把整段流式响应解析成事件序列。
     *
     * [chunks] 是网络层给出的文本分片，分片边界完全由网络决定 —— 这个函数不对
     * 分片粒度做任何假设。
     */
    fun parse(api: ChatPayload.Api, chunks: Sequence<String>): List<Event> {
        val framer = SseFramer()
        val events = mutableListOf<Event>()
        for (chunk in chunks) {
            for (frame in framer.feed(chunk)) events += mapFrame(api, frame)
        }
        for (frame in framer.flush()) events += mapFrame(api, frame)
        return events
    }

    /** 把事件序列里的正文拼起来，便于测试与非流式场景。 */
    fun collectText(events: List<Event>): String =
        events.filterIsInstance<Event.TextDelta>().joinToString("") { it.text }
}
