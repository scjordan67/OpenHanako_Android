package com.hanaagent.core.search

import java.text.Normalizer

/**
 * 会话搜索分词 —— 上游 `lib/search/session-search-tokenizer.ts` 的移植。
 *
 * 它的产物有两个消费方：写入侧把 term 拼成 FactStore 的 `search_text` 列，
 * 查询侧把 term 拼成 FTS5 的 MATCH 表达式。两侧必须用同一套规则，否则写进去的
 * 搜不出来。
 *
 * 跨设备一致性是这里的硬要求：同一句话在电脑和平板上必须切出同一组 term，
 * 不然同一个 Agent 的记忆在两台设备上会"搜得到"和"搜不到"。
 * [SessionSearchTokenizerTruthTest] 拿上游真实分词器产出的 ground truth 逐例比对。
 *
 * 分词器本身通过 [ChineseSegmenter] 注入：JVM 与 Android 用的是同一个纯 Java
 * 实现（huaban jieba-analysis），但接口留出来，方便替换和在测试里对照。
 */
class SessionSearchTokenizer(private val segmenter: ChineseSegmenter) {

    /**
     * 文本归一 —— 与上游逐步对齐：NFKC → 小写 → 空白折叠 → 去首尾。
     *
     * 顺序不能换：NFKC 会把全角字符和各种异形空白折进 ASCII，先做它，后面的
     * 小写和空白折叠才对得上。
     */
    fun normalize(value: String?): String {
        if (value.isNullOrEmpty()) return ""
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
            // JS 的 toLowerCase() 与 locale 无关；用 ROOT 保证土耳其语环境下
            // 也不会把 I 变成 ı。
            .lowercase(java.util.Locale.ROOT)
            .replace(JS_WHITESPACE_RUN, " ")
            .trim()
    }

    /**
     * 把一段查询切成检索 term 集合。
     *
     * 四个来源依次并入，顺序即返回顺序（用 LinkedHashSet 保序，与 JS 的 Set 行为一致）：
     * 1. 整条归一后的字符串 —— 让"整句精确命中"排在最前
     * 2. 按空白切的片段
     * 3. ASCII 词（标识符、文件名、版本号这类）
     * 4. jieba 搜索模式切出的中文词
     */
    fun tokenize(query: String?): List<String> {
        val normalized = normalize(query)
        if (normalized.isEmpty()) return emptyList()

        val terms = LinkedHashSet<String>()
        addToken(terms, normalized)

        for (match in SPACED_TERM_RE.findAll(normalized)) addToken(terms, match.value)
        for (match in ASCII_WORD_RE.findAll(normalized)) {
            addToken(terms, match.value)
            // 分隔符切出的子词：hana_home → hana / home，2026-08-24 → 2026 / 08 / 24。
            // 上游是靠 jieba 顺手切出这些的；这里改成确定性规则，不依赖分词器对
            // 拉丁文的处理 —— 两个 jieba 实现在这件事上不一致（见 Spike B 报告）。
            // 单字符片段丢掉：`v0.450.0` 里的 `0` 之类没有区分度。
            for (part in ASCII_SUBTOKEN_SPLIT.split(match.value)) {
                if (part.length >= 2) addToken(terms, part)
            }
        }
        for (token in segmenter.cutForSearch(normalized)) addToken(terms, normalize(token))

        return terms.toList()
    }

    /**
     * 单个 term 的准入规则。
     *
     * 丢掉两类：纯标点/符号/空白，以及单个汉字。单字被丢是刻意的 —— 中文单字
     * 的区分度太低，"的""了""记"这种会把召回稀释成噪声。
     */
    private fun addToken(terms: MutableSet<String>, token: String) {
        val value = normalize(token)
        if (value.isEmpty()) return
        if (PUNCTUATION_RE.matches(value)) return
        if (value.length == 1 && SINGLE_HAN_RE.matches(value)) return
        terms.add(value)
    }

    private companion object {
        /**
         * JS 正则的 `\s` 比 Java 默认的 `\s` 宽：它含 NBSP、各种 EN/EM space、
         * 行分隔符、表意空格和 BOM。NFKC 会折掉其中大部分，但仍逐字对齐，
         * 免得某个漏网字符让两端切出不同结果。
         */
        val JS_WHITESPACE_RUN =
            Regex("[ \\t\\n\\u000B\\f\\r\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff]+")

        val SPACED_TERM_RE =
            Regex("[^ \\t\\n\\u000B\\f\\r\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff]+")

        /** 上游：`/[a-z0-9_][a-z0-9_.-]*​/giu`。输入已小写，`i` 保留只为对齐语义。 */
        val ASCII_WORD_RE = Regex("[a-z0-9_][a-z0-9_.\\-]*", RegexOption.IGNORE_CASE)

        /** 上游：`/^[\p{P}\p{S}\s]+$/u` */
        val PUNCTUATION_RE =
            Regex("^[\\p{P}\\p{S} \\t\\n\\u000B\\f\\r\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff]+$")

        /** 上游：`/^[\p{Script=Han}]$/u` */
        val SINGLE_HAN_RE = Regex("^\\p{IsHan}$")

        /** ASCII 词内部的分隔符：下划线、点、连字符。 */
        val ASCII_SUBTOKEN_SPLIT = Regex("[_.\\-]+")
    }
}

/**
 * 中文分词器接口。
 *
 * 只需要「搜索模式」这一种切法：尽可能多切出可检索的子词，宁可重叠也不漏。
 */
interface ChineseSegmenter {
    /** 对应 jieba 的 cut_for_search。 */
    fun cutForSearch(text: String): List<String>
}
