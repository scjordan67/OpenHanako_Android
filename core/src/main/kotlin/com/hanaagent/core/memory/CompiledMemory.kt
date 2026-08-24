package com.hanaagent.core.memory

import java.io.File

/**
 * 记忆传送带里**不需要 LLM** 的那一半 —— 上游 `lib/memory/compile.ts` 的移植。
 *
 * 传送带的完整链路是：
 *
 * ```
 * session 摘要 → today.md → daily/日期.md → week.md → longterm.md → memory.md
 *              (LLM)       (LLM)          (纯装配)   (纯装配)      (纯装配)
 * ```
 *
 * 后三步没有模型参与：从 daily 目录挑最近几天拼成 week，把滚出窗口的条目 fold 进
 * longterm，最后把四份文件拼成送进 system prompt 的 `memory.md`。这部分完全确定性，
 * 也是整条链路里唯一能在没有 API key 的情况下完整验证的部分。
 *
 * 归一化规则（[normalizeSectionBody]）看着琐碎，但每一条都在挡一种真实的模型输出
 * 毛病：思考标签漏进正文、把结果包成 JSON 数组、自作主张加 Markdown 标题层级、
 * 连续空行堆积。不归一的话这些会一路带进 system prompt。
 */
object CompiledMemory {

    /** week 段展示今天之前的几个已结束逻辑日；更早的 fold 进 longterm。 */
    const val DAILY_WINDOW_RETENTION_DAYS = 6

    /** week.md 的硬性总长上限（字符数）。 */
    const val WEEK_ASSEMBLY_MAX_CHARS = 1200

    private val DAILY_FILE_RE = Regex("^(\\d{4}-\\d{2}-\\d{2})\\.md$")
    private val WEEK_DATE_HEADING_RE = Regex("^#{2,3} (\\d{4}-\\d{2}-\\d{2})$")
    private val MARKDOWN_HEADING_RE = Regex("^#{1,6}\\s+\\S")
    private val THINK_BLOCK_RE = Regex("<think(?:ing)?>[\\s\\S]*?</think(?:ing)?>\\s*", RegexOption.IGNORE_CASE)
    private val THINK_OPEN_TAIL_RE = Regex("^\\s*<think(?:ing)?>[\\s\\S]*$", RegexOption.IGNORE_CASE)
    private val THINK_CLOSE_RE = Regex("</think(?:ing)?>\\s*", RegexOption.IGNORE_CASE)
    private val BLANK_RUN_RE = Regex("\\n{3,}")

    /**
     * 剥掉模型的思考标签。
     *
     * 三条替换分别处理：完整的 `<think>…</think>` 对、只有开标签没闭合（流被截断）、
     * 以及孤立的闭标签。DeepSeek / Qwen / Kimi 这类模型会把思考混在正文里输出，
     * 不剥干净就会一路进到 system prompt 里。
     */
    fun stripThinkTagBlocks(value: String?): String =
        (value ?: "")
            .replace(THINK_BLOCK_RE, "")
            .replace(THINK_OPEN_TAIL_RE, "")
            .replace(THINK_CLOSE_RE, "")

    /**
     * 归一一个记忆段的正文。
     *
     * 依次做四件事：剥思考标签、把 JSON 字符串数组摊成 markdown 列表、
     * 去掉模型自作主张加的标题行、压缩连续空行。
     *
     * 去标题这条是必要的：`memory.md` 自己用 `## ` 分四段，模型如果在段内又加
     * `### 今天的事`，层级就乱了，拼进 system prompt 后会把后面的内容视觉上收进
     * 那个子标题下面。
     */
    fun normalizeSectionBody(value: String?): String {
        val raw = stripThinkTagBlocks(value).trim()
        if (raw.isEmpty()) return ""

        val text = parseStringArray(raw)
            ?.joinToString("\n") { "- ${it.trim()}" }
            ?: raw

        return text.split(Regex("\\r?\\n"))
            // 用 containsMatchIn 而不是 matches：正则 `^#{1,6}\s+\S` 只描述行首形状，
            // matches() 要求整行匹配会一条都命不中（对应 JS 的 .test() 语义）
            .filterNot { MARKDOWN_HEADING_RE.containsMatchIn(it.trim()) }
            .joinToString("\n")
            .replace(BLANK_RUN_RE, "\n\n")
            .trim()
    }

    /**
     * week 段的归一：保留 `### 日期` 抬头，其余按普通段落归一。
     *
     * 与 [normalizeSectionBody] 的区别只在这一点 —— week 段是按天分块的，
     * 日期抬头是结构信息不能删，否则几天的内容会糊成一团分不出先后。
     */
    fun normalizeWeekSectionBody(value: String?): String {
        val raw = stripThinkTagBlocks(value).trim()
        if (raw.isEmpty()) return ""

        val parts = mutableListOf<String>()
        val bodyLines = mutableListOf<String>()

        fun flushBody() {
            val body = normalizeSectionBody(bodyLines.joinToString("\n"))
            if (body.isNotEmpty()) parts += body
            bodyLines.clear()
        }

        for (line in raw.split(Regex("\\r?\\n"))) {
            val match = WEEK_DATE_HEADING_RE.matchEntire(line)
            val date = match?.groupValues?.get(1)
            if (date != null && isValidIsoDate(date)) {
                flushBody()
                parts += "### $date"
            } else {
                bodyLines += line
            }
        }
        flushBody()

        return parts.joinToString("\n\n")
    }

    /**
     * 拼出最终送进 system prompt 的 `memory.md`。
     *
     * 四个标题**始终保留**，空栏写占位符而不是省略 —— 段落数量固定，
     * prompt 的形状就不会随记忆多少而变，对 cache 前缀友好，
     * 也让模型知道"这一栏是空的"而不是"没有这一栏"。
     */
    fun buildMemoryMarkdown(
        facts: String? = null,
        today: String? = null,
        week: String? = null,
        longterm: String? = null,
        isZh: Boolean = true,
    ): String {
        val empty = if (isZh) "（暂无）" else "(none)"
        fun section(title: String, content: String?) =
            "## $title\n\n" + (normalizeSectionBody(content).ifEmpty { empty })
        fun weekSection(title: String, content: String?) =
            "## $title\n\n" + (normalizeWeekSectionBody(content).ifEmpty { empty })

        return listOf(
            section(if (isZh) "重要事实" else "Key facts", facts),
            section(if (isZh) "今天" else "Today", today),
            weekSection(if (isZh) "本周早些时候" else "Earlier this week", week),
            section(if (isZh) "长期情况" else "Long-term context", longterm),
        ).joinToString("\n\n") + "\n"
    }

    /** daily 目录里的一条日记。 */
    data class DailyEntry(val date: String, val file: File)

    /** 列出 daily 目录里的日记，按日期升序。文件名不合规的忽略。 */
    fun listDailyEntries(dailyDir: File): List<DailyEntry> {
        val names = dailyDir.list() ?: return emptyList()
        return names.mapNotNull { name ->
            DAILY_FILE_RE.matchEntire(name)?.let { DailyEntry(it.groupValues[1], File(dailyDir, name)) }
        }.sortedBy { it.date }
    }

    /**
     * 从 daily 目录装配 week 段 —— **零 LLM**。
     *
     * 取最近 [maxDays] 天的日记拼起来。超长时从**最老的**开始丢，因为越近的越相关。
     * 丢到只剩一条仍然超长时，保留头部截断尾部 —— 头部有日期抬头，
     * 砍掉它会让这条内容失去时间归属。
     *
     * @return 装配好的内容（不落盘，由调用方决定写哪里）
     */
    fun assembleWeekFromDaily(
        dailyDir: File,
        maxDays: Int = DAILY_WINDOW_RETENTION_DAYS,
        maxChars: Int = WEEK_ASSEMBLY_MAX_CHARS,
    ): String {
        val blocks = listDailyEntries(dailyDir)
            .takeLast(maxDays)
            .map { it.file.takeIf(File::exists)?.readText()?.trim().orEmpty() }
            .filter { it.isNotEmpty() }

        if (blocks.isEmpty()) return ""

        var content = blocks.joinToString("\n\n")
        if (content.length > maxChars) {
            val kept = ArrayDeque(blocks)
            while (kept.size > 1 && kept.joinToString("\n\n").length > maxChars) {
                kept.removeFirst()
            }
            content = kept.joinToString("\n\n")
            if (content.length > maxChars) content = content.substring(0, maxChars)
        }
        return content
    }

    /**
     * 找出滚出保留窗口的 daily 条目 —— 它们该被 fold 进 longterm 然后删除。
     *
     * 只挑出来，不做 fold 也不删文件：fold 需要 LLM，删除是破坏性操作，
     * 两者都该由调用方在确认 fold 成功之后再执行。这个函数保持纯粹可测。
     */
    fun dailyEntriesOutsideWindow(
        dailyDir: File,
        retentionDays: Int = DAILY_WINDOW_RETENTION_DAYS,
    ): List<DailyEntry> {
        val entries = listDailyEntries(dailyDir)
        if (entries.size <= retentionDays) return emptyList()
        return entries.dropLast(retentionDays)
    }

    private fun parseStringArray(raw: String): List<String>? {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("[")) return null
        return runCatching {
            val element = kotlinx.serialization.json.Json.parseToJsonElement(trimmed)
            val array = element as? kotlinx.serialization.json.JsonArray ?: return null
            val items = array.map {
                val primitive = it as? kotlinx.serialization.json.JsonPrimitive ?: return null
                if (!primitive.isString) return null
                primitive.content
            }
            items.filter { it.isNotBlank() }
        }.getOrNull()
    }

    private fun isValidIsoDate(date: String): Boolean =
        runCatching { java.time.LocalDate.parse(date) }.isSuccess
}
