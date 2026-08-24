package com.hanaagent.core.search

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Spike D —— 搜索 URL 构造、降级阶梯、限流表与提取脚本资产。
 */
class BrowserSearchTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `搜索 URL 与上游逐字一致`() {
        val stream = javaClass.getResourceAsStream("/search-url-truth.json")
        assertNotNull(stream, "search-url-truth.json 缺失")
        val providers = stream.use { json.parseToJsonElement(it.readBytes().decodeToString()) }
            .jsonObject["providers"]!!.jsonObject

        val failures = mutableListOf<String>()
        for ((providerId, locales) in providers) {
            val provider = BrowserSearch.Provider.of(providerId)
            for ((localeKey, entry) in locales.jsonObject) {
                val locale = if (localeKey == "(none)") "" else localeKey
                val expected = entry.jsonObject["url"]!!.jsonPrimitive.content
                val actual = BrowserSearch.searchUrl(provider, "记忆传送带 memory", 5, locale)
                if (actual != expected) {
                    failures += "$providerId [$localeKey]\n  期望: $expected\n  实际: $actual"
                }
                val expectedUa = entry.jsonObject["loadOptions"]!!.jsonObject["userAgent"]!!.jsonPrimitive.content
                assertEquals(expectedUa, BrowserSearch.loadOptions(locale).userAgent, "$providerId 的 UA 不一致")
            }
        }
        assertTrue(failures.isEmpty(), "搜索 URL 与上游不一致：\n" + failures.joinToString("\n"))
    }

    @Test
    fun `locale 回落规则与上游一致`() {
        // 精确命中
        assertEquals("zh-CN", BrowserSearch.resolveLocale("zh-CN")?.mkt)
        assertEquals("zh-TW", BrowserSearch.resolveLocale("zh-TW")?.mkt)
        // 前缀回落
        assertEquals("zh-CN", BrowserSearch.resolveLocale("zh-Hans-CN")?.mkt)
        assertEquals("ja-JP", BrowserSearch.resolveLocale("ja-Latn")?.mkt)
        assertEquals("en-US", BrowserSearch.resolveLocale("en-GB")?.mkt)
        // 认不出来就不加 locale 参数
        assertEquals(null, BrowserSearch.resolveLocale("de-DE"))
        assertEquals(null, BrowserSearch.resolveLocale(""))
        assertEquals(null, BrowserSearch.resolveLocale(null))
    }

    @Test
    fun `必须用桌面 UA —— 否则拿到移动版页面选择器全落空`() {
        val ua = BrowserSearch.loadOptions("zh-CN").userAgent
        assertTrue("Windows NT" in ua, "UA 必须伪装成桌面浏览器：$ua")
        assertTrue("Android" !in ua, "UA 里不能出现 Android，否则搜索引擎给移动版页面：$ua")
    }

    @Test
    fun `Accept-Language 跟随 locale`() {
        assertEquals("zh-CN,zh;q=0.9,en;q=0.8", BrowserSearch.loadOptions("zh-CN").extraHeaders["Accept-Language"])
        assertEquals("ja-JP,ja;q=0.9,en;q=0.8", BrowserSearch.loadOptions("ja").extraHeaders["Accept-Language"])
        assertTrue(BrowserSearch.loadOptions("de-DE").extraHeaders.isEmpty())
    }

    @Test
    fun `maxResults 被钳制到 1 到 10`() {
        assertTrue(BrowserSearch.searchUrl(BrowserSearch.Provider.BING, "x", 0).contains("count=1"))
        assertTrue(BrowserSearch.searchUrl(BrowserSearch.Provider.BING, "x", 99).contains("count=10"))
        assertTrue(BrowserSearch.extractionScript(BrowserSearch.Provider.BING, 0).contains("const maxResults = 1;"))
        assertTrue(BrowserSearch.extractionScript(BrowserSearch.Provider.BING, 99).contains("const maxResults = 10;"))
    }

    @Test
    fun `提取脚本资产与锁文件一致 —— 防止有人手改选择器`() {
        val lock = javaClass.getResourceAsStream("/search-asset-lock.sha256")!!
            .use { it.readBytes().decodeToString() }
            .lineSequence().map { it.trim() }.filter { it.isNotEmpty() }
            .map { line -> line.split(Regex("\\s+"), 2).let { it[0] to it[1] } }
            .toList()

        assertEquals(3, lock.size, "三个 provider 的脚本都应被锁住")
        for ((expectedSha, fileName) in lock) {
            val bytes = javaClass.getResourceAsStream("/assets/search/$fileName")
                ?.use { it.readBytes() }
            assertNotNull(bytes, "提取脚本资产缺失：$fileName")
            val actual = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
            assertEquals(
                expectedSha, actual,
                "$fileName 被改动了。这段脚本是从上游逐字导出的，要更新请重跑 " +
                    "tools/search-extractors/generate.mjs 并同步锁文件，别手改选择器",
            )
        }
    }

    @Test
    fun `提取脚本形状正确且不含 Electron 专有 API`() {
        for (provider in BrowserSearch.Provider.entries) {
            val script = BrowserSearch.extractionScript(provider, 5)
            assertTrue(script.startsWith("(() => {"), "${provider.id} 应是自执行函数")
            assertTrue("__MAX_RESULTS__" !in script, "${provider.id} 的占位符没被替换")
            assertTrue("const engine = \"${provider.engine}\";" in script, "${provider.id} 的 engine 常量不对")
            // 这几个是 WebView.evaluateJavascript 能提供的全部依赖；出现别的说明脚本不再自包含
            for (forbidden in listOf("require(", "module.exports", "process.", "electron")) {
                assertTrue(forbidden !in script, "${provider.id} 含有 WebView 里不存在的依赖：$forbidden")
            }
            // 三家的空结果/验证码信号都要在
            assertTrue("hasCaptchaSignals" in script)
            assertTrue("hasNoResultsSignals" in script)
        }
    }

    @Test
    fun `auto 降级阶梯顺序与上游一致`() {
        // 没配任何 key：免费 API 打头，再退到三个浏览器档
        assertEquals(
            listOf("anysearch_free", "bing_browser", "google_browser", "duckduckgo_browser"),
            SearchProviders.autoChain(emptySet()),
        )
        // 配了 key 的 API 档排在最前，且保持 API_PROVIDERS 的声明顺序
        assertEquals(
            listOf("tavily", "brave", "anysearch_free", "bing_browser", "google_browser", "duckduckgo_browser"),
            SearchProviders.autoChain(setOf("brave", "tavily")),
        )
        // google 反爬最凶，排在浏览器档的中间而不是最前 —— 与上游一致
        val chain = SearchProviders.autoChain(emptySet())
        assertTrue(chain.indexOf("bing_browser") < chain.indexOf("google_browser"))
    }

    @Test
    fun `限流表与上游逐字一致`() {
        // 这些数字是拿真实封禁换来的，测试的作用是防止有人"顺手优化"
        with(SearchProviders.policyFor("google_browser")) {
            assertEquals(6_000L, minIntervalMs)
            assertEquals(8_000L, jitterMs)
            assertEquals(30_000L, rateLimitBaseDelayMs)
            assertEquals(10 * 60_000L, maxCooldownMs)
        }
        for (p in listOf("bing_browser", "duckduckgo_browser")) {
            with(SearchProviders.policyFor(p)) {
                assertEquals(3_000L, minIntervalMs)
                assertEquals(4_000L, jitterMs)
                assertEquals(10_000L, rateLimitBaseDelayMs)
                assertEquals(5 * 60_000L, maxCooldownMs)
            }
        }
        with(SearchProviders.policyFor("tavily")) {
            assertEquals(650L, minIntervalMs)
            assertEquals(350L, jitterMs)
        }
        // 未知 provider 按来源类型兜底：不在 BROWSER_PROVIDERS 里的一律按 API 档
        assertEquals(1_000L, SearchProviders.policyFor("unknown_api").minIntervalMs)
        assertEquals(2_000L, SearchProviders.policyFor("unknown_api").rateLimitBaseDelayMs)
        assertEquals(3_000L, SearchProviders.FALLBACK_POLICIES.getValue(SearchProviders.SourceType.BROWSER).minIntervalMs)
    }

    @Test
    fun `provider 分类判定正确`() {
        assertTrue(SearchProviders.requiresApiKey("tavily"))
        assertTrue(!SearchProviders.requiresApiKey("anysearch_free"))
        assertTrue(!SearchProviders.requiresApiKey("bing_browser"))
        assertEquals(SearchProviders.SourceType.BROWSER, SearchProviders.sourceTypeOf("google_browser"))
        assertEquals(SearchProviders.SourceType.API, SearchProviders.sourceTypeOf("tavily"))
        assertTrue(SearchProviders.isKnown("auto"))
        assertTrue(!SearchProviders.isKnown("bing"))
    }
}
