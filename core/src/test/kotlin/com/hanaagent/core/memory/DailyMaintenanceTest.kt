package com.hanaagent.core.memory

import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Spike C —— 逻辑日与补偿式调度。
 *
 * 两件事：把上游的日界线与断点续跑语义钉死；再验证 Android 特有的"欠账补偿"。
 */
class DailyMaintenanceTest {

    private val tz: ZoneId = ZoneId.of("Asia/Shanghai")
    private fun at(iso: String): ZonedDateTime = ZonedDateTime.parse(iso + "[Asia/Shanghai]")

    // ── 逻辑日 ────────────────────────────────────────────────

    @Test
    fun `日界线在凌晨四点`() {
        // 03:59 仍算前一天
        assertEquals("2026-08-23", LogicalDay.of(at("2026-08-24T03:59:59+08:00")).logicalDate)
        // 04:00 整翻页
        assertEquals("2026-08-24", LogicalDay.of(at("2026-08-24T04:00:00+08:00")).logicalDate)
        assertEquals("2026-08-24", LogicalDay.of(at("2026-08-24T23:59:59+08:00")).logicalDate)
        // 午夜之后、四点之前，属于前一天 —— 这正是"熬夜聊天算前一天"的语义
        assertEquals("2026-08-24", LogicalDay.of(at("2026-08-25T02:30:00+08:00")).logicalDate)
    }

    @Test
    fun `逻辑日的区间是从当天四点到次日四点`() {
        val day = LogicalDay.of(at("2026-08-24T10:00:00+08:00"))
        assertEquals(4, day.rangeStart.hour)
        assertEquals("2026-08-24", day.rangeStart.toLocalDate().toString())
        assertEquals("2026-08-25", day.rangeEnd.toLocalDate().toString())
        assertEquals(4, day.rangeEnd.hour)
    }

    @Test
    fun `日期偏移跨月跨年跨闰日都正确`() {
        assertEquals("2026-09-01", LogicalDay.shift("2026-08-31", 1))
        assertEquals("2026-08-31", LogicalDay.shift("2026-09-01", -1))
        assertEquals("2027-01-01", LogicalDay.shift("2026-12-31", 1))
        // 2028 是闰年
        assertEquals("2028-02-29", LogicalDay.shift("2028-02-28", 1))
        assertEquals("2028-03-01", LogicalDay.shift("2028-02-29", 1))
        // 非闰年
        assertEquals("2026-03-01", LogicalDay.shift("2026-02-28", 1))
    }

    @Test
    fun `非法日期回落而不是抛异常`() {
        val now = at("2026-08-24T10:00:00+08:00")
        // 2 月 30 日不存在，回落到今天
        assertEquals("2026-08-24", LogicalDay.forDate("2026-02-30", now).logicalDate)
        assertEquals("2026-08-24", LogicalDay.forDate("乱写的", now).logicalDate)
        assertEquals("2026-08-24", LogicalDay.forDate(null, now).logicalDate)
        // shift 对非法输入原样返回
        assertEquals("乱写的", LogicalDay.shift("乱写的", 1))
    }

    // ── 步骤顺序的硬约束 ──────────────────────────────────────

    @Test
    fun `compileDaily 永远排在 compileToday 之前`() {
        // 这条约束一旦破坏，昨天的记忆会被静默清空且无法恢复
        val daily = DailyMaintenance.STEP_ORDER.indexOf("compileDaily")
        val today = DailyMaintenance.STEP_ORDER.indexOf("compileToday")
        assertTrue(daily >= 0 && today >= 0, "步骤名写错了")
        assertTrue(daily < today, "compileDaily 必须先于 compileToday，否则昨天的草稿会被日期切换清空")

        // 任何计划里都必须保持这个相对顺序
        val plan = DailyMaintenance.plan(
            persisted = null,
            now = at("2026-08-24T05:00:00+08:00"),
            lastCompletedLogicalDate = "2026-08-20",
        )
        for (day in plan.days) {
            val d = day.pendingSteps.indexOf("compileDaily")
            val t = day.pendingSteps.indexOf("compileToday")
            if (d >= 0 && t >= 0) {
                assertTrue(d < t, "${day.context.logicalDate} 的计划里顺序被破坏：${day.pendingSteps}")
            }
        }
    }

    // ── 断点续跑 ──────────────────────────────────────────────

    @Test
    fun `已完成的步骤在同一天内不会重跑`() {
        val now = at("2026-08-24T10:00:00+08:00")
        val state = DailyMaintenance.State(
            schemaVersion = DailyMaintenance.SCHEMA_VERSION,
            logicalDate = "2026-08-24",
            resetAt = null,
            completedSteps = mapOf(
                "compileDaily" to "2026-08-24T04:01:00Z",
                "compileToday" to "2026-08-24T04:02:00Z",
            ),
            dailyCompletedAt = null,
        )
        val plan = DailyMaintenance.plan(state, now)
        assertEquals(DailyMaintenance.DiscardReason.NONE, plan.discardReason)
        assertEquals(1, plan.days.size)
        assertEquals(listOf("rollDailyWindow", "compileFacts", "deepMemory"), plan.days.single().pendingSteps)
    }

    @Test
    fun `schema 版本变了就整天重算`() {
        val state = DailyMaintenance.State(
            schemaVersion = DailyMaintenance.SCHEMA_VERSION - 1,
            logicalDate = "2026-08-24",
            resetAt = null,
            completedSteps = DailyMaintenance.STEP_ORDER.associateWith { "2026-08-24T04:00:00Z" },
            dailyCompletedAt = "2026-08-24T04:05:00Z",
        )
        val plan = DailyMaintenance.plan(state, at("2026-08-24T10:00:00+08:00"))
        assertEquals(DailyMaintenance.DiscardReason.SCHEMA_CHANGED, plan.discardReason)
        assertEquals(DailyMaintenance.STEP_ORDER, plan.days.single().pendingSteps)
    }

    @Test
    fun `用户重置记忆后已完成的步骤全部作废`() {
        val state = DailyMaintenance.State(
            schemaVersion = DailyMaintenance.SCHEMA_VERSION,
            logicalDate = "2026-08-24",
            resetAt = "2026-08-01T00:00:00Z",
            completedSteps = DailyMaintenance.STEP_ORDER.associateWith { "2026-08-24T04:00:00Z" },
            dailyCompletedAt = "2026-08-24T04:05:00Z",
        )
        // resetAt 变了 = 旧编译产物作废
        val plan = DailyMaintenance.plan(
            state,
            at("2026-08-24T10:00:00+08:00"),
            resetAt = "2026-08-24T09:00:00Z",
        )
        assertEquals(DailyMaintenance.DiscardReason.MEMORY_RESET, plan.discardReason)
        assertEquals(DailyMaintenance.STEP_ORDER, plan.days.single().pendingSteps)
    }

    @Test
    fun `跨过日界线后昨天的完成记录不再适用`() {
        val state = DailyMaintenance.State(
            schemaVersion = DailyMaintenance.SCHEMA_VERSION,
            logicalDate = "2026-08-23",
            resetAt = null,
            completedSteps = DailyMaintenance.STEP_ORDER.associateWith { "2026-08-23T04:00:00Z" },
            dailyCompletedAt = "2026-08-23T04:05:00Z",
        )
        val plan = DailyMaintenance.plan(
            state,
            at("2026-08-24T05:00:00+08:00"),
            lastCompletedLogicalDate = "2026-08-23",
        )
        assertEquals(DailyMaintenance.DiscardReason.DATE_CHANGED, plan.discardReason)
        // 昨天刚跑完，今天没有欠账，只做今天
        assertEquals(1, plan.days.size)
        assertEquals(DailyMaintenance.STEP_ORDER, plan.days.single().pendingSteps)
    }

    // ── 欠账补偿（Android 特有）────────────────────────────────

    @Test
    fun `App 关了几天，欠的账按时间顺序补`() {
        val plan = DailyMaintenance.plan(
            persisted = null,
            now = at("2026-08-24T10:00:00+08:00"),
            lastCompletedLogicalDate = "2026-08-21",
        )
        // 漏掉 22、23，加上今天 24
        assertEquals(listOf("2026-08-22", "2026-08-23", "2026-08-24"), plan.days.map { it.context.logicalDate })
        assertEquals(0, plan.skippedDays)
        // 补账的日子要跑全套
        assertEquals(DailyMaintenance.STEP_ORDER, plan.days.first().pendingSteps)
    }

    @Test
    fun `欠账超过上限时截断，且明确报出被跳过的天数`() {
        val plan = DailyMaintenance.plan(
            persisted = null,
            now = at("2026-08-24T10:00:00+08:00"),
            lastCompletedLogicalDate = "2026-08-10",   // 关了两周
            maxCatchUpDays = 3,
        )
        // 只补最近 3 天 + 今天
        assertEquals(4, plan.days.size)
        assertEquals(listOf("2026-08-21", "2026-08-22", "2026-08-23", "2026-08-24"), plan.days.map { it.context.logicalDate })
        // 11..20 共 10 天被跳过，不静默丢弃
        assertEquals(10, plan.skippedDays)
    }

    @Test
    fun `昨天刚跑完则没有欠账`() {
        val plan = DailyMaintenance.plan(
            persisted = null,
            now = at("2026-08-24T10:00:00+08:00"),
            lastCompletedLogicalDate = "2026-08-23",
        )
        assertEquals(listOf("2026-08-24"), plan.days.map { it.context.logicalDate })
        assertEquals(0, plan.skippedDays)
    }

    @Test
    fun `首次运行不产生欠账`() {
        val plan = DailyMaintenance.plan(
            persisted = null,
            now = at("2026-08-24T10:00:00+08:00"),
            lastCompletedLogicalDate = null,
        )
        assertEquals(listOf("2026-08-24"), plan.days.map { it.context.logicalDate })
        assertEquals(0, plan.skippedDays)
    }

    @Test
    fun `同一天全部跑完后计划为空 —— 可重复调用不会重跑`() {
        val now = at("2026-08-24T20:00:00+08:00")
        val state = DailyMaintenance.State(
            schemaVersion = DailyMaintenance.SCHEMA_VERSION,
            logicalDate = "2026-08-24",
            resetAt = null,
            completedSteps = DailyMaintenance.STEP_ORDER.associateWith { "2026-08-24T04:00:00Z" },
            dailyCompletedAt = "2026-08-24T04:05:00Z",
        )
        val plan = DailyMaintenance.plan(state, now, lastCompletedLogicalDate = "2026-08-23")
        assertTrue(plan.isEmpty, "全部完成后不应再有待办：${plan.days}")
        assertEquals(0, plan.totalSteps)
    }

    @Test
    fun `熬夜场景：凌晨三点打开 App，算的是前一天的账`() {
        // 用户 25 号凌晨 3 点还在用，逻辑上仍是 24 号
        val plan = DailyMaintenance.plan(
            persisted = null,
            now = at("2026-08-25T03:00:00+08:00"),
            lastCompletedLogicalDate = "2026-08-23",
        )
        assertEquals(listOf("2026-08-24"), plan.days.map { it.context.logicalDate })
    }
}
