package com.hanaagent.core.theme

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 主题资产与颜色解析。
 *
 * 这个移植版存在的理由是「喜欢它的设计」，所以配色按资产处理、sha256 锁住。
 * 手抄的问题不是麻烦，是漂移了也没人看得出来 —— 没有测试会失败，只是用久了觉得
 * "好像哪里不太一样"。
 */
class ThemeAssetsTest {

    @Test
    fun `主题资产与锁文件一致 —— 防止有人手改配色`() {
        val lock = javaClass.getResourceAsStream("/theme-asset-lock.sha256")!!
            .use { it.readBytes().decodeToString() }
            .trim().split(Regex("\\s+"))
        assertEquals(
            lock[0], ThemeAssets.sha256(),
            "主题资产被改动了。要跟随上游更新请重跑 tools/themes/generate.mjs 并同步锁文件，" +
                "别手改颜色值",
        )
    }

    @Test
    fun `11 套主题齐全，默认是暖纸`() {
        assertEquals(11, ThemeAssets.themeIds.size, "上游是 11 套：${ThemeAssets.themeIds}")
        assertEquals("warm-paper", ThemeAssets.defaultTheme)
        // 跟随系统时浅色/深色各用哪套
        assertEquals("warm-paper", ThemeAssets.autoLightDefault)
        assertEquals("midnight", ThemeAssets.autoDarkDefault)
    }

    @Test
    fun `只有两套 midnight 是暗色`() {
        val dark = ThemeAssets.themeIds.filter { ThemeAssets.theme(it)!!.dark }
        assertEquals(listOf("midnight", "midnight-contrast"), dark.sorted())
    }

    @Test
    fun `每套主题都拿到完整 token —— 基线叠加覆盖`() {
        // 主题文件本身只写 35–43 个 token，直接用会缺一大半，
        // 界面上表现为一堆元素没有颜色
        val baselineSize = ThemeAssets.baseline.size
        assertTrue(baselineSize >= 80, "基线 token 只有 $baselineSize 个，导出可能不完整")

        for (id in ThemeAssets.themeIds) {
            val tokens = ThemeAssets.tokensOf(id)
            assertTrue(
                tokens.size >= baselineSize,
                "$id 合并后只有 ${tokens.size} 个 token，少于基线 $baselineSize —— 合并逻辑有问题",
            )
            for (key in listOf("--bg", "--text", "--accent", "--border", "--mood-bg")) {
                assertTrue(key in tokens, "$id 缺少 token $key")
            }
        }
    }

    @Test
    fun `覆盖确实生效 —— 各主题的背景色互不相同`() {
        val backgrounds = ThemeAssets.themeIds.associateWith { ThemeAssets.tokensOf(it)["--bg"] }
        // 如果合并写反了（基线覆盖主题），11 套的 --bg 会全都一样
        assertTrue(
            backgrounds.values.toSet().size >= 10,
            "各主题背景色几乎相同，覆盖顺序大概率写反了：$backgrounds",
        )
        assertEquals("#F8F4ED", backgrounds["warm-paper"])
    }

    @Test
    fun `注册表声明的背景色与 token 里的 bg 一致`() {
        // 两处来源，对不上说明导出时错位了
        for (id in ThemeAssets.themeIds) {
            val theme = ThemeAssets.theme(id)!!
            assertEquals(
                theme.backgroundColor.lowercase(),
                theme.tokens["--bg"]?.lowercase(),
                "$id 的注册表背景色与 --bg 不一致",
            )
        }
    }

    @Test
    fun `不存在的主题明确报错而不是给个空表`() {
        assertNull(ThemeAssets.theme("不存在的主题"))
        val error = kotlin.runCatching { ThemeAssets.tokensOf("不存在的主题") }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun `每套主题的关键颜色都解析得出来`() {
        // 解析不出来的话界面上就是一片默认色，而且不会报错
        for (id in ThemeAssets.themeIds) {
            val tokens = ThemeAssets.tokensOf(id)
            for (key in listOf("--bg", "--text", "--accent", "--border")) {
                assertNotNull(
                    CssColor.parse(tokens[key]!!, tokens),
                    "$id 的 $key = ${tokens[key]} 解析不出颜色",
                )
            }
        }
    }
}

/** CSS 颜色解析。token 的值不全是颜色，解析不了要明确返回 null。 */
class CssColorTest {

    @Test
    fun `六位十六进制`() {
        assertEquals(0xFFF8F4ED.toInt(), CssColor.parse("#F8F4ED"))
        assertEquals(0xFF000000.toInt(), CssColor.parse("#000000"))
    }

    @Test
    fun `三位十六进制每位翻倍`() {
        assertEquals(0xFFFFFFFF.toInt(), CssColor.parse("#fff"))
        assertEquals(0xFFAABBCC.toInt(), CssColor.parse("#abc"))
    }

    @Test
    fun `八位十六进制的 alpha 在末尾 —— 与 ARGB 顺序相反`() {
        // CSS 是 #rrggbbaa，ARGB 是 0xAARRGGBB。搞反了颜色会整个错位，
        // 而且错得"看起来像另一套配色"而不是明显的乱码
        assertEquals(0x80112233.toInt(), CssColor.parse("#11223380"))
    }

    @Test
    fun `rgb 与 rgba`() {
        assertEquals(0xFF0A141E.toInt(), CssColor.parse("rgb(10, 20, 30)"))
        assertEquals(0x800A141E.toInt(), CssColor.parse("rgba(10, 20, 30, 0.502)"))
        assertEquals(0xFF0A141E.toInt(), CssColor.parse("rgba(10, 20, 30, 1)"))
        assertEquals(0x000A141E, CssColor.parse("rgba(10, 20, 30, 0)"))
    }

    @Test
    fun `rgba 里的 var 被展开 —— 上游大量用这个写法`() {
        // --accent-rgb: 193, 116, 92  →  rgba(var(--accent-rgb), 0.5)
        val tokens = mapOf("--accent-rgb" to "193, 116, 92")
        assertEquals(0x80C1745C.toInt(), CssColor.parse("rgba(var(--accent-rgb), 0.502)", tokens))
    }

    @Test
    fun `整体是 var 时跟着指过去`() {
        val tokens = mapOf("--accent" to "#C1745C", "--x" to "var(--accent)")
        assertEquals(0xFFC1745C.toInt(), CssColor.parse("var(--x)", tokens))
    }

    @Test
    fun `var 成环时放弃而不是无限递归`() {
        val tokens = mapOf("--a" to "var(--b)", "--b" to "var(--a)")
        assertNull(CssColor.parse("var(--a)", tokens))
    }

    @Test
    fun `指向不存在的 token 返回 null`() {
        assertNull(CssColor.parse("var(--没有这个)", emptyMap()))
        assertNull(CssColor.parse("rgba(var(--没有这个), 0.5)", emptyMap()))
    }

    @Test
    fun `不是颜色的 token 值返回 null 而不是硬凑一个`() {
        // 基线里混着间距和纹理；悄悄给个黑色比返回 null 难查得多
        assertNull(CssColor.parse("0.5rem"))
        assertNull(CssColor.parse("url(data:image/svg+xml;base64,AAA)"))
        assertNull(CssColor.parse(""))
        assertNull(CssColor.parse("#12345"))
    }

    @Test
    fun `裸三元组不当成颜色`() {
        // 193, 116, 92 在 CSS 里从来不单独用，只作为 rgba() 的参数。
        // 单独解析出来大概率是调用方用错了 token
        assertNull(CssColor.parse("193, 116, 92"))
    }

    @Test
    fun `百分号写法`() {
        assertEquals(0xFFFF8000.toInt(), CssColor.parse("rgb(100%, 50.2%, 0%)"))
        assertEquals(0x80FFFFFF.toInt(), CssColor.parse("rgba(255, 255, 255, 50.2%)"))
    }
}
