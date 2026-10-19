package com.tomatime.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.tomatime.app.data.Store

@Composable
fun App(store: Store) {
    val data by store.data.collectAsState()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Tomatime 建置測試：${data.categories.size} 個分類")
    }
}
