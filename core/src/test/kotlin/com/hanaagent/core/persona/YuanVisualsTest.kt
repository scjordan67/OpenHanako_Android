package com.hanaagent.core.persona

import com.hanaagent.core.mood.InternalMoodBlock
import com.hanaagent.core.theme.CssColor
import com.hanaagent.core.theme.ThemeAssets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class YuanVisualsTest {

    @Test
    fun `三个源的符号与标签与上游一致`() {
        assertEquals("✿ MOOD", YuanVisuals.moodLabel("hanako"))
        assertEquals("❊ PULSE", YuanVisuals.moodLabel("butter"))
        assertEquals("◈ REFLECT", YuanVisuals.moodLabel("ming"))
    }

    @Test
    fun `标签与解析器认的标签对得上`() {
        // 界面标题写着 MOOD、解析器认的却是 pulse —— 这种不一致不会报错，
        // 只会让人觉得"这个源怎么标错了"
        for ((yuan, visual) in YuanVisuals.ALL) {
            val tag = visual.moodLabel.lowercase()
            assertTrue(
                tag in InternalMoodBlock.TAGS,
                "源 $yuan 的标签 ${visual.moodLabel} 不在解析器认得的集合 ${InternalMoodBlock.TAGS} 里",
            )
        }
    }

    @Test
    fun `认不出的源回落到 hanako`() {
        assertEquals("hanako", YuanVisuals.normalize(null))
        assertEquals("hanako", YuanVisuals.normalize(""))
        assertEquals("hanako", YuanVisuals.normalize("kong"))
        assertEquals("hanako", YuanVisuals.normalize("不存在"))
        // 大小写与空白不敏感
        assertEquals("butter", YuanVisuals.normalize("  BUTTER "))
    }

    @Test
    fun `hanako 的内省块跟随主题强调色，另外两个用固定色`() {
        // 上游 .moodWrapper[data-yuan="hanako"] { --mood-accent: var(--accent) }
        // 换主题时 hanako 的内省块颜色要跟着变，butter/ming 不变
        val warmPaper = ThemeAssets.tokensOf("warm-paper")["--accent"]!!
        val midnight = ThemeAssets.tokensOf("midnight")["--accent"]!!
        assertTrue(warmPaper != midnight, "两套主题的 --accent 应当不同，否则这条测不出东西")

        assertEquals(warmPaper, YuanVisuals.moodAccent("hanako", warmPaper))
        assertEquals(midnight, YuanVisuals.moodAccent("hanako", midnight))

        assertEquals("#5BA88C", YuanVisuals.moodAccent("butter", warmPaper))
        assertEquals("#5BA88C", YuanVisuals.moodAccent("butter", midnight))
        assertEquals("#8BA4B4", YuanVisuals.moodAccent("ming", warmPaper))
    }

    @Test
    fun `所有强调色都解析得出颜色`() {
        for ((yuan, visual) in YuanVisuals.ALL) {
            assertNotNull(CssColor.parse(visual.accent), "$yuan 的 accent ${visual.accent} 解析不出来")
        }
        for (themeId in ThemeAssets.themeIds) {
            val tokens = ThemeAssets.tokensOf(themeId)
            for (yuan in YuanVisuals.ALL.keys) {
                assertNotNull(
                    CssColor.parse(YuanVisuals.moodAccent(yuan, tokens["--accent"]!!), tokens),
                    "$themeId + $yuan 的内省块强调色解析不出来",
                )
            }
        }
    }

    @Test
    fun `内置源清单与人格资产一致`() {
        // PersonaAssets 里 kong 是占位（只有空的 yuan 层），没有视觉标识，
        // 所以这里是 BUILT_IN_YUAN 去掉 kong
        assertEquals(
            PersonaAssets.BUILT_IN_YUAN.filter { it != "kong" }.toSet(),
            YuanVisuals.ALL.keys,
        )
    }
}
