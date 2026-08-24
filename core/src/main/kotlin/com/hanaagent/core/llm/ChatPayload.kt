package com.hanaagent.core.llm

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 模型请求的载荷构造 —— 上游 `core/llm-client.ts` 与 `core/provider-media-serializer.ts` 的移植。
 *
 * 上游支持四种 API 形态，这里只做两种：`anthropic-messages` 和 `openai-completions`。
 * 另外两种（`openai-responses` / `openai-codex-responses`）是 OAuth 登录专用通道，
 * 这个移植版用 API key 直连，用不上。
 *
 * 内部统一用 [ChatContent] 这套规范块表示内容，序列化到各家形态在最后一步做 ——
 * 这样会话存储、记忆、界面都不需要知道当前用的是哪家模型。
 */
object ChatPayload {

    /** 支持的 API 形态。 */
    enum class Api(val id: String) {
        ANTHROPIC_MESSAGES("anthropic-messages"),
        OPENAI_COMPLETIONS("openai-completions"),
    }

    /** 规范内容块。图片以 base64 承载，不用 URL —— 平板上的图片来自相册，没有公网地址。 */
    sealed interface ChatContent {
        data class Text(val text: String) : ChatContent
        data class Image(val base64: String, val mimeType: String = "image/png") : ChatContent
    }

    data class ChatMessage(val role: String, val content: List<ChatContent>) {
        companion object {
            fun text(role: String, text: String) = ChatMessage(role, listOf(ChatContent.Text(text)))
        }
    }

    /**
     * 图片输入策略 —— 与上游 `MODEL_IMAGE_INPUT_POLICY` 逐字一致。
     *
     * 这些数字不要凭感觉改：单图 4.5MB 是 Pi SDK 图片路径用的余量，
     * 总预算 24MB 是为了把整个 JSON 请求压在常见的 32MB provider 上限之下 ——
     * 超了不会有友好报错，而是请求被对端直接拒掉。
     */
    object ImagePolicy {
        const val MAX_WIDTH = 2000
        const val MAX_HEIGHT = 2000
        const val MAX_IMAGE_BASE64_BYTES = (4.5 * 1024 * 1024).toLong()
        const val TOTAL_BASE64_BUDGET_BYTES = 24L * 1024 * 1024
        const val JPEG_QUALITY = 80

        /** 单张图是否超限。 */
        fun exceedsPerImageLimit(base64: String): Boolean =
            base64.toByteArray(Charsets.UTF_8).size > MAX_IMAGE_BASE64_BYTES

        /**
         * 校验整轮请求的图片总量。
         *
         * @return 超出预算时返回说明文字，未超出返回 null。调用方应把这句话如实告诉
         *   用户，而不是静默丢弃图片 —— 后者会让模型在看不见图的情况下硬答。
         */
        fun checkBudget(images: List<ChatContent.Image>): String? {
            var total = 0L
            for ((index, image) in images.withIndex()) {
                val size = image.base64.toByteArray(Charsets.UTF_8).size.toLong()
                if (size > MAX_IMAGE_BASE64_BYTES) {
                    return "第 ${index + 1} 张图片编码后 ${size / 1024} KB，超过单图上限 ${MAX_IMAGE_BASE64_BYTES / 1024} KB"
                }
                total += size
            }
            if (total > TOTAL_BASE64_BUDGET_BYTES) {
                return "本轮图片编码后共 ${total / 1024 / 1024} MB，超过总预算 ${TOTAL_BASE64_BUDGET_BYTES / 1024 / 1024} MB"
            }
            return null
        }
    }

    // ── 内容块序列化 ──────────────────────────────────────────

    /** Anthropic：图片是 `{type:"image", source:{type:"base64", media_type, data}}`。 */
    private fun anthropicBlock(content: ChatContent): JsonObject = when (content) {
        is ChatContent.Text -> buildJsonObject {
            put("type", "text")
            put("text", content.text)
        }

        is ChatContent.Image -> buildJsonObject {
            put("type", "image")
            putJsonObject("source") {
                put("type", "base64")
                put("media_type", content.mimeType)
                put("data", content.base64)
            }
        }
    }

    /** OpenAI Chat Completions：图片走 `image_url`，值是 data URL。 */
    private fun openAiBlock(content: ChatContent): JsonObject = when (content) {
        is ChatContent.Text -> buildJsonObject {
            put("type", "text")
            put("text", content.text)
        }

        is ChatContent.Image -> buildJsonObject {
            put("type", "image_url")
            putJsonObject("image_url") {
                put("url", "data:${content.mimeType};base64,${content.base64}")
            }
        }
    }

    private fun contentArray(api: Api, blocks: List<ChatContent>): JsonArray = buildJsonArray {
        for (block in blocks) {
            add(if (api == Api.ANTHROPIC_MESSAGES) anthropicBlock(block) else openAiBlock(block))
        }
    }

    // ── 请求构造 ──────────────────────────────────────────────

    /**
     * 构造一次对话请求。
     *
     * system prompt 的位置是两家最大的差异：Anthropic 有独立的顶层 `system` 字段，
     * OpenAI 要求把它作为第一条 `role: "system"` 消息塞进 messages。放错位置不会
     * 报错，只会让人格设定被当成普通对话内容，模型的表现会明显走样。
     */
    fun build(
        api: Api,
        model: String,
        systemPrompt: String,
        messages: List<ChatMessage>,
        maxTokens: Int? = null,
        temperature: Double? = null,
        stream: Boolean = true,
    ): JsonObject = buildJsonObject {
        put("model", model)
        put("stream", stream)
        temperature?.let { put("temperature", it) }

        when (api) {
            Api.ANTHROPIC_MESSAGES -> {
                // Anthropic 要求 max_tokens 必填，没有服务端默认值
                put("max_tokens", maxTokens ?: DEFAULT_ANTHROPIC_MAX_TOKENS)
                if (systemPrompt.isNotEmpty()) put("system", systemPrompt)
                putJsonArray("messages") {
                    for (message in messages) {
                        add(
                            buildJsonObject {
                                put("role", message.role)
                                put("content", contentArray(api, message.content))
                            },
                        )
                    }
                }
            }

            Api.OPENAI_COMPLETIONS -> {
                maxTokens?.let { put("max_tokens", it) }
                putJsonArray("messages") {
                    if (systemPrompt.isNotEmpty()) {
                        add(
                            buildJsonObject {
                                put("role", "system")
                                // system 消息用纯字符串：部分兼容实现不接受数组形式的 system
                                put("content", systemPrompt)
                            },
                        )
                    }
                    for (message in messages) {
                        add(
                            buildJsonObject {
                                put("role", message.role)
                                put("content", contentArray(api, message.content))
                            },
                        )
                    }
                }
            }
        }
    }

    /** 请求头。两家的认证方式不同，且 Anthropic 需要 API 版本头。 */
    fun headers(api: Api, apiKey: String): Map<String, String> = when (api) {
        Api.ANTHROPIC_MESSAGES -> mapOf(
            "x-api-key" to apiKey,
            "anthropic-version" to ANTHROPIC_API_VERSION,
            "content-type" to "application/json",
        )

        Api.OPENAI_COMPLETIONS -> mapOf(
            "Authorization" to "Bearer $apiKey",
            "content-type" to "application/json",
        )
    }

    /** 相对 baseUrl 的请求路径。 */
    fun path(api: Api): String = when (api) {
        Api.ANTHROPIC_MESSAGES -> "/v1/messages"
        Api.OPENAI_COMPLETIONS -> "/chat/completions"
    }

    /** Anthropic 没有服务端默认 max_tokens，必须给一个。 */
    const val DEFAULT_ANTHROPIC_MAX_TOKENS = 8192
    const val ANTHROPIC_API_VERSION = "2023-06-01"
}
