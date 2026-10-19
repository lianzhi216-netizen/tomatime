package com.tomatime.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tomatime.app.BuildConfig
import com.tomatime.app.R
import com.tomatime.app.data.AppData
import com.tomatime.app.data.Category
import com.tomatime.app.data.ErrorLog
import com.tomatime.app.data.Settings
import com.tomatime.app.data.Store
import com.tomatime.app.data.Unfinished
import com.tomatime.app.domain.dateLabel
import com.tomatime.app.domain.fmtDur
import com.tomatime.app.domain.fmtMin
import com.tomatime.app.domain.regenerateSleep
import java.time.LocalDate

@Composable
fun OnboardingScreen(initial: Settings, onDone: (Settings) -> Unit) {
    var wake by remember { mutableStateOf(initial.wakeMin) }
    var sleep by remember { mutableStateOf(initial.sleepMin) }
    var cap by remember { mutableStateOf(initial.dailyTaskCapMin) }
    Column(
        Modifier.fillMaxSize().background(Cream).safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Image(painterResource(R.drawable.mascot_streak), contentDescription = null, modifier = Modifier.size(150.dp))
        Text("歡迎使用 Tomatime", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("先設定三件事，之後都可以在設定裡修改。", color = Muted, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        SectionTitle("1. 作息")
        Stepper("起床", wake, 4 * 60, 12 * 60) { wake = it }
        Stepper("睡覺", sleep, 20 * 60, 28 * 60) { sleep = it }
        SectionTitle("2. 每天最多排幾小時任務")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(4, 6, 8, 10).forEach { h -> Pill("$h 小時", cap == h * 60) { cap = h * 60 } }
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { onDone(initial.copy(wakeMin = wake, sleepMin = sleep, dailyTaskCapMin = cap)) },
            colors = ButtonDefaults.buttonColors(containerColor = Tomato),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text("3. 開始使用", fontSize = 17.sp) }
    }
}

@Composable
private fun ScreenHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton("‹ 返回") { onBack() }
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun UnfinishedScreen(
    data: AppData,
    cats: Map<String, Category>,
    today: LocalDate,
    onBack: () -> Unit,
    onTap: (Unfinished) -> Unit,
) {
    val items = data.unfinished.sortedWith(
        compareBy<Unfinished> { it.deadline ?: "9999-12-31" }.thenByDescending { it.importance },
    )
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("未完成清單", onBack)
        if (items.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Image(painterResource(R.drawable.mascot_celebrate), contentDescription = null, modifier = Modifier.size(140.dp))
                Text("沒有未完成的任務", color = Muted)
            }
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(items, key = { it.id }) { u ->
                val c = cats[u.categoryId]
                val deadline = u.deadline?.let { LocalDate.parse(it) }
                val overdue = deadline != null && deadline.isBefore(today)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White)
                        .clickable { onTap(u) }
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${c?.emoji.orEmpty()} ${u.title}", fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text(importanceLabel(u.importance), color = importanceColor(u.importance), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    LinearProgressIndicator(
                        progress = { if (u.totalMin > 0) u.doneMin.toFloat() / u.totalMin else 0f },
                        color = Leaf, trackColor = PillBg, modifier = Modifier.fillMaxWidth(),
                    )
                    Text("已做 ${fmtDur(u.doneMin)} / 共 ${fmtDur(u.totalMin)}", fontSize = 13.sp, color = Muted)
                    Row {
                        Text("剩 ${fmtDur(u.totalMin - u.doneMin)}", fontSize = 13.sp, modifier = Modifier.weight(1f))
                        if (deadline != null) {
                            Text(
                                if (overdue) "已過期限 ${dateLabel(deadline)}" else "期限 ${dateLabel(deadline)}",
                                fontSize = 13.sp, color = if (overdue) Tomato else Muted,
                                fontWeight = if (overdue) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
        }
    }
}
