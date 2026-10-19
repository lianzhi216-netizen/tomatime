package com.tomatime.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tomatime.app.data.AppData
import com.tomatime.app.data.Category
import com.tomatime.app.data.Event
import com.tomatime.app.data.Origin
import com.tomatime.app.data.Store
import com.tomatime.app.domain.dateLabel
import com.tomatime.app.domain.fmtDur
import com.tomatime.app.domain.fmtMin
import com.tomatime.app.domain.minOfDay
import com.tomatime.app.domain.newId
import com.tomatime.app.domain.overlapping
import com.tomatime.app.system.Perms
import com.tomatime.app.system.Usage
import com.tomatime.app.system.UsageBlock
import com.tomatime.app.system.importantItems
import java.time.LocalDate
import java.time.ZoneId

@Composable
private fun Header(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton("‹ 返回") { onBack() }
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

/** Week / two weeks / month list of each day's important things. */
@Composable
fun OverviewScreen(data: AppData, today: LocalDate, onBack: () -> Unit, onDay: (LocalDate) -> Unit) {
    var span by remember { mutableIntStateOf(14) }
    Column(Modifier.fillMaxSize()) {
        Header("概覽", onBack)
        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(7 to "一週", 14 to "兩週", 30 to "一個月").forEach { (n, label) -> Pill(label, span == n) { span = n } }
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items((0 until span).map { today.plusDays(it.toLong()) }) { d ->
                val items = importantItems(data, d)
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White)
                        .then(Modifier.padding(12.dp)),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (d == today) "今天 ${dateLabel(d)}" else dateLabel(d),
                            fontWeight = FontWeight.Bold, color = if (d == today) Tomato else Ink, modifier = Modifier.weight(1f),
                        )
                        TextButton("看這天", Muted) { onDay(d) }
                    }
                    if (items.isEmpty()) Text("沒有重要事項", fontSize = 13.sp, color = Muted)
                    items.forEach { Text(it, fontSize = 14.sp) }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** 補登 suggestions from the phone's own usage history. */
@Composable
fun UsageScreen(store: Store, data: AppData, cats: Map<String, Category>, today: LocalDate, onBack: () -> Unit, pickCategory: ((String) -> Unit) -> Unit, toast: (String) -> Unit) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        Header("從手機使用紀錄補登", onBack)
        if (!Perms.usage(ctx)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("需要先開啟「使用情形存取權」，App 才能讀取你在什麼時間用了哪些 App。資料只留在手機裡。")
                Pill("前往開啟", true) { ctx.startActivity(Perms.usageSettings()) }
            }
            return@Column
        }
        val zone = ZoneId.systemDefault()
        val days = data.settings.lookbackDays
        val from = today.minusDays((days - 1).toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
        var refresh by remember { mutableIntStateOf(0) }
        val blocks = remember(refresh, data.events.size, data.appCategories, data.ignoredApps) {
            Usage.blocks(ctx, from, System.currentTimeMillis(), data.settings.usageMinMin, data)
                .filter { overlapping(data, it.start, it.end).isEmpty() }
                .sortedByDescending { it.start }
        }
        val cap = remember { Usage.capability(ctx) }
        if (cap < 2) {
            Text(
                if (cap == 1) "你的手機只提供每天總時數，沒有詳細時段，所以無法猜出時段。" else "目前讀不到使用紀錄。",
                modifier = Modifier.padding(20.dp), color = Muted,
            )
        }
        if (blocks.isEmpty() && cap == 2) Text("最近 $days 天沒有需要補登的使用紀錄（連續 ${data.settings.usageMinMin} 分鐘以上）。", modifier = Modifier.padding(20.dp), color = Muted)
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(blocks, key = { it.pkg + it.start }) { b ->
                UsageRow(b, data, cats,
                    onPick = { pickCategory { catId -> store.update { it.copy(appCategories = it.appCategories + (b.pkg to catId)) } } },
                    onAdd = { catId ->
                        val d = b.start.toLocalDate()
                        val a = minOfDay(b.start, d)
                        store.update {
                            it.copy(events = it.events + Event(
                                id = newId(), title = b.label, categoryId = catId, date = d.toString(), startMin = a,
                                durationMin = b.minutes, origin = Origin.LOGGED, createdAt = System.currentTimeMillis(),
                            ))
                        }
                        refresh++
                        toast("已補登 ${b.label}")
                    },
                    onIgnore = {
                        store.update { it.copy(ignoredApps = (it.ignoredApps + b.pkg).distinct()) }
                        toast("之後不再顯示 ${b.label}")
                    },
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun UsageRow(b: UsageBlock, data: AppData, cats: Map<String, Category>, onPick: () -> Unit, onAdd: (String) -> Unit, onIgnore: () -> Unit) {
    val catId = data.appCategories[b.pkg] ?: Usage.defaultCategory(b.appKind)
    val c = catId?.let { cats[it] }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("${dateLabel(b.start.toLocalDate())} ${fmtMin(b.start.hour * 60 + b.start.minute)}–${fmtMin(b.end.hour * 60 + b.end.minute)}（${fmtDur(b.minutes)}）", fontSize = 13.sp, color = Muted)
        Text(b.label + if (b.appKind.isNotBlank()) "（${b.appKind}）" else "", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill(if (c != null) "${c.emoji}${c.name}" else "選分類") { onPick() }
            if (catId != null) Pill("加入", true) { onAdd(catId) }
            TextButton("忽略這個 App", Muted) { onIgnore() }
        }
        if (catId != null && data.appCategories[b.pkg] == null) Text("第一次看到這個 App，預設算「${c?.name}」，可以點分類修改", fontSize = 11.sp, color = Muted)
    }
}
