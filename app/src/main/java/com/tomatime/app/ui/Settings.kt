package com.tomatime.app.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.tomatime.app.BuildConfig
import com.tomatime.app.data.AppData
import com.tomatime.app.data.ErrorLog
import com.tomatime.app.data.PomoCfg
import com.tomatime.app.data.Settings
import com.tomatime.app.data.Store
import com.tomatime.app.domain.fmtDur
import com.tomatime.app.domain.fmtMin
import com.tomatime.app.domain.regenerateSleep
import com.tomatime.app.system.GuardianService
import com.tomatime.app.system.Notifs
import com.tomatime.app.system.Perms
import com.tomatime.app.system.Usage
import java.time.LocalDate

@Composable
private fun SwitchRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedTrackColor = Tomato))
    }
}

@Composable
private fun Choice(label: String, options: List<Pair<String, Boolean>>, onPick: (Int) -> Unit) {
    Text(label, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp))
    Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { i, (t, sel) -> Pill(t, sel) { onPick(i) } }
    }
}

/** One permission line: what it is for, whether it is on, and a button to the system page. */
@Composable
private fun PermRow(name: String, why: String, granted: Boolean, onFix: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text((if (granted) "✅ " else "⚠️ ") + name, fontSize = 14.sp)
            Text(why, fontSize = 12.sp, color = Muted)
        }
        if (!granted) Pill("去開啟", true) { onFix() }
    }
}

@Composable
fun SettingsScreen(store: Store, onBack: () -> Unit, toast: (String) -> Unit) {
    val ctx = LocalContext.current
    val data by store.data.collectAsState()
    val s = data.settings
    var draft by remember(s) { mutableStateOf(s) }
    var pendingRestore by remember { mutableStateOf<AppData?>(null) }
    var editPomo by remember { mutableStateOf<String?>(null) }
    var pickApps by remember { mutableStateOf(false) }
    // Re-read permission states whenever the user comes back from a system settings page.
    var resumed by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) resumed++ }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    val canNotify = remember(resumed) { Notifs.canPost(ctx) }
    val canOverlay = remember(resumed) { Perms.overlay(ctx) }
    val canUsage = remember(resumed) { Perms.usage(ctx) }
    val battery = remember(resumed) { Perms.batteryUnrestricted(ctx) }
    val exact = remember(resumed) { Perms.exactAlarms(ctx) }

    fun save(next: Settings) {
        store.update { it.copy(settings = next) }
    }

    fun open(i: android.content.Intent) {
        try {
            ctx.startActivity(i)
        } catch (t: Throwable) {
            toast("這支手機打不開這個設定頁")
        }
    }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { resumed++ }
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
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton("‹ 返回") { onBack() }
            Text("設定", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 40.dp)) {
            // ---- 作息 ----
            SectionTitle("作息（會更新今天起的睡覺時間）")
            Stepper(if (draft.splitWeekend) "平日起床" else "起床", draft.wakeMin, 4 * 60, 12 * 60) { draft = draft.copy(wakeMin = it) }
            Stepper(if (draft.splitWeekend) "平日睡覺" else "睡覺", draft.sleepMin, 20 * 60, 28 * 60) { draft = draft.copy(sleepMin = it) }
            SwitchRow("平日和週末分開設定", draft.splitWeekend) { draft = draft.copy(splitWeekend = it) }
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

            // ---- 排程 ----
            SectionTitle("排程")
            Choice("每天最多排幾小時任務", listOf(4, 6, 8, 10).map { "$it 小時" to (s.dailyTaskCapMin == it * 60) }) { i ->
                save(s.copy(dailyTaskCapMin = listOf(4, 6, 8, 10)[i] * 60))
            }
            Choice("任務之間的彈性時間（算作休息）", listOf(0, 10, 15, 20).map { (if (it == 0) "不留" else "$it 分") to (s.bufferMin == it) }) { i ->
                save(s.copy(bufferMin = listOf(0, 10, 15, 20)[i]))
            }

            // ---- 提醒 ----
            SectionTitle("提醒")
            PermRow("通知", "提醒、番茄鐘、睡眠守門員都需要", canNotify) {
                if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) else open(Perms.appSettings(ctx))
            }
            PermRow("準時鬧鐘", "讓提醒和番茄鐘準時響", exact) { open(Perms.appSettings(ctx)) }
            Choice("任務開始前提醒", listOf(0, 5, 10, 15).map { (if (it == 0) "關閉" else "$it 分") to (s.remindBeforeMin == it) }) { i ->
                save(s.copy(remindBeforeMin = listOf(0, 5, 10, 15)[i]))
            }
            SwitchRow("上課等固定行程也提醒", s.remindFixed) { save(s.copy(remindFixed = it)) }
            SwitchRow("睡前一小時提醒補登空白時段", s.logReminder) { save(s.copy(logReminder = it)) }

            // ---- 補登 ----
            SectionTitle("補登記")
            Choice("可以補登最近幾天", listOf(1, 2, 3, 7).map { "$it 天" to (s.lookbackDays == it) }) { i -> save(s.copy(lookbackDays = listOf(1, 2, 3, 7)[i])) }
            Choice("預設補登方式", listOf("快捷" to !s.logDetailed, "詳細" to s.logDetailed)) { i -> save(s.copy(logDetailed = i == 1)) }
            SwitchRow("用手機使用紀錄猜補登", s.usageEnabled) { on ->
                save(s.copy(usageEnabled = on))
                if (on && !Perms.usage(ctx)) open(Perms.usageSettings())
            }
            if (s.usageEnabled) {
                PermRow("使用情形存取權", "讀取什麼時間用了哪些 App，資料只留在手機裡", canUsage) { open(Perms.usageSettings()) }
                if (canUsage) {
                    val cap = remember(resumed) { Usage.capability(ctx) }
                    Text(
                        when (cap) {
                            2 -> "你的手機可以偵測使用時段"
                            1 -> "你的手機只提供每天總時數，無法猜時段"
                            else -> "目前讀不到使用紀錄"
                        },
                        fontSize = 13.sp, color = Muted,
                    )
                }
                Choice("連續使用多久才算一筆", listOf(10, 15, 30).map { "$it 分" to (s.usageMinMin == it) }) { i -> save(s.copy(usageMinMin = listOf(10, 15, 30)[i])) }
            }

            // ---- 番茄鐘 ----
            SectionTitle("番茄鐘（全部分類的預設）")
            PomoEditor(s.pomo) { save(s.copy(pomo = it)) }
            Text("各分類另外設定", fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp))
            data.categories.filter { !it.system }.forEach { c ->
                val own = s.pomoByCategory[c.id]
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { editPomo = c.id }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${c.emoji}${c.name}", fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text(own?.let { "${it.focusMin}／${it.shortMin} 分" } ?: "使用預設", fontSize = 13.sp, color = if (own != null) Tomato else Muted)
                }
            }

            // ---- 睡眠守門員 ----
            SectionTitle("睡眠守門員")
            SwitchRow("睡覺時間滑手機時提醒我", s.guardEnabled) { on ->
                save(s.copy(guardEnabled = on))
                if (on && Build.VERSION.SDK_INT >= 33 && !Notifs.canPost(ctx)) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                if (!on && GuardianService.running) GuardianService.stop(ctx)
            }
            Text("間隔 5 → 3 → 1 分鐘 → 每 30 秒；放下手機 10 分鐘後重新從 5 分鐘開始。", fontSize = 12.sp, color = Muted)
            Choice("提醒強度", listOf("輕：通知", "中：中間卡片", "強：要等 5 秒").mapIndexed { i, t -> t to (s.guardStrength == i) }) { i ->
                save(s.copy(guardStrength = i))
                if (i >= 1 && !Perms.overlay(ctx)) open(Perms.overlaySettings(ctx))
            }
            if (s.guardEnabled) {
                PermRow("顯示在其他應用程式上層", "中、強兩種提醒需要", canOverlay) { open(Perms.overlaySettings(ctx)) }
                PermRow("不限制電池用量", "否則小米可能在半夜把它關掉", battery) { open(Perms.batterySettings(ctx)) }
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("小米：自啟動、背景彈出介面", fontSize = 14.sp)
                        Text("在小米的設定裡打開「自啟動」，並允許「顯示懸浮窗」和「背景彈出介面」", fontSize = 12.sp, color = Muted)
                    }
                    Pill("去設定", true) { open(Perms.xiaomiAutostart(ctx)) }
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("我有急事時可以用的 App", fontSize = 14.sp)
                        Text(if (s.emergencyApps.isEmpty()) "還沒選" else "${s.emergencyApps.size} 個", fontSize = 12.sp, color = Muted)
                    }
                    Pill("選擇") { pickApps = true }
                }
                if (s.emergencyApps.isNotEmpty() && !canUsage) {
                    PermRow("使用情形存取權", "分辨你正在用哪個 App；沒開時「我有急事」會暫停全部提醒 15 分鐘", false) { open(Perms.usageSettings()) }
                }
                Spacer(Modifier.height(6.dp))
                Pill(if (GuardianService.running) "運作中（點此停止）" else "測試 5 分鐘（間隔縮短成幾秒）", true) {
                    if (GuardianService.running) GuardianService.stop(ctx) else {
                        GuardianService.start(ctx, test = true)
                        toast("測試開始：回到桌面或打開別的 App 等幾秒")
                    }
                }
                Text("提醒視窗最多 60 秒自動關閉；下拉通知欄一定能進設定或按「今晚停用」。", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 4.dp))
            }

            // ---- 畫面與小工具 ----
            SectionTitle("畫面與小工具")
            SwitchRow("有未完成任務時，選單顯示小圓點", s.showMenuDot) { save(s.copy(showMenuDot = it)) }
            Choice("概覽小工具顯示", listOf(7, 14, 30).map { (if (it == 30) "一個月" else "$it 天") to (s.widgetOverviewDays == it) }) { i ->
                save(s.copy(widgetOverviewDays = listOf(7, 14, 30)[i]))
            }
            Choice("今日小工具的按鈕", listOf("簡潔：只有 ✅" to !s.widgetFullButtons, "完整：✅🟡❌" to s.widgetFullButtons)) { i ->
                save(s.copy(widgetFullButtons = i == 1))
            }
            Text("在手機桌面長按空白處 → 小工具 → Tomatime，可以加入概覽、今日、新增三種小工具。", fontSize = 12.sp, color = Muted)

            // ---- 資料 ----
            SectionTitle("資料")
            val days = (System.currentTimeMillis() - s.lastBackupAt) / 86_400_000L
            Text("上次備份：${if (days <= 0) "今天" else "$days 天前"}", fontSize = 15.sp)
            Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("備份到雲端硬碟", true) { exportLauncher.launch("tomatime-backup-${LocalDate.now()}.json") }
                Pill("從備份還原") { importLauncher.launch(arrayOf("application/json", "application/octet-stream", "text/plain", "*/*")) }
            }
            Text("按「備份」後，在跳出的畫面選 Google 雲端硬碟即可。", fontSize = 12.sp, color = Muted)
            Choice("提醒備份的間隔", listOf(7, 14, 30).map { "$it 天" to (s.backupIntervalDays == it) }) { i -> save(s.copy(backupIntervalDays = listOf(7, 14, 30)[i])) }

            SectionTitle("問題回報")
            Pill("匯出錯誤紀錄") { errorLauncher.launch("tomatime-errors-${LocalDate.now()}.txt") }
            Text("App 出問題時匯出這個檔案，傳給 Claude 檢查。", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(16.dp))
            Text("Tomatime ${BuildConfig.VERSION_NAME}（build ${BuildConfig.VERSION_CODE}）", fontSize = 12.sp, color = Muted)
        }
    }

    editPomo?.let { catId ->
        val c = data.categories.find { it.id == catId }
        var cfg by remember(catId) { mutableStateOf(s.pomoByCategory[catId] ?: s.pomo) }
        AlertDialog(
            onDismissRequest = { editPomo = null },
            title = { Text("${c?.emoji.orEmpty()}${c?.name.orEmpty()} 的番茄鐘") },
            text = { Column { PomoEditor(cfg) { cfg = it } } },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    save(s.copy(pomoByCategory = s.pomoByCategory + (catId to cfg)))
                    editPomo = null
                }) { Text("儲存") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = {
                    save(s.copy(pomoByCategory = s.pomoByCategory - catId))
                    editPomo = null
                }) { Text("恢復預設") }
            },
        )
    }

    if (pickApps) {
        val apps = remember { Usage.launchableApps(ctx) }
        var chosen by remember { mutableStateOf(s.emergencyApps.toSet()) }
        AlertDialog(
            onDismissRequest = { pickApps = false },
            title = { Text("急事時可以用的 App") },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(apps, key = { it.pkg }) { a ->
                        Row(
                            Modifier.fillMaxWidth().clickable { chosen = if (a.pkg in chosen) chosen - a.pkg else chosen + a.pkg },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = a.pkg in chosen, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = Tomato))
                            Text(a.label, fontSize = 15.sp)
                        }
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    save(s.copy(emergencyApps = chosen.toList()))
                    pickApps = false
                }) { Text("完成") }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { pickApps = false }) { Text("取消") } },
        )
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

@Composable
private fun PomoEditor(cfg: PomoCfg, onChange: (PomoCfg) -> Unit) {
    Stepper("專注", cfg.focusMin, 5, 120, step = 5, format = { fmtDur(it) }) { onChange(cfg.copy(focusMin = it)) }
    Stepper("短休息", cfg.shortMin, 1, 30, step = 1, format = { fmtDur(it) }) { onChange(cfg.copy(shortMin = it)) }
    Stepper("長休息", cfg.longMin, 5, 60, step = 5, format = { fmtDur(it) }) { onChange(cfg.copy(longMin = it)) }
    Stepper("每幾顆長休息", cfg.longEvery, 2, 8, step = 1, format = { "$it 顆" }) { onChange(cfg.copy(longEvery = it)) }
}

