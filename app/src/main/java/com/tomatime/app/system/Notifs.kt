package com.tomatime.app.system

import android.Manifest
import android.app.AppOpsManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.tomatime.app.MainActivity
import com.tomatime.app.R
import com.tomatime.app.data.AppData
import com.tomatime.app.data.ErrorLog
import com.tomatime.app.data.PomoPhase

object Notifs {
    const val CH_REMIND = "remind"
    const val CH_POMO_ALERT = "pomo_alert"
    const val CH_POMO = "pomo_ongoing"
    const val CH_GUARD = "guard_alert"
    const val CH_GUARD_ON = "guard_ongoing"

    const val ID_POMO = 10
    const val ID_POMO_ALERT = 11
    const val ID_LOG = 20
    const val ID_BACKUP = 21
    const val ID_GUARD_ON = 30
    const val ID_GUARD_ALERT = 31
    const val ID_EMERGENCY = 32

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        val alarmAttrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        nm.createNotificationChannels(listOf(
            NotificationChannel(CH_REMIND, "行程提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "事情開始前、睡前補登、備份提醒"
            },
            NotificationChannel(CH_POMO_ALERT, "番茄鐘時間到", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(alarmSound, alarmAttrs)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 400, 200, 400)
            },
            NotificationChannel(CH_POMO, "番茄鐘倒數", NotificationManager.IMPORTANCE_LOW),
            NotificationChannel(CH_GUARD, "該睡覺了", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
            },
            NotificationChannel(CH_GUARD_ON, "睡眠守門員運作中", NotificationManager.IMPORTANCE_LOW),
        ))
    }

    fun canPost(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Opens the app; [open] tells it which screen or dialog to show. */
    fun openIntent(ctx: Context, open: String, requestCode: Int): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java)
            .putExtra("open", open)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(ctx, requestCode, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun broadcastIntent(ctx: Context, action: String, requestCode: Int, extra: String? = null): PendingIntent {
        val i = Intent(ctx, ActionReceiver::class.java).setAction(action).putExtra("extra", extra)
        return PendingIntent.getBroadcast(ctx, requestCode, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun base(ctx: Context, channel: String, title: String, text: String): NotificationCompat.Builder =
        NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)

    @android.annotation.SuppressLint("MissingPermission")
    fun post(ctx: Context, id: Int, b: NotificationCompat.Builder) {
        if (!canPost(ctx)) return
        try {
            NotificationManagerCompat.from(ctx).notify(id, b.build())
        } catch (t: SecurityException) {
            ErrorLog.log("通知權限不足", t)
        }
    }

    fun cancel(ctx: Context, id: Int) = NotificationManagerCompat.from(ctx).cancel(id)

    /** Keeps the pomodoro countdown in the notification shade and on the lock screen. */
    fun syncPomo(ctx: Context, data: AppData) {
        val s = data.pomoState
        if (s == null) {
            cancel(ctx, ID_POMO)
            return
        }
        val title = when (s.phase) {
            PomoPhase.FOCUS -> "🍅 專注中：${s.title}"
            PomoPhase.SHORT_BREAK, PomoPhase.LONG_BREAK -> "☕ 休息中"
            PomoPhase.WAITING -> "🍅 準備好下一顆了嗎？"
        }
        val b = base(ctx, CH_POMO, title, if (s.phase == PomoPhase.WAITING) "點一下開始" else "第 ${s.done + 1} 顆")
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openIntent(ctx, "pomo", ID_POMO))
        if (s.phase != PomoPhase.WAITING) {
            b.setUsesChronometer(true).setChronometerCountDown(true).setWhen(s.phaseEnd).setShowWhen(true)
        }
        post(ctx, ID_POMO, b)
    }
}

object Perms {
    fun overlay(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)

    fun usage(ctx: Context): Boolean {
        val ops = ctx.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun batteryUnrestricted(ctx: Context): Boolean =
        ctx.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(ctx.packageName) == true

    fun exactAlarms(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || ctx.getSystemService(android.app.AlarmManager::class.java)?.canScheduleExactAlarms() == true

    fun overlaySettings(ctx: Context) = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${ctx.packageName}"))
    fun usageSettings() = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
    fun appSettings(ctx: Context) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${ctx.packageName}"))

    @Suppress("BatteryLife")
    fun batterySettings(ctx: Context) = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:${ctx.packageName}"))

    /** Xiaomi's own autostart page; falls back to the app's settings page on other phones. */
    fun xiaomiAutostart(ctx: Context): Intent {
        val mi = Intent().setClassName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
        return if (mi.resolveActivity(ctx.packageManager) != null) mi else appSettings(ctx)
    }
}
