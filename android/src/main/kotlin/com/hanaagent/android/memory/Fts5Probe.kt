package com.hanaagent.android.memory

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.hanaagent.core.memory.FactSearchText
import com.hanaagent.core.memory.FactStoreSchema

/**
 * FTS5 真机探针 —— Spike A 留下的那个问题已经有答案了，这里负责持续钉住它。
 *
 * ## 已知结论（2026-08-24，目标平板实测）
 *
 * 系统 SQLite 是 **3.44.5，没有编 FTS5**：`CREATE VIRTUAL TABLE … USING fts5(…)`
 * 直接抛异常。所以 FactStore 必须走 bundle 的那份。
 *
 * 探针因此改成**两条都测**，而不是测完系统的就完事：
 *
 * - [Engine.SYSTEM]：设备自带的 `android.database.sqlite`。预期失败，留着是为了
 *   记录事实 —— 换一台设备/换一个 ROM 结论可能不同，而这是唯一能发现"其实不用
 *   bundle 了"的方式。
 * - [Engine.BUNDLED]：`androidx.sqlite:sqlite-bundled`，随 APK 一起打包。
 *   这条**必须**全绿，否则记忆搜索在设备上是静默失效的。
 *
 * 两条跑的是同一套断言：把 [FactStoreSchema] 整套建起来，插入、检索、删除，
 * 验证外部内容表、三个同步触发器、`unicode61` 分词器、以及 CJK n-gram 能不能被
 * 正确索引和命中。只问"有没有 FTS5"是不够的 —— 真正要成立的是整条链路，
 * 任何一环缺失都会让记忆搜索悄无声息地不工作。
 */
object Fts5Probe {

    enum class Engine(val label: String) {
        SYSTEM("系统 SQLite"),
        BUNDLED("随包 SQLite"),
    }

    /** 单个引擎的探测结果。任何一项为 false 都意味着这个引擎不能用来跑 FactStore。 */
    data class Result(
        val engine: Engine,
        val sqliteVersion: String,
        val hasFts5: Boolean,
        val schemaCreated: Boolean,
        val cjkSubstringSearchWorks: Boolean,
        val triggersSyncIndex: Boolean,
        val failure: String? = null,
    ) {
        /** 这个引擎是否足够跑 FactStore —— 全部为真才算够。 */
        val isSufficient: Boolean
            get() = hasFts5 && schemaCreated && cjkSubstringSearchWorks && triggersSyncIndex

        fun summary(): String = buildString {
            appendLine("${engine.label}: $sqliteVersion")
            appendLine("  FTS5 可用: $hasFts5")
            appendLine("  schema 建立: $schemaCreated")
            appendLine("  中文子串检索: $cjkSubstringSearchWorks")
            appendLine("  触发器同步索引: $triggersSyncIndex")
            appendLine("  可用于 FactStore: " + if (isSufficient) "是" else "否")
            failure?.let { appendLine("  失败原因: $it") }
        }
    }

    /** 两个引擎各跑一遍。 */
    data class Report(val system: Result, val bundled: Result) {
        /**
         * 结论。[bundled] 不达标是**严重问题** —— 那意味着这台设备上记忆搜索
         * 根本不工作，而且不会有任何报错。
         */
        fun verdict(): String = when {
            !bundled.isSufficient -> "⚠ 随包 SQLite 也不达标 —— 记忆搜索在这台设备上无法工作"
            system.isSufficient -> "两者都可用（系统 SQLite 也够，bundle 目前是冗余）"
            else -> "按预期：系统 SQLite 不够，走随包的那份"
        }

        fun summary(): String = system.summary() + "\n" + bundled.summary() + "\n" + verdict()
    }

    fun run(): Report = Report(system = probeSystem(), bundled = probeBundled())

    // ── 系统 SQLite ──────────────────────────────────────────

    private fun probeSystem(): Result {
        var db: SQLiteDatabase? = null
        var version = "unknown"
        var hasFts5 = false
        var schemaCreated = false
        var cjkWorks = false
        var triggersWork = false
        return try {
            db = SQLiteDatabase.create(null)
            version = systemQueryOne(db, "SELECT sqlite_version()") ?: "unknown"

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
                systemInsert(db, FACT_A, TAGS_A)
                systemInsert(db, FACT_B, TAGS_B)
                cjkWorks = systemSearch(db, NEEDLE).contains(FACT_A)
                db.execSQL("DELETE FROM facts WHERE fact = ?", arrayOf(FACT_B))
                triggersWork = !systemSearch(db, DELETED_NEEDLE).contains(FACT_B)
            }

            Result(Engine.SYSTEM, version, hasFts5, schemaCreated, cjkWorks, triggersWork)
        } catch (error: Throwable) {
            Result(Engine.SYSTEM, version, hasFts5, schemaCreated, cjkWorks, triggersWork, error.toString())
        } finally {
            runCatching { db?.close() }
        }
    }

    private fun systemInsert(db: SQLiteDatabase, fact: String, tags: List<String>) {
        db.execSQL(FactStoreSchema.INSERT_FACT, insertArgs(fact, tags))
    }

    private fun systemSearch(db: SQLiteDatabase, query: String): List<String> {
        val ftsQuery = FactSearchText.buildFtsQuery(query)
        if (ftsQuery.isEmpty()) return emptyList()
        val hits = mutableListOf<String>()
        db.rawQuery(FactStoreSchema.FTS_SEARCH, arrayOf(ftsQuery, "20")).use { cursor ->
            val column = cursor.getColumnIndex("fact")
            while (cursor.moveToNext()) hits += cursor.getString(column)
        }
        return hits
    }

    private fun systemQueryOne(db: SQLiteDatabase, sql: String): String? =
        db.rawQuery(sql, null).use { if (it.moveToFirst()) it.getString(0) else null }

    // ── 随包 SQLite ──────────────────────────────────────────

    private fun probeBundled(): Result {
        var connection: SQLiteConnection? = null
        var version = "unknown"
        var hasFts5 = false
        var schemaCreated = false
        var cjkWorks = false
        var triggersWork = false
        return try {
            connection = BundledSQLiteDriver().open(":memory:")
            version = bundledQueryOne(connection, "SELECT sqlite_version()") ?: "unknown"

            hasFts5 = try {
                connection.execSQL("CREATE VIRTUAL TABLE _fts5_probe USING fts5(x, tokenize='unicode61')")
                connection.execSQL("DROP TABLE _fts5_probe")
                true
            } catch (_: Throwable) {
                false
            }

            if (hasFts5) {
                for (sql in FactStoreSchema.CREATE_STATEMENTS) connection.execSQL(sql)
                schemaCreated = true
                bundledInsert(connection, FACT_A, TAGS_A)
                bundledInsert(connection, FACT_B, TAGS_B)
                cjkWorks = bundledSearch(connection, NEEDLE).contains(FACT_A)
                connection.prepare("DELETE FROM facts WHERE fact = ?").use { statement ->
                    statement.bindText(1, FACT_B)
                    statement.step()
                }
                triggersWork = !bundledSearch(connection, DELETED_NEEDLE).contains(FACT_B)
            }

            Result(Engine.BUNDLED, version, hasFts5, schemaCreated, cjkWorks, triggersWork)
        } catch (error: Throwable) {
            Result(Engine.BUNDLED, version, hasFts5, schemaCreated, cjkWorks, triggersWork, error.toString())
        } finally {
            runCatching { connection?.close() }
        }
    }

    private fun bundledInsert(connection: SQLiteConnection, fact: String, tags: List<String>) {
        connection.prepare(FactStoreSchema.INSERT_FACT).use { statement ->
            insertArgs(fact, tags).forEachIndexed { index, value -> statement.bindText(index + 1, value) }
            statement.step()
        }
    }

    private fun bundledSearch(connection: SQLiteConnection, query: String): List<String> {
        val ftsQuery = FactSearchText.buildFtsQuery(query)
        if (ftsQuery.isEmpty()) return emptyList()
        val hits = mutableListOf<String>()
        connection.prepare(FactStoreSchema.FTS_SEARCH).use { statement ->
            statement.bindText(1, ftsQuery)
            statement.bindLong(2, 20)
            val factColumn = (0 until statement.getColumnCount())
                .firstOrNull { statement.getColumnName(it) == "fact" } ?: return emptyList()
            while (statement.step()) hits += statement.getText(factColumn)
        }
        return hits
    }

    private fun bundledQueryOne(connection: SQLiteConnection, sql: String): String? =
        connection.prepare(sql).use { if (it.step()) it.getText(0) else null }

    // ── 两个引擎共用的探测数据 ───────────────────────────────

    /** 「传送带」是中间子串，unicode61 自己切不出来，全靠写入时铺的 3-gram。 */
    private const val FACT_A = "记忆传送带每天凌晨四点滚动"
    private val TAGS_A = listOf("记忆", "调度")
    private const val NEEDLE = "传送带"

    private const val FACT_B = "用户喜欢暖纸主题"
    private val TAGS_B = listOf("偏好")
    private const val DELETED_NEEDLE = "暖纸主题"

    private fun insertArgs(fact: String, tags: List<String>): Array<String> = arrayOf(
        fact,
        FactSearchText.buildFactSearchText(fact, tags),
        tags.joinToString(prefix = "[", postfix = "]") { "\"$it\"" },
        "2026-08-24T10:00:00Z",
        "probe",
        "2026-08-24T10:00:00Z",
    )
}
