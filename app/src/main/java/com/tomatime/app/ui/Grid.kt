package com.tomatime.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tomatime.app.data.Category
import com.tomatime.app.data.Origin
import com.tomatime.app.data.Status
import com.tomatime.app.domain.Occ
import com.tomatime.app.domain.fmtMin
import com.tomatime.app.domain.wd
import java.time.LocalDate
import java.time.LocalDateTime

/** A dashed box showing where 幫我排 suggests putting something. */
data class Preview(val date: LocalDate, val startMin: Int, val durationMin: Int)

/** What a long-press drag on the grid does. */
enum class Purpose { PLAN, LOG, MOVE }

@Composable
fun CalendarGrid(
    days: List<LocalDate>,
    range: Pair<Int, Int>,
    occs: List<Occ>,
    cats: Map<String, Category>,
    titles: Map<String, String>,
    selecting: Purpose?,
    moveDuration: Int?,
    preview: Preview?,
    now: LocalDateTime,
    wide: Boolean,
    onHeader: (LocalDate) -> Unit,
    onOccTap: (Occ) -> Unit,
    onSelecting: (LocalDate, Int, Int) -> Unit,
    onSelected: (LocalDate, Int, Int) -> Unit,
    onSwipe: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val (rs, re) = range
    val today = now.toLocalDate()
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(start = 30.dp)) {
            days.forEach { d ->
                val isToday = d == today
                Column(
                    Modifier.weight(1f).clickable { onHeader(d) }.padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        if (wide) "週${wd(d)}" else wd(d),
                        fontSize = 13.sp,
                        fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                        color = if (isToday) Tomato else Ink,
                    )
                    Text("${d.monthValue}/${d.dayOfMonth}", fontSize = 10.sp, color = if (isToday) Tomato else Muted)
                }
            }
        }
        val swipe by rememberUpdatedState(onSwipe)
        var drag by remember { mutableFloatStateOf(0f) }
        val swipeMod = if (selecting == null) Modifier.pointerInput(Unit) {
            detectHorizontalDragGestures(
                onDragStart = { drag = 0f },
                onDragEnd = {
                    if (drag > 140f) swipe(-1) else if (drag < -140f) swipe(1)
                },
            ) { _, amount -> drag += amount }
        } else Modifier
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().then(swipeMod)) {
            val h = maxHeight
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.width(30.dp).fillMaxHeight()) {
                    var hr = rs
                    while (hr < re) {
                        Text(
                            fmtMin(hr).substring(0, 2),
                            fontSize = 10.sp,
                            color = Muted,
                            modifier = Modifier.offset(y = yOf(hr, rs, re, h)).padding(start = 6.dp),
                        )
                        hr += 60
                    }
                }
                days.forEach { d ->
                    DayColumn(
                        d, rs, re, h, occs.filter { it.date == d && !it.hidden }, cats, titles,
                        selecting, moveDuration, preview?.takeIf { it.date == d },
                        now, wide, onOccTap, onSelecting, onSelected,
                    )
                }
            }
        }
    }
}

private fun yOf(min: Int, rs: Int, re: Int, h: Dp): Dp = h * ((min - rs).toFloat() / (re - rs).toFloat())

@Composable
private fun RowScope.DayColumn(
    d: LocalDate,
    rs: Int,
    re: Int,
    h: Dp,
    occs: List<Occ>,
    cats: Map<String, Category>,
    titles: Map<String, String>,
    selecting: Purpose?,
    moveDuration: Int?,
    preview: Preview?,
    now: LocalDateTime,
    wide: Boolean,
    onOccTap: (Occ) -> Unit,
    onSelecting: (LocalDate, Int, Int) -> Unit,
    onSelected: (LocalDate, Int, Int) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    var sel by remember(d) { mutableStateOf<Pair<Int, Int>?>(null) }
    val selectingCb by rememberUpdatedState(onSelecting)
    val selectedCb by rememberUpdatedState(onSelected)
    val total = (re - rs).toFloat()

    val selMod = if (selecting != null) Modifier.pointerInput(selecting, moveDuration, d, rs, re) {
        fun toMin(py: Float): Int = rs + (py / size.height * total).toInt()
        fun floor30(m: Int): Int = (m / 30) * 30
        fun near30(m: Int): Int = ((m + 15) / 30) * 30
        var anchor = rs
        fun rangeFor(py: Float): Pair<Int, Int> =
            if (moveDuration != null) {
                val st = floor30(toMin(py)).coerceIn(rs, (re - 30).coerceAtLeast(rs))
                st to st + moveDuration
            } else {
                val m = near30(toMin(py)).coerceIn(rs, re)
                if (m < anchor) m to anchor + 30 else anchor to maxOf(m, anchor + 30)
            }
        detectDragGesturesAfterLongPress(
            onDragStart = { off ->
                anchor = floor30(toMin(off.y)).coerceIn(rs, (re - 30).coerceAtLeast(rs))
                val r = if (moveDuration != null) anchor to anchor + moveDuration else anchor to anchor + 30
                sel = r
                selectingCb(d, r.first, r.second)
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            },
            onDrag = { change, _ ->
                change.consume()
                val r = rangeFor(change.position.y)
                if (r != sel) {
                    sel = r
                    selectingCb(d, r.first, r.second)
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
            },
            onDragEnd = {
                sel?.let { selectedCb(d, it.first, it.second) }
                sel = null
            },
            onDragCancel = { sel = null },
        )
    } else Modifier

    Box(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .then(selMod)
            .drawBehind {
                val step = size.height / total * 60f
                var y = 0f
                while (y <= size.height + 1f) {
                    drawLine(GridLine, Offset(0f, y), Offset(size.width, y), 1.5f)
                    y += step
                }
                drawLine(GridLine, Offset(0f, 0f), Offset(0f, size.height), 1.5f)
            },
    ) {
        occs.forEach { o ->
            val a = o.startMin.coerceAtLeast(rs)
            val b = o.endMin.coerceAtMost(re)
            if (b > a) {
                OccBlock(
                    o, cats[o.event.categoryId], titles[o.event.id].orEmpty(), wide,
                    enabled = selecting == null,
                    modifier = Modifier.offset(y = yOf(a, rs, re, h)).height(yOf(b, rs, re, h) - yOf(a, rs, re, h)).fillMaxWidth(),
                    onClick = { onOccTap(o) },
                )
            }
        }
        if (preview != null) {
            val a = preview.startMin
            val b = preview.startMin + preview.durationMin
            Box(
                Modifier
                    .offset(y = yOf(a, rs, re, h))
                    .height(yOf(b, rs, re, h) - yOf(a, rs, re, h))
                    .fillMaxWidth()
                    .padding(1.dp)
                    .background(Tomato.copy(alpha = 0.10f), RoundedCornerShape(6.dp))
                    .drawBehind {
                        drawRoundRect(
                            Tomato, cornerRadius = CornerRadius(14f),
                            style = Stroke(4f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))),
                        )
                    },
            )
        }
        sel?.let { (a, b) ->
            Box(
                Modifier
                    .offset(y = yOf(a, rs, re, h))
                    .height(yOf(b, rs, re, h) - yOf(a, rs, re, h))
                    .fillMaxWidth()
                    .padding(1.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Tomato.copy(alpha = 0.35f))
                    .border(2.dp, Tomato, RoundedCornerShape(6.dp)),
            )
        }
        if (d == now.toLocalDate()) {
            val nm = now.hour * 60 + now.minute
            if (nm in rs..re) {
                Box(Modifier.offset(y = yOf(nm, rs, re, h)).fillMaxWidth().height(2.dp).background(Tomato))
            }
        }
    }
}

@Composable
private fun OccBlock(
    o: Occ,
    cat: Category?,
    title: String,
    wide: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val c = Color(cat?.color ?: 0xFF8A8A8A)
    val st = o.status?.status
    val logged = o.event.origin == Origin.LOGGED
    val alpha = when {
        st == Status.MISSED -> 0.18f
        st == Status.DONE || st == Status.PARTIAL || logged -> 0.9f
        else -> 0.45f
    }
    Box(
        modifier
            .padding(1.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(c.copy(alpha = alpha))
            .drawBehind {
                if (st == Status.PARTIAL) {
                    drawRect(Color.White.copy(alpha = 0.55f), topLeft = Offset(0f, size.height / 2f), size = Size(size.width, size.height / 2f))
                }
                if (st == Status.MISSED) {
                    var x = -size.height
                    while (x < size.width) {
                        drawLine(c.copy(alpha = 0.6f), Offset(x, size.height), Offset(x + size.height, 0f), 2f)
                        x += 14f
                    }
                }
                if (logged) {
                    val p = Path().apply { moveTo(0f, 0f); lineTo(20f, 0f); lineTo(0f, 20f); close() }
                    drawPath(p, Color.White.copy(alpha = 0.95f))
                }
            }
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(2.dp),
    ) {
        if (wide) {
            Column {
                Text("${cat?.emoji.orEmpty()} $title", fontSize = 13.sp, color = Ink, maxLines = 2)
                Text("${fmtMin(o.startMin)}–${fmtMin(o.endMin)}", fontSize = 11.sp, color = Ink.copy(alpha = 0.7f))
            }
        } else {
            Text(cat?.emoji.orEmpty(), fontSize = 14.sp, modifier = Modifier.align(Alignment.TopCenter))
        }
    }
}
