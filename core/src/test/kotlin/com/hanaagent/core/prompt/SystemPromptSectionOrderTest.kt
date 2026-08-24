package com.hanaagent.core.prompt

import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 锁住 system prompt 的段落顺序与 cache 分界线。
 *
 * 对应上游的 `tests/agent-system-prompt-section-order.test.ts`。它守的东西是：
 * 用户档案 / 人格 / 样貌属于事件驱动的稳定段，必须留在静态前缀里；记忆与时间会被
 * 后台编译和时钟自动推动，必须留在分界线之后。
 *
 * 破坏这条约束不会有任何报错，只会让 prompt cache 命中率悄悄掉下去 —— 表现是
 * 每轮对话都变贵变慢，而且很难归因。所以要用断言钉住。
 */
class SystemPromptSectionOrderTest {

    private val now: ZonedDateTime =
        ZonedDateTime.of(2026, 6, 4, 15, 53, 0, 0, ZoneId.of("Asia/Shanghai"))

    private fun input(locale: String) = SystemPromptBuilder.Input(
        locale = locale,
        userName = if (locale.startsWith("zh")) "黎" else "Li",
        userProfile = "PROFILE-MARKER",
        persona = "PERSONA-TEMPLATE-MARKER",
        appearance = "APPEARANCE-MARKER",
        memoryEnabled = true,
        pinnedMemory = "PINNED-MARKER",
        memory = "MEMORY-MARKER",
        now = now,
        deviceDescription = "Test Tablet",
    )

    private fun assertOrdered(prompt: String, anchors: List<String>) {
        val indexes = anchors.map { anchor ->
            val at = prompt.indexOf(anchor)
            assertTrue(at >= 0, "prompt 里找不到锚点：$anchor")
            at
        }
        assertEquals(
            indexes.sorted(),
            indexes,
            "段落顺序被打乱了。锚点与位置：" + anchors.zip(indexes).joinToString(", "),
        )
    }

    @Test
    fun `中文：稳定段在前缀，记忆与时间在尾部`() {
        assertOrdered(
            SystemPromptBuilder(input("zh-CN")).build(),
            listOf(
                "# 执行环境",
                "# 用户档案",
                "PERSONA-TEMPLATE-MARKER",
                "APPEARANCE-MARKER",
                "## 行动纪律",
                "## 网页工具优先级",
                SystemPromptBuilder.CACHE_BOUNDARY_MARKER,
                "## 记忆使用规则",
                "# 置顶记忆",
                "MEMORY-MARKER",
                "Session started at:",
            ),
        )
    }

    @Test
    fun `英文：稳定段在前缀，记忆与时间在尾部`() {
        assertOrdered(
            SystemPromptBuilder(input("en")).build(),
            listOf(
                "# Environment",
                "# User Profile",
                "PERSONA-TEMPLATE-MARKER",
                "APPEARANCE-MARKER",
                "## Action Discipline",
                "## Web Tool Priority",
                SystemPromptBuilder.CACHE_BOUNDARY_MARKER,
                "## Memory Rules",
                "# Pinned Memories",
                "MEMORY-MARKER",
                "Session started at:",
            ),
        )
    }

    @Test
    fun `人格排在用户档案之后 —— 先说用户是谁，再说你是谁`() {
        // 人格模板里有「你和{{userName}}是认识很久的人」这类引用，
        // 顺序反过来的话模型读到人格时还不知道用户是谁
        val prompt = SystemPromptBuilder(input("zh-CN")).build()
        assertTrue(
            prompt.indexOf("# 用户档案") < prompt.indexOf("PERSONA-TEMPLATE-MARKER"),
            "人格段必须排在用户档案之后",
        )
    }

    @Test
    fun `分界线之前不出现任何会自动漂移的内容`() {
        val prompt = SystemPromptBuilder(input("zh-CN")).build()
        val boundary = prompt.indexOf(SystemPromptBuilder.CACHE_BOUNDARY_MARKER)
        assertTrue(boundary > 0, "找不到 cache 分界线")
        val prefix = prompt.substring(0, boundary)

        for (drifting in listOf("MEMORY-MARKER", "PINNED-MARKER", "Session started at:", "记忆使用规则")) {
            assertTrue(
                drifting !in prefix,
                "「$drifting」出现在了静态前缀里 —— 它会漂移，必须放到分界线之后",
            )
        }
    }

    @Test
    fun `分界线之后不出现稳定段 —— 否则白白撑大动态区`() {
        val prompt = SystemPromptBuilder(input("zh-CN")).build()
        val tail = prompt.substringAfter(SystemPromptBuilder.CACHE_BOUNDARY_MARKER)

        for (stable in listOf("PERSONA-TEMPLATE-MARKER", "PROFILE-MARKER", "APPEARANCE-MARKER", "# 执行环境")) {
            assertTrue(
                stable !in tail,
                "「$stable」出现在了动态尾部 —— 它是稳定段，应该留在前缀里",
            )
        }
    }

    // ── 内容契约 ──────────────────────────────────────────────

    @Test
    fun `记忆规则要求模型永不暴露记忆的存在`() {
        val prompt = SystemPromptBuilder(input("zh-CN")).build()
        // 这条规则是"有灵魂"体验的核心之一：记忆只影响角度语气，不出现在文字里
        assertTrue("我记得" in prompt, "缺少对「我记得」这类表述的禁止")
        assertTrue("你之前说过" in prompt)
        assertTrue("当前对话永远优先" in prompt, "缺少「对话优先于旧记忆」的规则")
    }

    @Test
    fun `明确告知没有文件系统与命令行 —— 省得模型去试`() {
        val zh = SystemPromptBuilder(input("zh-CN")).build()
        assertTrue("<no_filesystem>true</no_filesystem>" in zh)
        assertTrue("<no_shell>true</no_shell>" in zh)
        assertTrue("不要假装读过某个文件" in zh, "缺少禁止编造文件内容的约束")

        val en = SystemPromptBuilder(input("en")).build()
        assertTrue("never pretend to have read a file" in en)
    }

    @Test
    fun `网页工具只剩两级 —— 这个移植版没有可见浏览器`() {
        val prompt = SystemPromptBuilder(input("zh-CN")).build()
        assertTrue("web_search" in prompt)
        assertTrue("web_fetch" in prompt)
        assertTrue(
            "**browser**" !in prompt,
            "不应提到 browser 工具，这个版本没有它",
        )
    }

    @Test
    fun `日界线与记忆传送带用的是同一个 04-00`() {
        val zh = SystemPromptBuilder(input("zh-CN")).build()
        assertTrue("你的一天从 04:00 开始" in zh, "日界线说明缺失或小时数不对")
        val en = SystemPromptBuilder(input("en")).build()
        assertTrue("Your day starts at 04:00" in en)
    }

    @Test
    fun `会话时间被明确标为快照而非实时钟`() {
        // 不写这句的话，长会话里模型会拿开头的时间当"现在"
        val zh = SystemPromptBuilder(input("zh-CN")).build()
        assertTrue("不会随对话推进更新" in zh)
    }

    // ── 缺省与降级 ────────────────────────────────────────────

    @Test
    fun `关闭记忆时整块记忆 prompt 都不注入`() {
        val prompt = SystemPromptBuilder(input("zh-CN").copy(memoryEnabled = false)).build()
        assertTrue("记忆使用规则" !in prompt)
        assertTrue("MEMORY-MARKER" !in prompt)
        assertTrue("PINNED-MARKER" !in prompt)
        // 但人格和用户档案照常
        assertTrue("PERSONA-TEMPLATE-MARKER" in prompt)
    }

    @Test
    fun `没有记忆内容时不注入空的记忆段`() {
        val prompt = SystemPromptBuilder(
            input("zh-CN").copy(pinnedMemory = null, memory = "（暂无记忆）"),
        ).build()
        assertTrue("记忆使用规则" !in prompt, "空记忆不应注入规则段，那是纯噪声")
    }

    @Test
    fun `没配用户名时用中性称呼兜底`() {
        val zh = SystemPromptBuilder(input("zh-CN").copy(userName = null)).build()
        assertTrue("用户的名字叫：用户" in zh, "中文应兜底成「用户」")

        val en = SystemPromptBuilder(input("en").copy(userName = "  ")).build()
        assertTrue("The user's name is: User" in en, "英文应兜底成「User」")
    }

    @Test
    fun `没有样貌自述时不留空段`() {
        val prompt = SystemPromptBuilder(input("zh-CN").copy(appearance = null)).build()
        assertTrue("APPEARANCE-MARKER" !in prompt)
        // 顺序其余部分不受影响
        assertOrdered(
            prompt,
            listOf("# 用户档案", "PERSONA-TEMPLATE-MARKER", SystemPromptBuilder.CACHE_BOUNDARY_MARKER, "MEMORY-MARKER"),
        )
    }
}
