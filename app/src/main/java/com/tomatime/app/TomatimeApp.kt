package com.tomatime.app

import android.app.Application
import com.tomatime.app.data.ErrorLog
import com.tomatime.app.data.Store
import com.tomatime.app.system.Notifs
import com.tomatime.app.system.Ticks
import com.tomatime.app.system.Widgets

class TomatimeApp : Application() {
    companion object {
        /** True while a Tomatime screen is visible; the sleep guard does not nag inside the app. */
        @Volatile
        var inForeground = false
    }

    lateinit var store: Store
        private set

    override fun onCreate() {
        super.onCreate()
        ErrorLog.init(this)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            ErrorLog.log("程式當掉", error)
            previous?.uncaughtException(thread, error)
        }
        store = Store(this)
        try {
            Notifs.createChannels(this)
        } catch (t: Throwable) {
            ErrorLog.log("建立通知頻道失敗", t)
        }
        // Alarms, the pomodoro notification and widgets always follow the latest data.
        store.onChanged = { d ->
            Ticks.schedule(this)
            Notifs.syncPomo(this, d)
            Widgets.refresh(this)
        }
        Ticks.schedule(this)
        Notifs.syncPomo(this, store.data.value)
    }
}
