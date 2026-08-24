package com.hanaagent.core.memory

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 记忆传送带里零 LLM 的那一半。
 *
 * 这部分完全确定性——没有模型参与，输入相同输出必然相同——也是整条链路里唯一能在
 * 没有 API key 的情况下完整验证的部分，所以测得实一点。
 */
class CompiledMemoryTest {

    private val tempDirs = mutableListOf<File>()

    private fun tempDir(): File =
        Files.createTempDirectory("hana-memory-test").toFile().also { tempDirs += it }

    @AfterTest
    fun cleanup() {
        for (dir in tempDirs) dir.deleteRecursively()
        tempDirs.clear()
    }

    private fun dailyDir(vararg entries: Pair<String, String>): File {
        val dir = tempDir()
        for ((date, content) in entries) File(dir, "$date.md").writeText(content)
        return dir
    }

    // ── 思考标签剥离 ─────────────────────────────────────────

    @Test
    fun `剥掉完整的思考块`() {
        assertEquals("正文", CompiledMemory.stripThinkTagBlocks("<think>先想想</think>正文"))
        assertEquals("正文", CompiledMemory.stripThinkTagBlocks("<thinking>先想想</thinking>正文"))
        // 大小写不敏感
        assertEquals("正文", CompiledMemory.stripThinkTagBlocks("<THINK>先想想</THINK>正文"))
    }

    @Test
    fun `开头未闭合的思考块被剥掉`() {
        // 模型输出到一半被中断，留下一个没闭合的 <think>
        assertEquals("", CompiledMemory.stripThinkTagBlocks("<think>想到一半就断了"))
        assertEquals("", CompiledMemory.stripThinkTagBlocks("  \n<thinking>前面有空白也算"))
    }

    @Test
    fun `正文中间未闭合的思考标签不剥 —— 与上游一致`() {
        // 上游第二条正则锚定在 `^\s*<think>`，只处理"整段输出以未闭合思考开头"。
        // 中间出现的未闭合标签保持原样：那个位置的 `<think>` 也可能是记忆正文本身
        // 引用了这个字符串，无差别剥到行尾会吃掉真实内容。
        // 这是刻意保留的上游行为，不是遗漏。
        assertEquals(
            "前面的正文<think>没闭合",
            CompiledMemory.stripThinkTagBlocks("前面的正文<think>没闭合"),
        )
    }

    @Test
    fun `孤立的闭标签也剥掉`() {
        assertEquals("正文", CompiledMemory.stripThinkTagBlocks("</think>正文"))
    }

    // ── 段落归一 ─────────────────────────────────────────────

    @Test
    fun `模型自作主张加的标题被去掉`() {
        // memory.md 自己用 ## 分四段；段内再加标题会打乱层级，
        // 拼进 system prompt 后视觉上会把后面的内容收进那个子标题下
        val input = """
            ### 今天的事
            和用户聊了平板移植
            #### 细节
            决定用 Kotlin 重写
        """.trimIndent()
        assertEquals("和用户聊了平板移植\n决定用 Kotlin 重写", CompiledMemory.normalizeSectionBody(input))
    }

    @Test
    fun `JSON 字符串数组被摊成 markdown 列表`() {
        // 模型偶尔会把结果包成 JSON 数组返回
        assertEquals(
            "- 用户喜欢暖纸主题\n- 平板是 Android 设备",
            CompiledMemory.normalizeSectionBody("""["用户喜欢暖纸主题", "平板是 Android 设备"]"""),
        )
        // 数组里的空串被丢掉
        assertEquals("- 只有这条", CompiledMemory.normalizeSectionBody("""["只有这条", "  "]"""))
    }

    @Test
    fun `不是字符串数组的 JSON 原样保留`() {
        // 数字数组、对象都不该被当成列表处理
        val numbers = "[1, 2, 3]"
        assertEquals(numbers, CompiledMemory.normalizeSectionBody(numbers))
        val obj = """{"a":1}"""
        assertEquals(obj, CompiledMemory.normalizeSectionBody(obj))
    }

    @Test
    fun `连续空行被压缩`() {
        assertEquals("第一行\n\n第二行", CompiledMemory.normalizeSectionBody("第一行\n\n\n\n\n第二行"))
    }

    @Test
    fun `空输入归一成空串`() {
        assertEquals("", CompiledMemory.normalizeSectionBody(null))
        assertEquals("", CompiledMemory.normalizeSectionBody("   \n\n  "))
        assertEquals("", CompiledMemory.normalizeSectionBody("<think>只有思考</think>"))
    }

    // ── week 段的日期抬头 ────────────────────────────────────

    @Test
    fun `week 段保留日期抬头 —— 那是结构信息`() {
        val input = """
            ### 2026-08-22
            聊了记忆系统
            ### 2026-08-23
            聊了移植方案
        """.trimIndent()
        val result = CompiledMemory.normalizeWeekSectionBody(input)
        assertTrue("### 2026-08-22" in result, "日期抬头被删了，几天的内容会糊成一团")
        assertTrue("### 2026-08-23" in result)
        assertTrue("聊了记忆系统" in result)
    }

    @Test
    fun `不是合法日期的标题按普通标题处理掉`() {
        val input = """
            ### 2026-02-30
            这个日期不存在
        """.trimIndent()
        val result = CompiledMemory.normalizeWeekSectionBody(input)
        assertTrue("2026-02-30" !in result, "非法日期不该被当成结构抬头保留：$result")
        assertTrue("这个日期不存在" in result)
    }

    // ── memory.md 装配 ───────────────────────────────────────

    @Test
    fun `四个段落标题始终保留，空栏写占位符`() {
        val md = CompiledMemory.buildMemoryMarkdown(facts = "有事实", today = null, week = null, longterm = null)
        for (title in listOf("## 重要事实", "## 今天", "## 本周早些时候", "## 长期情况")) {
            assertTrue(title in md, "缺少段落：$title")
        }
        // 段落数量固定，prompt 形状不随记忆多少而变 —— 对 cache 前缀友好
        assertEquals(3, Regex("（暂无）").findAll(md).count(), "三个空栏都该写占位符")
        assertTrue("有事实" in md)
    }

    @Test
    fun `英文 locale 用英文标题与占位符`() {
        val md = CompiledMemory.buildMemoryMarkdown(facts = "a fact", isZh = false)
        for (title in listOf("## Key facts", "## Today", "## Earlier this week", "## Long-term context")) {
            assertTrue(title in md, "缺少段落：$title")
        }
        assertTrue("(none)" in md)
    }

    @Test
    fun `装配时对每段都做归一`() {
        val md = CompiledMemory.buildMemoryMarkdown(
            facts = "<think>思考</think>真实的事实",
            today = """["今天第一件", "今天第二件"]""",
        )
        assertTrue("思考" !in md, "思考标签内容漏进了 memory.md")
        assertTrue("真实的事实" in md)
        assertTrue("- 今天第一件" in md, "JSON 数组没被摊开")
    }

    // ── daily 目录装配 ───────────────────────────────────────

    @Test
    fun `按日期升序列出 daily 条目，忽略不合规文件名`() {
        val dir = dailyDir(
            "2026-08-23" to "b",
            "2026-08-21" to "a",
            "2026-08-24" to "c",
        )
        File(dir, "随手记.md").writeText("不该被算进去")
        File(dir, "2026-8-1.md").writeText("格式不对")

        assertEquals(
            listOf("2026-08-21", "2026-08-23", "2026-08-24"),
            CompiledMemory.listDailyEntries(dir).map { it.date },
        )
    }

    @Test
    fun `daily 目录不存在时返回空而不是抛异常`() {
        assertEquals(emptyList(), CompiledMemory.listDailyEntries(File(tempDir(), "不存在")))
        assertEquals("", CompiledMemory.assembleWeekFromDaily(File(tempDir(), "不存在")))
    }

    @Test
    fun `week 只取最近几天`() {
        val entries = (1..10).map { day -> "2026-08-%02d".format(day) to "第 $day 天" }
        val dir = dailyDir(*entries.toTypedArray())

        val week = CompiledMemory.assembleWeekFromDaily(dir, maxDays = 3)
        assertTrue("第 8 天" in week && "第 9 天" in week && "第 10 天" in week, "应保留最近三天：$week")
        assertTrue("第 7 天" !in week, "更早的不该出现：$week")
    }

    @Test
    fun `超长时从最老的开始丢 —— 越近的越相关`() {
        val dir = dailyDir(
            "2026-08-21" to "最老的内容".repeat(50),
            "2026-08-22" to "中间的内容".repeat(50),
            "2026-08-23" to "最新的内容",
        )
        val week = CompiledMemory.assembleWeekFromDaily(dir, maxChars = 300)
        assertTrue(week.length <= 300, "没有压到上限内：${week.length}")
        assertTrue("最新的内容" in week, "最近的内容必须保留：$week")
        assertTrue("最老的内容" !in week, "最老的应该先被丢掉")
    }

    @Test
    fun `只剩一条仍超长时保留头部截断尾部 —— 头部有日期抬头`() {
        val dir = dailyDir("2026-08-23" to "### 2026-08-23\n" + "内容".repeat(500))
        val week = CompiledMemory.assembleWeekFromDaily(dir, maxChars = 100)
        assertEquals(100, week.length)
        assertTrue(week.startsWith("### 2026-08-23"), "日期抬头被砍掉了，这条内容就失去时间归属：$week")
    }

    @Test
    fun `空的 daily 文件不参与装配`() {
        val dir = dailyDir(
            "2026-08-22" to "   \n\n  ",
            "2026-08-23" to "有内容",
        )
        assertEquals("有内容", CompiledMemory.assembleWeekFromDaily(dir))
    }

    // ── 滚出窗口 ─────────────────────────────────────────────

    @Test
    fun `滚出保留窗口的条目被挑出来`() {
        val entries = (1..10).map { day -> "2026-08-%02d".format(day) to "第 $day 天" }
        val dir = dailyDir(*entries.toTypedArray())

        val outside = CompiledMemory.dailyEntriesOutsideWindow(dir, retentionDays = 6)
        assertEquals(
            listOf("2026-08-01", "2026-08-02", "2026-08-03", "2026-08-04"),
            outside.map { it.date },
            "应挑出最老的四条",
        )
    }

    @Test
    fun `条目数没超过窗口时不挑任何东西`() {
        val dir = dailyDir("2026-08-22" to "a", "2026-08-23" to "b")
        assertEquals(emptyList(), CompiledMemory.dailyEntriesOutsideWindow(dir, retentionDays = 6))
    }

    @Test
    fun `挑出滚出条目不会顺手删文件`() {
        // fold 进 longterm 需要 LLM，删除是破坏性操作 —— 两者都该由调用方
        // 在确认 fold 成功之后再做，这个函数保持纯粹可测
        val dir = dailyDir(*(1..8).map { "2026-08-%02d".format(it) to "第 $it 天" }.toTypedArray())
        val before = dir.list()!!.size
        CompiledMemory.dailyEntriesOutsideWindow(dir, retentionDays = 6)
        assertEquals(before, dir.list()!!.size, "不该删除任何文件")
    }

    @Test
    fun `装配与滚出用同一个默认窗口`() {
        val entries = (1..10).map { day -> "2026-08-%02d".format(day) to "第 $day 天" }
        val dir = dailyDir(*entries.toTypedArray())

        val kept = CompiledMemory.assembleWeekFromDaily(dir, maxChars = 100_000)
        val rolled = CompiledMemory.dailyEntriesOutsideWindow(dir).map { it.date }.toSet()

        // 每一条要么进 week，要么滚出窗口，不能既不进也不滚（那就丢了）
        for ((date, text) in entries) {
            val inWeek = text in kept
            val isRolled = date in rolled
            assertTrue(inWeek != isRolled, "$date 既没进 week 也没被挑出滚动，会被静默丢掉")
        }
    }
}
