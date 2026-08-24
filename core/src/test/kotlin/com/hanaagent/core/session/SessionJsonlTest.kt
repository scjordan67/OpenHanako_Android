package com.hanaagent.core.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 会话分支模型的行为契约。
 *
 * 会话是一棵树：用户可以从任意消息处分叉重问。这里守的是"重开会话时读到的是不是
 * 用户真正在看的那条分支" —— 判错了不会报错，只会让用户刚说的话消失，
 * 或者被莫名其妙拉回一条已经放弃的对话。
 */
class SessionJsonlTest {

    private fun entry(
        id: String,
        parentId: String?,
        role: String? = null,
        text: String? = null,
        timestamp: String? = null,
        type: String = if (role != null) "message" else "meta",
    ): String {
        val message = if (role != null) {
            ""","message":{"role":"$role","content":${text?.let { "\"$it\"" } ?: "null"}}"""
        } else {
            ""
        }
        val ts = timestamp?.let { ""","timestamp":"$it"""" } ?: ""
        val parent = parentId?.let { "\"$it\"" } ?: "null"
        return """{"id":"$id","parentId":$parent,"type":"$type"$ts$message}"""
    }

    private fun parse(vararg lines: String) = SessionJsonl.parseEntries(lines.joinToString("\n"))

    private fun texts(projection: SessionJsonl.Projection) =
        projection.messages.map { it.content?.toString()?.trim('"') }

    // ── 基本投影 ─────────────────────────────────────────────

    @Test
    fun `线性会话按顺序投影成消息`() {
        val entries = parse(
            entry("a", null, "user", "你好"),
            entry("b", "a", "assistant", "你好呀"),
            entry("c", "b", "user", "在吗"),
        )
        val projection = SessionJsonl.projectCurrentBranch(entries)
        assertEquals(listOf("你好", "你好呀", "在吗"), texts(projection))
        assertEquals("c", projection.selectedLeafId)
        assertEquals(SessionJsonl.HeadResolution.LEGACY_TAIL, projection.headResolution)
    }

    @Test
    fun `非 message 类型的条目不进消息列表但参与谱系`() {
        val entries = parse(
            entry("a", null, "user", "你好"),
            entry("meta", "a", type = "tool_use"),
            entry("b", "meta", "assistant", "你好呀"),
        )
        val projection = SessionJsonl.projectCurrentBranch(entries)
        assertEquals(listOf("你好", "你好呀"), texts(projection))
        assertEquals(3, projection.lineage.size, "元数据条目也应出现在谱系里")
    }

    @Test
    fun `type 为 session 的头部条目被排除`() {
        val entries = parse(
            """{"type":"session","id":"s","parentId":null}""",
            entry("a", null, "user", "你好"),
        )
        val projection = SessionJsonl.projectCurrentBranch(entries)
        assertEquals(listOf("你好"), texts(projection))
        assertEquals("a", projection.selectedLeafId)
    }

    @Test
    fun `只有 user 与 assistant 进消息列表`() {
        val entries = parse(
            entry("a", null, "user", "你好"),
            entry("b", "a", "system", "系统提示"),
            entry("c", "b", "assistant", "你好呀"),
        )
        assertEquals(listOf("你好", "你好呀"), texts(SessionJsonl.projectCurrentBranch(entries)))
    }

    // ── 分支 ─────────────────────────────────────────────────

    @Test
    fun `分叉后没有持久化头时读物理末尾那条分支`() {
        // a → b（原答案），a → c（重问后的答案）
        val entries = parse(
            entry("a", null, "user", "问题"),
            entry("b", "a", "assistant", "原答案"),
            entry("c", "a", "assistant", "新答案"),
        )
        val projection = SessionJsonl.projectCurrentBranch(entries)
        assertEquals(listOf("问题", "新答案"), texts(projection), "应读物理末尾所在的分支")
    }

    @Test
    fun `持久化头指向旧分支时尊重它`() {
        val entries = parse(
            entry("a", null, "user", "问题"),
            entry("b", "a", "assistant", "原答案"),
            entry("c", "a", "assistant", "新答案"),
        )
        // 用户切回了 b 这条分支，且当时观察到的末尾就是 c
        val head = SessionJsonl.BranchHead(leafId = "b", observedTailLeafId = "c")
        val projection = SessionJsonl.projectCurrentBranch(entries, head)
        assertEquals(listOf("问题", "原答案"), texts(projection))
        assertEquals(SessionJsonl.HeadResolution.PERSISTED_HEAD, projection.headResolution)
    }

    @Test
    fun `正常追加时把头前移 —— 否则刚说的话会消失`() {
        val entries = parse(
            entry("a", null, "user", "问题"),
            entry("b", "a", "assistant", "答案"),
            entry("c", "b", "user", "追问"),
        )
        // 上次记录时末尾是 b，现在多了 c，且 c 是 b 的后代 → 正常追加
        val head = SessionJsonl.BranchHead(leafId = "b", observedTailLeafId = "b")
        val projection = SessionJsonl.projectCurrentBranch(entries, head)
        assertEquals(SessionJsonl.HeadResolution.APPEND_RECOVERY, projection.headResolution)
        assertEquals(listOf("问题", "答案", "追问"), texts(projection), "追加的消息不能丢")
        assertEquals("c", projection.recommendedHead.leafId)
    }

    @Test
    fun `用户切换分支后，长在旧分支上的新条目不把用户拽回去`() {
        // 用户曾在 c 这条分支上（observedTail=c），主动切回 b。
        // 之后 c 分支上又长出了 d —— 这些不属于用户当前所在的分支。
        val entries = parse(
            entry("a", null, "user", "问题"),
            entry("b", "a", "assistant", "原答案"),
            entry("c", "a", "assistant", "新答案"),
            entry("d", "c", "user", "在新分支上的追问"),
        )
        val head = SessionJsonl.BranchHead(leafId = "b", observedTailLeafId = "c")
        val projection = SessionJsonl.projectCurrentBranch(entries, head)
        assertEquals(
            SessionJsonl.HeadResolution.PERSISTED_HEAD,
            projection.headResolution,
            "新条目长在被放弃的分支上，不该触发 append_recovery",
        )
        assertEquals(listOf("问题", "原答案"), texts(projection))
    }

    @Test
    fun `显式选择空分支与没有记录不是一回事`() {
        val entries = parse(
            entry("a", null, "user", "你好"),
            entry("b", "a", "assistant", "你好呀"),
        )
        // leafId=null 且 observedTail=b：显式选了"第一条之前"
        val head = SessionJsonl.BranchHead(leafId = null, observedTailLeafId = "b")
        val projection = SessionJsonl.projectCurrentBranch(entries, head)
        assertEquals(emptyList(), texts(projection), "显式空分支应读出零条消息")

        // 而没有记录时读的是物理末尾
        assertEquals(2, SessionJsonl.projectCurrentBranch(entries).messages.size)
    }

    // ── 结构性错误 ───────────────────────────────────────────

    @Test
    fun `父节点不存在时报错而不是静默读错分支`() {
        val entries = parse(
            entry("a", null, "user", "你好"),
            entry("b", "missing", "assistant", "你好呀"),
        )
        val error = assertFailsWith<SessionJsonl.BranchError> {
            SessionJsonl.projectCurrentBranch(entries)
        }
        assertEquals("session_branch_dangling_parent", error.code)
    }

    @Test
    fun `id 重复时报错`() {
        val entries = parse(
            entry("a", null, "user", "你好"),
            entry("a", null, "assistant", "重复 id"),
        )
        assertEquals(
            "session_branch_duplicate_id",
            assertFailsWith<SessionJsonl.BranchError> { SessionJsonl.projectCurrentBranch(entries) }.code,
        )
    }

    @Test
    fun `存在环时报错而不是死循环`() {
        val entries = parse(
            entry("a", "b", "user", "一"),
            entry("b", "a", "assistant", "二"),
        )
        assertEquals(
            "session_branch_cycle",
            assertFailsWith<SessionJsonl.BranchError> { SessionJsonl.projectCurrentBranch(entries) }.code,
        )
    }

    @Test
    fun `持久化头指向已不存在的叶子时报错`() {
        val entries = parse(entry("a", null, "user", "你好"))
        val head = SessionJsonl.BranchHead(leafId = "gone", observedTailLeafId = "a")
        assertEquals(
            "session_branch_head_missing",
            assertFailsWith<SessionJsonl.BranchError> {
                SessionJsonl.projectCurrentBranch(entries, head)
            }.code,
        )
    }

    @Test
    fun `非法 JSON 行报出行号`() {
        val error = assertFailsWith<SessionJsonl.BranchError> {
            SessionJsonl.parseEntries("{\"id\":\"a\"}\n这不是 JSON\n")
        }
        assertEquals("session_branch_invalid_json", error.code)
        assertTrue("第 2 行" in error.message.orEmpty(), "错误信息应指明行号：${error.message}")
    }

    // ── 旧格式兼容 ───────────────────────────────────────────

    @Test
    fun `全部条目都没有 id 时按行号合成线性链`() {
        val entries = SessionJsonl.parseEntries(
            """
            {"type":"message","message":{"role":"user","content":"你好"}}
            {"type":"message","message":{"role":"assistant","content":"你好呀"}}
            """.trimIndent(),
        )
        val projection = SessionJsonl.projectCurrentBranch(entries)
        assertTrue(projection.legacySyntheticIds, "应标记为旧格式合成 id")
        assertEquals(listOf("你好", "你好呀"), texts(projection))
    }

    @Test
    fun `部分有 id 部分没有时报错 —— 无法安全推断`() {
        val entries = SessionJsonl.parseEntries(
            """
            {"id":"a","parentId":null,"type":"message","message":{"role":"user","content":"你好"}}
            {"type":"message","message":{"role":"assistant","content":"没有 id"}}
            """.trimIndent(),
        )
        assertEquals(
            "session_branch_invalid_id",
            assertFailsWith<SessionJsonl.BranchError> { SessionJsonl.projectCurrentBranch(entries) }.code,
        )
    }

    // ── 增量读取 ─────────────────────────────────────────────

    @Test
    fun `since 只取更新的消息`() {
        val entries = parse(
            entry("a", null, "user", "早上说的", timestamp = "2026-08-24T01:00:00Z"),
            entry("b", "a", "assistant", "早上答的", timestamp = "2026-08-24T02:00:00Z"),
            entry("c", "b", "user", "下午说的", timestamp = "2026-08-24T09:00:00Z"),
        )
        val projection = SessionJsonl.projectCurrentBranch(entries, since = "2026-08-24T05:00:00Z")
        assertEquals(listOf("下午说的"), texts(projection))
        assertEquals("2026-08-24T09:00:00Z", projection.lastTimestamp)
    }

    @Test
    fun `没有时间戳的消息在 since 过滤下被排除`() {
        val entries = parse(
            entry("a", null, "user", "无时间戳"),
            entry("b", "a", "assistant", "有时间戳", timestamp = "2026-08-24T09:00:00Z"),
        )
        val projection = SessionJsonl.projectCurrentBranch(entries, since = "2026-08-24T05:00:00Z")
        assertEquals(listOf("有时间戳"), texts(projection))
    }

    // ── 谱系哈希 ─────────────────────────────────────────────

    @Test
    fun `谱系哈希逐节点累积且对内容变化敏感`() {
        val base = parse(
            entry("a", null, "user", "你好"),
            entry("b", "a", "assistant", "你好呀"),
        )
        val changed = parse(
            entry("a", null, "user", "你好"),
            entry("b", "a", "assistant", "换了个说法"),
        )
        val h1 = SessionJsonl.projectCurrentBranch(base).lineageHash
        val h2 = SessionJsonl.projectCurrentBranch(changed).lineageHash
        assertTrue(h1 != h2, "内容变了哈希必须变")

        // 前缀哈希：共同前缀 a 的哈希应当相同
        val p1 = SessionJsonl.projectCurrentBranch(base).prefixHashes
        val p2 = SessionJsonl.projectCurrentBranch(changed).prefixHashes
        assertEquals(p1["a"], p2["a"], "共同前缀的哈希应相同")
        assertTrue(p1["b"] != p2["b"])
    }

    @Test
    fun `空会话的谱系哈希等于根哈希`() {
        val projection = SessionJsonl.projectCurrentBranch(emptyList())
        assertEquals(projection.rootLineageHash, projection.lineageHash)
        assertEquals(null, projection.selectedLeafId)
        assertTrue(projection.messages.isEmpty())
    }
}
