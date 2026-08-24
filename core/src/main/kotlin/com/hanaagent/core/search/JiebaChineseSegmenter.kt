package com.hanaagent.core.search

import com.huaban.analysis.jieba.JiebaSegmenter
import com.huaban.analysis.jieba.WordDictionary
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/**
 * huaban jieba-analysis 适配器。
 *
 * 选它是因为它是**纯 Java**：JVM 与 Android 跑的是同一份实现，所以在这台机器上
 * 测出来的切词结果，在平板上成立。上游桌面端用的是 `@node-rs/jieba`（Rust），
 * 两者是 jieba 算法的不同实现，切法不完全一致 —— 具体差多少见
 * [com.hanaagent.core.search.SessionSearchTokenizerTruthTest] 的实测报告。
 *
 * ## 自定义词典
 *
 * 上游用 `jieba.loadDict(CUSTOM_WORDS)` 把项目术语（session_search、A2A通信、
 * HANA_HOME、better-sqlite3 …）塞进词典，避免它们被切碎。huaban 版只接受文件
 * 路径，所以这里把同一份词表写到临时文件再加载。
 *
 * 注意 [WordDictionary] 是**进程级单例**：加载用户词典会影响整个进程里所有
 * segmenter 实例。这与上游的 `jiebaInstance` 模块级缓存语义一致。
 */
class JiebaChineseSegmenter(
    customWords: List<String> = DEFAULT_CUSTOM_WORDS,
) : ChineseSegmenter {

    private val segmenter = JiebaSegmenter()

    init {
        loadCustomWordsOnce(customWords.filterNot(::poisonsLatinSegmentation))
    }

    override fun cutForSearch(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        return segmenter.process(text, JiebaSegmenter.SegMode.SEARCH).map { it.word }
    }

    companion object {
        /**
         * 与上游 `session-search-tokenizer.ts` 的 CUSTOM_WORDS 逐字一致。
         * 格式是 jieba 的「词 词频 词性」；huaban 版只读前两列，第三列被忽略。
         */
        val DEFAULT_CUSTOM_WORDS: List<String> = listOf(
            "session_search 1000 nz",
            "session 1000 eng",
            "SessionFile 1000 eng",
            "A2A通信 1000 nz",
            "聊天记录 1000 nz",
            "搜不到 1000 v",
            "Agent 1000 eng",
            "CodeX 1000 eng",
            "Claude 1000 eng",
            "OpenClaw 1000 eng",
            "Cherry 1000 eng",
            "Studio 1000 eng",
            "HANA_HOME 1000 eng",
            "Bridge 1000 eng",
            "MCP 1000 eng",
            "RC 1000 eng",
            "better-sqlite3 1000 eng",
        )

        /**
         * 判断一条自定义词是否会污染拉丁文切分。
         *
         * 实测（Spike B）：词表里的 `RC 1000 eng` 在 huaban 版 jieba 下会赢过
         * 英文单词的自然切分，把 `search` 切成 `sea`/`rc`/`h`、把 `lowercase`
         * 切成 `lowe`/`rc`/`ase`。切出来的 `rc` 是高频噪声 term，会让搜索命中
         * 一大堆无关会话。上游的 Rust 版没有这个行为，所以词表本身没问题，
         * 是 Java 实现对短词的处理更激进。
         *
         * 判据：长度 ≤2 的纯 ASCII 词。这类词本来也不需要进词典 —— 拉丁词
         * 由 `ASCII_WORD_RE` 这条确定性规则负责，不依赖分词器。
         *
         * 这是与上游的一处**有意偏离**，理由和实测数据见
         * `build/reports/spike-b-tokenizer.md`。
         */
        internal fun poisonsLatinSegmentation(entry: String): Boolean {
            val word = entry.substringBefore(' ')
            return word.length <= 2 && word.all { it.code < 128 }
        }

        @Volatile
        private var loadedSignature: String? = null

        /**
         * 词典是全局单例，重复加载既浪费又可能叠加词频。按内容签名去重。
         */
        @Synchronized
        private fun loadCustomWordsOnce(customWords: List<String>) {
            if (customWords.isEmpty()) return
            val signature = customWords.joinToString("\n")
            if (signature == loadedSignature) return

            val dictFile = Files.createTempFile("hana-jieba-user-dict", ".txt")
            try {
                Files.write(dictFile, signature.toByteArray(StandardCharsets.UTF_8))
                WordDictionary.getInstance().loadUserDict(dictFile, StandardCharsets.UTF_8)
                loadedSignature = signature
            } finally {
                runCatching { Files.deleteIfExists(dictFile) }
            }
        }
    }
}
