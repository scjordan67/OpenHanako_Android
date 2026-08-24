package com.hanaagent.core.search

import java.net.URLEncoder

/**
 * 浏览器搜索 —— 上游 `lib/browser/browser-search-extractors.cjs` 的移植。
 *
 * 这是 `auto` 降级阶梯的最后一级：不需要 API key，靠真的加载搜索结果页再从
 * DOM 里抓结果。桌面端用一个**一次性隐藏 WebContentsView**（不挂到可见窗口、
 * 独立 session 分区、静音、拒绝弹窗、用完关掉）；Android 侧对应一个不 attach
 * 到布局的 WebView，逐项映射见 `docs/spike-d-search.md`。
 *
 * **页内提取脚本不在这里重写。** 它写死了三家搜索引擎的 DOM 选择器、CAPTCHA
 * 信号和多语言"无结果"文案，是整条链路里最脆弱、最需要跟随上游更新的部分。
 * 脚本以逐字资产的形式放在 `assets/search/<provider>.js`，由
 * [extractionScript] 读出后只替换 `__MAX_RESULTS__` 一个占位符。
 */
object BrowserSearch {

    enum class Provider(val id: String, val engine: String, val baseUrl: String) {
        BING("bing_browser", "bing", "https://www.bing.com/search"),
        GOOGLE("google_browser", "google", "https://www.google.com/search"),
        DUCKDUCKGO("duckduckgo_browser", "duckduckgo", "https://duckduckgo.com/");

        companion object {
            fun of(id: String): Provider = entries.firstOrNull { it.id == id }
                ?: throw IllegalArgumentException("Unknown browser search provider: $id")
        }
    }

    /** 与上游 `SEARCH_LOCALE_PRESETS` 一致。 */
    data class LocalePreset(
        val mkt: String,
        val setlang: String,
        val cc: String,
        val acceptLanguage: String,
    )

    private val PRESETS: Map<String, LocalePreset> = mapOf(
        "zh" to LocalePreset("zh-CN", "zh-CN", "CN", "zh-CN,zh;q=0.9,en;q=0.8"),
        "zh-CN" to LocalePreset("zh-CN", "zh-CN", "CN", "zh-CN,zh;q=0.9,en;q=0.8"),
        "zh-TW" to LocalePreset("zh-TW", "zh-TW", "TW", "zh-TW,zh;q=0.9,en;q=0.8"),
        "ja" to LocalePreset("ja-JP", "ja-JP", "JP", "ja-JP,ja;q=0.9,en;q=0.8"),
        "ja-JP" to LocalePreset("ja-JP", "ja-JP", "JP", "ja-JP,ja;q=0.9,en;q=0.8"),
        "ko" to LocalePreset("ko-KR", "ko-KR", "KR", "ko-KR,ko;q=0.9,en;q=0.8"),
        "ko-KR" to LocalePreset("ko-KR", "ko-KR", "KR", "ko-KR,ko;q=0.9,en;q=0.8"),
        "en" to LocalePreset("en-US", "en-US", "US", "en-US,en;q=0.9"),
        "en-US" to LocalePreset("en-US", "en-US", "US", "en-US,en;q=0.9"),
    )

    /**
     * 桌面版 UA。搜索引擎会按 UA 给不同版式的页面，而提取脚本的选择器是照着
     * 桌面版写的 —— 在平板上尤其要注意：不改 UA 的话 WebView 默认报 Android，
     * 拿到的是移动版页面，选择器会全部落空。
     */
    const val DESKTOP_USER_AGENT: String =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    fun resolveLocale(locale: String?): LocalePreset? {
        val raw = locale?.trim().orEmpty()
        if (raw.isEmpty()) return null
        PRESETS[raw]?.let { return it }
        return when {
            raw.startsWith("zh") -> PRESETS["zh-CN"]
            raw.startsWith("ja") -> PRESETS["ja-JP"]
            raw.startsWith("ko") -> PRESETS["ko-KR"]
            raw.startsWith("en") -> PRESETS["en-US"]
            else -> null
        }
    }

    /** 加载页面时要设的 UA 与附加请求头。 */
    data class LoadOptions(val userAgent: String, val extraHeaders: Map<String, String>)

    fun loadOptions(locale: String?): LoadOptions {
        val preset = resolveLocale(locale)
        val headers = buildMap {
            preset?.acceptLanguage?.takeIf { it.isNotEmpty() }?.let { put("Accept-Language", it) }
        }
        return LoadOptions(DESKTOP_USER_AGENT, headers)
    }

    /**
     * 拼搜索 URL。maxResults 按上游钳制到 1..10。
     *
     * 参数顺序对得上上游：先按 provider 各自的 params() 顺序放，再逐个 set。
     */
    fun searchUrl(provider: Provider, query: String, maxResults: Int = 5, locale: String? = null): String {
        val limit = maxResults.coerceIn(1, 10)
        val params = LinkedHashMap<String, String>()
        when (provider) {
            Provider.BING -> {
                params["q"] = query.trim()
                params["count"] = limit.toString()
                resolveLocale(locale)?.let {
                    params["mkt"] = it.mkt
                    params["setlang"] = it.setlang
                    params["cc"] = it.cc
                }
            }
            Provider.GOOGLE -> {
                params["q"] = query.trim()
                params["num"] = limit.toString()
            }
            Provider.DUCKDUCKGO -> {
                params["q"] = query.trim()
                params["kl"] = "wt-wt"
            }
        }
        val queryString = params.entries.joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" }
        return "${provider.baseUrl}?$queryString"
    }

    /**
     * 读出页内提取脚本，替换 maxResults 占位符。
     *
     * 脚本本体是从上游逐字导出的资产，[BrowserSearchAssetLockTest] 用 sha256 锁着。
     */
    fun extractionScript(provider: Provider, maxResults: Int = 5): String {
        val limit = maxResults.coerceIn(1, 10)
        val raw = BrowserSearch::class.java
            .getResourceAsStream("/assets/search/${provider.id}.js")
            ?.use { it.readBytes().decodeToString() }
            ?: throw IllegalStateException("提取脚本资产缺失: ${provider.id}.js")
        return raw.replace("__MAX_RESULTS__", limit.toString())
    }

    /** URLSearchParams 的编码语义：空格是 `+`，`*` `!` `'` `(` `)` 不转义回来。 */
    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8)
            .replace("%21", "!")
            .replace("%27", "'")
            .replace("%28", "(")
            .replace("%29", ")")
            .replace("%7E", "~")
            .replace("*", "%2A")
}
