package com.hanaagent.core.llm

/**
 * 把用户填进设置页的那串地址整理成能直接用的端点配置。
 *
 * 这一层存在的唯一理由是：**没人知道该不该带 `/v1`**。同一个框里，
 * 有人粘 `https://api.deepseek.com`，有人粘 `https://api.deepseek.com/v1`，
 * 有人干脆把文档里那行完整的 `.../v1/chat/completions` 整个粘进来。三种都合理，
 * 而拼错的结果是 404 —— 界面上只会显示"接口地址或模型名不存在"，用户没法从中
 * 看出少了或多了一个路径段。
 *
 * 两家 API 的路径约定还不一样，更容易搞混：
 *
 * | | 完整地址 | [ChatPayload.path] | 所以 baseUrl 该是 |
 * |---|---|---|---|
 * | Anthropic | `https://api.anthropic.com/v1/messages` | `/v1/messages` | `https://api.anthropic.com` |
 * | OpenAI 兼容 | `https://api.openai.com/v1/chat/completions` | `/chat/completions` | `https://api.openai.com/v1` |
 *
 * 一个带 `/v1` 一个不带。指望用户记住这个区别是不现实的，所以在这里归一。
 */
object EndpointConfig {

    /** 已知的 Anthropic 官方主机。其余一律按 OpenAI 兼容处理。 */
    private val ANTHROPIC_HOSTS = setOf("api.anthropic.com", "anthropic.com")

    /** 用户可能整个粘进来的请求路径，识别到就剥掉。 */
    private val ENDPOINT_SUFFIXES = listOf(
        "/v1/messages",
        "/chat/completions",
        "/v1/chat/completions",
        "/completions",
    )

    data class Resolved(
        val api: ChatPayload.Api,
        val baseUrl: String,
        /** 拼出来的完整请求地址，仅供界面显示，让用户自己看一眼对不对。 */
        val fullUrl: String,
    )

    /**
     * 整理一个用户输入的地址。
     *
     * @param raw 用户填的原文
     * @param apiOverride 用户在设置里显式选了形态时传进来；null 表示按主机名推断
     * @return 整理结果；[raw] 空白或不像个地址时返回 null
     */
    fun resolve(raw: String, apiOverride: ChatPayload.Api? = null): Resolved? {
        var text = raw.trim()
        if (text.isEmpty()) return null

        // 没写协议时补 https —— 几乎没有人会用明文 http 调模型 API，
        // 而漏写协议是最常见的手滑
        if (!text.contains("://")) text = "https://$text"

        val schemeEnd = text.indexOf("://") + 3
        val scheme = text.substring(0, schemeEnd)
        var rest = text.substring(schemeEnd).trimEnd('/')
        if (rest.isEmpty()) return null

        // 剥掉用户整段粘进来的请求路径
        for (suffix in ENDPOINT_SUFFIXES) {
            if (rest.endsWith(suffix, ignoreCase = true)) {
                rest = rest.dropLast(suffix.length).trimEnd('/')
                break
            }
        }
        if (rest.isEmpty()) return null

        val host = rest.substringBefore('/').substringBefore(':').lowercase()
        val api = apiOverride
            ?: if (host in ANTHROPIC_HOSTS) ChatPayload.Api.ANTHROPIC_MESSAGES
            else ChatPayload.Api.OPENAI_COMPLETIONS

        var base = scheme + rest

        // OpenAI 兼容端点的路径里必须有版本段，Anthropic 则不能有 ——
        // 因为 path() 已经带了 /v1/messages
        if (api == ChatPayload.Api.OPENAI_COMPLETIONS && !rest.substringAfter(host, "").contains("/v")) {
            base = "$base/v1"
        }
        if (api == ChatPayload.Api.ANTHROPIC_MESSAGES && base.endsWith("/v1")) {
            base = base.dropLast(3)
        }

        return Resolved(api = api, baseUrl = base, fullUrl = base + ChatPayload.path(api))
    }

    /**
     * 配置是否完整到可以发请求。
     *
     * @return 缺什么的人话说明；都齐了返回 null
     */
    fun validate(raw: String, apiKey: String, model: String): String? = when {
        raw.isBlank() -> "还没填接口地址"
        resolve(raw) == null -> "接口地址看不出是个网址"
        apiKey.isBlank() -> "还没填 API key"
        model.isBlank() -> "还没填模型名"
        else -> null
    }
}
