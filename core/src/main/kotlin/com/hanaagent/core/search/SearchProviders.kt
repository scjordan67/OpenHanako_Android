package com.hanaagent.core.search

/**
 * 搜索 provider 契约与限流策略 —— 上游 `shared/search-providers.ts` 与
 * `lib/tools/search-rate-limiter.ts` 的移植。
 */
object SearchProviders {

    const val AUTO = "auto"

    /** 需要 API key 的付费/注册档。 */
    val API_PROVIDERS: List<String> = listOf("anysearch", "tavily", "brave", "serper")

    /** 免费 API 档，不需要 key。 */
    val FREE_API_PROVIDERS: List<String> = listOf("anysearch_free")

    /** 浏览器档：不需要 key，靠真的加载结果页抓取。 */
    val BROWSER_PROVIDERS: List<String> = listOf("bing_browser", "google_browser", "duckduckgo_browser")

    enum class SourceType { API, BROWSER }

    fun sourceTypeOf(provider: String): SourceType =
        if (provider in BROWSER_PROVIDERS) SourceType.BROWSER else SourceType.API

    fun requiresApiKey(provider: String): Boolean = provider in API_PROVIDERS

    fun isKnown(provider: String): Boolean =
        provider == AUTO || provider in API_PROVIDERS ||
            provider in FREE_API_PROVIDERS || provider in BROWSER_PROVIDERS

    /**
     * `auto` 的降级阶梯 —— 与上游 `doAutoSearch` 一致。
     *
     * 顺序：已配置 key 的 API 档（按 [API_PROVIDERS] 的顺序）→ 免费 API → 三个浏览器档。
     * 每一级除了「报错」之外，「空结果」和「结果质量差」也会继续往下走；
     * 全部走完仍只有低质量结果时，返回第一个低质量结果而不是空。
     */
    fun autoChain(configuredApiKeys: Set<String>): List<String> =
        API_PROVIDERS.filter { it in configuredApiKeys } + FREE_API_PROVIDERS + BROWSER_PROVIDERS

    /**
     * 限流策略。
     *
     * **这些数字不要自己调。** 它们是上游拿真实封禁换来的经验值 ——
     * 尤其 google_browser 的 6s 间隔 + 8s 抖动 + 30s 起退避，改小会很快撞上
     * "unusual traffic" 验证页，而那时提取脚本只会返回 blocked，用户看到的是
     * "搜索用不了"。
     */
    data class RateLimitPolicy(
        val minIntervalMs: Long,
        val jitterMs: Long,
        val rateLimitBaseDelayMs: Long,
        val retryJitterMs: Long,
        val maxCooldownMs: Long,
        val maxConcurrent: Int = 1,
    )

    private const val DEFAULT_RETRY_JITTER_MS = 1_000L

    val POLICIES: Map<String, RateLimitPolicy> = mapOf(
        "anysearch" to RateLimitPolicy(0, 0, 2_000, DEFAULT_RETRY_JITTER_MS, 5 * 60_000, maxConcurrent = 5),
        "anysearch_free" to RateLimitPolicy(0, 0, 10_000, DEFAULT_RETRY_JITTER_MS, 5 * 60_000, maxConcurrent = 3),
        "bing_browser" to RateLimitPolicy(3_000, 4_000, 10_000, DEFAULT_RETRY_JITTER_MS, 5 * 60_000),
        "duckduckgo_browser" to RateLimitPolicy(3_000, 4_000, 10_000, DEFAULT_RETRY_JITTER_MS, 5 * 60_000),
        "google_browser" to RateLimitPolicy(6_000, 8_000, 30_000, DEFAULT_RETRY_JITTER_MS, 10 * 60_000),
        "brave" to RateLimitPolicy(1_100, 400, 2_000, DEFAULT_RETRY_JITTER_MS, 5 * 60_000),
        "tavily" to RateLimitPolicy(650, 350, 2_000, DEFAULT_RETRY_JITTER_MS, 5 * 60_000),
        "serper" to RateLimitPolicy(1_000, 500, 2_000, DEFAULT_RETRY_JITTER_MS, 5 * 60_000),
    )

    /** provider 没有专属策略时按来源类型兜底。 */
    val FALLBACK_POLICIES: Map<SourceType, RateLimitPolicy> = mapOf(
        SourceType.BROWSER to RateLimitPolicy(3_000, 4_000, 10_000, DEFAULT_RETRY_JITTER_MS, 5 * 60_000),
        SourceType.API to RateLimitPolicy(1_000, 500, 2_000, DEFAULT_RETRY_JITTER_MS, 5 * 60_000),
    )

    fun policyFor(provider: String): RateLimitPolicy =
        POLICIES[provider] ?: FALLBACK_POLICIES.getValue(sourceTypeOf(provider))
}
