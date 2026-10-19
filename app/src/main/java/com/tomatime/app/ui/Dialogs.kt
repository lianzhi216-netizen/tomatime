package com.tomatime.app.ui

import android.app.DatePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import com.tomatime.app.data.Origin
import com.tomatime.app.data.Repeat
import com.tomatime.app.data.Status
import com.tomatime.app.data.Unfinished
import com.tomatime.app.domain.EditScope
import com.tomatime.app.domain.EditValues
import com.tomatime.app.domain.Occ
import com.tomatime.app.domain.dateLabel
import com.tomatime.app.domain.fmtDur
import com.tomatime.app.domain.fmtMin
import com.tomatime.app.domain.isTask
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

@Composable
fun SimpleDialog(title: String, text: String, confirm: Pair<String, () -> Unit>, dismiss: Pair<String, () -> Unit>) {
    AlertDialog(
        onDismissRequest = dismiss.second,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { androidx.compose.material3.TextButton(onClick = confirm.second) { Text(confirm.first) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = dismiss.second) { Text(dismiss.first) } },
    )
}

@Composable
private fun BigChoice(title: String, sub: String, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(PillBg)
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text(sub, fontSize = 13.sp, color = Muted)
    }
}

@Composable
fun ChooseHowDialog(label: String, onSelf: () -> Unit, onAuto: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("安排 $label") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                BigChoice("自己排", "在表格上長按拖曳選時間", onSelf)
                BigChoice("幫我排", "填期限和預估時間，自動找空檔", onAuto)
            }
        },
        confirmButton = {},
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private val REPEAT_LABELS = listOf(Repeat.NONE to "不重複", Repeat.WEEKLY to "每週", Repeat.BIWEEKLY to "每兩週", Repeat.MONTHLY to "每月")

@Composable
private fun RepeatPicker(value: Repeat, onChange: (Repeat) -> Unit) {
    var more by remember { mutableStateOf(value == Repeat.BIWEEKLY || value == Repeat.MONTHLY) }
    val shown = if (more) REPEAT_LABELS else REPEAT_LABELS.take(2)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        shown.forEach { (r, label) -> Pill(label, value == r) { onChange(r) } }
        if (!more) Pill("更多…") { more = true }
    }
}

@Composable
fun ConfirmPlanDialog(d: Dlg.ConfirmPlan, label: String, onDismiss: () -> Unit, onSave: (String, Repeat) -> Unit) {
    var title by remember { mutableStateOf(d.sel.title) }
    var repeat by remember { mutableStateOf(Repeat.NONE) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("安排 $label") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${dateLabel(d.d)} ${fmtMin(d.a)}–${fmtMin(d.b)}（${fmtDur(d.b - d.a)}）", fontWeight = FontWeight.Bold, color = Tomato)
                OutlinedTextField(
                    value = title, onValueChange = { title = it }, singleLine = true,
                    label = { Text("名稱（可不填）") }, modifier = Modifier.fillMaxWidth(),
                )
                if (d.sel.from == null) {
                    Text("重複", fontSize = 13.sp, color = Muted)
                    RepeatPicker(repeat) { repeat = it }
                }
                if (d.replace.isNotEmpty()) Text("會取代這段時間原本的 ${d.replace.size} 件安排", fontSize = 13.sp, color = Muted)
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { onSave(title, repeat) }) { Text("儲存") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun deadlineChoices(today: LocalDate): List<Pair<String, LocalDate>> {
    val fri = today.with(java.time.temporal.TemporalAdjusters.nextOrSame(DayOfWeek.FRIDAY))
    val nextSunday = today.with(java.time.temporal.TemporalAdjusters.next(DayOfWeek.SUNDAY)).let {
        if (it.isBefore(today.plusDays(7))) it.plusDays(7) else it
    }
    return listOf(
        "今天" to today,
        "明天" to today.plusDays(1),
        (if (today.dayOfWeek.value > 5) "下週五" else "本週五") to fri,
        "下週" to nextSunday,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanFormSheet(
    form: Dlg.PlanForm,
    label: String,
    today: LocalDate,
    onDismiss: () -> Unit,
    onFind: (Dlg.PlanForm, LocalDate) -> Unit,
) {
    val ctx = LocalContext.current
    val choices = remember(today) { deadlineChoices(today) }
    var title by remember { mutableStateOf(form.title) }
    var deadline by remember { mutableStateOf(form.deadline ?: choices[2].second) }
    var est by remember { mutableIntStateOf(form.estimateMin) }
    var importance by remember { mutableIntStateOf(form.importance) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Color.White) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("幫我排：$label", fontSize = 19.sp, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = title, onValueChange = { title = it }, singleLine = true,
                label = { Text("名稱（可不填）") }, modifier = Modifier.fillMaxWidth(),
            )
            SectionTitle("期限：${dateLabel(deadline)}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                choices.forEach { (name, d) -> Pill(name, deadline == d) { deadline = d } }
            }
            Pill("選日期…") {
                DatePickerDialog(ctx, { _, y, m, dd -> deadline = LocalDate.of(y, m + 1, dd) }, deadline.year, deadline.monthValue - 1, deadline.dayOfMonth).apply {
                    datePicker.minDate = System.currentTimeMillis() - 1000
                }.show()
            }
            SectionTitle("預估時間")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(30, 60, 120, 180).forEach { m -> Pill(fmtDur(m), est == m) { est = m } }
            }
            Stepper("自訂", est, 30, 12 * 60, format = { fmtDur(it) }) { est = it }
            SectionTitle("重要度")
            FiveStepSlider(importance) { importance = it }
            Spacer(Modifier.height(6.dp))
            Button(
                onClick = { onFind(form.copy(title = title, estimateMin = est, importance = importance, deadline = deadline), deadline) },
                colors = ButtonDefaults.buttonColors(containerColor = Tomato),
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) { Text("找時間", fontSize = 17.sp) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailSheet(
    occ: Occ,
    data: AppData,
    cat: Category?,
    title: String,
    now: LocalDateTime,
    onDismiss: () -> Unit,
    onStatus: (Status?) -> Unit,
    onPartial: () -> Unit,
    onAction: (Action) -> Unit,
    onPomo: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val e = occ.event
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Color.White) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("${cat?.emoji.orEmpty()} $title", fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Text("${dateLabel(occ.date)} ${fmtMin(occ.startMin)}–${fmtMin(occ.endMin)}（${fmtDur(occ.durationMin)}）", fontSize = 15.sp)
            val tags = buildList {
                cat?.let { add(it.name) }
                when (e.repeat) {
                    Repeat.WEEKLY -> add("每週重複")
                    Repeat.BIWEEKLY -> add("每兩週重複")
                    Repeat.MONTHLY -> add("每月重複")
                    Repeat.NONE -> Unit
                }
                if (e.origin == Origin.LOGGED) add("補登記")
                e.deadline?.let { add("期限 ${dateLabel(LocalDate.parse(it))}") }
                if (isTask(data, occ)) add("重要度：${importanceLabel(e.importance)}")
            }
            Text(tags.joinToString(" · "), fontSize = 13.sp, color = Muted)
            if (e.totalMin > 0) {
                Text("進度：之前已做 ${fmtDur(e.doneBeforeMin)} / 共 ${fmtDur(e.totalMin)}", fontSize = 13.sp, color = Muted)
                LinearProgressIndicator(
                    progress = { e.doneBeforeMin.toFloat() / e.totalMin.toFloat() },
                    color = Leaf, trackColor = PillBg, modifier = Modifier.fillMaxWidth(),
                )
            }
            val st = occ.status
            if (!occ.isSleep && e.origin == Origin.PLANNED && !occ.start.isAfter(now)) {
                Spacer(Modifier.height(4.dp))
                if (st == null) {
                    Text("做得怎麼樣？", fontSize = 14.sp, color = Muted)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusButton("✅ 完成", Modifier.weight(1f)) { onStatus(Status.DONE) }
                        StatusButton("🟡 做一半", Modifier.weight(1f)) { onPartial() }
                        StatusButton("❌ 沒做", Modifier.weight(1f)) { onStatus(Status.MISSED) }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val text = when (st.status) {
                            Status.DONE -> "✅ 完成"
                            Status.PARTIAL -> "🟡 做一半，剩 ${fmtDur(st.remainingMin)}"
                            Status.MISSED -> "❌ 沒做"
                        }
                        Text("目前：$text", fontSize = 15.sp, modifier = Modifier.weight(1f))
                        TextButton("重新回報", Tomato) { onStatus(null) }
                    }
                }
            }
            if (!occ.isSleep && e.origin == Origin.PLANNED && occ.status == null && occ.end.isAfter(now)) {
                StatusButton("🍅 開始番茄鐘", Modifier.fillMaxWidth()) { onPomo() }
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusButton("✏️ 修改", Modifier.weight(1f)) { onAction(Action.EDIT) }
                StatusButton("🕘 移動", Modifier.weight(1f)) { onAction(Action.MOVE) }
                StatusButton("🗑 刪除", Modifier.weight(1f)) { onAction(Action.DELETE) }
            }
        }
    }
}

@Composable
private fun StatusButton(text: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(12.dp)).background(PillBg).clickable(onClick = onClick).padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, fontSize = 15.sp) }
}

@Composable
fun RemainingDialog(defaultMin: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var rem by remember { mutableIntStateOf(((defaultMin / 2) / 30 * 30).coerceAtLeast(30)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("大概還剩多少？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PillRows(listOf(30, 60, 90, 120, 180).map { fmtDur(it) to (rem == it) }, perRow = 3) { i ->
                    rem = listOf(30, 60, 90, 120, 180)[i]
                }
                Stepper("自訂", rem, 30, 12 * 60, format = { fmtDur(it) }) { rem = it }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { onConfirm(rem) }) { Text("確定") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
fun EditDialog(occ: Occ, scope: EditScope, data: AppData, onDismiss: () -> Unit, onSave: (EditValues) -> Unit) {
    val ctx = LocalContext.current
    val e = occ.event
    var title by remember { mutableStateOf(e.title) }
    var cat by remember { mutableStateOf(e.categoryId) }
    var date by remember { mutableStateOf(occ.date) }
    var start by remember { mutableIntStateOf(occ.startMin) }
    var end by remember { mutableIntStateOf(occ.endMin) }
    var repeat by remember { mutableStateOf(if (scope == EditScope.ONLY_THIS) Repeat.NONE else e.repeat) }
    val showRepeat = !(e.repeat != Repeat.NONE && scope == EditScope.ONLY_THIS)
    val choices = data.categories.filter { !it.system || it.id == e.categoryId }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("修改") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title, onValueChange = { title = it }, singleLine = true,
                    label = { Text("名稱（可不填）") }, modifier = Modifier.fillMaxWidth(),
                )
                Text("分類", fontSize = 13.sp, color = Muted)
                PillRows(choices.map { "${it.emoji}${it.name}" to (it.id == cat) }, perRow = 3) { i -> cat = choices[i].id }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("日期", fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Pill(dateLabel(date)) {
                        DatePickerDialog(ctx, { _, y, m, dd -> date = LocalDate.of(y, m + 1, dd) }, date.year, date.monthValue - 1, date.dayOfMonth).show()
                    }
                }
                val step = if (e.origin == Origin.LOGGED) 5 else 30
                Stepper("開始", start, 0, 28 * 60, step = step) { v ->
                    val dur = end - start
                    start = v
                    end = v + dur
                }
                Stepper("結束", end, start + step, start + 16 * 60, step = step) { end = it }
                if (showRepeat) {
                    Text("重複", fontSize = 13.sp, color = Muted)
                    RepeatPicker(repeat) { repeat = it }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                onSave(EditValues(title.trim(), cat, date, start, (end - start).coerceAtLeast(30), if (showRepeat) repeat else Repeat.NONE))
            }) { Text("儲存") }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
fun AllCatsDialog(cats: List<Category>, extra: Pair<String, () -> Unit>? = null, onDismiss: () -> Unit, onPick: (Category) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("選擇分類") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PillRows(cats.map { "${it.emoji}${it.name}" to false }, perRow = 3) { i -> onPick(cats[i]) }
                if (extra != null) BigChoice(extra.first, "依照手機記錄的 App 使用時間", extra.second)
            }
        },
        confirmButton = {},
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
fun UnfinishedActionDialog(
    u: Unfinished,
    onDismiss: () -> Unit,
    onSelf: () -> Unit,
    onAuto: () -> Unit,
    onDone: () -> Unit,
    onDrop: () -> Unit,
) {
    var confirmDrop by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(u.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("剩 ${fmtDur(u.totalMin - u.doneMin)}", color = Muted)
                if (confirmDrop) {
                    Text("確定不做了？它會從清單移除。", color = Tomato)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill("確定不做", true) { onDrop() }
                        Pill("返回") { confirmDrop = false }
                    }
                } else {
                    BigChoice("自己排", "在表格上長按拖曳選時間", onSelf)
                    BigChoice("幫我排", "自動找期限前的空檔", onAuto)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill("✅ 已完成") { onDone() }
                        Pill("不做了") { confirmDrop = true }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("關閉") } },
    )
}


/** 詳細補登: a name and exact times in 5-minute steps. */
@Composable
fun ConfirmLogDialog(d: LocalDate, a0: Int, b0: Int, label: String, onDismiss: () -> Unit, onSave: (String, Int, Int) -> Unit) {
    var title by remember { mutableStateOf("") }
    var a by remember { mutableIntStateOf(a0) }
    var b by remember { mutableIntStateOf(b0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("詳細補登 $label") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(dateLabel(d), color = Muted)
                OutlinedTextField(
                    value = title, onValueChange = { title = it }, singleLine = true,
                    label = { Text("做了什麼（可不填）") }, modifier = Modifier.fillMaxWidth(),
                )
                Stepper("開始", a, 0, b - 5, step = 5) { a = it }
                Stepper("結束", b, a + 5, a + 16 * 60, step = 5) { b = it }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { onSave(title, a, b) }) { Text("儲存") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private val PREF_LABELS = listOf("隨意", "稍好", "偏好", "強烈", "必須")

@Composable
fun AskPrefDialog(cat: String, bucket: String, onYes: (Int) -> Unit, onNo: () -> Unit) {
    var strength by remember { mutableIntStateOf(3) }
    AlertDialog(
        onDismissRequest = onNo,
        title = { Text("$cat 好像都在$bucket") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("要固定排在$bucket嗎？「必須」代表排不下就不排。", fontSize = 14.sp)
                FiveStepSlider(strength, PREF_LABELS) { strength = it }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { onYes(strength) }) { Text("確認") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onNo) { Text("不用") } },
    )
}

/** A mascot that pops in with a bounce and fades away on its own. */
@Composable
fun StickerPop(res: Int, onDone: () -> Unit) {
    val scale = remember { androidx.compose.animation.core.Animatable(0.2f) }
    val fade = remember { androidx.compose.animation.core.Animatable(1f) }
    androidx.compose.runtime.LaunchedEffect(res) {
        scale.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.45f, stiffness = 300f))
        kotlinx.coroutines.delay(700)
        fade.animateTo(0f, androidx.compose.animation.core.tween(350))
        onDone()
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Image(
            androidx.compose.ui.res.painterResource(res), null,
            Modifier.size(200.dp).graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                alpha = fade.value
                rotationZ = (1f - scale.value) * -25f
            },
        )
    }
}
