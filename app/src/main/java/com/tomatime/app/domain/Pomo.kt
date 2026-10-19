package com.tomatime.app.domain

import com.tomatime.app.data.AppData
import com.tomatime.app.data.PomoCfg
import com.tomatime.app.data.PomoPhase
import com.tomatime.app.data.PomoSession
import com.tomatime.app.data.PomoState

private const val MINUTE = 60_000L

fun pomoCfg(data: AppData, categoryId: String): PomoCfg =
    data.settings.pomoByCategory[categoryId] ?: data.settings.pomo

fun pomoStart(data: AppData, occId: String?, categoryId: String, title: String, now: Long): AppData {
    val cfg = pomoCfg(data, categoryId)
    return data.copy(
        pomoState = PomoState(
            occId = occId, categoryId = categoryId, title = title, phase = PomoPhase.FOCUS,
            phaseStart = now, phaseEnd = now + cfg.focusMin * MINUTE,
        ),
    )
}

/** Moves past any phases that have ended (also catches up after the app was closed). */
fun pomoAdvance(data: AppData, now: Long): AppData {
    var d = data
    var guard = 0
    while (guard++ < 10) {
        val s = d.pomoState ?: return d
        if (s.phase == PomoPhase.WAITING || now < s.phaseEnd) return d
        val cfg = pomoCfg(d, s.categoryId)
        d = when (s.phase) {
            PomoPhase.FOCUS -> {
                val done = s.done + 1
                val long = cfg.longEvery > 0 && done % cfg.longEvery == 0
                val breakMin = if (long) cfg.longMin else cfg.shortMin
                d.copy(
                    pomoSessions = d.pomoSessions + PomoSession(newId(), s.categoryId, s.occId, s.phaseStart, s.phaseEnd, completed = true),
                    pomoState = s.copy(
                        phase = if (long) PomoPhase.LONG_BREAK else PomoPhase.SHORT_BREAK, done = done,
                        phaseStart = s.phaseEnd, phaseEnd = s.phaseEnd + breakMin * MINUTE,
                    ),
                )
            }
            else -> d.copy(pomoState = s.copy(phase = PomoPhase.WAITING, phaseStart = s.phaseEnd))
        }
    }
    return d
}

/** Starts the next focus round from a break or from waiting. */
fun pomoNext(data: AppData, now: Long): AppData {
    val s = data.pomoState ?: return data
    val cfg = pomoCfg(data, s.categoryId)
    return data.copy(pomoState = s.copy(phase = PomoPhase.FOCUS, phaseStart = now, phaseEnd = now + cfg.focusMin * MINUTE))
}

/** 放棄這顆: an interrupted pomodoro does not count; wait for a fresh start. */
fun pomoAbandon(data: AppData, now: Long): AppData {
    val s = data.pomoState ?: return data
    if (s.phase != PomoPhase.FOCUS) return data
    return data.copy(
        pomoSessions = data.pomoSessions + PomoSession(newId(), s.categoryId, s.occId, s.phaseStart, now, completed = false),
        pomoState = s.copy(phase = PomoPhase.WAITING, phaseStart = now, phaseEnd = now, interruptions = s.interruptions + 1),
    )
}

/** 結束任務: keeps the focus time already spent, then clears the timer. */
fun pomoStop(data: AppData, now: Long): AppData {
    val s = data.pomoState ?: return data
    val extra = if (s.phase == PomoPhase.FOCUS && now - s.phaseStart >= MINUTE) {
        listOf(PomoSession(newId(), s.categoryId, s.occId, s.phaseStart, now, completed = false))
    } else emptyList()
    return data.copy(pomoSessions = data.pomoSessions + extra, pomoState = null)
}

fun sessionMinutes(s: PomoSession): Int = ((s.end - s.start) / MINUTE).toInt()
