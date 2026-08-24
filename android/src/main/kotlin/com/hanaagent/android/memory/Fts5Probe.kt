package com.hanaagent.android.memory

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import com.hanaagent.core.memory.FactSearchText
import com.hanaagent.core.memory.FactStoreSchema

/**
 * FTS5 真机探针 —— 回答 Spike A 留下的那个问题。
 *
 * Spike A 在 JVM 上（`sqlite-jdbc`，自带开了 FTS5 的 SQLite）验证了 schema 和检索
 * 契约都成立，但那不能代表 Android：系统自带的 SQLite 版本随 OS 走，而 FTS5 是
 * 编译期选项。当时的结论是"必须 bundle 一个"。
 *
 * 在真去 bundle 之前，先在目标设备上实测一次。理由很简单：如果目标设备的系统
 * SQLite 本来就有 FTS5，bundle 就是白白给 APK 加几 MB、多一个要跟进安全更新的
 * 原生库。而 FTS5 从 Android 8.0 起在多数 ROM 上是开着的 —— 但"多数"不等于
 * "你手上这台"，所以这件事要测，不要猜。
 *
 * 探针不只问"有没有 FTS5"，而是把 [FactStoreSchema] 整套建起来跑一遍真实检索，
 * 因为真正要成立的是**整条链路**：外部内容表、三个同步触发器、`unicode61` 分词器、
 * 以及 CJK n-gram 能不能被正确索引和命中。任何一环缺失都会让记忆搜索静默失效。
 */
object Fts5Probe {

    /** 探测结果。任何一项为 false 都意味着必须 bundle 自带 FTS5 的 SQLite。 */
    data class Result(
        val sqliteVersion: String,
        val hasFts5: Boolean,
        val schemaCreated: Boolean,
        val cjkSubstringSearchWorks: Boolean,
        val triggersSyncIndex: Boolean,
        val failure: String? = null,
    ) {
        /** 系统 SQLite 是否够用 —— 全部为真才算够。 */
        val systemSqliteIsSufficient: Boolean
            get() = hasFts5 && schemaCreated && cjkSubstringSearchWorks && triggersSyncIndex

        fun summary(): String = buildString {
            appendLine("SQLite 版本: $sqliteVersion")
            appendLine("FTS5 可用: $hasFts5")
            appendLine("schema 建立: $schemaCreated")
            appendLine("中文子串检索: $cjkSubstringSearchWorks")
            appendLine("触发器同步索引: $triggersSyncIndex")
            appendLine("结论: " + if (systemSqliteIsSufficient) "系统 SQLite 够用，无需 bundle" else "必须 bundle 自带 FTS5 的 SQLite")
            failure?.let { appendLine("失败原因: $it") }
        }
    }

    /**
     * 在内存库上跑一遍完整探测。不落盘，不影响真实数据。
     */
    fun run(): Result {
        var db: SQLiteDatabase? = null
        var version = "unknown"
        var hasFts5 = false
        var schemaCreated = false
        var cjkWorks = false
        var triggersWork = false
        return try {
            db = SQLiteDatabase.create(null)
            version = queryOne(db, "SELECT sqlite_version()") ?: "unknown"

            // 直接建一张 FTS5 虚表来问 —— 比翻 compile_options 更直接，
            // 因为最终要用的就是这个能力
            hasFts5 = try {
                db.execSQL("CREATE VIRTUAL TABLE _fts5_probe USING fts5(x, tokenize='unicode61')")
                db.execSQL("DROP TABLE _fts5_probe")
                true
            } catch (_: SQLiteException) {
                false
            }

            if (hasFts5) {
                for (sql in FactStoreSchema.CREATE_STATEMENTS) db.execSQL(sql)
                schemaCreated = true

                insert(db, "记忆传送带每天凌晨四点滚动", listOf("记忆", "调度"))
                insert(db, "用户喜欢暖纸主题", listOf("偏好"))

                // 「传送带」是中间子串，unicode61 自己切不出来，全靠写入时铺的 3-gram
                cjkWorks = search(db, "传送带").contains("记忆传送带每天凌晨四点滚动")

                db.execSQL("DELETE FROM facts WHERE fact = '用户喜欢暖纸主题'")
                triggersWork = !search(db, "暖纸主题").contains("用户喜欢暖纸主题")
            }

            Result(version, hasFts5, schemaCreated, cjkWorks, triggersWork)
        } catch (error: Throwable) {
            Result(version, hasFts5, schemaCreated, cjkWorks, triggersWork, failure = error.toString())
        } finally {
            runCatching { db?.close() }
        }
    }

    private fun insert(db: SQLiteDatabase, fact: String, tags: List<String>) {
        db.execSQL(
            FactStoreSchema.INSERT_FACT,
            arrayOf(
                fact,
                FactSearchText.buildFactSearchText(fact, tags),
                tags.joinToString(prefix = "[", postfix = "]") { "\"$it\"" },
                "2026-08-24T10:00:00Z",
                "probe",
                "2026-08-24T10:00:00Z",
            ),
        )
    }

    private fun search(db: SQLiteDatabase, query: String): List<String> {
        val ftsQuery = FactSearchText.buildFtsQuery(query)
        if (ftsQuery.isEmpty()) return emptyList()
        val hits = mutableListOf<String>()
        db.rawQuery(FactStoreSchema.FTS_SEARCH, arrayOf(ftsQuery, "20")).use { cursor ->
            val column = cursor.getColumnIndex("fact")
            while (cursor.moveToNext()) hits += cursor.getString(column)
        }
        return hits
    }

    private fun queryOne(db: SQLiteDatabase, sql: String): String? =
        db.rawQuery(sql, null).use { if (it.moveToFirst()) it.getString(0) else null }
}
