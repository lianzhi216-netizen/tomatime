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

@Composable
fun SettingsScreen(store: Store, onBack: () -> Unit, toast: (String) -> Unit) {
    val ctx = LocalContext.current
    val data by store.data.collectAsState()
    val s = data.settings
    var draft by remember(s) { mutableStateOf(s) }
    var pendingRestore by remember { mutableStateOf<AppData?>(null) }

    fun save(next: Settings) {
        draft = next
        store.update { it.copy(settings = next) }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            try {
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(store.exportJson().toByteArray()) }
                store.update { it.copy(settings = it.settings.copy(lastBackupAt = System.currentTimeMillis())) }
                toast("已備份")
            } catch (t: Throwable) {
                ErrorLog.log("備份失敗", t)
                toast("備份失敗")
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val text = try {
                ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            } catch (t: Throwable) {
                ErrorLog.log("讀取備份失敗", t)
                null
            }
            val d = text?.let { store.decode(it) }
            if (d == null) toast("這不是 Tomatime 的備份檔") else pendingRestore = d
        }
    }
    val errorLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            try {
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(ErrorLog.read().toByteArray()) }
                toast("已匯出錯誤紀錄")
            } catch (t: Throwable) {
                toast("匯出失敗")
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("設定", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
            SectionTitle("作息（會更新今天起的睡覺時間）")
            Stepper(if (draft.splitWeekend) "平日起床" else "起床", draft.wakeMin, 4 * 60, 12 * 60) { draft = draft.copy(wakeMin = it) }
            Stepper(if (draft.splitWeekend) "平日睡覺" else "睡覺", draft.sleepMin, 20 * 60, 28 * 60) { draft = draft.copy(sleepMin = it) }
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("平日和週末分開設定", fontSize = 15.sp, modifier = Modifier.weight(1f))
                Switch(
                    checked = draft.splitWeekend, onCheckedChange = { draft = draft.copy(splitWeekend = it) },
                    colors = SwitchDefaults.colors(checkedTrackColor = Tomato),
                )
            }
            if (draft.splitWeekend) {
                Stepper("週末起床", draft.weekendWakeMin, 4 * 60, 13 * 60) { draft = draft.copy(weekendWakeMin = it) }
                Stepper("週末睡覺（週五、六晚）", draft.weekendSleepMin, 20 * 60, 28 * 60) { draft = draft.copy(weekendSleepMin = it) }
            }
            val sleepChanged = draft.wakeMin != s.wakeMin || draft.sleepMin != s.sleepMin || draft.splitWeekend != s.splitWeekend ||
                draft.weekendWakeMin != s.weekendWakeMin || draft.weekendSleepMin != s.weekendSleepMin
            if (sleepChanged) {
                Button(
                    onClick = {
                        val next = s.copy(
                            wakeMin = draft.wakeMin, sleepMin = draft.sleepMin, splitWeekend = draft.splitWeekend,
                            weekendWakeMin = draft.weekendWakeMin, weekendSleepMin = draft.weekendSleepMin,
                        )
                        store.update { regenerateSleep(it.copy(settings = next), LocalDate.now()) }
                        toast("已更新作息：${fmtMin(next.wakeMin)} 起床、${fmtMin(next.sleepMin)} 睡覺")
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Tomato),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("套用作息") }
            }

            SectionTitle("排程")
            Text("每天最多排幾小時任務", fontSize = 15.sp)
            Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(4, 6, 8, 10).forEach { h -> Pill("$h 小時", s.dailyTaskCapMin == h * 60) { save(s.copy(dailyTaskCapMin = h * 60)) } }
            }
            Text("任務之間的彈性時間（算作休息）", fontSize = 15.sp)
            Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0, 10, 15, 20).forEach { m -> Pill(if (m == 0) "不留" else "$m 分", s.bufferMin == m) { save(s.copy(bufferMin = m)) } }
            }

            SectionTitle("補登記")
            Text("可以補登最近幾天", fontSize = 15.sp)
            Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 2, 3, 7).forEach { n -> Pill("$n 天", s.lookbackDays == n) { save(s.copy(lookbackDays = n)) } }
            }

            SectionTitle("畫面")
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("有未完成任務時，選單顯示小圓點", fontSize = 15.sp, modifier = Modifier.weight(1f))
                Switch(
                    checked = s.showMenuDot, onCheckedChange = { save(s.copy(showMenuDot = it)) },
                    colors = SwitchDefaults.colors(checkedTrackColor = Tomato),
                )
            }

            SectionTitle("資料")
            val days = (System.currentTimeMillis() - s.lastBackupAt) / 86_400_000L
            Text("上次備份：${if (days <= 0) "今天" else "$days 天前"}", fontSize = 15.sp)
            Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("備份到雲端硬碟", true) { exportLauncher.launch("tomatime-backup-${LocalDate.now()}.json") }
                Pill("從備份還原") { importLauncher.launch(arrayOf("application/json", "application/octet-stream", "text/plain", "*/*")) }
            }
            Text("按「備份」後，在跳出的畫面選 Google 雲端硬碟即可。", fontSize = 12.sp, color = Muted)
            Text("提醒備份的間隔", fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(7, 14, 30).forEach { n -> Pill("$n 天", s.backupIntervalDays == n) { save(s.copy(backupIntervalDays = n)) } }
            }

            SectionTitle("問題回報")
            Pill("匯出錯誤紀錄") { errorLauncher.launch("tomatime-errors-${LocalDate.now()}.txt") }
            Text("App 出問題時匯出這個檔案，傳給 Claude 檢查。", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(16.dp))
            Text("Tomatime ${BuildConfig.VERSION_NAME}（build ${BuildConfig.VERSION_CODE}）", fontSize = 12.sp, color = Muted)
        }
    }

    pendingRestore?.let { d ->
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text("從備份還原？") },
            text = { Text("備份裡有 ${d.events.size} 筆事件、${d.unfinished.size} 項未完成。目前的資料會被取代，但會先自動另存一份在手機裡。") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    store.restore(d)
                    pendingRestore = null
                    toast("已還原")
                }) { Text("還原") }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { pendingRestore = null }) { Text("取消") } },
        )
    }
}
