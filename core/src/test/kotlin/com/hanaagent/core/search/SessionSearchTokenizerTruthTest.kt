package com.hanaagent.core.search

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Spike B —— 分词跨设备一致性实测。
 *
 * ## 为什么要测这个
 *
 * 同一个 Agent 的记忆会在电脑和平板之间搬（角色卡 zip）。如果两端切词不一致，
 * 就会出现"在电脑上搜得到、在平板上搜不到"——而这种 bug 没有报错、没有崩溃，
 * 只是安静地少给你几条结果，最难发现。
 *
 * ## 测法
 *
 * `tokenizer-truth.json` 由 `tools/tokenizer-truth/generate.mjs` 跑**上游真正使用的**
 * `@node-rs/jieba` 2.0.1 生成，是 ground truth。这里把 term 分成两类分别处理：
 *
 * - **确定性 term**（整句、空白切片、ASCII 词）：由纯正则规则产生，与分词器无关。
 *   两端必须**逐字一致**，不一致就是移植出错 —— 硬断言。
 * - **分词 term**（jieba 切出的中文词）：Rust 版与 Java 版是 jieba 的两个不同实现，
 *   本来就会有差异。这里**测量**差异并留档，不硬性要求相同。
 *
 * 测量结果会写到 `build/reports/spike-b-tokenizer.md`。
 */
class SessionSearchTokenizerTruthTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** 不分词的 segmenter：用来单独算出"与分词器无关"的那部分 term。 */
    private object NoOpSegmenter : ChineseSegmenter {
        override fun cutForSearch(text: String): List<String> = emptyList()
    }

    private data class TruthCase(val input: String, val terms: List<String>)

    private fun loadTruth(): List<TruthCase> {
        val stream = javaClass.getResourceAsStream("/tokenizer-truth.json")
        assertNotNull(stream, "tokenizer-truth.json 缺失，请先跑 tools/tokenizer-truth/generate.mjs")
        val root = stream.use { json.parseToJsonElement(it.readBytes().decodeToString()) }.jsonObject
        return root["cases"]!!.jsonArray.map { element ->
            val obj = element.jsonObject
            TruthCase(
                input = obj["input"]!!.jsonPrimitive.content,
                terms = obj["terms"]!!.jsonArray.map { it.jsonPrimitive.content },
            )
        }
    }

    /**
     * 目标不是「和上游一模一样」，而是「不比上游少」。
     *
     * 两个 jieba 实现切法本来就有差异，强求相等做不到。但只要本端的 term 是
     * 上游的**超集**，桌面上搜得到的东西平板上就一定搜得到 —— 多出来的 term
     * 只会增加召回。真正危险的是反过来：少了 term，就会安静地少给结果。
     *
     * 所以这里把不变式定在「我们自己那几条确定性规则的产物必须全部出现」，
     * 而与上游的差距交给下面的实测用例去量。
     */
    @Test
    fun `确定性规则的产物一个都不能少`() {
        val tokenizer = SessionSearchTokenizer(NoOpSegmenter)
        val failures = mutableListOf<String>()

        for (case in loadTruth()) {
            val actual = tokenizer.tokenize(case.input).toSet()
            val normalized = tokenizer.normalize(case.input)
            if (normalized.isEmpty()) continue

            val required = buildList {
                add(normalized)
                addAll(normalized.split(" ").filter { it.isNotEmpty() })
                for (m in Regex("[a-z0-9_][a-z0-9_.\\-]*").findAll(normalized)) {
                    add(m.value)
                    addAll(Regex("[_.\\-]+").split(m.value).filter { it.length >= 2 })
                }
            }.filterNot { term ->
                // 与 addToken 相同的准入规则：纯标点、单汉字会被丢，不该出现在期望里
                term.isEmpty() ||
                    Regex("^[\\p{P}\\p{S}\\s]+$").matches(term) ||
                    (term.length == 1 && Regex("^\\p{IsHan}$").matches(term))
            }

            val missing = required.filterNot { it in actual }
            if (missing.isNotEmpty()) {
                failures += "输入 ${quote(case.input)}：确定性规则应产出但缺失的 term = ${missing.distinct()}"
            }
        }

        assertTrue(failures.isEmpty(), "确定性分词规则有漏项：\n" + failures.joinToString("\n"))
    }

    /**
     * 针对 Spike B 实测到的具体缺陷的回归测试。
     *
     * 词表里的 `RC 1000 eng` 曾让 huaban 版 jieba 把 `search` 切成 `sea`/`rc`/`h`。
     * `rc` 这种两字符碎片是高频噪声，会让搜索命中大量无关会话。
     * 修法见 [JiebaChineseSegmenter.poisonsLatinSegmentation]。
     */
    @Test
    fun `短 ASCII 词条不再污染英文切分`() {
        val tokenizer = SessionSearchTokenizer(JiebaChineseSegmenter())

        val searchTerms = tokenizer.tokenize("web_search 降级到浏览器搜索")
        assertTrue("search" in searchTerms, "`search` 应被完整切出，实际：$searchTerms")
        assertTrue("rc" !in searchTerms, "不应出现噪声碎片 `rc`，实际：$searchTerms")

        val lowerTerms = tokenizer.tokenize("MixedCase Should Lowercase")
        assertTrue("lowercase" in lowerTerms, "`lowercase` 应保持完整，实际：$lowerTerms")
        assertTrue("rc" !in lowerTerms, "不应出现噪声碎片 `rc`，实际：$lowerTerms")
        assertTrue("ase" !in lowerTerms, "不应出现噪声碎片 `ase`，实际：$lowerTerms")

        // 被剔除的只应是「长度<=2 的纯 ASCII」这一类，中文词条必须全部保留
        assertTrue(
            JiebaChineseSegmenter.DEFAULT_CUSTOM_WORDS
                .filterNot(JiebaChineseSegmenter::poisonsLatinSegmentation)
                .any { it.startsWith("A2A通信") },
            "中文自定义词条被误剔除",
        )
    }

    @Test
    fun `归一化规则与上游一致`() {
        val tokenizer = SessionSearchTokenizer(NoOpSegmenter)
        // 全角 → 半角（NFKC），大写 → 小写，连续空白折成单空格，首尾去空白
        assertEquals("fullwidth 全角转半角", tokenizer.normalize("ＦＵＬＬＷＩＤＴＨ 全角转半角"))
        assertEquals("mixedcase should lowercase", tokenizer.normalize("MixedCase Should Lowercase"))
        assertEquals("多余 空格 要归一", tokenizer.normalize("  多余   空格   要归一  "))
        assertEquals("", tokenizer.normalize(null))
        assertEquals("", tokenizer.normalize("   "))
        // 表意空格 U+3000 经 NFKC 折成普通空格
        assertEquals("a b", tokenizer.normalize("a　b"))
    }

    @Test
    fun `纯标点与单个汉字被丢弃`() {
        val tokenizer = SessionSearchTokenizer(NoOpSegmenter)
        assertEquals(emptyList(), tokenizer.tokenize("！？。，、；："))
        assertEquals(emptyList(), tokenizer.tokenize("的"))
        assertEquals(emptyList(), tokenizer.tokenize("记"))
        assertEquals(emptyList(), tokenizer.tokenize(""))
        // 单个 ASCII 字符不受"单汉字"规则影响，保留
        assertEquals(listOf("a"), tokenizer.tokenize("a"))
    }

    @Test
    fun `ASCII 标识符不被切碎`() {
        val tokenizer = SessionSearchTokenizer(NoOpSegmenter)
        assertTrue("better-sqlite3" in tokenizer.tokenize("better-sqlite3 编译失败"))
        assertTrue("hana_home" in tokenizer.tokenize("HANA_HOME 在哪"))
        assertTrue("file-history/service.ts:42".let { "file-history" in tokenizer.tokenize(it) })
        assertTrue("v0.450.0" in tokenizer.tokenize("v0.450.0 发版摘要"))
    }

    @Test
    fun `实测并留档：与上游分词器的召回差异`() {
        val truth = loadTruth()
        val ours = SessionSearchTokenizer(JiebaChineseSegmenter())

        var exactMatch = 0
        var truthTermTotal = 0
        var truthTermCovered = 0
        val rows = mutableListOf<String>()

        for (case in truth) {
            val actual = ours.tokenize(case.input)
            val expected = case.terms
            val missing = expected.filterNot { it in actual }
            val extra = actual.filterNot { it in expected }

            truthTermTotal += expected.size
            truthTermCovered += expected.size - missing.size
            if (missing.isEmpty() && extra.isEmpty()) exactMatch++

            if (missing.isNotEmpty() || extra.isNotEmpty()) {
                rows += buildString {
                    append("| `").append(case.input.ifEmpty { "(空)" }).append("` | ")
                    append(if (missing.isEmpty()) "—" else missing.joinToString(" ") { "`$it`" })
                    append(" | ")
                    append(if (extra.isEmpty()) "—" else extra.joinToString(" ") { "`$it`" })
                    append(" |")
                }
            }
        }

        val recall = truthTermCovered * 100.0 / truthTermTotal
        val report = buildString {
            appendLine("# Spike B — 分词跨设备一致性实测")
            appendLine()
            appendLine("上游（桌面）：`@node-rs/jieba` 2.0.1（Rust）")
            appendLine("本端（平板）：`com.huaban:jieba-analysis` 1.0.2（纯 Java，JVM 与 Android 同一实现）")
            appendLine()
            appendLine("| 指标 | 值 |")
            appendLine("|---|---|")
            appendLine("| 用例数 | ${truth.size} |")
            appendLine("| 完全一致的用例 | $exactMatch / ${truth.size} |")
            appendLine("| 上游 term 总数 | $truthTermTotal |")
            appendLine("| 被本端覆盖的上游 term | $truthTermCovered |")
            appendLine("| **term 召回率** | **${"%.1f".format(recall)}%** |")
            appendLine()
            if (rows.isEmpty()) {
                appendLine("所有用例逐字一致。")
            } else {
                appendLine("## 有差异的用例")
                appendLine()
                appendLine("| 输入 | 上游有、本端缺 | 本端有、上游无 |")
                appendLine("|---|---|---|")
                rows.forEach { appendLine(it) }
            }
            appendLine()
            appendLine("> 由 `SessionSearchTokenizerTruthTest` 自动生成，勿手工编辑。")
        }

        File("build/reports").mkdirs()
        File("build/reports/spike-b-tokenizer.md").writeText(report)
        println(report)

        // 召回率是这个 spike 的判定线：低于它说明换实现的代价太大，
        // 得改走"把上游分词结果一起打进角色卡"或换分词器的路子。
        assertTrue(
            recall >= 80.0,
            "对上游 term 的召回率只有 ${"%.1f".format(recall)}%，低于 80% 判定线 —— " +
                "换分词实现会明显改变搜索行为，需要重新选型。详见 build/reports/spike-b-tokenizer.md",
        )
    }

    private fun quote(value: String) = "\"" + value.replace("\"", "\\\"") + "\""
}
