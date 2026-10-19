package com.tomatime.app.domain

import com.tomatime.app.data.AppData
import com.tomatime.app.data.Category
import com.tomatime.app.data.Defaults
import com.tomatime.app.data.Event
import com.tomatime.app.data.OccStatus
import com.tomatime.app.data.Origin
import com.tomatime.app.data.Repeat
import com.tomatime.app.data.Settings
import com.tomatime.app.data.Status
import com.tomatime.app.data.Unfinished
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(12)

/** One concrete appearance of an event on a given date. */
data class Occ(val event: Event, val key: String, val date: LocalDate) {
    val startMin: Int get() = event.startMin
    val durationMin: Int get() = event.durationMin
    val endMin: Int get() = event.startMin + event.durationMin
    val start: LocalDateTime get() = at(date, event.startMin)
    val end: LocalDateTime get() = start.plusMinutes(event.durationMin.toLong())
    val status: OccStatus? get() = event.statuses[key]
    val id: String get() = "${event.id}@$key"
    val hidden: Boolean get() = status?.replaced == true
    val isSleep: Boolean get() = event.categoryId == Defaults.SLEEP
}

fun Event.occursOn(d: LocalDate): Boolean {
    val s = LocalDate.parse(date)
    if (d.isBefore(s)) return false
    if (until != null && d.isAfter(LocalDate.parse(until))) return false
    val key = d.toString()
    if (key in deleted) return false
    return when (repeat) {
        Repeat.NONE -> d == s
        Repeat.WEEKLY -> ChronoUnit.DAYS.between(s, d) % 7 == 0L
        Repeat.BIWEEKLY -> ChronoUnit.DAYS.between(s, d) % 14 == 0L
        Repeat.MONTHLY -> d.dayOfMonth == s.dayOfMonth
    }
}

/** Occurrences touching [from]..[to] (inclusive), including ones from the night before that run past midnight. */
fun occurrences(data: AppData, from: LocalDate, to: LocalDate): List<Occ> {
    val out = ArrayList<Occ>()
    val fromStart = from.atStartOfDay()
    for (e in data.events) {
        var d = from.minusDays(1)
        while (!d.isAfter(to)) {
            if (e.occursOn(d)) {
                val o = Occ(e, d.toString(), d)
                if (!d.isBefore(from) || o.end.isAfter(fromStart)) out += o
            }
            d = d.plusDays(1)
        }
    }
    return out.sortedBy { epoch(it.start) }
}

fun findOcc(data: AppData, id: String): Occ? {
    val i = id.lastIndexOf('@')
    if (i < 0) return null
    val e = data.events.find { it.id == id.substring(0, i) } ?: return null
    val key = id.substring(i + 1)
    return Occ(e, key, LocalDate.parse(key))
}

fun cats(data: AppData): Map<String, Category> = data.categories.associateBy { it.id }

fun displayTitle(e: Event, data: AppData): String =
    e.title.ifBlank { data.categories.find { it.id == e.categoryId }?.name ?: "" }

fun isTask(data: AppData, o: Occ): Boolean =
    o.event.repeat == Repeat.NONE && o.event.origin == Origin.PLANNED &&
        (data.categories.find { it.id == o.event.categoryId }?.taskLike == true)

fun overlapping(data: AppData, a: LocalDateTime, b: LocalDateTime, excludeEventId: String? = null): List<Occ> =
    occurrences(data, a.toLocalDate(), b.toLocalDate()).filter {
        !it.hidden && it.event.id != excludeEventId && it.start.isBefore(b) && it.end.isAfter(a)
    }

// ---------- sleep & day windows ----------

private fun weekendDay(s: Settings, d: DayOfWeek) = s.splitWeekend && (d == DayOfWeek.SATURDAY || d == DayOfWeek.SUNDAY)
private fun weekendNight(s: Settings, d: DayOfWeek) = s.splitWeekend && (d == DayOfWeek.FRIDAY || d == DayOfWeek.SATURDAY)

fun wakeOn(s: Settings, date: LocalDate): Int = if (weekendDay(s, date.dayOfWeek)) s.weekendWakeMin else s.wakeMin
fun sleepStartFor(s: Settings, night: DayOfWeek): Int = if (weekendNight(s, night)) s.weekendSleepMin else s.sleepMin

/**
 * Turns the wake/sleep settings into seven weekly "睡覺" events starting today.
 * Older series are ended yesterday so past nights keep the times they had.
 */
fun regenerateSleep(data: AppData, today: LocalDate): AppData {
    val s = data.settings
    val kept = data.events.mapNotNull { e ->
        if (e.categoryId != Defaults.SLEEP || e.repeat == Repeat.NONE) e
        else {
            val start = LocalDate.parse(e.date)
            when {
                !start.isBefore(today) -> null
                e.until != null && LocalDate.parse(e.until).isBefore(today) -> e
                else -> e.copy(until = today.minusDays(1).toString())
            }
        }
    }
    val fresh = (0 until 7).map { i ->
        val date = today.plusDays(i.toLong())
        val start = sleepStartFor(s, date.dayOfWeek)
        val wake = wakeOn(s, date.plusDays(1))
        Event(
            id = "sleep-" + newId(), title = "睡覺", categoryId = Defaults.SLEEP,
            date = date.toString(), startMin = start, durationMin = 1440 + wake - start,
            repeat = Repeat.WEEKLY, createdAt = System.currentTimeMillis(),
        )
    }
    return data.copy(events = kept + fresh)
}

/** Waking window of a day: wake time to the start of that night's sleep. */
fun dayWindow(data: AppData, day: LocalDate): Pair<LocalDateTime, LocalDateTime> {
    val s = data.settings
    val ws = at(day, wakeOn(s, day))
    val sleep = occurrences(data, day, day).firstOrNull { it.date == day && it.isSleep && !it.hidden }
    val we = sleep?.start ?: at(day, sleepStartFor(s, day.dayOfWeek))
    return ws to we
}

/** Rows shown on the grid: earliest wake hour to latest sleep hour (capped at midnight). */
fun gridRange(s: Settings): Pair<Int, Int> {
    val wake = if (s.splitWeekend) minOf(s.wakeMin, s.weekendWakeMin) else s.wakeMin
    val sleep = if (s.splitWeekend) maxOf(s.sleepMin, s.weekendSleepMin) else s.sleepMin
    val start = (wake / 60) * 60
    val end = minOf(1440, ((sleep + 59) / 60) * 60)
    return start to maxOf(end, start + 360)
}

// ---------- 幫我排 ----------

/**
 * Candidate start times before the deadline, earliest first, at least an hour apart.
 * Prefers slots with the flexible gap free on both sides; falls back to tight slots.
 */
fun suggest(data: AppData, estimateMin: Int, deadline: LocalDate, now: LocalDateTime): List<LocalDateTime> {
    val s = data.settings
    val roomy = ArrayList<LocalDateTime>()
    val tight = ArrayList<LocalDateTime>()
    val earliest = roundUp30(now)
    var day = now.toLocalDate()
    var guard = 0
    while (!day.isAfter(deadline) && guard < 60) {
        guard++
        val (ws, we) = dayWindow(data, day)
        val occs = occurrences(data, day, day.plusDays(1)).filter { !it.hidden }
        val load = occs.filter { it.date == day && isTask(data, it) }.sumOf { it.durationMin }
        if (load + estimateMin <= s.dailyTaskCapMin) {
            var t = later(ws, earliest)
            if (t.minute % 30 != 0) t = roundUp30(t)
            var steps = 0
            while (!t.plusMinutes(estimateMin.toLong()).isAfter(we) && steps < 96) {
                steps++
                val e = t.plusMinutes(estimateMin.toLong())
                if (occs.none { it.start.isBefore(e) && it.end.isAfter(t) }) {
                    val b = s.bufferMin.toLong()
                    val gapFree = occs.none { it.start.isBefore(e.plusMinutes(b)) && it.end.isAfter(t.minusMinutes(b)) }
                    if (gapFree) roomy += t else tight += t
                }
                t = t.plusMinutes(30)
            }
        }
        day = day.plusDays(1)
    }
    val base = if (roomy.isNotEmpty()) roomy else tight
    val out = ArrayList<LocalDateTime>()
    for (c in base) if (out.none { kotlin.math.abs(ChronoUnit.MINUTES.between(it, c)) < 60 }) out += c
    return out
}

// ---------- edits ----------

enum class EditScope { ONLY_THIS, THIS_AND_FUTURE }

data class EditValues(
    val title: String,
    val categoryId: String,
    val date: LocalDate,
    val startMin: Int,
    val durationMin: Int,
    val repeat: Repeat,
)

fun applyEdit(data: AppData, occ: Occ, scope: EditScope, v: EditValues): AppData {
    val e = occ.event
    val newKey = v.date.toString()
    fun withV(x: Event) = x.copy(
        title = v.title, categoryId = v.categoryId, date = newKey,
        startMin = v.startMin, durationMin = v.durationMin, repeat = v.repeat,
    )
    if (e.repeat == Repeat.NONE) {
        return data.copy(events = data.events.map {
            if (it.id != e.id) it
            else withV(it).copy(statuses = it.statuses.mapKeys { (k, _) -> if (k == e.date) newKey else k })
        })
    }
    return when (scope) {
        EditScope.ONLY_THIS -> {
            val one = withV(e).copy(
                id = newId(), repeat = Repeat.NONE, until = null, deleted = emptyList(),
                statuses = e.statuses[occ.key]?.let { mapOf(newKey to it) } ?: emptyMap(),
            )
            data.copy(events = data.events.map { if (it.id == e.id) it.copy(deleted = it.deleted + occ.key) else it } + one)
        }
        EditScope.THIS_AND_FUTURE ->
            if (occ.key == e.date) data.copy(events = data.events.map { if (it.id == e.id) withV(it) else it })
            else {
                val next = withV(e).copy(
                    id = newId(), until = e.until,
                    deleted = e.deleted.filter { it >= occ.key },
                    statuses = e.statuses.filterKeys { it >= occ.key },
                )
                data.copy(events = data.events.map {
                    if (it.id != e.id) it
                    else it.copy(
                        until = occ.date.minusDays(1).toString(),
                        deleted = it.deleted.filter { d -> d < occ.key },
                        statuses = it.statuses.filterKeys { k -> k < occ.key },
                    )
                } + next)
            }
    }
}

fun applyDelete(data: AppData, occ: Occ, scope: EditScope): AppData {
    val e = occ.event
    val d = data.copy(unfinished = data.unfinished.filter { it.sourceKey != occ.id })
    return when {
        e.repeat == Repeat.NONE || (scope == EditScope.THIS_AND_FUTURE && occ.key == e.date) ->
            d.copy(events = d.events.filter { it.id != e.id })
        scope == EditScope.ONLY_THIS ->
            d.copy(events = d.events.map { if (it.id == e.id) it.copy(deleted = it.deleted + occ.key) else it })
        else ->
            d.copy(events = d.events.map { if (it.id == e.id) it.copy(until = occ.date.minusDays(1).toString()) else it })
    }
}

/** ✅ 🟡 ❌ feedback. Unfinished tasks go to the unfinished list; fixed things only keep the status. */
fun setStatus(data: AppData, occ: Occ, st: Status?, remainingMin: Int = 0): AppData {
    var d = data.copy(unfinished = data.unfinished.filter { it.sourceKey != occ.id })
    d = d.copy(events = d.events.map {
        if (it.id != occ.event.id) it
        else it.copy(statuses = if (st == null) it.statuses - occ.key else it.statuses + (occ.key to OccStatus(st, remainingMin)))
    })
    if (st != null && st != Status.DONE && isTask(data, occ)) {
        val e = occ.event
        val total = if (e.totalMin > 0) e.totalMin else occ.durationMin
        val done = if (st == Status.PARTIAL) (total - remainingMin).coerceAtLeast(0) else e.doneBeforeMin
        if (total - done > 0) {
            d = d.copy(unfinished = d.unfinished + Unfinished(
                id = newId(), title = displayTitle(e, data), categoryId = e.categoryId,
                totalMin = total, doneMin = done, deadline = e.deadline, importance = e.importance,
                createdAt = System.currentTimeMillis(), sourceKey = occ.id,
            ))
        }
    }
    return d
}

/**
 * Removes [a, b) from an occurrence. The longest remaining piece (if 30+ minutes) is kept.
 * With [markMissed] the covered part counts as not done: a fully covered occurrence stays
 * as a hidden ❌ record, and a task's covered minutes go to the unfinished list.
 */
fun carveOut(data: AppData, occ: Occ, a: LocalDateTime, b: LocalDateTime, markMissed: Boolean): AppData {
    var d = data
    var e = occ.event
    if (e.repeat != Repeat.NONE) {
        val one = e.copy(
            id = newId(), repeat = Repeat.NONE, until = null, deleted = emptyList(), date = occ.key,
            statuses = e.statuses[occ.key]?.let { mapOf(occ.key to it) } ?: emptyMap(),
        )
        d = d.copy(events = d.events.map { if (it.id == e.id) it.copy(deleted = it.deleted + occ.key) else it } + one)
        e = one
    }
    val s = occ.start
    val en = occ.end
    val pieces = ArrayList<Pair<LocalDateTime, LocalDateTime>>()
    if (s.isBefore(a)) pieces += s to earlier(a, en)
    if (b.isBefore(en)) pieces += later(b, s) to en
    val overlap = minutesBetween(later(a, s), earlier(b, en)).coerceAtLeast(0)
    val keep = pieces.filter { minutesBetween(it.first, it.second) >= 30 }
        .maxByOrNull { minutesBetween(it.first, it.second) }
    val eid = e.id
    val ekey = e.date
    d = if (keep == null) {
        if (markMissed) d.copy(events = d.events.map {
            if (it.id == eid) it.copy(statuses = it.statuses + (ekey to OccStatus(Status.MISSED, replaced = true))) else it
        })
        else d.copy(events = d.events.filter { it.id != eid })
    } else {
        val nd = keep.first.toLocalDate()
        d.copy(events = d.events.map {
            if (it.id != eid) it
            else it.copy(
                date = nd.toString(), startMin = minOfDay(keep.first, nd),
                durationMin = minutesBetween(keep.first, keep.second),
                statuses = it.statuses.mapKeys { (k, _) -> if (k == ekey) nd.toString() else k },
            )
        })
    }
    if (markMissed && overlap > 0 && isTask(data, occ)) {
        d = d.copy(unfinished = d.unfinished + Unfinished(
            id = newId(), title = displayTitle(occ.event, data), categoryId = occ.event.categoryId,
            totalMin = overlap, doneMin = 0, deadline = occ.event.deadline, importance = occ.event.importance,
            createdAt = System.currentTimeMillis(),
        ))
    }
    return d
}

/** Category order for the register bar: most used first, then the default order. */
fun usageOrder(data: AppData, origin: Origin): List<Category> {
    val counts = data.events.filter { it.origin == origin }.groupingBy { it.categoryId }.eachCount()
    val defaults = if (origin == Origin.PLANNED) Defaults.planOrder else Defaults.logOrder
    return data.categories.filter { !it.system }.sortedWith(
        compareByDescending<Category> { counts[it.id] ?: 0 }.thenBy { defaults.indexOf(it.id).let { i -> if (i < 0) 99 else i } }
    )
}
