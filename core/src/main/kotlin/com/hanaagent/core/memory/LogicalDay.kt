package com.hanaagent.core.memory

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * 逻辑日 —— 上游 `lib/time-utils.ts` 的移植。
 *
 * 全系统以**凌晨 4:00** 为日界线：4:00 之前发生的事算前一天。记忆编译、日记、
 * 滚动摘要都共享这个定义，system prompt 里也会明确告诉模型「你的一天从 04:00 开始」。
 *
 * 上游用本机时区（`Date.getHours()`）。这里把时区做成显式参数：一是可测，
 * 二是 Android 上用户跨时区移动比桌面常见得多，把它藏在隐式默认里会很难查。
 */
object LogicalDay {

    const val DAY_BOUNDARY_HOUR = 4

    private val DATE_RE = Regex("^(\\d{4})-(\\d{2})-(\\d{2})$")
    private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    /** 一个逻辑日及其真实时间区间 `[rangeStart, rangeEnd)`。 */
    data class Day(
        val logicalDate: String,
        val rangeStart: ZonedDateTime,
        val rangeEnd: ZonedDateTime,
    )

    /** 当前时刻属于哪个逻辑日。 */
    fun of(now: ZonedDateTime): Day {
        val base = if (now.hour < DAY_BOUNDARY_HOUR) now.minusDays(1) else now
        return dayOf(base.toLocalDate(), now.zone)
    }

    fun of(instant: Instant, zone: ZoneId): Day = of(instant.atZone(zone))

    /**
     * 指定日期字符串对应的逻辑日。
     *
     * 非法输入（格式不对，或 `2026-02-30` 这种不存在的日期）回落到"现在"，
     * 与上游的 round-trip 校验行为一致 —— 宁可算今天，也不要抛异常打断记忆流水线。
     */
    fun forDate(dateString: String?, now: ZonedDateTime): Day {
        val match = dateString?.let { DATE_RE.matchEntire(it) } ?: return of(now)
        val (y, m, d) = match.destructured
        val date = runCatching { LocalDate.of(y.toInt(), m.toInt(), d.toInt()) }.getOrNull()
            ?: return of(now)
        // 上游会检查构造出的 Date 是否与输入吻合，用来挡掉 2 月 30 日这类溢出。
        // java.time 直接抛异常，上面 runCatching 已经等价覆盖。
        return dayOf(date, now.zone)
    }

    /**
     * 逻辑日期按天偏移（可为负）。纯日期算术，不涉及日界线小时数。
     * 非法输入原样返回 —— 与上游一致。
     */
    fun shift(dateString: String?, days: Long): String? {
        val match = dateString?.let { DATE_RE.matchEntire(it) } ?: return dateString
        val (y, m, d) = match.destructured
        val date = runCatching { LocalDate.of(y.toInt(), m.toInt(), d.toInt()) }.getOrNull()
            ?: return dateString
        return date.plusDays(days).format(DATE_FORMAT)
    }

    /** 两个逻辑日期之间相差几天；任一非法则返回 null。 */
    fun daysBetween(from: String?, to: String?): Long? {
        val a = parseOrNull(from) ?: return null
        val b = parseOrNull(to) ?: return null
        return java.time.temporal.ChronoUnit.DAYS.between(a, b)
    }

    private fun parseOrNull(dateString: String?): LocalDate? {
        val match = dateString?.let { DATE_RE.matchEntire(it) } ?: return null
        val (y, m, d) = match.destructured
        return runCatching { LocalDate.of(y.toInt(), m.toInt(), d.toInt()) }.getOrNull()
    }

    private fun dayOf(date: LocalDate, zone: ZoneId): Day {
        val start = ZonedDateTime.of(LocalDateTime.of(date, java.time.LocalTime.of(DAY_BOUNDARY_HOUR, 0)), zone)
        return Day(
            logicalDate = date.format(DATE_FORMAT),
            rangeStart = start,
            rangeEnd = start.plusDays(1),
        )
    }
}
