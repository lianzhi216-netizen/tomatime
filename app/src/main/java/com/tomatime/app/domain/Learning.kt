package com.tomatime.app.domain

import com.tomatime.app.data.AppData
import com.tomatime.app.data.Category
import com.tomatime.app.data.Defaults
import com.tomatime.app.data.Origin
import com.tomatime.app.data.Repeat
import com.tomatime.app.data.Status
import com.tomatime.app.data.TimePref
import java.time.LocalDate
import java.time.LocalDateTime

/** Learning here is plain statistics on the user's own records; nothing leaves the phone. */

const val MIN_SAMPLES = 5

fun bucketOf(startMin: Int): Int {
    val m = ((startMin % 1440) + 1440) % 1440
    return when {
        m < 12 * 60 -> 0
        m < 18 * 60 -> 1
        else -> 2
    }
}

fun bucketLabel(b: Int): String = when (b) {
    0 -> "早上"
    1 -> "下午"
    else -> "晚上"
}

fun bucketRange(b: Int): IntRange = when (b) {
    0 -> 0 until 12 * 60
    1 -> 12 * 60 until 18 * 60
    else -> 18 * 60 until 28 * 60
}

/** Remembers where a category was put (keeps the last five). */
fun recordPlacement(data: AppData, categoryId: String, startMin: Int): AppData {
    if (categoryId == Defaults.SLEEP) return data
    val list = (data.placements[categoryId].orEmpty() + bucketOf(startMin)).takeLast(5)
    return data.copy(placements = data.placements + (categoryId to list))
}

/** The bucket to ask about when the last three placements of a category agree. */
fun prefToAsk(data: AppData, categoryId: String): Int? {
    if (categoryId == Defaults.SLEEP || categoryId in data.timePrefs || categoryId in data.declinedPrefs) return null
    val last = data.placements[categoryId].orEmpty().takeLast(3)
    return if (last.size == 3 && last.distinct().size == 1) last[0] else null
}

fun setPref(data: AppData, categoryId: String, bucket: Int, strength: Int): AppData =
    data.copy(timePrefs = data.timePrefs + (categoryId to TimePref(bucket, strength)))

fun declinePref(data: AppData, categoryId: String): AppData =
    data.copy(declinedPrefs = (data.declinedPrefs + categoryId).distinct())

private fun median(xs: List<Double>): Double {
    val s = xs.sorted()
    return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
}

/**
 * Actual ÷ planned time for finished tasks timed with the pomodoro, using the median so one
 * distracted day does not skew it. Null until there are enough samples.
 */
fun durationRatio(data: AppData, categoryId: String): Double? {
    val byOcc = data.pomoSessions.filter { it.categoryId == categoryId && it.occId != null }.groupBy { it.occId!! }
    val ratios = byOcc.mapNotNull { (occId, sessions) ->
        val occ = findOcc(data, occId) ?: return@mapNotNull null
        if (occ.status?.status != Status.DONE || occ.durationMin <= 0) return@mapNotNull null
        val actual = sessions.sumOf { sessionMinutes(it) }
        if (actual <= 0) null else actual.toDouble() / occ.durationMin
    }
    return if (ratios.size >= MIN_SAMPLES) median(ratios) else null
}

/** Suggested estimate (rounded to 30 minutes) when the learned ratio differs from 1 by 15%+. */
fun suggestedEstimate(data: AppData, categoryId: String, estimateMin: Int): Int? {
    val r = durationRatio(data, categoryId) ?: return null
    if (r in 0.85..1.15) return null
    val v = (((estimateMin * r) / 30.0).let { kotlin.math.round(it) } * 30).toInt().coerceAtLeast(30)
    return if (v == estimateMin) null else v
}

/** "Most of the time at least" this long: the 20th percentile of finished or logged lengths. */
fun typicalMinBlock(data: AppData, categoryId: String): Int? {
    val lens = data.events.filter { it.categoryId == categoryId && it.repeat == Repeat.NONE }
        .filter { e -> e.origin == Origin.LOGGED || e.statuses.values.any { it.status == Status.DONE } }
        .map { it.durationMin }
        .sorted()
    if (lens.size < MIN_SAMPLES) return null
    return lens[(lens.size * 0.2).toInt().coerceAtMost(lens.size - 1)]
}

/** Focus length that matches how long focus actually lasts, when it is clearly shorter. */
fun suggestedFocus(data: AppData, categoryId: String): Int? {
    val sessions = data.pomoSessions.filter { it.categoryId == categoryId }.takeLast(10)
    if (sessions.size < MIN_SAMPLES) return null
    val early = sessions.filter { !it.completed }
    if (early.size * 2 < sessions.size) return null
    val m = median(early.map { sessionMinutes(it).toDouble() }).toInt()
    val v = ((m + 2) / 5 * 5).coerceAtLeast(10)
    return if (v < pomoCfg(data, categoryId).focusMin) v else null
}

/** Sorts or filters 幫我排 candidates by the learned time-of-day preference. */
fun applyTimePref(data: AppData, categoryId: String, slots: List<LocalDateTime>): List<LocalDateTime> {
    val p = data.timePrefs[categoryId] ?: return slots
    val inBucket = slots.filter { bucketOf(it.hour * 60 + it.minute) == p.bucket }
    return when {
        p.strength >= 5 -> inBucket
        p.strength >= 2 -> inBucket + (slots - inBucket.toSet())
        else -> slots
    }
}

/** A repeated title in 其他 worth its own category (three or more times). */
fun newCategoryIdea(data: AppData): String? {
    val names = data.categories.map { it.name }.toSet()
    return data.events.filter { it.categoryId == "other" && it.title.isNotBlank() }
        .groupingBy { it.title.trim() }.eachCount()
        .filter { (t, n) -> n >= 3 && t !in names && t !in data.declinedNewCats }
        .maxByOrNull { it.value }?.key
}

private val NEW_COLORS = listOf(0xFF6C8EBF, 0xFFC97B63, 0xFF7FA36B, 0xFFB57EDC, 0xFFD4A63A, 0xFF5BA3A0)

/** Creates the category and moves the matching 其他 events into it. */
fun addCategoryFromTitle(data: AppData, title: String): AppData {
    val id = "c" + newId()
    val cat = Category(id, title.take(6), "📌", NEW_COLORS[data.categories.size % NEW_COLORS.size], taskLike = true)
    val cats = data.categories.toMutableList()
    val sleepAt = cats.indexOfFirst { it.system }
    if (sleepAt >= 0) cats.add(sleepAt, cat) else cats.add(cat)
    return data.copy(
        categories = cats,
        events = data.events.map { if (it.categoryId == "other" && it.title.trim() == title) it.copy(categoryId = id) else it },
    )
}

/** Blank half-hours (as gap count) in today's waking time up to now, for the 補登 reminder. */
fun blankGaps(data: AppData, day: LocalDate, until: LocalDateTime): Int {
    val (ws, we) = dayWindow(data, day)
    val end = earlier(we, until)
    if (!ws.isBefore(end)) return 0
    val occs = occurrences(data, day, day).filter { !it.hidden }.sortedBy { epoch(it.start) }
    var gaps = 0
    var t = ws
    for (o in occs) {
        if (!o.end.isAfter(t)) continue
        if (!o.start.isBefore(end)) break
        if (minutesBetween(t, o.start) >= 30) gaps++
        t = later(t, o.end)
    }
    if (minutesBetween(t, end) >= 30) gaps++
    return gaps
}
