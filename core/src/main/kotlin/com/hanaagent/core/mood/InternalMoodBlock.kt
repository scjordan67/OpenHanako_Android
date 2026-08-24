package com.hanaagent.core.mood

/**
 * 内省块的开标签探测 —— 上游 `shared/internal-mood-block.ts` 的移植。
 *
 * 三个「源」各自规定一种标签，但它们在协议上是同一件事：模型在开口之前先写一段
 * 内心独白，前端把它单独渲染，不混进正文。
 */
object InternalMoodBlock {

    /** hanako 用 mood，butter 用 pulse，ming 用 reflect。 */
    val TAGS: List<String> = listOf("mood", "pulse", "reflect")

    sealed interface Inspection {
        /** 确认是开标签。[remainder] 是标签之后的剩余内容。 */
        data class Open(
            val prefix: String,
            val tag: String,
            val openTag: String,
            val remainder: String,
        ) : Inspection

        /**
         * 可能是开标签，但还没收够字符。流式调用方必须**留住** [pending]，
         * 等下一个 chunk 或 flush —— 这是跨 chunk 断标签不丢内容的关键。
         */
        data class Pending(val prefix: String, val pending: String) : Inspection

        /** 确定不是开标签，按正文处理。 */
        data object Text : Inspection
    }

    /**
     * 只判定**回复开头**这一个位置。
     *
     * 内省块只允许出现在回复的最前面：一旦正文开始，后面再出现 `<mood>` 也只是
     * 普通文本。这个约束由调用方（[MoodParser] 的 allowOpenTag）维持，这里只负责
     * 判断"当前这个位置是不是开标签"。
     */
    fun inspectLeadingOpener(input: String): Inspection {
        var openerIndex = 0
        while (openerIndex < input.length && isLeadingWhitespace(input[openerIndex])) {
            openerIndex++
        }

        val prefix = input.substring(0, openerIndex)
        val candidate = input.substring(openerIndex)
        if (candidate.isEmpty()) return Inspection.Pending(prefix, "")

        for (tag in TAGS) {
            val openTag = "<$tag>"
            if (candidate.startsWith(openTag)) {
                return Inspection.Open(prefix, tag, openTag, candidate.substring(openTag.length))
            }
        }

        // 收到的还不够长，但已有的部分是某个开标签的前缀 —— 留住等下一块
        if (TAGS.any { "<$it>".startsWith(candidate) }) {
            return Inspection.Pending(prefix, candidate)
        }
        return Inspection.Text
    }

    /** 解析一个开头位置上完整闭合的内省块；不完整或不在开头则返回 null。 */
    fun parseLeadingBlock(input: String): Parsed? {
        val inspection = inspectLeadingOpener(input) as? Inspection.Open ?: return null
        val closeTag = "</${inspection.tag}>"
        val closeIndex = inspection.remainder.indexOf(closeTag)
        if (closeIndex < 0) return null
        return Parsed(
            prefix = inspection.prefix,
            tag = inspection.tag,
            content = inspection.remainder.substring(0, closeIndex),
            rest = inspection.remainder.substring(closeIndex + closeTag.length),
        )
    }

    data class Parsed(val prefix: String, val tag: String, val content: String, val rest: String)

    /**
     * 与 JS 正则 `\s`（带 u 标志）等价的空白判定，外加 BOM。
     *
     * 不能用 Kotlin 的 [Char.isWhitespace]：它基于 `Character.isWhitespace`，
     * **不含 NBSP（U+00A0）**，而 JS 的 `\s` 含。模型偶尔会用 NBSP 做缩进，
     * 判定不一致会让开标签探测在这种输入上错过整个内省块。
     */
    internal fun isLeadingWhitespace(char: Char): Boolean = when (char) {
        // JS 的 \s 集合，逐个列出而不是靠 Char.isWhitespace()
        '\u0009', '\u000A', '\u000B', '\u000C', '\u000D', '\u0020',
        '\u00A0', '\u1680', '\u2028', '\u2029', '\u202F', '\u205F',
        '\u3000', '\uFEFF',
        -> true

        // U+2000..U+200A：EN QUAD 到 HAIR SPACE
        else -> char in '\u2000'..'\u200A'
    }
}
