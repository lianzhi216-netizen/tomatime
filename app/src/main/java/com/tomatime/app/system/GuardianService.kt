package com.tomatime.app.system

import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.tomatime.app.R
import com.tomatime.app.data.ErrorLog
import com.tomatime.app.data.SleepNight
import com.tomatime.app.domain.fmtMin
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Runs only during the night. While the phone is unlocked it reminds the user to sleep,
 * at 5 → 3 → 1 → 0.5 minute intervals. Safety rules:
 * - a reminder window always closes by itself after 60 seconds;
 * - no new reminder while one is still showing;
 * - never during a call, never over whitelisted apps in emergency mode;
 * - stops at the end of the night or after 12 hours at most; app crash removes its windows.
 */
class GuardianService : Service() {

    companion object {
        const val ACTION_EMERGENCY = "com.tomatime.EMERGENCY"
        const val ACTION_EMERGENCY_DONE = "com.tomatime.EMERGENCY_DONE"
        const val ACTION_EMERGENCY_EXTEND = "com.tomatime.EMERGENCY_EXTEND"
        const val ACTION_STOP = "com.tomatime.GUARD_STOP"
        private const val EXTRA_TEST = "test"

        @Volatile
        var running = false
            private set

        fun start(ctx: Context, test: Boolean = false) {
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, GuardianService::class.java).putExtra(EXTRA_TEST, test))
            } catch (t: Throwable) {
                ErrorLog.log("無法啟動睡眠守門員", t)
                Notifs.post(ctx, Notifs.ID_GUARD_ALERT, Notifs.base(ctx, Notifs.CH_GUARD, "睡眠守門員沒有啟動", "點一下打開 Tomatime 啟動")
                    .setContentIntent(Notifs.openIntent(ctx, "guardStart", Notifs.ID_GUARD_ALERT)))
            }
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, GuardianService::class.java).setAction(ACTION_STOP))
        }

        fun send(ctx: Context, action: String) {
            if (running) ctx.startService(Intent(ctx, GuardianService::class.java).setAction(action))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var test = false
    private var nightId: String? = null
    private var endAt = 0L
    private var level = 0
    private var overlay: View? = null
    private var unlocked = false
    private var screenOffAt = 0L
    private var usingSince = 0L
    private var phoneMs = 0L
    private var reminders = 0
    private var emergencies = 0
    private var emergencyMs = 0L
    private var emergencyUntil = 0L
    private var emergencyAll = false
    private var emergencyStartedAt = 0L

    private val intervals: List<Long>
        get() = if (test) listOf(10_000L, 6_000L, 4_000L, 3_000L) else listOf(300_000L, 180_000L, 60_000L, 30_000L)

    private val fire = Runnable { remind() }
    private val autoClose = Runnable { closeOverlay() }
    private val endCheck = object : Runnable {
        override fun run() {
            if (System.currentTimeMillis() >= endAt) finishNight() else handler.postDelayed(this, 60_000L)
        }
    }
    private val emergencyAsk = Runnable { askEmergency() }
    private val emergencyEnd = Runnable { endEmergency() }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val now = System.currentTimeMillis()
            when (i.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    if (unlocked && usingSince > 0) phoneMs += now - usingSince
                    usingSince = 0
                    unlocked = false
                    screenOffAt = now
                    handler.removeCallbacks(fire)
                    closeOverlay(rearm = false)
                }
                Intent.ACTION_USER_PRESENT -> onUnlocked(now)
                Intent.ACTION_SCREEN_ON -> if (!isLocked()) onUnlocked(now)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                finishNight()
                return START_NOT_STICKY
            }
            ACTION_EMERGENCY -> {
                startEmergency()
                return START_NOT_STICKY
            }
            ACTION_EMERGENCY_DONE -> {
                endEmergency()
                return START_NOT_STICKY
            }
            ACTION_EMERGENCY_EXTEND -> {
                extendEmergency()
                return START_NOT_STICKY
            }
        }
        if (running) return START_NOT_STICKY
        test = intent?.getBooleanExtra(EXTRA_TEST, false) == true
        val now = System.currentTimeMillis()
        val store = Ticks.store(this)
        val night = Ticks.sleepNow(store.data.value, LocalDateTime.now())
        nightId = if (test) null else night?.id
        endAt = when {
            test -> now + 5 * 60_000L
            night != null -> night.end.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            else -> now
        }.coerceAtMost(now + 12 * 3_600_000L)
        if (endAt <= now) {
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        try {
            ServiceCompat.startForeground(
                this, Notifs.ID_GUARD_ON, ongoingNotification(),
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
            )
        } catch (t: Throwable) {
            ErrorLog.log("睡眠守門員無法進入前景", t)
            running = false
            stopSelf()
            return START_NOT_STICKY
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        handler.postDelayed(endCheck, 60_000L)
        val pm = getSystemService(android.os.PowerManager::class.java)
        if (pm?.isInteractive == true && !isLocked()) onUnlocked(now)
        return START_NOT_STICKY
    }

    private fun ongoingNotification() = Notifs.base(
        this, Notifs.CH_GUARD_ON,
        if (test) "睡眠守門員測試中（5 分鐘）" else "睡眠守門員運作中",
        "到 ${fmtMin(LocalDateTime.ofInstant(Instant.ofEpochMilli(endAt), ZoneId.systemDefault()).let { it.hour * 60 + it.minute })} 為止",
    )
        .setOngoing(true)
        .setAutoCancel(false)
        .setSilent(true)
        .setContentIntent(Notifs.openIntent(this, "guardStop", Notifs.ID_GUARD_ON))
        .addAction(0, "今晚停用", Notifs.openIntent(this, "guardStop", Notifs.ID_GUARD_ON + 1))
        .addAction(0, "我有急事", Notifs.broadcastIntent(this, ACTION_EMERGENCY, Notifs.ID_GUARD_ON + 2))
        .build()

    private fun isLocked(): Boolean =
        getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true

    private fun onUnlocked(now: Long) {
        if (unlocked) return
        unlocked = true
        usingSince = now
        // Put down for over 10 minutes: start gently again.
        if (screenOffAt > 0 && now - screenOffAt > (if (test) 30_000L else 600_000L)) level = 0
        arm()
    }

    private fun arm() {
        handler.removeCallbacks(fire)
        if (overlay == null && unlocked) handler.postDelayed(fire, intervals[level.coerceIn(0, intervals.size - 1)])
    }

    private fun inCall(): Boolean {
        val mode = getSystemService(AudioManager::class.java)?.mode
        return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_RINGTONE
    }

    private fun foregroundApp(): String? {
        if (!Perms.usage(this)) return null
        val usm = getSystemService(UsageStatsManager::class.java) ?: return null
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - 10 * 60_000L, now)
        val e = UsageEvents.Event()
        var last: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) last = e.packageName
        }
        return last
    }

    private fun isClockApp(pkg: String?): Boolean =
        pkg != null && (pkg.contains("deskclock") || pkg.contains(".clock") || pkg.endsWith("clock"))

    private fun remind() {
        if (!unlocked) return
        val now = System.currentTimeMillis()
        val fg = foregroundApp()
        val store = Ticks.store(this)
        val skip = inCall() || isClockApp(fg) || fg == packageName || com.tomatime.app.TomatimeApp.inForeground ||
            (now < emergencyUntil && (emergencyAll || (fg != null && fg in store.data.value.settings.emergencyApps)))
        if (skip) {
            handler.postDelayed(fire, 15_000L)
            return
        }
        reminders++
        val strength = store.data.value.settings.guardStrength
        if (strength >= 1 && Perms.overlay(this)) showOverlay(strong = strength >= 2) else showNotification()
        level = (level + 1).coerceAtMost(intervals.size - 1)
    }

    private fun showNotification() {
        Notifs.post(this, Notifs.ID_GUARD_ALERT, Notifs.base(this, Notifs.CH_GUARD, "😴 該睡覺了", "放下手機，明天會更有精神")
            .setOnlyAlertOnce(false)
            .addAction(0, "我有急事", Notifs.broadcastIntent(this, ACTION_EMERGENCY, Notifs.ID_GUARD_ALERT)))
        // The same notification is updated, never stacked, so the next one can be armed right away.
        arm()
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun showOverlay(strong: Boolean) {
        val wm = getSystemService(WindowManager::class.java) ?: return showNotification()
        val dm = resources.displayMetrics
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(24), dp(24), dp(20))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FFF8EE"))
                cornerRadius = dp(28).toFloat()
            }
            elevation = dp(12).toFloat()
        }
        card.addView(ImageView(this).apply { setImageResource(R.drawable.mascot_bedtime) }, LinearLayout.LayoutParams(dp(150), dp(150)))
        card.addView(TextView(this).apply {
            text = "該睡覺了"
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#2B2522"))
            gravity = Gravity.CENTER
        })
        val nowLdt = LocalDateTime.now()
        card.addView(TextView(this).apply {
            text = "已經 ${fmtMin(nowLdt.hour * 60 + nowLdt.minute)} 了，放下手機吧"
            textSize = 15f
            setTextColor(Color.parseColor("#8A7F78"))
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(16))
        })
        val ok = Button(this).apply {
            isAllCaps = false
            textSize = 16f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { setColor(Color.parseColor("#E8473B")); cornerRadius = dp(24).toFloat() }
            setOnClickListener { closeOverlay() }
        }
        card.addView(ok, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)))
        card.addView(Button(this).apply {
            text = "我有急事"
            isAllCaps = false
            textSize = 14f
            setTextColor(Color.parseColor("#8A7F78"))
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener {
                closeOverlay(rearm = false)
                startEmergency()
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)))

        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val root: View
        val params: WindowManager.LayoutParams
        if (strong) {
            root = FrameLayout(this).apply {
                setBackgroundColor(Color.parseColor("#B3000000"))
                addView(card, FrameLayout.LayoutParams((dm.widthPixels * 0.85).toInt(), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
                isClickable = true
            }
            params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT,
            )
            // The button only appears after 5 seconds, giving a moment to stop.
            ok.isEnabled = false
            for (i in 5 downTo 1) handler.postDelayed({ ok.text = "我知道了（$i）" }, (5 - i) * 1_000L)
            handler.postDelayed({ ok.text = "我知道了"; ok.isEnabled = true }, 5_000L)
            ok.text = "我知道了（5）"
        } else {
            root = card
            ok.text = "我知道了"
            params = WindowManager.LayoutParams(
                (dm.widthPixels * 0.85).toInt(), WindowManager.LayoutParams.WRAP_CONTENT, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT,
            )
        }
        params.gravity = Gravity.CENTER
        try {
            wm.addView(root, params)
            overlay = root
            handler.postDelayed(autoClose, 60_000L)
        } catch (t: Throwable) {
            ErrorLog.log("無法顯示提醒視窗", t)
            showNotification()
        }
    }

    private fun closeOverlay(rearm: Boolean = true) {
        handler.removeCallbacks(autoClose)
        overlay?.let { v ->
            try {
                getSystemService(WindowManager::class.java)?.removeView(v)
            } catch (_: Throwable) {
            }
        }
        overlay = null
        if (rearm) arm()
    }

    private fun startEmergency() {
        val now = System.currentTimeMillis()
        if (now >= emergencyUntil) {
            emergencies++
            emergencyStartedAt = now
        }
        emergencyAll = !Perms.usage(this)
        emergencyUntil = now + (if (test) 60_000L else 15 * 60_000L)
        handler.removeCallbacks(emergencyAsk)
        handler.removeCallbacks(emergencyEnd)
        handler.postDelayed(emergencyAsk, emergencyUntil - now - (if (test) 20_000L else 60_000L))
        handler.postDelayed(emergencyEnd, emergencyUntil - now)
        Notifs.cancel(this, Notifs.ID_GUARD_ALERT)
        arm()
    }

    private fun askEmergency() {
        Notifs.post(this, Notifs.ID_EMERGENCY, Notifs.base(this, Notifs.CH_GUARD, "處理完了嗎？", "急事時間還剩 1 分鐘")
            .addAction(0, "處理完了", Notifs.broadcastIntent(this, ACTION_EMERGENCY_DONE, Notifs.ID_EMERGENCY))
            .addAction(0, "再延長 15 分鐘", Notifs.broadcastIntent(this, ACTION_EMERGENCY_EXTEND, Notifs.ID_EMERGENCY + 1)))
    }

    private fun extendEmergency() {
        Notifs.cancel(this, Notifs.ID_EMERGENCY)
        val now = System.currentTimeMillis()
        val add = if (test) 60_000L else 15 * 60_000L
        emergencyUntil = maxOf(emergencyUntil, now) + add
        handler.removeCallbacks(emergencyAsk)
        handler.removeCallbacks(emergencyEnd)
        handler.postDelayed(emergencyAsk, emergencyUntil - now - (if (test) 20_000L else 60_000L))
        handler.postDelayed(emergencyEnd, emergencyUntil - now)
    }

    private fun endEmergency() {
        Notifs.cancel(this, Notifs.ID_EMERGENCY)
        val now = System.currentTimeMillis()
        if (emergencyStartedAt > 0) emergencyMs += minOf(now, emergencyUntil.coerceAtLeast(emergencyStartedAt)) - emergencyStartedAt
        emergencyStartedAt = 0
        handler.removeCallbacks(emergencyAsk)
        handler.removeCallbacks(emergencyEnd)
        emergencyUntil = 0
        arm()
    }

    private fun finishNight() {
        handler.removeCallbacksAndMessages(null)
        closeOverlay(rearm = false)
        val now = System.currentTimeMillis()
        if (unlocked && usingSince > 0) phoneMs += now - usingSince
        if (running) {
            try {
                unregisterReceiver(screenReceiver)
            } catch (_: Throwable) {
            }
            if (!test) {
                val date = LocalDateTime.ofInstant(Instant.ofEpochMilli(endAt), ZoneId.systemDefault()).toLocalDate().minusDays(1).toString()
                val add = SleepNight(date, (phoneMs / 60_000L).toInt(), reminders, emergencies, (emergencyMs / 60_000L).toInt())
                Ticks.store(this).update { d ->
                    val old = d.sleepNights.find { it.date == date }
                    val merged = if (old == null) add else old.copy(
                        phoneMin = old.phoneMin + add.phoneMin, reminders = old.reminders + add.reminders,
                        emergencies = old.emergencies + add.emergencies, emergencyMin = old.emergencyMin + add.emergencyMin,
                    )
                    d.copy(sleepNights = (d.sleepNights.filter { it.date != date } + merged).takeLast(120))
                }
            }
        }
        running = false
        Notifs.cancel(this, Notifs.ID_GUARD_ALERT)
        Notifs.cancel(this, Notifs.ID_EMERGENCY)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        closeOverlay(rearm = false)
        if (running) {
            running = false
            try {
                unregisterReceiver(screenReceiver)
            } catch (_: Throwable) {
            }
        }
        super.onDestroy()
    }
}
