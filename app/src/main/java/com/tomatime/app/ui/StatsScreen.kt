package com.tomatime.app.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tomatime.app.R
import com.tomatime.app.data.AppData
import com.tomatime.app.data.Category
import com.tomatime.app.data.Origin
import com.tomatime.app.data.Status
import com.tomatime.app.data.Store
import com.tomatime.app.domain.bucketLabel
import com.tomatime.app.domain.dateLabel
import com.tomatime.app.domain.durationRatio
import com.tomatime.app.domain.fmtDur
import com.tomatime.app.domain.mondayOf
import com.tomatime.app.domain.occurrences
import com.tomatime.app.domain.typicalMinBlock
import com.tomatime.app.system.Perms
import com.tomatime.app.system.Usage
import java.time.LocalDate
import java.time.ZoneId

/** Minutes actually spent per category in [from]..[to]: 補登, ✅, and the done part of 🟡. */
fun spentMinutes(data: AppData, from: LocalDate, to: LocalDate): Map<String, Int> {
    val out = HashMap<String, Int>()
    occurrences(data, from, to).filter { !it.date.isBefore(from) && !it.date.isAfter(to) && !it.isSleep }.forEach { o ->
        val st = o.status
        val m = when {
            o.event.origin == Origin.LOGGED -> o.durationMin
            st?.status == Status.DONE -> o.durationMin
            st?.status == Status.PARTIAL -> (o.durationMin - st.remainingMin).coerceAtLeast(0)
            else -> 0
        }
        if (m > 0) out[o.event.categoryId] = (out[o.event.categoryId] ?: 0) + m
    }
    return out
}

/** Days with at least one record (補登, a check-off or a pomodoro). */
fun recordedDays(data: AppData, from: LocalDate, to: LocalDate): Set<LocalDate> {
    val days = HashSet<LocalDate>()
    occurrences(data, from, to).filter { !it.isSleep && (it.event.origin == Origin.LOGGED || it.status != null) }
        .forEach { if (!it.date.isBefore(from) && !it.date.isAfter(to)) days += it.date }
    val zone = ZoneId.systemDefault()
    data.pomoSessions.forEach {
        val d = java.time.Instant.ofEpochMilli(it.start).atZone(zone).toLocalDate()
        if (!d.isBefore(from) && !d.isAfter(to)) days += d
    }
    return days
}

@Composable
fun StatsScreen(store: Store, data: AppData, cats: Map<String, Category>, today: LocalDate, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var monday by remember { mutableStateOf(mondayOf(today)) }
    val sunday = monday.plusDays(6)
    val thisWeek = spentMinutes(data, monday, sunday)
    val lastWeek = spentMinutes(data, monday.minusDays(7), monday.minusDays(1))
    val weekOccs = occurrences(data, monday, sunday).filter { !it.date.isBefore(monday) && !it.date.isAfter(sunday) }
    val done = weekOccs.count { it.status?.status == Status.DONE }
    val missed = weekOccs.count { it.status?.status == Status.MISSED && it.status?.replaced != true }
    val zone = ZoneId.systemDefault()
    val wFrom = monday.atStartOfDay(zone).toInstant().toEpochMilli()
    val wTo = sunday.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val pomos = data.pomoSessions.filter { it.start in wFrom until wTo }
    val monthStart = today.withDayOfMonth(1)
    val monthDays = recordedDays(data, monthStart, today)
    var streak = 0
    run {
        var d = if (today in monthDays || today in recordedDays(data, today, today)) today else today.minusDays(1)
        val recent = recordedDays(data, today.minusDays(60), today)
        while (d in recent) {
            streak++
            d = d.minusDays(1)
        }
    }

    var popped by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { popped = true }
    val scale by animateFloatAsState(if (popped) 1f else 0.4f, spring(dampingRatio = Spring.DampingRatioMediumBouncy), label = "pop")

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton("‹ 返回") { onBack() }
            Text("統計", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton("‹") { monday = monday.minusDays(7) }
            Text("${dateLabel(monday)}起", fontSize = 13.sp)
            TextButton("›") { monday = monday.plusDays(7) }
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painterResource(if (done + pomos.count { it.completed } > 0) R.drawable.mascot_review else R.drawable.mascot_encourage),
                        null, Modifier.size(110.dp).graphicsLayer { scaleX = scale; scaleY = scale },
                    )
                    Column(Modifier.padding(start = 8.dp)) {
                        Text("這週完成 $done 件事", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text("🍅 ${pomos.count { it.completed }} 顆番茄", fontSize = 15.sp)
                        if (missed > 0) Text("有 $missed 件沒做到，下週挑最重要的一件先做就好", fontSize = 13.sp, color = Muted)
                        else if (done == 0) Text("還沒有完成紀錄，從一顆番茄開始吧", fontSize = 13.sp, color = Muted)
                    }
                }
            }

            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(R.drawable.mascot_streak), null, Modifier.size(80.dp))
                    Column(Modifier.padding(start = 8.dp)) {
                        Text(if (streak > 0) "已連續記錄 $streak 天" else "今天還沒記錄", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Text("這個月有 ${monthDays.size} 天有紀錄", fontSize = 14.sp, color = Muted)
                    }
                }
            }

            Card {
                Text("時間花在哪裡", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                val maxM = (thisWeek.values + lastWeek.values).maxOrNull()?.coerceAtLeast(60) ?: 60
                val ids = (thisWeek.keys + lastWeek.keys).distinct().sortedByDescending { thisWeek[it] ?: 0 }
                if (ids.isEmpty()) Text("這週還沒有紀錄。完成或補登的事情會出現在這裡。", fontSize = 13.sp, color = Muted)
                ids.forEach { id ->
                    val c = cats[id]
                    val m = thisWeek[id] ?: 0
                    val prev = lastWeek[id] ?: 0
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${c?.emoji.orEmpty()}${c?.name.orEmpty()}", fontSize = 13.sp, modifier = Modifier.width(72.dp))
                        Box(Modifier.weight(1f).height(14.dp).clip(RoundedCornerShape(7.dp)).background(PillBg)) {
                            Box(
                                Modifier.fillMaxWidth(m.toFloat() / maxM).height(14.dp).clip(RoundedCornerShape(7.dp))
                                    .background(Color(c?.color ?: 0xFF8A8A8A)),
                            )
                        }
                        Text(fmtDur(m), fontSize = 12.sp, modifier = Modifier.width(76.dp).padding(start = 6.dp))
                    }
                    val diff = m - prev
                    if (prev > 0 && kotlin.math.abs(diff) >= 30) {
                        Text(
                            if (diff > 0) "　比上週多 ${fmtDur(diff)} 👍" else "　比上週少 ${fmtDur(-diff)}，下週先排一顆番茄試試",
                            fontSize = 12.sp, color = if (diff > 0) Leaf else Muted,
                        )
                    }
                }
                val leisure = thisWeek["leisure"] ?: 0
                val rest = thisWeek["rest"] ?: 0
                if (leisure + rest > 0) {
                    Spacer(Modifier.height(6.dp))
                    Text("休閒 ${fmtDur(leisure)}、休息 ${fmtDur(rest)}", fontSize = 13.sp, color = Muted)
                }
            }

            val nights = data.sleepNights.filter { !LocalDate.parse(it.date).isBefore(monday.minusDays(1)) && !LocalDate.parse(it.date).isAfter(sunday) }
            if (nights.isNotEmpty()) {
                Card {
                    Text("睡前滑手機", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text("這週睡覺時間滑了 ${fmtDur(nights.sumOf { it.phoneMin })}，提醒 ${nights.sumOf { it.reminders }} 次", fontSize = 14.sp)
                    val em = nights.sumOf { it.emergencies }
                    if (em > 0) Text("「我有急事」$em 次，共 ${fmtDur(nights.sumOf { it.emergencyMin })}", fontSize = 13.sp, color = Muted)
                }
            }

            if (data.settings.usageEnabled && Perms.usage(ctx)) {
                val kinds = remember(monday) { Usage.minutesByKind(ctx, wFrom, wTo) }
                if (kinds.isNotEmpty()) {
                    Card {
                        Text("手機 App 使用時間", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        kinds.entries.sortedByDescending { it.value }.take(6).forEach { (k, m) ->
                            Text("$k　${fmtDur(m)}", fontSize = 13.sp)
                        }
                    }
                }
            }

            Card {
                Text("從你的紀錄學到的", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                var any = false
                data.categories.filter { !it.system }.forEach { c ->
                    val lines = buildList {
                        durationRatio(data, c.id)?.let { r ->
                            val pct = ((r - 1) * 100).toInt()
                            add(if (pct >= 0) "通常比預估多花 $pct%" else "通常比預估少花 ${-pct}%")
                        }
                        typicalMinBlock(data, c.id)?.let { add("大部分時候一次至少 ${fmtDur(it)}") }
                        data.timePrefs[c.id]?.let { add("偏好排在${bucketLabel(it.bucket)}（${listOf("隨意", "稍好", "偏好", "強烈", "必須")[it.strength - 1]}）") }
                    }
                    if (lines.isNotEmpty()) {
                        any = true
                        Text("${c.emoji}${c.name}：${lines.joinToString("；")}", fontSize = 13.sp)
                        if (data.timePrefs.containsKey(c.id)) {
                            TextButton("取消${c.name}的時段偏好", Tomato) {
                                store.update { it.copy(timePrefs = it.timePrefs - c.id) }
                            }
                        }
                    }
                }
                if (!any) Text("每一類累積約 5 筆紀錄後，這裡會出現你的習慣。", fontSize = 13.sp, color = Muted)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White).padding(14.dp)) { content() }
}
