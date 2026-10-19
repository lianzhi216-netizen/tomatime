package com.tomatime.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.tomatime.app.ui.App
import com.tomatime.app.ui.TomatimeTheme

class MainActivity : ComponentActivity() {
    /** What a notification or widget asked to open ("pomo", "log", "detail:<id>"…). */
    private val launch = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) launch.value = intent?.getStringExtra("open")
        val store = (application as TomatimeApp).store
        setContent {
            TomatimeTheme {
                App(store, launch.value) { launch.value = null }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        TomatimeApp.inForeground = true
    }

    override fun onStop() {
        TomatimeApp.inForeground = false
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        launch.value = intent.getStringExtra("open")
    }
}
