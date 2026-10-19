package com.tomatime.app.domain

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

private val WEEKDAYS = listOf("一", "二", "三", "四", "五", "六", "日")

fun wd(d: LocalDate): String = WEEKDAYS[d.dayOfWeek.value - 1]

/** "07:30"; values past midnight wrap (1500 -> "01:00"). */
fun fmtMin(m: Int): String {
    val mm = ((m % 1440) + 1440) % 1440
    return "%02d:%02d".format(mm / 60, mm % 60)
}

fun fmtDur(min: Int): String {
    val h = min / 60
    val m = min % 60
    return when {
        h == 0 -> "$m 分"
        m == 0 -> "$h 小時"
        m == 30 -> "$h.5 小時"
        else -> "$h 小時 $m 分"
    }
}

fun mondayOf(d: LocalDate): LocalDate = d.minusDays((d.dayOfWeek.value - 1).toLong())

fun at(d: LocalDate, min: Int): LocalDateTime = d.atStartOfDay().plusMinutes(min.toLong())

fun minOfDay(t: LocalDateTime, base: LocalDate): Int = ChronoUnit.MINUTES.between(base.atStartOfDay(), t).toInt()

fun minutesBetween(a: LocalDateTime, b: LocalDateTime): Int = ChronoUnit.MINUTES.between(a, b).toInt()

fun earlier(a: LocalDateTime, b: LocalDateTime): LocalDateTime = if (a.isBefore(b)) a else b

fun later(a: LocalDateTime, b: LocalDateTime): LocalDateTime = if (a.isAfter(b)) a else b

fun epoch(t: LocalDateTime): Long = t.toEpochSecond(ZoneOffset.UTC)

/** Next half-hour mark at or after [t]. */
fun roundUp30(t: LocalDateTime): LocalDateTime {
    val base = t.withSecond(0).withNano(0)
    val r = base.minute % 30
    val exact = r == 0 && t.second == 0 && t.nano == 0
    return if (exact) base else base.plusMinutes((30 - r).toLong())
}

fun weekTitle(monday: LocalDate): String {
    val thursday = monday.plusDays(3)
    return "${thursday.monthValue}月第${(thursday.dayOfMonth - 1) / 7 + 1}週"
}

fun dateLabel(d: LocalDate): String = "${d.monthValue}/${d.dayOfMonth}（${wd(d)}）"

fun rangeLabel(d: LocalDate, a: Int, b: Int): String = "${dateLabel(d)} ${fmtMin(a)}–${fmtMin(b)}（${fmtDur(b - a)}）"
