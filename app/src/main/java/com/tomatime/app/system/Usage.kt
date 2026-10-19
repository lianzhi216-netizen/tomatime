package com.tomatime.app.system

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.tomatime.app.data.AppData
import com.tomatime.app.data.ErrorLog
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** A stretch of continuous use of one app, read from Android's usage history. */
data class UsageBlock(
    val pkg: String,
    val label: String,
    val appKind: String,
    val start: LocalDateTime,
    val end: LocalDateTime,
) {
    val minutes: Int get() = java.time.Duration.between(start, end).toMinutes().toInt()
}

data class InstalledApp(val pkg: String, val label: String)

object Usage {
    private fun ldt(ms: Long) = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault())

    fun label(ctx: Context, pkg: String): String = try {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Throwable) {
        pkg.substringAfterLast('.')
    }

    /** Android's own app type: 遊戲, 影音, 社群… or empty when the app does not say. */
    fun kind(ctx: Context, pkg: String): String = try {
        when (ctx.packageManager.getApplicationInfo(pkg, 0).category) {
            ApplicationInfo.CATEGORY_GAME -> "遊戲"
            ApplicationInfo.CATEGORY_VIDEO -> "影音"
            ApplicationInfo.CATEGORY_AUDIO -> "音樂"
            ApplicationInfo.CATEGORY_SOCIAL -> "社群"
            ApplicationInfo.CATEGORY_NEWS -> "新聞"
            ApplicationInfo.CATEGORY_IMAGE -> "圖片"
            ApplicationInfo.CATEGORY_MAPS -> "地圖"
            ApplicationInfo.CATEGORY_PRODUCTIVITY -> "工具"
            else -> ""
        }
    } catch (_: Throwable) {
        ""
    }

    /** Suggested category for an app kind before the user has chosen one. */
    fun defaultCategory(kind: String): String? = when (kind) {
        "遊戲", "影音", "音樂", "社群", "新聞", "圖片" -> "leisure"
        else -> null
    }

    private fun homePackages(ctx: Context): Set<String> {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return ctx.packageManager.queryIntentActivities(i, PackageManager.MATCH_DEFAULT_ONLY).map { it.activityInfo.packageName }.toSet()
    }

    /** Continuous use per app between [from] and [to], merged across gaps under two minutes. */
    fun blocks(ctx: Context, from: Long, to: Long, minMinutes: Int, data: AppData): List<UsageBlock> {
        if (!Perms.usage(ctx)) return emptyList()
        return try {
            val usm = ctx.getSystemService(UsageStatsManager::class.java) ?: return emptyList()
            val skip = homePackages(ctx) + ctx.packageName + "com.android.systemui" + data.ignoredApps
            val events = usm.queryEvents(from, to)
            val e = UsageEvents.Event()
            val raw = ArrayList<Triple<String, Long, Long>>()
            var cur: String? = null
            var curStart = 0L
            fun close(at: Long) {
                val p = cur
                if (p != null && at > curStart) raw += Triple(p, curStart, at)
                cur = null
            }
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                when (e.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> {
                        if (cur != e.packageName) {
                            close(e.timeStamp)
                            cur = e.packageName
                            curStart = e.timeStamp
                        }
                    }
                    UsageEvents.Event.ACTIVITY_PAUSED -> if (cur == e.packageName) close(e.timeStamp)
                    UsageEvents.Event.SCREEN_NON_INTERACTIVE -> close(e.timeStamp)
                }
            }
            close(minOf(to, System.currentTimeMillis()))
            val merged = ArrayList<Triple<String, Long, Long>>()
            for (r in raw.filter { it.first !in skip }.sortedBy { it.second }) {
                val last = merged.lastOrNull()
                if (last != null && last.first == r.first && r.second - last.third < 120_000L) {
                    merged[merged.size - 1] = Triple(last.first, last.second, r.third)
                } else merged += r
            }
            merged.filter { (it.third - it.second) >= minMinutes * 60_000L }.map { (p, a, b) ->
                UsageBlock(p, label(ctx, p), kind(ctx, p), ldt(a), ldt(b))
            }
        } catch (t: Throwable) {
            ErrorLog.log("讀取手機使用紀錄失敗", t)
            emptyList()
        }
    }

    /** What the phone can tell us: 2 = detailed sessions, 1 = daily totals only, 0 = nothing. */
    fun capability(ctx: Context): Int {
        if (!Perms.usage(ctx)) return 0
        val usm = ctx.getSystemService(UsageStatsManager::class.java) ?: return 0
        val now = System.currentTimeMillis()
        val ev = usm.queryEvents(now - 86_400_000L, now)
        if (ev.hasNextEvent()) return 2
        return if (usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 86_400_000L, now).isNotEmpty()) 1 else 0
    }

    /** Minutes per app kind over a range, for the weekly review. */
    fun minutesByKind(ctx: Context, from: Long, to: Long): Map<String, Int> {
        if (!Perms.usage(ctx)) return emptyMap()
        return try {
            val usm = ctx.getSystemService(UsageStatsManager::class.java) ?: return emptyMap()
            usm.queryAndAggregateUsageStats(from, to)
                .filter { it.value.totalTimeInForeground > 60_000L }
                .entries.groupBy { kind(ctx, it.key).ifBlank { "其他 App" } }
                .mapValues { (_, v) -> (v.sumOf { it.value.totalTimeInForeground } / 60_000L).toInt() }
        } catch (t: Throwable) {
            ErrorLog.log("讀取使用時間失敗", t)
            emptyMap()
        }
    }

    /** Apps with a launcher icon, for the emergency whitelist. */
    fun launchableApps(ctx: Context): List<InstalledApp> {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val pm = ctx.packageManager
        val list = if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(i, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(i, 0)
        }
        return list.map { InstalledApp(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .filter { it.pkg != ctx.packageName }
            .distinctBy { it.pkg }
            .sortedBy { it.label }
    }
}
