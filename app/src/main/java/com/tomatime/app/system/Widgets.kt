package com.tomatime.app.system

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.tomatime.app.MainActivity
import com.tomatime.app.TomatimeApp
import com.tomatime.app.data.AppData
import com.tomatime.app.data.Origin
import com.tomatime.app.data.Status
import com.tomatime.app.domain.Occ
import com.tomatime.app.domain.dateLabel
import com.tomatime.app.domain.displayTitle
import com.tomatime.app.domain.findOcc
import com.tomatime.app.domain.fmtMin
import com.tomatime.app.domain.isTask
import com.tomatime.app.domain.occurrences
import com.tomatime.app.domain.setStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime

private val WCream = Color(0xFFFFF8EE)
private val WInk = Color(0xFF2B2522)
private val WMuted = Color(0xFF8A7F78)
private val WTomato = Color(0xFFE8473B)
private val WPill = Color(0xFFF1E7DC)

private val OpenKey = ActionParameters.Key<String>("open")
private val OccKey = ActionParameters.Key<String>("occ")
private val StatusKey = ActionParameters.Key<String>("status")

private fun style(size: Int, color: Color = WInk, bold: Boolean = false) =
    TextStyle(color = ColorProvider(color), fontSize = size.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)

private fun openApp(open: String) = actionStartActivity<MainActivity>(actionParametersOf(OpenKey to open))

object Widgets {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun refresh(ctx: Context) {
        val app = ctx.applicationContext
        scope.launch {
            try {
                TodayWidget().updateAll(app)
                OverviewWidget().updateAll(app)
            } catch (_: Throwable) {
            }
        }
    }
}

private fun store(ctx: Context) = (ctx.applicationContext as TomatimeApp).store

private fun emoji(data: AppData, o: Occ) = data.categories.find { it.id == o.event.categoryId }?.emoji.orEmpty()

// ---------- 今日 ----------

class TodayWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = store(context).data.value
        val now = LocalDateTime.now()
        provideContent { TodayContent(data, now) }
    }
}

@Composable
private fun TodayContent(data: AppData, now: LocalDateTime) {
    val today = now.toLocalDate()
    val occs = occurrences(data, today, today).filter { it.date == today && !it.hidden && !it.isSleep }
    val current = occs.firstOrNull { !it.start.isAfter(now) && it.end.isAfter(now) }
    val next = occs.firstOrNull { it.start.isAfter(now) }
    Column(
        GlanceModifier.fillMaxSize().background(WCream).cornerRadius(20.dp).padding(10.dp).clickable(openApp("main")),
    ) {
        Text("今天 ${dateLabel(today)}", style = style(14, bold = true))
        Spacer(GlanceModifier.height(4.dp))
        Column(GlanceModifier.defaultWeight()) {
            if (occs.isEmpty()) Text("今天還沒有安排", style = style(12, WMuted))
            occs.take(7).forEach { o ->
                val done = o.status?.status == Status.DONE
                Text(
                    "${fmtMin(o.startMin)} ${emoji(data, o)}${displayTitle(o.event, data)}${if (done) " ✓" else ""}",
                    style = style(12, if (done || o.end.isBefore(now)) WMuted else WInk),
                    maxLines = 1,
                )
            }
            if (occs.size > 7) Text("還有 ${occs.size - 7} 件…", style = style(11, WMuted))
        }
        Column(GlanceModifier.fillMaxWidth().background(WPill).cornerRadius(12.dp).padding(8.dp)) {
            if (current != null) {
                Text("現在 ${emoji(data, current)}${displayTitle(current.event, data)}", style = style(13, bold = true), maxLines = 1)
                val canMark = current.event.origin == Origin.PLANNED && current.status == null
                if (canMark) {
                    Row(GlanceModifier.fillMaxWidth().padding(top = 4.dp)) {
                        WButton("✅", actionRunCallback<MarkAction>(actionParametersOf(OccKey to current.id, StatusKey to "DONE")))
                        if (data.settings.widgetFullButtons) {
                            Spacer(GlanceModifier.width(6.dp))
                            WButton("🟡", openApp("remaining:${current.id}"))
                            Spacer(GlanceModifier.width(6.dp))
                            WButton("❌", actionRunCallback<MarkAction>(actionParametersOf(OccKey to current.id, StatusKey to "MISSED")))
                        }
                    }
                }
            } else {
                Text("現在 —", style = style(13, WMuted))
            }
            Text(
                "下個 " + (next?.let { "${fmtMin(it.startMin)} ${emoji(data, it)}${displayTitle(it.event, data)}" } ?: "—"),
                style = style(12, WMuted), maxLines = 1,
            )
        }
    }
}

@Composable
private fun WButton(label: String, action: androidx.glance.action.Action) {
    Box(
        GlanceModifier.width(44.dp).height(34.dp).background(Color.White).cornerRadius(10.dp).clickable(action),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = style(15)) }
}

class MarkAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[OccKey] ?: return
        val st = parameters[StatusKey]?.let { runCatching { Status.valueOf(it) }.getOrNull() } ?: Status.DONE
        val s = store(context)
        val occ = findOcc(s.data.value, id) ?: return
        s.update { setStatus(it, occ, st) }
        TodayWidget().updateAll(context)
    }
}

class TodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayWidget()
}

// ---------- 概覽 ----------

class OverviewWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = store(context).data.value
        provideContent { OverviewContent(data, LocalDate.now()) }
    }
}

/** The day's important things: tasks rated 重要/必做, deadlines, and one-off plans. */
fun importantItems(data: AppData, day: LocalDate): List<String> {
    val cats = data.categories.associateBy { it.id }
    val out = ArrayList<String>()
    occurrences(data, day, day).filter { it.date == day && !it.hidden && !it.isSleep && it.event.origin == Origin.PLANNED }
        .filter { it.event.repeat == com.tomatime.app.data.Repeat.NONE }
        .forEach { o ->
            val star = if (isTask(data, o) && o.event.importance >= 4) "❗" else ""
            out += "$star${fmtMin(o.startMin)} ${cats[o.event.categoryId]?.emoji.orEmpty()}${displayTitle(o.event, data)}"
        }
    data.unfinished.filter { it.deadline == day.toString() }.forEach { out += "⏰ 期限：${it.title}" }
    data.events.filter { it.deadline == day.toString() && it.repeat == com.tomatime.app.data.Repeat.NONE }
        .filter { e -> e.statuses.values.none { it.status == Status.DONE } }
        .forEach { out += "⏰ 期限：${displayTitle(it, data)}" }
    return out.distinct()
}

@Composable
private fun OverviewContent(data: AppData, today: LocalDate) {
    val days = data.settings.widgetOverviewDays
    Column(GlanceModifier.fillMaxSize().background(WCream).cornerRadius(20.dp).padding(12.dp).clickable(openApp("overview"))) {
        Text("接下來 $days 天", style = style(15, bold = true))
        Spacer(GlanceModifier.height(6.dp))
        var lines = 0
        for (i in 0 until days) {
            if (lines > 22) break
            val d = today.plusDays(i.toLong())
            val items = importantItems(data, d)
            if (items.isEmpty() && i > 0) continue
            Text(if (i == 0) "今天 ${dateLabel(d)}" else dateLabel(d), style = style(12, if (i == 0) WTomato else WMuted, bold = true))
            lines++
            if (items.isEmpty()) {
                Text("　沒有重要事項", style = style(12, WMuted))
                lines++
            }
            items.take(4).forEach {
                Text("　$it", style = style(12), maxLines = 1)
                lines++
            }
            if (items.size > 4) {
                Text("　還有 ${items.size - 4} 件", style = style(11, WMuted))
                lines++
            }
        }
    }
}

class OverviewWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = OverviewWidget()
}

// ---------- 新增 ----------

class AddWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            Row(GlanceModifier.fillMaxSize().background(WCream).cornerRadius(20.dp).padding(6.dp)) {
                Box(
                    GlanceModifier.defaultWeight().fillMaxHeight().background(WPill).cornerRadius(14.dp).clickable(openApp("plan")),
                    contentAlignment = Alignment.Center,
                ) { Text("📅 安排", style = style(14, bold = true)) }
                Spacer(GlanceModifier.width(6.dp))
                Box(
                    GlanceModifier.defaultWeight().fillMaxHeight().background(WPill).cornerRadius(14.dp).clickable(openApp("log")),
                    contentAlignment = Alignment.Center,
                ) { Text("📝 補登", style = style(14, bold = true)) }
            }
        }
    }
}

class AddWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = AddWidget()
}
