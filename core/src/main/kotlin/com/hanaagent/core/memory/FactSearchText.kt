package com.hanaagent.core.memory

import java.text.Normalizer

/**
 * FactStore 的检索文本构造 —— 上游 `lib/memory/fact-store.ts` 里那组纯函数的移植。
 *
 * 注意这条链路和会话搜索（[com.hanaagent.core.search.SessionSearchTokenizer]）
 * **不是同一套规则**，两者刻意不同：
 *
 * |          | 会话搜索        | FactStore          |
 * |----------|-----------------|--------------------|
 * | 归一     | NFKC + 小写 + 折空白 | NFKC + trim（不小写） |
 * | 中文切分 | jieba 词         | 2/3-gram           |
 * | 跨实现风险 | 有（两个 jieba 实现）| **无**（纯确定性）   |
 *
 * n-gram 没有词典、没有模型、没有原生依赖，所以桌面和平板可以做到**逐字一致**。
 * 这也意味着记忆搬过来之后，检索行为不会有任何偏移。
 */
object FactSearchText {

    /**
     * 归一：NFKC + 去首尾。
     *
     * 刻意**不小写** —— 与上游一致。FTS5 的 `unicode61` 分词器自己会做
     * 大小写折叠，在这里再折一次只会让 `search_text` 和原文对不上。
     */
    fun normalize(text: String?): String {
        if (text.isNullOrEmpty()) return ""
        return Normalizer.normalize(text, Normalizer.Form.NFKC).trim()
    }

    /**
     * 从文本里抽出所有 CJK 连续段，对每段生成全部 2-gram 和 3-gram。
     *
     * 为什么要 n-gram：FTS5 的 `unicode61` 不认识中日韩词边界，会把整段
     * CJK 当成一个 token。预先铺开 n-gram，搜「传送带」才能命中
     * 「记忆传送带每天凌晨四点滚动」。
     */
    fun cjkNgrams(text: String): List<String> {
        val tokens = mutableListOf<String>()
        for (match in CJK_RUN_RE.findAll(normalize(text))) {
            // 按**码点**切，不按 UTF-16 码元 —— 扩展区汉字是代理对，
            // 按 Char 切会把一个字劈成两半。上游的 Array.from() 同样按码点。
            val chars = match.value.codePoints().toArray().map { String(Character.toChars(it)) }
            for (size in intArrayOf(2, 3)) {
                if (chars.size < size) continue
                for (i in 0..chars.size - size) {
                    tokens += chars.subList(i, i + size).joinToString("")
                }
            }
        }
        return tokens
    }

    /** 归一后去重，保持首次出现顺序。 */
    fun uniqueTokens(tokens: List<String>): List<String> {
        val seen = LinkedHashSet<String>()
        for (token in tokens) {
            val normalized = normalize(token)
            if (normalized.isEmpty()) continue
            seen += normalized
        }
        return seen.toList()
    }

    /**
     * 构造写入 `facts.search_text` 列的内容。
     *
     * 原文和标签先拼成 base（整句保留，让精确匹配有得打），再把 CJK n-gram
     * 附在后面。`facts.fact` 列仍存原文，展示用。
     */
    fun buildFactSearchText(fact: String?, tags: List<String> = emptyList()): String {
        val base = (listOf(fact) + tags)
            .map { normalize(it) }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        return uniqueTokens(listOf(base) + cjkNgrams(base)).joinToString(" ")
    }

    /**
     * 把用户查询翻成 FTS5 的 MATCH 表达式。
     *
     * 所有 token 之间是 OR：宁可多召回也不漏。每个 token 用双引号包起来当短语，
     * 内部的双引号按 FTS5 规则转义成两个 —— 否则用户搜一个带引号的字符串会
     * 直接把查询语法搞崩（上游对这种情况有 LIKE 兜底，但先别让它崩）。
     */
    fun buildFtsQuery(query: String?): String {
        val normalized = normalize(query)
        if (normalized.isEmpty()) return ""
        val lexicalTokens = normalized.split(JS_WHITESPACE_RUN)
        return uniqueTokens(lexicalTokens + cjkNgrams(normalized))
            .joinToString(" OR ") { "\"" + it.replace("\"", "\"\"") + "\"" }
    }

    /** 查询里是否含 CJK —— 决定 FTS 空结果时要不要走 LIKE 兜底。 */
    fun hasCjk(text: String?): Boolean = CJK_RUN_RE.containsMatchIn(normalize(text))

    /** 上游：`/[\p{Script=Han}\p{Script=Hiragana}\p{Script=Katakana}\p{Script=Hangul}]+/gu` */
    private val CJK_RUN_RE =
        Regex("[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}]+")

    /** JS 的 `\s`：比 Java 默认的宽，含 NBSP / 各类 EN-EM space / 行分隔符 / BOM。 */
    private val JS_WHITESPACE_RUN =
        Regex("[ \\t\\n\\u000B\\f\\r\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff]+")
}
