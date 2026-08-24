package com.hanaagent.core.memory

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Spike A —— FTS5 schema 与检索契约。
 *
 * 分两半：
 *
 * 1. **纯函数对齐**：`search_text` 和 FTS 查询串必须与上游逐字一致。这条链路
 *    没有分词器依赖（NFKC + n-gram），所以要求的是**完全相等**，不是"召回率"。
 * 2. **真库跑通**：拿一个真的 SQLite 建表、插入、检索，确认 schema、触发器、
 *    `unicode61`、外部内容表这一套确实能工作。
 *
 * 这里用的是 `sqlite-jdbc`（自带编译好的 SQLite，开了 FTS5）。**它不能代表
 * Android**：系统自带的 SQLite 版本随 OS 走，FTS5 未必在。结论与待设备验证项
 * 见 `docs/spike-a-fts5.md`。
 */
class FactStoreFts5Test {

    private val json = Json { ignoreUnknownKeys = true }

    private fun openDb(): Connection {
        val db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use { st ->
            // 内存库不支持 WAL，跳过；其余 pragma 照上游设置
            for (pragma in FactStoreSchema.PRAGMAS.filterNot { it.startsWith("journal_mode") }) {
                st.execute("PRAGMA $pragma")
            }
            for (sql in FactStoreSchema.CREATE_STATEMENTS) st.execute(sql)
            st.execute("PRAGMA user_version = ${FactStoreSchema.SCHEMA_VERSION}")
        }
        return db
    }

    private fun insertFact(db: Connection, fact: String, tags: List<String>, sessionId: String = "s1") {
        db.prepareStatement(FactStoreSchema.INSERT_FACT).use { ps ->
            ps.setString(1, fact)
            ps.setString(2, FactSearchText.buildFactSearchText(fact, tags))
            ps.setString(3, tags.joinToString(prefix = "[", postfix = "]") { "\"$it\"" })
            ps.setString(4, "2026-08-24T10:00:00Z")
            ps.setString(5, sessionId)
            ps.setString(6, "2026-08-24T10:00:00Z")
            ps.executeUpdate()
        }
    }

    private fun search(db: Connection, query: String, limit: Int = 20): List<String> {
        val ftsQuery = FactSearchText.buildFtsQuery(query)
        if (ftsQuery.isEmpty()) return emptyList()
        val hits = mutableListOf<String>()
        db.prepareStatement(FactStoreSchema.FTS_SEARCH).use { ps ->
            ps.setString(1, ftsQuery)
            ps.setInt(2, limit)
            ps.executeQuery().use { rs -> while (rs.next()) hits += rs.getString("fact") }
        }
        return hits
    }

    // ── 1. 纯函数与上游逐字对齐 ────────────────────────────────

    @Test
    fun `search_text 与上游逐字一致`() {
        val root = loadTruth()
        val failures = mutableListOf<String>()
        for (element in root["searchText"]!!.jsonArray) {
            val obj = element.jsonObject
            val fact = obj["fact"]!!.jsonPrimitive.content
            val tags = obj["tags"]!!.jsonArray.map { it.jsonPrimitive.content }
            val expected = obj["searchText"]!!.jsonPrimitive.content
            val actual = FactSearchText.buildFactSearchText(fact, tags)
            if (actual != expected) {
                failures += "fact=${quote(fact)} tags=$tags\n  期望: $expected\n  实际: $actual"
            }
        }
        assertTrue(failures.isEmpty(), "search_text 构造与上游不一致：\n" + failures.joinToString("\n"))
    }

    @Test
    fun `FTS 查询串与上游逐字一致`() {
        val root = loadTruth()
        val failures = mutableListOf<String>()
        for (element in root["ftsQuery"]!!.jsonArray) {
            val obj = element.jsonObject
            val query = obj["query"]!!.jsonPrimitive.content
            val expectedQuery = obj["ftsQuery"]!!.jsonPrimitive.content
            val expectedHasCjk = obj["hasCjk"]!!.jsonPrimitive.boolean

            val actualQuery = FactSearchText.buildFtsQuery(query)
            if (actualQuery != expectedQuery) {
                failures += "query=${quote(query)}\n  期望: $expectedQuery\n  实际: $actualQuery"
            }
            val actualHasCjk = FactSearchText.hasCjk(query)
            if (actualHasCjk != expectedHasCjk) {
                failures += "query=${quote(query)} 的 hasCjk 期望 $expectedHasCjk，实际 $actualHasCjk"
            }
        }
        assertTrue(failures.isEmpty(), "FTS 查询构造与上游不一致：\n" + failures.joinToString("\n"))
    }

    // ── 2. 真库跑通 ────────────────────────────────────────────

    @Test
    fun `SQLite 支持 FTS5 且 schema 能建起来`() {
        openDb().use { db ->
            db.createStatement().use { st ->
                st.executeQuery("SELECT sqlite_version()").use { rs ->
                    rs.next()
                    println("SQLite 版本: ${rs.getString(1)}")
                }
                // FTS5 是编译期选项，用 compile_options 直接确认
                val options = mutableListOf<String>()
                st.executeQuery("PRAGMA compile_options").use { rs ->
                    while (rs.next()) options += rs.getString(1)
                }
                assertTrue(
                    options.any { it.contains("FTS5") },
                    "这个 SQLite 没有编译 FTS5。compile_options = $options",
                )
                st.executeQuery("PRAGMA user_version").use { rs ->
                    rs.next()
                    assertEquals(FactStoreSchema.SCHEMA_VERSION, rs.getInt(1))
                }
            }
        }
    }

    @Test
    fun `中文子串能被检索到 —— n-gram 起作用`() {
        openDb().use { db ->
            insertFact(db, "记忆传送带每天凌晨四点滚动", listOf("记忆", "调度"))
            insertFact(db, "用户喜欢暖纸主题", listOf("偏好", "界面"))

            // 「传送带」是「记忆传送带…」的中间子串，unicode61 单靠自己切不出来，
            // 全靠写入时铺的 3-gram
            assertEquals(listOf("记忆传送带每天凌晨四点滚动"), search(db, "传送带"))
            // 两字子串
            assertEquals(listOf("用户喜欢暖纸主题"), search(db, "暖纸"))
            // 标签也进了 search_text
            assertTrue(search(db, "偏好").isNotEmpty())
        }
    }

    @Test
    fun `英文与标识符按词检索`() {
        openDb().use { db ->
            insertFact(db, "better-sqlite3 需要 aarch64 重编", listOf("移植"))
            insertFact(db, "User prefers dark mode at night", listOf("preference"))

            assertTrue(search(db, "dark mode").isNotEmpty())
            // unicode61 把连字符当分隔符，所以 sqlite3 这个片段能命中
            assertTrue(search(db, "sqlite3").isNotEmpty())
        }
    }

    @Test
    fun `带引号的查询不会把 FTS 语法搞崩`() {
        openDb().use { db ->
            insertFact(db, "带\"引号\"的事实", listOf("转义"))
            // 双引号必须转义成两个，否则 MATCH 直接抛语法错误
            val hits = search(db, "带\"引号\"的")
            assertTrue(hits.isNotEmpty(), "带引号的查询应能正常命中，实际：$hits")
        }
    }

    @Test
    fun `触发器让 FTS 索引跟着增删改走`() {
        openDb().use { db ->
            insertFact(db, "会被改掉的事实内容", emptyList())
            assertTrue(search(db, "会被改掉").isNotEmpty())

            // UPDATE：facts_au 应先删旧索引再插新的
            db.prepareStatement("UPDATE facts SET fact = ?, search_text = ? WHERE id = 1").use { ps ->
                ps.setString(1, "换成完全不同的内容")
                ps.setString(2, FactSearchText.buildFactSearchText("换成完全不同的内容"))
                ps.executeUpdate()
            }
            assertTrue(search(db, "会被改掉").isEmpty(), "旧内容仍能搜到，说明 facts_au 没删干净")
            assertTrue(search(db, "完全不同").isNotEmpty(), "新内容搜不到，说明 facts_au 没插进去")

            // DELETE：facts_ad 应把索引清掉
            db.createStatement().use { it.execute("DELETE FROM facts WHERE id = 1") }
            assertTrue(search(db, "完全不同").isEmpty(), "删除后仍能搜到，说明 facts_ad 没生效")
        }
    }

    @Test
    fun `按 session 删除会连带清掉 FTS 索引`() {
        openDb().use { db ->
            insertFact(db, "第一个会话产生的事实", emptyList(), sessionId = "sess-a")
            insertFact(db, "第二个会话产生的事实", emptyList(), sessionId = "sess-b")

            db.prepareStatement("DELETE FROM facts WHERE session_id = ?").use { ps ->
                ps.setString(1, "sess-a")
                ps.executeUpdate()
            }

            val hits = search(db, "第一个会话")
            // 注意断言的是「被删的那条不在结果里」，不是「结果为空」——
            // 见下面 `n-gram 检索的召回很宽` 那条用例：两句话共享
            // 「个会」「会话」这些 n-gram，删掉一条之后另一条照样命中。
            assertTrue("第一个会话产生的事实" !in hits, "被删的事实仍能搜到，说明 facts_ad 没生效：$hits")
            assertTrue("第二个会话产生的事实" in hits, "未删除的事实应仍可检索：$hits")
        }
    }

    /**
     * n-gram + OR 的固有性质，不是缺陷，但决定了上层怎么用它。
     *
     * 写入时把每段 CJK 铺成全部 2/3-gram，查询时把所有 token 用 OR 连起来 ——
     * 这意味着**只要共享一个二字片段就会命中**。好处是几乎不会漏搜；代价是
     * 结果里会混进一堆低相关度的条目，排序完全压在 FTS5 的 `rank` 上。
     *
     * 所以调用方不能把「有结果」当成「找到了」，必须带 limit 并尊重 rank 顺序。
     */
    @Test
    fun `n-gram 检索的召回很宽 —— 共享片段即命中`() {
        openDb().use { db ->
            insertFact(db, "第一个会话产生的事实", emptyList())
            insertFact(db, "第二个会话产生的事实", emptyList())

            val hits = search(db, "第一个会话")
            assertEquals(
                2,
                hits.size,
                "两句共享「个会」「会话」等 n-gram，OR 语义下应当都命中：$hits",
            )
            // 但相关度更高的那条要排在前面 —— 这是 ORDER BY rank 的职责
            assertEquals("第一个会话产生的事实", hits.first(), "rank 排序没把更相关的排在前面：$hits")
        }
    }

    private fun loadTruth() =
        javaClass.getResourceAsStream("/factstore-truth.json")
            .also { assertNotNull(it, "factstore-truth.json 缺失，请跑 tools/factstore-truth/generate.mjs") }!!
            .use { json.parseToJsonElement(it.readBytes().decodeToString()) }
            .jsonObject

    private fun quote(value: String) = "\"" + value.replace("\"", "\\\"") + "\""
}
