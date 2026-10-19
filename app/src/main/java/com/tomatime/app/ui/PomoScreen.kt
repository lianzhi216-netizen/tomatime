package com.tomatime.app.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tomatime.app.R
import com.tomatime.app.data.AppData
import com.tomatime.app.data.Category
import com.tomatime.app.data.PomoPhase
import com.tomatime.app.data.Store
import com.tomatime.app.data.Unfinished
import com.tomatime.app.domain.displayTitle
import com.tomatime.app.domain.findOcc
import com.tomatime.app.domain.newId
import com.tomatime.app.domain.occurrences
import com.tomatime.app.domain.pomoAbandon
import com.tomatime.app.domain.pomoAdvance
import com.tomatime.app.domain.pomoCfg
import com.tomatime.app.domain.pomoNext
import com.tomatime.app.domain.pomoStart
import com.tomatime.app.domain.pomoStop
import com.tomatime.app.domain.suggestedFocus
import com.tomatime.app.system.Notifs
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.ZoneId

@Composable
fun PomoScreen(
    store: Store,
    data: AppData,
    cats: Map<String, Category>,
    startOccId: String?,
    onBack: () -> Unit,
    onFeedback: (String) -> Unit,
    toast: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val ms by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(250)
            value = System.currentTimeMillis()
        }
    }
    val state = data.pomoState
    LaunchedEffect(ms / 1000, state?.phaseEnd, state?.phase) {
        if (state != null && state.phase != PomoPhase.WAITING && ms >= state.phaseEnd) store.update { pomoAdvance(it, ms) }
    }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun askNotify() {
        if (Build.VERSION.SDK_INT >= 33 && !Notifs.canPost(ctx)) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    var thought by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton("‹ 返回") { onBack() }
            Text("番茄鐘", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state == null) {
                val now = LocalDateTime.now()
                val today = now.toLocalDate()
                val candidate = startOccId?.let { findOcc(data, it) }
                    ?: occurrences(data, today, today).firstOrNull { !it.isSleep && !it.hidden && !it.start.isAfter(now) && it.end.isAfter(now) }
                Image(painterResource(R.drawable.mascot_focus), null, Modifier.size(170.dp))
                if (candidate != null) {
                    val c = cats[candidate.event.categoryId]
                    val title = displayTitle(candidate.event, data)
                    Text("現在的事", color = Muted)
                    Text("${c?.emoji.orEmpty()} $title", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Button(
                        onClick = {
                            askNotify()
                            store.update { pomoStart(it, candidate.id, candidate.event.categoryId, title, System.currentTimeMillis()) }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Tomato),
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                    ) { Text("開始專注 ${pomoCfg(data, candidate.event.categoryId).focusMin} 分鐘", fontSize = 17.sp) }
                    Spacer(Modifier.height(8.dp))
                    Text("或選別的分類", color = Muted, fontSize = 13.sp)
                } else {
                    Text("現在沒有安排的事，選一個分類開始", color = Muted)
                }
                val choices = data.categories.filter { !it.system }
                PillRows(choices.map { "${it.emoji}${it.name}" to false }, perRow = 3) { i ->
                    askNotify()
                    val c = choices[i]
                    store.update { pomoStart(it, null, c.id, c.name, System.currentTimeMillis()) }
                }
            } else {
                val cfg = pomoCfg(data, state.categoryId)
                val c = cats[state.categoryId]
                val total = (state.phaseEnd - state.phaseStart).coerceAtLeast(1L)
                val left = (state.phaseEnd - ms).coerceAtLeast(0L)
                val frac = if (state.phase == PomoPhase.WAITING) 1f else left.toFloat() / total
                val ringColor = if (state.phase == PomoPhase.FOCUS) Tomato else Leaf
                Text("${c?.emoji.orEmpty()} ${state.title}", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(
                    when (state.phase) {
                        PomoPhase.FOCUS -> "專注中・第 ${state.done + 1} 顆（每 ${cfg.longEvery} 顆長休息）"
                        PomoPhase.SHORT_BREAK -> "短休息：伸展、喝水，別滑手機"
                        PomoPhase.LONG_BREAK -> "長休息：起來走一走"
                        PomoPhase.WAITING -> "已完成 ${state.done} 顆，準備好下一顆了嗎？"
                    },
                    color = Muted,
                )
                Box(Modifier.size(260.dp), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.fillMaxSize().padding(10.dp)) {
                        val stroke = 18.dp.toPx()
                        drawArc(PillBg, 0f, 360f, false, topLeft = Offset(stroke / 2, stroke / 2), size = Size(size.width - stroke, size.height - stroke), style = Stroke(stroke))
                        drawArc(ringColor, -90f, 360f * frac, false, topLeft = Offset(stroke / 2, stroke / 2), size = Size(size.width - stroke, size.height - stroke), style = Stroke(stroke, cap = StrokeCap.Round))
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Image(
                            painterResource(
                                when (state.phase) {
                                    PomoPhase.FOCUS -> R.drawable.mascot_focus
                                    PomoPhase.WAITING -> R.drawable.mascot_celebrate
                                    else -> R.drawable.mascot_rest
                                },
                            ),
                            null, Modifier.size(90.dp),
                        )
                        if (state.phase != PomoPhase.WAITING) {
                            val sec = left / 1000
                            Text("%02d:%02d".format(sec / 60, sec % 60), fontSize = 40.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Text("🍅".repeat(state.done.coerceAtMost(12)) + if (state.interruptions > 0) "　中斷 ${state.interruptions} 次" else "", fontSize = 18.sp)
                when (state.phase) {
                    PomoPhase.FOCUS -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            BigButton("💭 想到別的事", Modifier.weight(1f)) { thought = "" }
                            BigButton("✋ 放棄這顆", Modifier.weight(1f)) {
                                store.update { pomoAbandon(it, System.currentTimeMillis()) }
                                toast("這顆不算，準備好再開始新的一顆")
                            }
                        }
                    }
                    PomoPhase.SHORT_BREAK, PomoPhase.LONG_BREAK -> {
                        BigButton("跳過休息，開始下一顆", Modifier.fillMaxWidth()) { store.update { pomoNext(it, System.currentTimeMillis()) } }
                    }
                    PomoPhase.WAITING -> {
                        Button(
                            onClick = { store.update { pomoNext(it, System.currentTimeMillis()) } },
                            colors = ButtonDefaults.buttonColors(containerColor = Tomato),
                            modifier = Modifier.fillMaxWidth().height(54.dp),
                        ) { Text("開始下一顆（${cfg.focusMin} 分鐘）", fontSize = 17.sp) }
                    }
                }
                BigButton("結束任務", Modifier.fillMaxWidth()) {
                    val occId = state.occId
                    store.update { pomoStop(it, System.currentTimeMillis()) }
                    if (occId != null) onFeedback(occId) else onBack()
                }
                suggestedFocus(data, state.categoryId)?.let { m ->
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(PillBg).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("${c?.name.orEmpty()}你常常專注約 $m 分鐘就停下，要把這類的專注時間改成 $m 分鐘嗎？", fontSize = 13.sp)
                        Pill("改成 $m 分鐘", true) {
                            store.update { d ->
                                d.copy(settings = d.settings.copy(pomoByCategory = d.settings.pomoByCategory + (state.categoryId to cfg.copy(focusMin = m))))
                            }
                            toast("之後${c?.name.orEmpty()}的番茄鐘是 $m 分鐘")
                        }
                    }
                }
            }
            val today = LocalDateTime.now().toLocalDate()
            val zone = ZoneId.systemDefault()
            val dayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()
            val doneToday = data.pomoSessions.count { it.completed && it.start >= dayStart }
            Text("今天完成 $doneToday 顆番茄", color = Muted, fontSize = 13.sp)
            Spacer(Modifier.height(24.dp))
        }
    }

    thought?.let { text ->
        AlertDialog(
            onDismissRequest = { thought = null },
            title = { Text("想到別的事") },
            text = {
                Column {
                    Text("先記下來，這顆番茄繼續。它會放進未完成清單。", fontSize = 13.sp, color = Muted)
                    OutlinedTextField(value = text, onValueChange = { thought = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    if (text.isNotBlank()) {
                        store.update {
                            it.copy(unfinished = it.unfinished + Unfinished(
                                id = newId(), title = text.trim(), categoryId = "other", totalMin = 30, doneMin = 0,
                                createdAt = System.currentTimeMillis(),
                            ))
                        }
                        toast("已記下，繼續專注")
                    }
                    thought = null
                }) { Text("記下") }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { thought = null }) { Text("取消") } },
        )
    }
}

@Composable
fun BigButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(14.dp)).background(PillBg).clickable(onClick = onClick).padding(vertical = 15.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, fontSize = 15.sp) }
}
