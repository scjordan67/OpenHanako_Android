package com.hanaagent.core.memory

import java.time.ZonedDateTime

/**
 * 每日记忆维护的状态机与补偿式调度 —— Spike C。
 *
 * 桌面端的 `memory-ticker` 是常驻进程，日期一变就触发，断点续跑只需要防"进程重启"。
 * Android 上完全不同：App 可能被关几天，WorkManager 在 doze 下会被推迟，
 * 04:00 那一刻**大概率没有代码在跑**。
 *
 * 所以这里把调度从「到点触发」改成「**欠账补偿**」：每次有机会执行时（App 打开、
 * WorkManager 醒来），先问"我欠了什么"，再按顺序补。上游的 `daily-state.json`
 * 本来就是断点续跑结构，这个改造是顺着它的设计走的，不是另起炉灶。
 */
object DailyMaintenance {

    /** 与上游 `DAILY_STATE_SCHEMA_VERSION` 一致。schema 变了就丢弃旧状态重算。 */
    const val SCHEMA_VERSION = 4

    /**
     * 步骤顺序不可重排。
     *
     * `compileDaily` **必须**先于 `compileToday`：前者读的是"昨天最终版 today 草稿"，
     * 而那份草稿在日期切换前还躺在 today.md 里；一旦 compileToday 先跑，日期切换会把
     * today.md 重置成新一天的空白，昨天的内容就再也读不回来了。
     *
     * 上游在源码注释里专门写了一整段强调这件事，这里把它固化成常量顺序 +
     * 一条断言（见 DailyMaintenanceTest）。
     */
    val STEP_ORDER: List<String> = listOf(
        "compileDaily",
        "compileToday",
        "rollDailyWindow",
        "compileFacts",
        "deepMemory",
    )

    /**
     * 一次维护的上下文。
     *
     * `resetAt` 是记忆被用户手动重置的时间戳：它变了意味着旧的编译产物全部作废，
     * 已完成的步骤不能再算数 —— 所以它和 logicalDate 一起构成状态的有效性判据。
     */
    data class Context(val logicalDate: String, val resetAt: String?) {
        val key: String get() = logicalDate + "\n" + (resetAt ?: "")
    }

    /** 落盘的进度状态，对应上游的 `daily-state.json`。 */
    data class State(
        val schemaVersion: Int,
        val logicalDate: String,
        val resetAt: String?,
        val completedSteps: Map<String, String>,
        val dailyCompletedAt: String?,
    ) {
        fun matches(context: Context): Boolean =
            schemaVersion == SCHEMA_VERSION &&
                logicalDate == context.logicalDate &&
                resetAt == context.resetAt
    }

    /** 状态为何不适用 —— 用于日志和诊断，出问题时能一眼看出是哪种。 */
    enum class DiscardReason { NONE, MISSING, SCHEMA_CHANGED, DATE_CHANGED, MEMORY_RESET }

    /** 某一个逻辑日要跑的活。 */
    data class DayPlan(val context: Context, val pendingSteps: List<String>)

    /**
     * 完整的补偿计划。
     *
     * [days] 按时间顺序排列，先补旧账再做今天。[skippedDays] 是被上限截掉的天数 ——
     * 不静默丢弃，让调用方能告诉用户"有几天的记忆没能补上"。
     */
    data class Plan(
        val days: List<DayPlan>,
        val discardReason: DiscardReason,
        val skippedDays: Int,
    ) {
        val isEmpty: Boolean get() = days.all { it.pendingSteps.isEmpty() }
        val totalSteps: Int get() = days.sumOf { it.pendingSteps.size }
    }

    /**
     * 补账上限。
     *
     * 每个逻辑日的维护包含数次 LLM 调用；关了两周再打开时一口气补 14 天，
     * 会在用户毫无预期的情况下烧掉一大笔 token，还可能是在移动网络上。
     * 超出上限的旧账直接放弃（那些天的原始 session 摘要仍在，只是不再单独蒸馏成日记）。
     */
    const val DEFAULT_MAX_CATCH_UP_DAYS = 3

    /**
     * 算出「现在欠什么」。
     *
     * @param persisted 上次落盘的进度；null 表示没有或读取失败
     * @param now 当前时刻（带时区）
     * @param resetAt 记忆重置时间戳，参与状态有效性判定
     * @param lastCompletedLogicalDate 最后一个**完整跑完**的逻辑日；null 表示从未跑过
     * @param maxCatchUpDays 最多往回补几天
     */
    fun plan(
        persisted: State?,
        now: ZonedDateTime,
        resetAt: String? = null,
        lastCompletedLogicalDate: String? = null,
        maxCatchUpDays: Int = DEFAULT_MAX_CATCH_UP_DAYS,
    ): Plan {
        val today = LogicalDay.of(now)
        val context = Context(today.logicalDate, resetAt)

        val discardReason = when {
            persisted == null -> DiscardReason.MISSING
            persisted.schemaVersion != SCHEMA_VERSION -> DiscardReason.SCHEMA_CHANGED
            persisted.resetAt != context.resetAt -> DiscardReason.MEMORY_RESET
            persisted.logicalDate != context.logicalDate -> DiscardReason.DATE_CHANGED
            else -> DiscardReason.NONE
        }

        // 只有状态完全匹配当前上下文时，已完成的步骤才算数
        val completed: Set<String> =
            if (discardReason == DiscardReason.NONE) persisted!!.completedSteps.keys else emptySet()

        // ── 欠账：上次完整跑完的那天到今天之间漏掉的逻辑日 ──
        val gapDays = LogicalDay.daysBetween(lastCompletedLogicalDate, today.logicalDate)
        val missed: List<String> = when {
            lastCompletedLogicalDate == null -> emptyList()   // 从未跑过，没有"漏掉"的概念
            gapDays == null || gapDays <= 1L -> emptyList()   // 昨天刚跑过，今天正常做即可
            else -> (1 until gapDays).mapNotNull { offset ->
                LogicalDay.shift(lastCompletedLogicalDate, offset)
            }
        }

        val skipped = (missed.size - maxCatchUpDays).coerceAtLeast(0)
        val catchUp = missed.takeLast(maxCatchUpDays)   // 保留离今天最近的几天

        val days = buildList {
            // 补账的那几天：全套步骤都要跑（它们从未跑过，没有已完成记录）
            for (date in catchUp) add(DayPlan(Context(date, resetAt), STEP_ORDER))
            // 今天：跳过已完成的步骤，顺序不变
            add(DayPlan(context, STEP_ORDER.filterNot { it in completed }))
        }

        return Plan(days = days, discardReason = discardReason, skippedDays = skipped)
    }
}
