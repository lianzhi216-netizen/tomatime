package com.tomatime.app.system

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.tomatime.app.TomatimeApp
import com.tomatime.app.data.AppData
import com.tomatime.app.data.ErrorLog
import com.tomatime.app.data.Origin
import com.tomatime.app.data.PomoPhase
import com.tomatime.app.domain.Occ
import com.tomatime.app.domain.blankGaps
import com.tomatime.app.domain.dayWindow
import com.tomatime.app.domain.displayTitle
import com.tomatime.app.domain.fmtMin
import com.tomatime.app.domain.isTask
import com.tomatime.app.domain.occurrences
import com.tomatime.app.domain.pomoAdvance
import com.tomatime.app.domain.pomoCfg
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

private fun millis(t: LocalDateTime): Long = t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
private fun ldt(ms: Long): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault())

/** One thing that should happen at a moment: a notification, a timer step or starting the guard. */
private data class Due(val key: String, val at: Long, val run: (Context) -> Unit)

/**
 * Everything time-based runs off a single exact alarm: when it fires, due items run and the
 * next alarm is set. Each item has a key so it never runs twice.
 */
object Ticks {
    private const val PREFS = "ticks"
    private const val GRACE = 90_000L

    fun store(ctx: Context) = (ctx.applicationContext as TomatimeApp).store

    private fun sent(ctx: Context): MutableSet<String> =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet("sent", emptySet())!!.toMutableSet()

    private fun markSent(ctx: Context, key: String) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val keep = sent(ctx).filter { (it.substringAfterLast('|').toLongOrNull() ?: 0L) > now - 3 * 86_400_000L }.toMutableSet()
        keep += "$key|$now"
        p.edit().putStringSet("sent", keep).apply()
    }

    private fun wasSent(ctx: Context, key: String) = sent(ctx).any { it.substringBeforeLast('|') == key }

    fun bootDelayUntil(ctx: Context): Long = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("bootDelay", 0L)
    fun setBootDelay(ctx: Context, until: Long) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("bootDelay", until).apply()

    fun guardOffNight(ctx: Context): String? = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("guardOff", null)
    fun setGuardOffNight(ctx: Context, night: String?) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("guardOff", night).apply()

    /** The sleep occurrence covering [now], if any. */
    fun sleepNow(data: AppData, now: LocalDateTime): Occ? =
        occurrences(data, now.toLocalDate().minusDays(1), now.toLocalDate())
            .firstOrNull { it.isSleep && !it.hidden && !it.start.isAfter(now) && it.end.isAfter(now) }

    private fun nextSleep(data: AppData, now: LocalDateTime): Occ? =
        occurrences(data, now.toLocalDate(), now.toLocalDate().plusDays(1))
            .firstOrNull { it.isSleep && !it.hidden && it.start.isAfter(now) }

    private fun items(ctx: Context, data: AppData, nowMs: Long): List<Due> {
        val s = data.settings
        val now = ldt(nowMs)
        val today = now.toLocalDate()
        val out = ArrayList<Due>()

        // Pomodoro phase end.
        data.pomoState?.let { p ->
            if (p.phase != PomoPhase.WAITING) out += Due("pomo-${p.phaseEnd}", p.phaseEnd) { c -> pomoPhaseEnded(c) }
        }

        // Before-start and at-start reminders.
        val occs = occurrences(data, today, today.plusDays(1)).filter { !it.hidden && !it.isSleep && it.event.origin == Origin.PLANNED }
        for (o in occs) {
            val task = isTask(data, o)
            if (!task && !s.remindFixed) continue
            val start = millis(o.start)
            val title = displayTitle(o.event, data)
            val emoji = data.categories.find { it.id == o.event.categoryId }?.emoji.orEmpty()
            if (s.remindBeforeMin > 0) {
                out += Due("before-${o.id}", start - s.remindBeforeMin * 60_000L) { c ->
                    Notifs.post(c, o.id.hashCode(), Notifs.base(c, Notifs.CH_REMIND, "${s.remindBeforeMin} 分鐘後：$emoji $title", "${fmtMin(o.startMin)} 開始")
                        .setContentIntent(Notifs.openIntent(c, "detail:${o.id}", o.id.hashCode())))
                }
            }
            if (task) {
                out += Due("start-${o.id}", start) { c ->
                    Notifs.post(c, o.id.hashCode(), Notifs.base(c, Notifs.CH_REMIND, "現在開始：$emoji $title", "到 ${fmtMin(o.endMin)}")
                        .setContentIntent(Notifs.openIntent(c, "detail:${o.id}", o.id.hashCode()))
                        .addAction(0, "開始番茄鐘", Notifs.openIntent(c, "pomo:${o.id}", o.id.hashCode() + 1)))
                }
            }
        }

        // Daily 補登 reminder: an hour before tonight's sleep, only when there are blanks.
        if (s.logReminder) {
            val (_, sleepStart) = dayWindow(data, today)
            val at = millis(sleepStart.minusHours(1))
            out += Due("log-$today", at) { c ->
                val d = store(c).data.value
                val gaps = blankGaps(d, today, ldt(System.currentTimeMillis()))
                if (gaps > 0) {
                    val open = if (d.settings.usageEnabled && Perms.usage(c)) "usage" else "log"
                    Notifs.post(c, Notifs.ID_LOG, Notifs.base(c, Notifs.CH_REMIND, "今天還有 $gaps 段空白", "花 30 秒補一下？")
                        .setContentIntent(Notifs.openIntent(c, open, Notifs.ID_LOG)))
                }
            }
        }

        // Backup reminder, checked at 20:00.
        val backupAt = millis(today.atTime(20, 0))
        out += Due("backup-$today", backupAt) { c ->
            val d = store(c).data.value
            val days = (System.currentTimeMillis() - d.settings.lastBackupAt) / 86_400_000L
            if (days >= d.settings.backupIntervalDays && d.events.any { !it.categoryId.startsWith("sleep") }) {
                Notifs.post(c, Notifs.ID_BACKUP, Notifs.base(c, Notifs.CH_REMIND, "已經 $days 天沒備份了", "點一下備份到雲端硬碟")
                    .setContentIntent(Notifs.openIntent(c, "backup", Notifs.ID_BACKUP)))
            }
        }

        // Sleep guard: start at the beginning of the night (not before the after-boot delay).
        if (s.guardEnabled) {
            val current = sleepNow(data, now)
            val night = current ?: nextSleep(data, now)
            if (night != null) {
                val at = maxOf(millis(night.start), bootDelayUntil(ctx))
                out += Due("guard-${night.id}-$at", at) { c ->
                    if (guardOffNight(c) != night.id) GuardianService.start(c)
                }
            }
        }

        // Refresh widgets when something starts or ends today.
        occurrences(data, today, today).filter { !it.hidden }.forEach { o ->
            out += Due("w-${o.id}-s", millis(o.start)) { c -> Widgets.refresh(c) }
            out += Due("w-${o.id}-e", millis(o.end)) { c -> Widgets.refresh(c) }
        }
        out += Due("w-midnight-$today", millis(today.plusDays(1).atStartOfDay()) + 60_000L) { c -> Widgets.refresh(c) }
        return out
    }

    private fun pomoPhaseEnded(c: Context) {
        val store = store(c)
        val before = store.data.value.pomoState ?: return
        store.update { pomoAdvance(it, System.currentTimeMillis() + 1_000L) }
        val after = store.data.value.pomoState ?: return
        val text = when {
            before.phase == PomoPhase.FOCUS && after.phase == PomoPhase.LONG_BREAK -> "完成第 ${after.done} 顆！長休息 ${pomoCfg(store.data.value, after.categoryId).longMin} 分鐘"
            before.phase == PomoPhase.FOCUS -> "完成第 ${after.done} 顆！休息 ${pomoCfg(store.data.value, after.categoryId).shortMin} 分鐘，起來動一動"
            else -> "休息結束，準備開始下一顆"
        }
        Notifs.post(c, Notifs.ID_POMO_ALERT, Notifs.base(c, Notifs.CH_POMO_ALERT, "🍅 時間到", text)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(Notifs.openIntent(c, "pomo", Notifs.ID_POMO_ALERT)))
    }

    /** Runs everything due now and sets the next alarm. */
    fun run(ctx: Context) {
        val now = System.currentTimeMillis()
        val data = store(ctx).data.value
        try {
            for (d in items(ctx, data, now).sortedBy { it.at }) {
                if (d.at <= now + 30_000L && d.at > now - GRACE * 40 && !wasSent(ctx, d.key)) {
                    markSent(ctx, d.key)
                    try {
                        d.run(ctx)
                    } catch (t: Throwable) {
                        ErrorLog.log("提醒執行失敗 ${d.key}", t)
                    }
                }
            }
            // Missed the start of the night (phone was off): start the guard now.
            val s = store(ctx).data.value.settings
            if (s.guardEnabled && !GuardianService.running && now >= bootDelayUntil(ctx)) {
                val night = sleepNow(store(ctx).data.value, ldt(now))
                if (night != null && guardOffNight(ctx) != night.id) GuardianService.start(ctx)
            }
        } catch (t: Throwable) {
            ErrorLog.log("排程檢查失敗", t)
        }
        schedule(ctx)
    }

    /** Sets one exact alarm for the next due item. */
    @android.annotation.SuppressLint("ScheduleExactAlarm", "MissingPermission")
    fun schedule(ctx: Context) {
        try {
            val now = System.currentTimeMillis()
            val data = store(ctx).data.value
            val next = items(ctx, data, now).filter { it.at > now + 1_000L && !wasSent(ctx, it.key) }.minOfOrNull { it.at }
                ?: (now + 6 * 3_600_000L)
            val am = ctx.getSystemService(AlarmManager::class.java) ?: return
            val pi = PendingIntent.getBroadcast(
                ctx, 1, Intent(ctx, TickReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            if (Perms.exactAlarms(ctx)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
        } catch (t: Throwable) {
            ErrorLog.log("設定鬧鐘失敗", t)
        }
    }

    fun today(): LocalDate = LocalDate.now()
}

class TickReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        Ticks.run(context)
        Widgets.refresh(context)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        // Give the user 15 minutes after a restart before the sleep guard can start.
        Ticks.setBootDelay(context, System.currentTimeMillis() + 15 * 60_000L)
        Ticks.schedule(context)
        Notifs.syncPomo(context, Ticks.store(context).data.value)
        Widgets.refresh(context)
    }
}

/** Buttons inside notifications that act without opening the app. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            GuardianService.ACTION_EMERGENCY_DONE, GuardianService.ACTION_EMERGENCY_EXTEND, GuardianService.ACTION_EMERGENCY ->
                GuardianService.send(context, intent.action!!)
        }
    }
}
