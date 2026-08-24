package com.hanaagent.core.mood

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * MoodParser 的行为契约。
 *
 * 这个解析器出错时不会崩，只会让内心独白漏进正文、或者让一段正文消失 ——
 * 都是"看起来能用但不对"的故障，所以测得细一点。
 */
class MoodParserTest {

    /** 把一段文本按给定分片喂进去，收集全部事件。 */
    private fun parse(chunks: List<String>): List<MoodEvent> {
        val events = mutableListOf<MoodEvent>()
        val parser = MoodParser()
        for (chunk in chunks) parser.feed(chunk) { events += it }
        parser.flush { events += it }
        return events
    }

    private fun parse(input: String): List<MoodEvent> = parse(listOf(input))

    /** 合并相邻的同类事件，便于断言 —— 分片不同会导致事件被切碎，但语义相同。 */
    private fun normalize(events: List<MoodEvent>): List<MoodEvent> {
        val out = mutableListOf<MoodEvent>()
        for (event in events) {
            val last = out.lastOrNull()
            when {
                event is MoodEvent.Text && last is MoodEvent.Text ->
                    out[out.size - 1] = MoodEvent.Text(last.data + event.data)

                event is MoodEvent.MoodText && last is MoodEvent.MoodText ->
                    out[out.size - 1] = MoodEvent.MoodText(last.data + event.data)

                else -> out += event
            }
        }
        return out.filter { !(it is MoodEvent.Text && it.data.isEmpty()) }
    }

    private fun textOf(events: List<MoodEvent>) =
        events.filterIsInstance<MoodEvent.Text>().joinToString("") { it.data }

    private fun moodOf(events: List<MoodEvent>) =
        events.filterIsInstance<MoodEvent.MoodText>().joinToString("") { it.data }

    // ── 基本形状 ──────────────────────────────────────────────

    @Test
    fun `切出内省块与正文`() {
        val events = normalize(parse("<mood>\nVibe: 有点困\n</mood>\n今天想聊点轻松的。"))
        assertEquals(
            listOf(
                MoodEvent.MoodStart,
                MoodEvent.MoodText("\nVibe: 有点困\n"),
                MoodEvent.MoodEnd,
                MoodEvent.Text("今天想聊点轻松的。"),
            ),
            events,
        )
    }

    @Test
    fun `三个源的标签都认`() {
        for (tag in InternalMoodBlock.TAGS) {
            val events = normalize(parse("<$tag>内心</$tag>正文"))
            assertEquals(
                listOf(
                    MoodEvent.MoodStart,
                    MoodEvent.MoodText("内心"),
                    MoodEvent.MoodEnd,
                    MoodEvent.Text("正文"),
                ),
                events,
                "标签 $tag 没被识别",
            )
        }
    }

    @Test
    fun `没有内省块时正文原样透传`() {
        val input = "就是一段普通回复，没有任何标签。"
        assertEquals(listOf(MoodEvent.Text(input)), normalize(parse(input)))
    }

    @Test
    fun `标签前的空白算正文前缀，不吞掉`() {
        val events = normalize(parse("  \n<mood>x</mood>正文"))
        assertEquals(
            listOf(
                MoodEvent.Text("  \n"),
                MoodEvent.MoodStart,
                MoodEvent.MoodText("x"),
                MoodEvent.MoodEnd,
                MoodEvent.Text("正文"),
            ),
            events,
        )
    }

    // ── 三条要命的规则 ────────────────────────────────────────

    @Test
    fun `内省块结束后紧跟的换行被吃掉`() {
        val events = normalize(parse("<mood>x</mood>\n\n\n正文第一行"))
        assertEquals("正文第一行", textOf(events), "内省块后的换行没被吃干净")
    }

    @Test
    fun `正文开始之后出现的标签只当普通文本`() {
        // 模型在正文里提到标签名时，不能把后面所有内容都吞进内省块
        val events = normalize(parse("先说点别的。<mood>这不该被当成内省</mood>后面还有"))
        assertTrue(
            events.none { it is MoodEvent.MoodStart },
            "正文开始后不应再识别内省块，实际事件：$events",
        )
        assertEquals("先说点别的。<mood>这不该被当成内省</mood>后面还有", textOf(events))
    }

    @Test
    fun `只有开头的空白不阻断标签识别`() {
        // 纯空白不算"正文已开始"
        val events = normalize(parse(listOf("\n", "  ", "<mood>", "x", "</mood>", "正文")))
        assertTrue(events.any { it is MoodEvent.MoodStart }, "纯空白前缀不应阻断标签识别：$events")
    }

    // ── 跨 chunk 断标签 ───────────────────────────────────────

    @Test
    fun `开标签被切断在任意位置都能拼回来`() {
        val full = "<mood>内心独白</mood>正文"
        for (cut in 1 until "<mood>".length) {
            val events = normalize(parse(listOf(full.substring(0, cut), full.substring(cut))))
            assertEquals(
                listOf(
                    MoodEvent.MoodStart,
                    MoodEvent.MoodText("内心独白"),
                    MoodEvent.MoodEnd,
                    MoodEvent.Text("正文"),
                ),
                events,
                "开标签在第 $cut 个字符处被切断时解析错误",
            )
        }
    }

    @Test
    fun `关闭标签被切断在任意位置都能拼回来`() {
        val head = "<mood>内心独白"
        val closeTag = "</mood>"
        for (cut in 1 until closeTag.length) {
            val events = normalize(
                parse(listOf(head + closeTag.substring(0, cut), closeTag.substring(cut) + "正文")),
            )
            assertEquals("内心独白", moodOf(events), "关闭标签在第 $cut 处切断时内省内容错误")
            assertEquals("正文", textOf(events), "关闭标签在第 $cut 处切断时正文错误")
        }
    }

    @Test
    fun `逐字符喂入与整段喂入结果一致`() {
        // 流式解析器最容易在"分片粒度变化"上出错。逐字符是最极端的情况。
        val inputs = listOf(
            "<mood>\nVibe: 累\nSparks:\n  - 想到海\n</mood>\n那我们慢慢来。",
            "<pulse>体感</pulse>正文",
            "没有标签的纯文本",
            "  \n<reflect>沉思两层</reflect>\n\n结论是这样。",
            "<mood>里面有 < 和 > 这种字符</mood>后面",
        )
        for (input in inputs) {
            val whole = normalize(parse(input))
            val perChar = normalize(parse(input.map { it.toString() }))
            assertEquals(whole, perChar, "逐字符喂入结果不同：${input.take(30)}")
        }
    }

    @Test
    fun `任意二分点喂入结果都一致`() {
        val input = "<mood>\nVibe: 有点困\n</mood>\n今天想聊点轻松的。"
        val expected = normalize(parse(input))
        for (cut in 1 until input.length) {
            val split = normalize(parse(listOf(input.substring(0, cut), input.substring(cut))))
            assertEquals(expected, split, "在第 $cut 个字符处二分时结果不同")
        }
    }

    // ── 异常收尾 ──────────────────────────────────────────────

    @Test
    fun `流在内省块中途断掉时补上结束事件`() {
        // 模型被中断或网络断开：消费端的状态机不能永远停在"内省中"
        val events = normalize(parse("<mood>写到一半就断了"))
        assertEquals(
            listOf(
                MoodEvent.MoodStart,
                MoodEvent.MoodText("写到一半就断了"),
                MoodEvent.MoodEnd,
            ),
            events,
        )
    }

    @Test
    fun `flush 会把留住的部分标签吐出来而不是丢掉`() {
        // "<mo" 看起来像开标签前缀会被留住；流结束时必须作为正文发出
        val events = normalize(parse("<mo"))
        assertEquals(listOf(MoodEvent.Text("<mo")), events, "留住的部分标签被静默丢弃了")
    }

    @Test
    fun `空内省块不产生空的内容事件`() {
        val events = normalize(parse("<mood></mood>正文"))
        assertEquals(
            listOf(MoodEvent.MoodStart, MoodEvent.MoodEnd, MoodEvent.Text("正文")),
            events,
        )
    }

    @Test
    fun `reset 之后可以解析新一轮回复`() {
        val parser = MoodParser()
        val first = mutableListOf<MoodEvent>()
        parser.feed("正文开始了") { first += it }
        parser.flush { first += it }
        assertTrue(first.none { it is MoodEvent.MoodStart })

        parser.reset()
        val second = mutableListOf<MoodEvent>()
        parser.feed("<mood>新一轮</mood>") { second += it }
        parser.flush { second += it }
        assertTrue(
            second.any { it is MoodEvent.MoodStart },
            "reset 后应能重新识别内省块，实际：$second",
        )
    }

    // ── 空白判定 ──────────────────────────────────────────────

    @Test
    fun `NBSP 也算前导空白 —— Kotlin 默认判定不含它`() {
        // Char.isWhitespace() 不认 NBSP，而 JS 的 \s 认。模型偶尔用它做缩进。
        assertTrue(InternalMoodBlock.isLeadingWhitespace(' '), "NBSP 应算空白")
        assertTrue(InternalMoodBlock.isLeadingWhitespace('　'), "表意空格应算空白")
        assertTrue(InternalMoodBlock.isLeadingWhitespace('﻿'), "BOM 应算空白")
        assertTrue(InternalMoodBlock.isLeadingWhitespace(' '), "THIN SPACE 应算空白")
        assertTrue(!InternalMoodBlock.isLeadingWhitespace('x'))

        val events = normalize(parse(" 　<mood>x</mood>正文"))
        assertTrue(events.any { it is MoodEvent.MoodStart }, "NBSP 前缀后的标签没被识别：$events")
    }
}
