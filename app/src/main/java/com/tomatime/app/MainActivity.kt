package com.tomatime.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.tomatime.app.ui.App
import com.tomatime.app.ui.TomatimeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val store = (application as TomatimeApp).store
        setContent {
            TomatimeTheme {
                App(store)
            }
        }
    }
}
