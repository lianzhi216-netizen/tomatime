package com.tomatime.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tomatime.app.domain.fmtMin
import kotlin.math.roundToInt

val Tomato = Color(0xFFE8473B)
val Cream = Color(0xFFFFF8EE)
val Ink = Color(0xFF2B2522)
val Muted = Color(0xFF8A7F78)
val GridLine = Color(0xFFEADFD3)
val PillBg = Color(0xFFF1E7DC)
val Leaf = Color(0xFF4CAF6A)

@Composable
fun TomatimeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Tomato,
            onPrimary = Color.White,
            secondary = Leaf,
            background = Cream,
            onBackground = Ink,
            surface = Color.White,
            onSurface = Ink,
            surfaceContainerLow = Color.White,
            surfaceContainerHigh = Color.White,
        ),
        content = content,
    )
}

@Composable
fun Pill(text: String, selected: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) Tomato else PillBg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (selected) Color.White else Ink, fontSize = 14.sp)
    }
}

/** A row of pills that wraps into several rows of [perRow]. */
@Composable
fun PillRows(items: List<Pair<String, Boolean>>, perRow: Int = 4, onClick: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.withIndex().chunked(perRow).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (i, item) -> Pill(item.first, item.second) { onClick(i) } }
            }
        }
    }
}

@Composable
fun Stepper(
    label: String,
    value: Int,
    min: Int,
    max: Int,
    step: Int = 30,
    format: (Int) -> String = { fmtMin(it) },
    onChange: (Int) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 15.sp, color = Ink, modifier = Modifier.weight(1f))
        Pill("−") { onChange((value - step).coerceAtLeast(min)) }
        Text(format(value), fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.width(92.dp))
        Pill("＋") { onChange((value + step).coerceAtMost(max)) }
    }
}

private val IMPORTANCE = listOf("隨意", "有空", "該做", "重要", "必做")

fun importanceColor(v: Int): Color = lerp(Color(0xFF9CCB7A), Tomato, (v - 1) / 4f)
fun importanceLabel(v: Int): String = IMPORTANCE[(v - 1).coerceIn(0, 4)]

/** Five-step slider with a two-character label that follows the thumb. */
@Composable
fun FiveStepSlider(value: Int, labels: List<String> = IMPORTANCE, onChange: (Int) -> Unit) {
    val haptic = LocalHapticFeedback.current
    val color = importanceColor(value)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val w = maxWidth
        Column {
            Slider(
                value = value.toFloat(),
                onValueChange = { v ->
                    val n = v.roundToInt().coerceIn(1, 5)
                    if (n != value) {
                        onChange(n)
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                },
                valueRange = 1f..5f,
                steps = 3,
                colors = SliderDefaults.colors(thumbColor = color, activeTrackColor = color, activeTickColor = Color.White),
            )
            val frac = (value - 1) / 4f
            Text(
                labels[value - 1],
                color = color,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(48.dp).offset(x = 10.dp + (w - 20.dp) * frac - 24.dp),
            )
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    Spacer(Modifier.padding(top = 10.dp))
    Text(text, fontSize = 13.sp, color = Muted, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 6.dp))
}
