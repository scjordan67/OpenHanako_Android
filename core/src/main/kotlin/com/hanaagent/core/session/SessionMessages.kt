package com.hanaagent.core.session

import com.hanaagent.core.llm.ChatPayload
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * 会话存储与模型消息之间的转换 —— 对话能不能接上，全看这一层。
 *
 * [SessionJsonl] 负责"哪些条目属于当前分支"，[ChatPayload] 负责"怎么发给模型"，
 * 中间缺的就是这一步：把投影出来的历史翻成模型消息，以及把这一轮的问答翻回
 * 可追加的 JSONL 条目。
 *
 * ## 内省块留在历史里
 *
 * 助手的历史消息**带着 `<mood>` 标签**送回模型，不剥。这是照着上游来的：
 * `stripInternalTags` 在上游只出现在 `lib/bridge/bridge-manager.ts`（发往钉钉这类
 * 第三方平台时的出站清洗），模型请求那条路径上没有任何地方剥它。
 *
 * 这么做也有道理 —— 人格模板要求模型开口前先写内心独白，而历史是最强的示范。
 * 如果把过去几轮的内省块都剥掉再送回去，模型看到的是"我以前都不写内心独白"，
 * 很快就不写了，MOOD 机制会慢慢失效，而且失效得毫无征兆。
 *
 * 需要不带内省块的文本（比如做摘要、导出）时用 [visibleText]，不要改这里。
 */
object SessionMessages {

    /**
     * 把投影出来的历史翻成模型消息。
     *
     * 内容为空的条目会被丢掉：模型对空 content 的反应各家不一，有的直接报 400。
     */
    fun toChatMessages(messages: List<SessionJsonl.Message>): List<ChatPayload.ChatMessage> =
        messages.mapNotNull { message ->
            val content = parseContent(message.content)
            if (content.isEmpty()) null else ChatPayload.ChatMessage(message.role, content)
        }

    /**
     * 解析一条消息的 content。
     *
     * JSONL 里 content 有两种形状：纯字符串（绝大多数），或者内容块数组（带图片时）。
     * 数组里的块同时兼容两家的形状 —— 会话文件可能是从桌面端导出的，那边用哪种
     * 取决于当时接的是哪个模型。
     */
    fun parseContent(content: JsonElement?): List<ChatPayload.ChatContent> = when (content) {
        null -> emptyList()

        is JsonPrimitive ->
            if (content.isString) {
                listOfNotNull(content.content.takeIf { it.isNotBlank() }?.let { ChatPayload.ChatContent.Text(it) })
            } else {
                emptyList()
            }

        is JsonArray -> content.mapNotNull { parseBlock(it) }

        else -> emptyList()
    }

    private fun parseBlock(block: JsonElement): ChatPayload.ChatContent? {
        // 数组里也可能直接是裸字符串
        if (block is JsonPrimitive && block.isString) {
            return block.content.takeIf { it.isNotBlank() }?.let { ChatPayload.ChatContent.Text(it) }
        }
        val obj = block as? JsonObject ?: return null

        return when (obj["type"]?.stringOrNull()) {
            "text" -> obj["text"]?.stringOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { ChatPayload.ChatContent.Text(it) }

            // Anthropic 形状：source.data 是裸 base64
            "image" -> {
                val source = obj["source"] as? JsonObject ?: return null
                val data = source["data"]?.stringOrNull() ?: return null
                val mime = source["media_type"]?.stringOrNull() ?: "image/png"
                ChatPayload.ChatContent.Image(data, mime)
            }

            // OpenAI 形状：image_url.url 是 data URL，要把前缀拆掉
            "image_url" -> {
                val url = (obj["image_url"] as? JsonObject)?.get("url")?.stringOrNull()
                    ?: obj["image_url"]?.stringOrNull()
                    ?: return null
                parseDataUrl(url)
            }

            else -> null
        }
    }

    /** `data:image/jpeg;base64,xxxx` → [ChatPayload.ChatContent.Image]。不是 data URL 就放弃。 */
    internal fun parseDataUrl(url: String): ChatPayload.ChatContent.Image? {
        if (!url.startsWith("data:")) return null
        val comma = url.indexOf(',').takeIf { it > 0 } ?: return null
        val header = url.substring(5, comma)
        if (!header.endsWith(";base64")) return null
        val mime = header.removeSuffix(";base64").ifEmpty { "image/png" }
        return url.substring(comma + 1).takeIf { it.isNotEmpty() }
            ?.let { ChatPayload.ChatContent.Image(it, mime) }
    }

    // ── 写回 JSONL ───────────────────────────────────────────

    /**
     * 构造一条用户消息的 JSONL 条目。
     *
     * [id] 与 [timestamp] 由调用方给，不在这里生成 —— 这样这个函数是纯的，
     * 测试里能拿到确定的输出，而 id 的生成策略（UUID 还是别的）也不被锁死在这。
     */
    fun userEntry(
        id: String,
        parentId: String?,
        text: String,
        images: List<ChatPayload.ChatContent.Image> = emptyList(),
        timestamp: String,
    ): JsonObject = messageEntry(id, parentId, "user", timestamp) {
        if (images.isEmpty()) {
            put("content", JsonPrimitive(text))
        } else {
            put(
                "content",
                buildJsonArray {
                    if (text.isNotBlank()) {
                        add(buildJsonObject { put("type", "text"); put("text", text) })
                    }
                    for (image in images) {
                        add(
                            buildJsonObject {
                                put("type", "image")
                                putJsonObject("source") {
                                    put("type", "base64")
                                    put("media_type", image.mimeType)
                                    put("data", image.base64)
                                }
                            },
                        )
                    }
                },
            )
        }
    }

    /**
     * 构造一条助手回复的 JSONL 条目。
     *
     * 存的是 [ChatTurn.Outcome.raw][com.hanaagent.core.llm.ChatTurn.Outcome.raw]
     * 那份**原始**输出（含内省标签），不是切好的正文 —— 下次加载时重放一遍
     * MoodParser 才能还原出同样的切分。存正文的话内省块就永久丢了。
     */
    fun assistantEntry(
        id: String,
        parentId: String?,
        rawText: String,
        timestamp: String,
    ): JsonObject = messageEntry(id, parentId, "assistant", timestamp) {
        put("content", JsonPrimitive(rawText))
    }

    private fun messageEntry(
        id: String,
        parentId: String?,
        role: String,
        timestamp: String,
        fillMessage: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
    ): JsonObject = buildJsonObject {
        put("id", id)
        put("parentId", parentId?.let { JsonPrimitive(it) } ?: JsonPrimitive(null as String?))
        put("type", "message")
        put("timestamp", timestamp)
        putJsonObject("message") {
            put("role", role)
            fillMessage()
        }
    }

    // ── 给界面/摘要用的纯文本 ────────────────────────────────

    /**
     * 一条消息里对用户可见的正文 —— 剥掉内省块，图片折成占位符。
     *
     * 用于会话列表的预览、摘要输入、导出。**不要**拿它去构造模型请求：
     * 那条路径要的是带内省块的原文，见类注释。
     */
    fun visibleText(message: SessionJsonl.Message): String =
        parseContent(message.content).joinToString("") { part ->
            when (part) {
                is ChatPayload.ChatContent.Text ->
                    com.hanaagent.core.memory.CompiledMemory.stripThinkTagBlocks(
                        stripInternalNarration(part.text),
                    )

                is ChatPayload.ChatContent.Image -> "［图片］"
            }
        }.trim()

    /** 剥掉成对的内省块与落单的标签 —— 与上游 `stripInternalNarration` 同义。 */
    internal fun stripInternalNarration(value: String): String {
        val tags = com.hanaagent.core.mood.InternalMoodBlock.TAGS.joinToString("|")
        return value
            .replace(Regex("<($tags)\\b[^>]*>[\\s\\S]*?</\\1>\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("</?(?:$tags)\\b[^>]*>\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    private fun JsonElement.stringOrNull(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.content
}
