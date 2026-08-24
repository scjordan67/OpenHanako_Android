package com.hanaagent.core.persona

import com.hanaagent.core.mood.MoodEvent
import com.hanaagent.core.mood.MoodParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 三层人格合成。
 *
 * 这一步的失败模式很隐蔽：少一层或占位符没替换，Agent 照样能聊天，
 * 只是不再是原来那个人 —— 没有内心独白、或者张口闭口叫用户「{{userName}}」。
 */
class PersonaComposerTest {

    private fun compose(
        yuan: String = PersonaAssets.DEFAULT_YUAN,
        locale: String = "zh",
        publicVariant: Boolean = false,
    ) = PersonaComposer.compose(
        yuan = yuan,
        userName = "黎",
        agentName = "花子",
        agentId = "hana",
        locale = locale,
        publicVariant = publicVariant,
    )

    @Test
    fun `三层按 identity 源 AGENTS 的顺序拼接`() {
        val composed = compose()
        val identity = PersonaAssets.read(PersonaAssets.Layer.IDENTITY, "hanako")!!
            .replace("{{agentName}}", "花子").replace("{{userName}}", "黎")
        val agents = PersonaAssets.read(PersonaAssets.Layer.AGENTS, "hanako")!!

        val identityAt = composed.indexOf(identity.trim().lines().first())
        val yuanAt = composed.indexOf("## MOOD")
        val agentsAt = composed.indexOf(agents.trim().lines().first())

        assertTrue(identityAt >= 0 && yuanAt >= 0 && agentsAt >= 0, "三层都应出现在合成结果里")
        assertTrue(identityAt < yuanAt, "identity 应排在源之前")
        assertTrue(yuanAt < agentsAt, "源应排在 AGENTS.md 之前")
    }

    @Test
    fun `占位符被替换干净`() {
        val composed = compose()
        assertTrue(
            !PersonaComposer.hasUnfilledPlaceholders(composed),
            "仍有未替换的占位符，模型会照字面念出来：" +
                Regex("\\{\\{\\s*\\w+\\s*}}").findAll(composed).map { it.value }.toList(),
        )
        assertTrue("黎" in composed, "用户名没被填进去")
        assertTrue("花子" in composed, "Agent 名没被填进去")
        assertTrue("{{userName}}" !in composed)
        assertTrue("{{agentName}}" !in composed)
    }

    @Test
    fun `内省协议完整进入合成结果 —— 这是 MOOD 机制成立的前提`() {
        val composed = compose()
        for (marker in listOf("## MOOD", "<mood>", "</mood>", "Vibe", "Sparks", "Reflections", "Will")) {
            assertTrue(marker in composed, "合成结果里缺少内省协议标记：$marker")
        }
    }

    @Test
    fun `合成出的人格能驱动 MoodParser 认出标签`() {
        // 端到端的意义：源模板里规定用什么标签，MoodParser 就必须认得同一个标签。
        // 两边一旦漂移，模型照规定输出了内省块，解析器却当成正文。
        val composed = compose()
        val tagInPersona = InternalMoodTagFrom(composed)
        assertTrue(
            tagInPersona in com.hanaagent.core.mood.InternalMoodBlock.TAGS,
            "源模板要求的标签 <$tagInPersona> 不在 MoodParser 认得的集合里",
        )

        val parser = MoodParser()
        val events = mutableListOf<MoodEvent>()
        parser.feed("<$tagInPersona>内心</$tagInPersona>正文") { events += it }
        parser.flush { events += it }
        assertTrue(events.any { it is MoodEvent.MoodStart }, "解析器没认出源模板规定的标签")
    }

    /** 从人格文本里找出它要求使用的内省标签。 */
    private fun InternalMoodTagFrom(persona: String): String =
        com.hanaagent.core.mood.InternalMoodBlock.TAGS.first { "<$it>" in persona }

    @Test
    fun `三个源各自合成出自己的内省协议`() {
        val expected = mapOf("hanako" to "mood", "butter" to "pulse", "ming" to "reflect")
        for ((yuan, tag) in expected) {
            val composed = compose(yuan = yuan)
            assertTrue("<$tag>" in composed, "源 $yuan 应使用 <$tag> 标签")
        }
    }

    @Test
    fun `英文 locale 合成英文人格`() {
        val zh = compose(locale = "zh-CN")
        val en = compose(locale = "en-US")
        assertTrue(zh != en, "中英文人格不应是同一份")
        assertTrue(!PersonaComposer.hasUnfilledPlaceholders(en), "英文模板的占位符也要替换干净")
    }

    @Test
    fun `公开版用另一套行为守则`() {
        val private = compose(publicVariant = false)
        val public = compose(publicVariant = true)
        assertTrue(private != public, "公开版与私有版的 AGENTS.md 应当不同")
        // 但 identity 与源两层是一样的
        assertTrue("## MOOD" in public, "公开版仍应带完整的内省协议")
    }

    @Test
    fun `缺失的源直接报错而不是装配出半个人格`() {
        val error = assertFailsWith<IllegalStateException> {
            PersonaComposer.compose(
                yuan = "不存在的源",
                userName = "黎",
                agentName = "花子",
                agentId = "hana",
            )
        }
        assertTrue("人格资产缺失" in error.message.orEmpty())
    }

    @Test
    fun `kong 只有源这一层，合成时明确报错`() {
        // kong 在上游是占位（yuan/kong.md 内容为空，且没有 identity / AGENTS 层）。
        // 这里要的是明确失败，而不是悄悄合成出一个没有身份和守则的人格。
        assertFailsWith<IllegalStateException> {
            PersonaComposer.compose(
                yuan = "kong",
                userName = "黎",
                agentName = "花子",
                agentId = "hana",
            )
        }
    }

    @Test
    fun `合成结果可直接喂给 SystemPromptBuilder`() {
        val composed = compose()
        val prompt = com.hanaagent.core.prompt.SystemPromptBuilder(
            com.hanaagent.core.prompt.SystemPromptBuilder.Input(
                locale = "zh-CN",
                userName = "黎",
                persona = composed,
                now = java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Shanghai")),
            ),
        ).build()

        assertTrue("## MOOD" in prompt, "内省协议应随人格一起进入 system prompt")
        assertTrue(
            !PersonaComposer.hasUnfilledPlaceholders(prompt),
            "最终 prompt 里不应残留占位符",
        )
        // 人格必须落在 cache 分界线之前（它是稳定段）
        val boundary = prompt.indexOf(com.hanaagent.core.prompt.SystemPromptBuilder.CACHE_BOUNDARY_MARKER)
        assertTrue(prompt.indexOf("## MOOD") < boundary, "人格属于静态前缀，不该落在分界线之后")
    }
}
