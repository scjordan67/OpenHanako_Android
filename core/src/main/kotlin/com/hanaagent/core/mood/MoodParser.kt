package com.hanaagent.core.mood

/**
 * MoodParser —— 从流式文本里切出内省块。上游 `core/events.ts` 的移植。
 *
 * 三个「源」用三种标签（`<mood>` / `<pulse>` / `<reflect>`），但无论哪种，输出的
 * 都是同一套事件流：[MoodEvent.MoodStart] / [MoodEvent.MoodText] / [MoodEvent.MoodEnd]，
 * 正文走 [MoodEvent.Text]。界面据此把内心独白单独排版，与正文分开。
 *
 * ## 三条不显眼但要命的规则
 *
 * **① 内省块只能出现在回复最开头。** 一旦正文里出现过非空白字符，后面再出现
 * `<mood>` 就只是普通文本 —— 由 [allowOpenTag] 维持。没有这条约束的话，模型在
 * 正文中间提到"mood"标签时会把后面所有内容都吞进内省块。
 *
 * **② 标签可能被切断在任意 chunk 边界。** `<mo` + `od>` 和 `</mo` + `od>` 都必须
 * 正确拼回来。做法是把可能是标签前缀的尾巴留在 buffer 里不发出去，等下一块。
 * 这也是本类唯一真正复杂的地方。
 *
 * **③ 内省块结束后紧跟的换行要吃掉。** 否则正文会以一串空行开头。
 *
 * ## 用法
 *
 * ```
 * val parser = MoodParser()
 * parser.feed(delta) { event -> ... }   // 每收到一块流式增量
 * parser.flush { event -> ... }         // 流结束时，把留住的尾巴吐出来
 * ```
 *
 * **[flush] 必须调用。** 不调的话，被留住的部分标签会被静默丢弃 —— 表现是
 * 回复末尾少一小段文字，很难察觉。
 */
class MoodParser {

    /** 当前是否在内省块内部。 */
    var inMood: Boolean = false
        private set

    private var buffer: String = ""
    private var justEndedMood: Boolean = false
    private var currentTag: String? = null
    private var allowOpenTag: Boolean = true

    /** 喂入一段流式增量，通过 [emit] 输出解析出的事件。 */
    fun feed(delta: String, emit: (MoodEvent) -> Unit) {
        buffer += delta
        drain(emit)
    }

    /**
     * 流结束时调用：把 buffer 里剩下的内容强制吐出来。
     *
     * 如果流在内省块中途断了（模型被中断、网络断开），这里会补一个
     * [MoodEvent.MoodEnd]，让消费端的状态机能正常收尾，而不是永远停在"内省中"。
     */
    fun flush(emit: (MoodEvent) -> Unit) {
        if (buffer.isNotEmpty()) {
            if (inMood) {
                emit(MoodEvent.MoodText(buffer))
            } else {
                emit(MoodEvent.Text(buffer))
                if (buffer.isNotBlank()) allowOpenTag = false
            }
            buffer = ""
        }
        if (inMood) {
            emit(MoodEvent.MoodEnd)
            inMood = false
            currentTag = null
            allowOpenTag = false
        }
    }

    /** 复位到可以解析新一轮回复的状态。 */
    fun reset() {
        inMood = false
        buffer = ""
        justEndedMood = false
        currentTag = null
        allowOpenTag = true
    }

    /** 尽可能多地从 buffer 里提取完整事件。 */
    private fun drain(emit: (MoodEvent) -> Unit) {
        while (buffer.isNotEmpty()) {
            // 内省块刚结束：吃掉紧跟的换行，别让正文以空行开头
            if (justEndedMood && !inMood) {
                buffer = buffer.trimStart('\n')
                // 吃完还空，说明这一整块都是换行 —— 保持标志位，让下一块继续吃。
                //
                // 这里与上游有一处**有意偏离**：上游无论 buffer 是否变空都会清掉
                // 标志位，于是 `</mood>\n\n正文` 这种输入的结果取决于流怎么切片：
                // 整段到达时两个换行都被吃掉，而 `\n` 和 `\n正文` 分两块到达时只吃掉
                // 第一个，正文就多出一个空行。表现是"回复前面有时多一个空行、有时没有"，
                // 取决于供应商的分块策略——同一段内容在不同 provider 上排版不一样。
                // 保持标志位让结果与分块无关。语义没有变化：整段到达时的行为就是这个。
                if (buffer.isEmpty()) break
                justEndedMood = false
            }

            if (!inMood) {
                if (!drainOutsideMood(emit)) break
            } else {
                if (!drainInsideMood(emit)) break
            }
        }
    }

    /** @return true 表示还能继续处理，false 表示要等下一块 */
    private fun drainOutsideMood(emit: (MoodEvent) -> Unit): Boolean {
        // 正文已经开始，后面不再有内省块 —— 全部按文本发走
        if (!allowOpenTag) {
            emit(MoodEvent.Text(buffer))
            buffer = ""
            return true
        }

        return when (val inspection = InternalMoodBlock.inspectLeadingOpener(buffer)) {
            is InternalMoodBlock.Inspection.Open -> {
                if (inspection.prefix.isNotEmpty()) emit(MoodEvent.Text(inspection.prefix))
                emit(MoodEvent.MoodStart)
                inMood = true
                currentTag = inspection.tag
                buffer = inspection.remainder
                true
            }

            is InternalMoodBlock.Inspection.Pending -> {
                // 可能是开标签的前缀，留住等下一块
                if (inspection.prefix.isNotEmpty()) emit(MoodEvent.Text(inspection.prefix))
                buffer = inspection.pending
                false
            }

            InternalMoodBlock.Inspection.Text -> {
                emit(MoodEvent.Text(buffer))
                if (buffer.isNotBlank()) allowOpenTag = false
                buffer = ""
                true
            }
        }
    }

    /** @return true 表示还能继续处理，false 表示要等下一块 */
    private fun drainInsideMood(emit: (MoodEvent) -> Unit): Boolean {
        val closeTag = "</${currentTag}>"
        val index = buffer.indexOf(closeTag)
        if (index != -1) {
            val content = buffer.substring(0, index)
            if (content.isNotEmpty()) emit(MoodEvent.MoodText(content))
            emit(MoodEvent.MoodEnd)
            inMood = false
            justEndedMood = true
            allowOpenTag = false
            buffer = buffer.substring(index + closeTag.length)
            currentTag = null
            return true
        }

        // buffer 末尾可能是关闭标签的一部分（例如收到 "...想法</mo"），
        // 把这段留住，其余安全内容先发走
        val holdLength = trailingPrefixLength(buffer, closeTag)
        if (holdLength > 0) {
            val safe = buffer.substring(0, buffer.length - holdLength)
            if (safe.isNotEmpty()) emit(MoodEvent.MoodText(safe))
            buffer = buffer.substring(buffer.length - holdLength)
            return false
        }

        emit(MoodEvent.MoodText(buffer))
        buffer = ""
        return true
    }

    private companion object {
        /**
         * buffer 末尾有多少个字符构成 [target] 的前缀（1..target.length-1）。
         *
         * 用来判断"要留住多少字符"。取最长匹配：`...</moo` 里末尾 4 个字符是
         * `</mood>` 的前缀，就留 4 个。
         */
        fun trailingPrefixLength(buffer: String, target: String): Int {
            val maxCheck = minOf(buffer.length, target.length - 1)
            for (length in maxCheck downTo 1) {
                if (buffer.endsWith(target.substring(0, length))) return length
            }
            return 0
        }
    }
}

/** 解析出的事件。正文与内省内容严格分流，消费端据此分别渲染。 */
sealed interface MoodEvent {
    data class Text(val data: String) : MoodEvent
    data object MoodStart : MoodEvent
    data class MoodText(val data: String) : MoodEvent
    data object MoodEnd : MoodEvent
}
