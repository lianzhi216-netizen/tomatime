package com.tomatime.app

import android.app.Application
import com.tomatime.app.data.ErrorLog
import com.tomatime.app.data.Store

class TomatimeApp : Application() {
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
    }
}
