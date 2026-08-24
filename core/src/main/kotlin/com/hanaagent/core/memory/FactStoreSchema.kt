package com.hanaagent.core.memory

/**
 * FactStore 的 SQLite schema —— 与上游 `lib/memory/fact-store.ts` 一致。
 *
 * schema 原样保留是有代价换来的好处：从桌面导出的角色卡里带的就是这张表，
 * 平板导入时不需要转换，直接能读。
 *
 * ## 对 Android 的硬性要求
 *
 * [FACTS_FTS] 用的是 **FTS5**。Android 系统自带的 SQLite 版本随 OS 版本走，
 * FTS5 的可用性不能假设 —— 因此 `:android` 必须**自带**一个编译了 FTS5 的
 * SQLite（如 `requery/sqlite-android`），不能用 `android.database.sqlite`。
 * 这是 Spike A 的结论，详见 `docs/spike-a-fts5.md`。
 */
object FactStoreSchema {

    /** 与上游一致的 schema 版本，写在 `user_version` pragma 里。 */
    const val SCHEMA_VERSION = 2

    val PRAGMAS: List<String> = listOf(
        "journal_mode = WAL",
        "synchronous = NORMAL",
        "cache_size = -16000",   // 16MB（默认约 2MB）
        "temp_store = MEMORY",
        "mmap_size = 30000000",  // 30MB mmap I/O
    )

    val FACTS_TABLE: String = """
        CREATE TABLE IF NOT EXISTS facts (
          id         INTEGER PRIMARY KEY AUTOINCREMENT,
          fact       TEXT NOT NULL,
          search_text TEXT NOT NULL DEFAULT '',
          tags       TEXT NOT NULL DEFAULT '[]',
          time       TEXT,
          session_id TEXT,
          created_at TEXT NOT NULL
        )
    """.trimIndent()

    val INDEXES: List<String> = listOf(
        "CREATE INDEX IF NOT EXISTS idx_facts_time ON facts(time)",
        "CREATE INDEX IF NOT EXISTS idx_facts_session ON facts(session_id)",
    )

    /**
     * 外部内容表（`content=facts`）：FTS 索引不重复存正文，只存倒排，
     * 靠 `content_rowid` 回表取原文。省空间，但代价是**增删改必须走触发器同步**，
     * 见 [TRIGGERS]。
     */
    val FACTS_FTS: String = """
        CREATE VIRTUAL TABLE facts_fts USING fts5(
          fact,
          search_text,
          content=facts,
          content_rowid=id,
          tokenize='unicode61'
        )
    """.trimIndent()

    val TRIGGERS: List<String> = listOf(
        """
        CREATE TRIGGER IF NOT EXISTS facts_ai AFTER INSERT ON facts BEGIN
          INSERT INTO facts_fts(rowid, fact, search_text) VALUES (new.id, new.fact, new.search_text);
        END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS facts_ad AFTER DELETE ON facts BEGIN
          INSERT INTO facts_fts(facts_fts, rowid, fact, search_text) VALUES ('delete', old.id, old.fact, old.search_text);
        END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS facts_au AFTER UPDATE ON facts BEGIN
          INSERT INTO facts_fts(facts_fts, rowid, fact, search_text) VALUES ('delete', old.id, old.fact, old.search_text);
          INSERT INTO facts_fts(rowid, fact, search_text) VALUES (new.id, new.fact, new.search_text);
        END
        """.trimIndent(),
    )

    /** 建库的完整语句序列（不含 pragma）。 */
    val CREATE_STATEMENTS: List<String> =
        listOf(FACTS_TABLE) + INDEXES + listOf(FACTS_FTS) + TRIGGERS

    const val INSERT_FACT: String =
        "INSERT INTO facts (fact, search_text, tags, time, session_id, created_at) VALUES (?, ?, ?, ?, ?, ?)"

    /** 全文检索：按 FTS5 的 rank 排序，回表取完整行。 */
    const val FTS_SEARCH: String = """
        SELECT f.* FROM facts_fts
        JOIN facts f ON f.id = facts_fts.rowid
        WHERE facts_fts MATCH ?
        ORDER BY rank
        LIMIT ?
    """

    /** FTS 查不到（且查询含 CJK）或语法出错时的兜底。 */
    const val LIKE_FALLBACK: String =
        "SELECT * FROM facts WHERE fact LIKE '%' || ? || '%' ORDER BY time DESC LIMIT ?"
}
