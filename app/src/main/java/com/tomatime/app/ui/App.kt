package com.tomatime.app.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.tomatime.app.R
import com.tomatime.app.domain.addCategoryFromTitle
import com.tomatime.app.domain.bucketLabel
import com.tomatime.app.domain.declinePref
import com.tomatime.app.domain.newCategoryIdea
import com.tomatime.app.domain.prefToAsk
import com.tomatime.app.domain.recordPlacement
import com.tomatime.app.domain.setPref
import com.tomatime.app.system.GuardianService
import com.tomatime.app.system.Notifs
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tomatime.app.data.AppData
import com.tomatime.app.data.Category
import com.tomatime.app.data.Event
import com.tomatime.app.data.Origin
import com.tomatime.app.data.Repeat
import com.tomatime.app.data.Store
import com.tomatime.app.data.Unfinished
import com.tomatime.app.domain.EditScope
import com.tomatime.app.domain.EditValues
import com.tomatime.app.domain.Occ
import com.tomatime.app.domain.applyDelete
import com.tomatime.app.domain.applyEdit
import com.tomatime.app.domain.at
import com.tomatime.app.domain.carveOut
import com.tomatime.app.domain.cats
import com.tomatime.app.domain.dateLabel
import com.tomatime.app.domain.displayTitle
import com.tomatime.app.domain.findOcc
import com.tomatime.app.domain.fmtDur
import com.tomatime.app.domain.fmtMin
import com.tomatime.app.domain.gridRange
import com.tomatime.app.domain.minOfDay
import com.tomatime.app.domain.mondayOf
import com.tomatime.app.domain.newId
import com.tomatime.app.domain.occurrences
import com.tomatime.app.domain.overlapping
import com.tomatime.app.domain.rangeLabel
import com.tomatime.app.domain.regenerateSleep
import com.tomatime.app.domain.roundUp30
import com.tomatime.app.domain.suggest
import com.tomatime.app.domain.usageOrder
import com.tomatime.app.domain.weekTitle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime

/** What the grid is doing right now. */
sealed interface Mode {
    data object Browse : Mode

    data class Select(
        val purpose: Purpose,
        val categoryId: String,
        val title: String = "",
        val hintMin: Int? = null,
        val from: Unfinished? = null,
        val moving: String? = null,
        val scope: EditScope = EditScope.ONLY_THIS,
        val moveDuration: Int? = null,
        val detailed: Boolean = false,
    ) : Mode

    data class Suggest(val draft: Draft, val slots: List<LocalDateTime>, val index: Int) : Mode
}

data class Draft(
    val title: String,
    val categoryId: String,
    val estimateMin: Int,
    val deadline: LocalDate,
    val importance: Int,
    val from: Unfinished?,
)

enum class Action { EDIT, MOVE, DELETE }

sealed interface Dlg {
    data class ChooseHow(val categoryId: String) : Dlg
    data class PlanForm(
        val categoryId: String,
        val title: String = "",
        val estimateMin: Int = 60,
        val deadline: LocalDate? = null,
        val importance: Int = 3,
        val from: Unfinished? = null,
    ) : Dlg
    data class NoSlot(val form: PlanForm) : Dlg
    data class Overlap(val sel: Mode.Select, val d: LocalDate, val a: Int, val b: Int, val occs: List<Occ>) : Dlg
    data class ConfirmPlan(val sel: Mode.Select, val d: LocalDate, val a: Int, val b: Int, val replace: List<Occ>) : Dlg
    data class Detail(val occId: String) : Dlg
    data class Remaining(val occId: String) : Dlg
    data class AskScope(val occId: String, val action: Action) : Dlg
    data class Edit(val occId: String, val scope: EditScope) : Dlg
    data class AllCats(val purpose: Purpose) : Dlg
    data class UnfAction(val u: Unfinished) : Dlg
    data class AskPref(val categoryId: String, val bucket: Int) : Dlg
    data class AskNewCat(val title: String) : Dlg
    data class ConfirmLog(val sel: Mode.Select, val d: LocalDate, val a: Int, val b: Int, val replace: List<Occ>) : Dlg
    data object GuardStop : Dlg
}

@Composable
fun App(store: Store, launch: String? = null, onLaunchHandled: () -> Unit = {}) {
    val data by store.data.collectAsState()
    val now by produceState(LocalDateTime.now()) {
        while (true) {
            delay(20_000)
            value = LocalDateTime.now()
        }
    }
    if (!data.settings.onboarded) {
        OnboardingScreen(data.settings) { s ->
            store.update { regenerateSleep(it.copy(settings = s.copy(onboarded = true, lastBackupAt = System.currentTimeMillis())), LocalDate.now()) }
        }
        return
    }

    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val today = now.toLocalDate()
    var screen by rememberSaveable { mutableStateOf("main") }
    var dayDate by remember { mutableStateOf(today) }
    var weekStart by remember { mutableStateOf(mondayOf(today)) }
    var mode by remember { mutableStateOf<Mode>(Mode.Browse) }
    var dlg by remember { mutableStateOf<Dlg?>(null) }
    var live by remember { mutableStateOf<Triple<LocalDate, Int, Int>?>(null) }
    var backupDismissed by remember { mutableStateOf(false) }
    val catMap = remember(data) { cats(data) }
    val titles = remember(data) { data.events.associate { it.id to displayTitle(it, data) } }
    val ctx = LocalContext.current
    var sticker by remember { mutableStateOf<Int?>(null) }
    var pomoOcc by remember { mutableStateOf<String?>(null) }
    var pickFor by remember { mutableStateOf<((String) -> Unit)?>(null) }

    // Ask for notification permission once, the first time the main screen shows.
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        val prefs = ctx.getSharedPreferences("meta", android.content.Context.MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 && !Notifs.canPost(ctx) && !prefs.getBoolean("askedNotify", false)) {
            prefs.edit().putBoolean("askedNotify", true).apply()
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Opened from a notification or a widget.
    LaunchedEffect(launch) {
        val l = launch ?: return@LaunchedEffect
        when {
            l == "plan" -> dlg = Dlg.AllCats(Purpose.PLAN)
            l == "log" -> dlg = Dlg.AllCats(Purpose.LOG)
            l == "pomo" -> screen = "pomo"
            l.startsWith("pomo:") -> { pomoOcc = l.removePrefix("pomo:"); screen = "pomo" }
            l.startsWith("remaining:") -> { screen = "main"; dlg = Dlg.Remaining(l.removePrefix("remaining:")) }
            l.startsWith("detail:") -> { screen = "main"; dlg = Dlg.Detail(l.removePrefix("detail:")) }
            l == "usage" -> screen = "usage"
            l == "overview" -> screen = "overview"
            l == "stats" -> screen = "stats"
            l == "backup" -> screen = "settings"
            l == "guardStop" -> dlg = Dlg.GuardStop
            l == "guardStart" -> GuardianService.start(ctx)
            else -> screen = "main"
        }
        onLaunchHandled()
    }

    fun toast(msg: String) {
        scope.launch {
            snack.currentSnackbarData?.dismiss()
            snack.showSnackbar(msg, duration = SnackbarDuration.Short)
        }
    }

    /** Applies a change and offers 復原 for a few seconds. */
    fun commit(msg: String?, f: (AppData) -> AppData) {
        val before = store.data.value
        store.update(f)
        if (msg != null) scope.launch {
            snack.currentSnackbarData?.dismiss()
            val r = snack.showSnackbar(msg, actionLabel = "復原", duration = SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) store.replace(before)
        }
    }

    fun catLabel(id: String): String = catMap[id]?.let { "${it.emoji} ${it.name}" } ?: ""

    /** Learns where things go and asks about a time preference or a new category when it is time. */
    fun afterPlace(categoryId: String, startMin: Int) {
        store.update { recordPlacement(it, categoryId, startMin) }
        val d = store.data.value
        val bucket = prefToAsk(d, categoryId)
        if (bucket != null) {
            dlg = Dlg.AskPref(categoryId, bucket)
            return
        }
        if (categoryId == "other") newCategoryIdea(d)?.let { dlg = Dlg.AskNewCat(it) }
    }

    fun saveLog(sel: Mode.Select, d: LocalDate, a: Int, b: Int, replace: List<Occ>, title: String = "") {
        commit("已補登 ${catLabel(sel.categoryId)} ${fmtMin(a)}–${fmtMin(b)}") {
            var x = it
            replace.forEach { o -> x = carveOut(x, o, at(d, a), at(d, b), markMissed = o.event.origin == Origin.PLANNED) }
            x.copy(events = x.events + Event(
                id = newId(), title = title.trim(), categoryId = sel.categoryId, date = d.toString(), startMin = a, durationMin = b - a,
                origin = Origin.LOGGED, createdAt = System.currentTimeMillis(),
            ))
        }
        mode = Mode.Browse
        live = null
        sticker = R.drawable.mascot_logged
        afterPlace(sel.categoryId, a)
    }

    fun savePlan(c: Dlg.ConfirmPlan, title: String, repeat: Repeat) {
        val u = c.sel.from
        commit("已安排 ${catLabel(c.sel.categoryId)} ${rangeLabel(c.d, c.a, c.b)}") {
            var x = it
            c.replace.forEach { o -> x = carveOut(x, o, at(c.d, c.a), at(c.d, c.b), markMissed = false) }
            x = x.copy(events = x.events + Event(
                id = newId(), title = title.trim(), categoryId = c.sel.categoryId, date = c.d.toString(),
                startMin = c.a, durationMin = c.b - c.a, repeat = repeat,
                deadline = u?.deadline, importance = u?.importance ?: 3,
                totalMin = u?.totalMin ?: 0, doneBeforeMin = u?.doneMin ?: 0,
                createdAt = System.currentTimeMillis(),
            ))
            if (u != null) x = x.copy(unfinished = x.unfinished.filter { f -> f.id != u.id })
            x
        }
        mode = Mode.Browse
        live = null
        afterPlace(c.sel.categoryId, c.a)
    }

    fun doMove(sel: Mode.Select, occ: Occ, d: LocalDate, a: Int, replace: List<Occ>) {
        val keepRepeat = if (sel.scope == EditScope.ONLY_THIS && occ.event.repeat != Repeat.NONE) Repeat.NONE else occ.event.repeat
        commit("已移動到 ${rangeLabel(d, a, a + occ.durationMin)}") {
            var x = it
            replace.forEach { o -> x = carveOut(x, o, at(d, a), at(d, a + occ.durationMin), markMissed = false) }
            val fresh = findOcc(x, occ.id) ?: occ
            applyEdit(x, fresh, sel.scope, EditValues(occ.event.title, occ.event.categoryId, d, a, occ.durationMin, keepRepeat))
        }
        mode = Mode.Browse
        live = null
        afterPlace(occ.event.categoryId, a)
    }

    fun onSelected(d: LocalDate, a: Int, b: Int) {
        live = null
        val m = mode as? Mode.Select ?: return
        when (m.purpose) {
            Purpose.PLAN -> {
                val ov = overlapping(data, at(d, a), at(d, b))
                dlg = if (ov.isEmpty()) Dlg.ConfirmPlan(m, d, a, b, emptyList()) else Dlg.Overlap(m, d, a, b, ov)
            }
            Purpose.LOG -> {
                val days = data.settings.lookbackDays
                val earliest = today.minusDays((days - 1).toLong())
                if (d.isBefore(earliest) || !at(d, a).isBefore(now)) {
                    toast("只能補登最近 $days 天、已經過去的時間")
                    return
                }
                val cap = minOfDay(roundUp30(now), d)
                val end = minOf(b, cap)
                if (end <= a) {
                    toast("只能補登已經過去的時間")
                    return
                }
                val ov = overlapping(data, at(d, a), at(d, end))
                dlg = when {
                    ov.isNotEmpty() -> Dlg.Overlap(m, d, a, end, ov)
                    m.detailed -> Dlg.ConfirmLog(m, d, a, end, emptyList())
                    else -> {
                        saveLog(m, d, a, end, emptyList())
                        dlg
                    }
                }
            }
            Purpose.MOVE -> {
                val occ = m.moving?.let { findOcc(data, it) } ?: return
                val ov = overlapping(data, at(d, a), at(d, b), excludeEventId = occ.event.id)
                if (ov.isEmpty()) doMove(m, occ, d, a, emptyList()) else dlg = Dlg.Overlap(m, d, a, b, ov)
            }
        }
    }

    fun startScheduling(form: Dlg.PlanForm, deadline: LocalDate) {
        val slots = suggest(data, form.categoryId, form.estimateMin, deadline, now).take(20)
        if (slots.isEmpty()) {
            dlg = Dlg.NoSlot(form.copy(deadline = deadline))
        } else {
            mode = Mode.Suggest(
                Draft(form.title, form.categoryId, form.estimateMin, deadline, form.importance, form.from), slots, 0,
            )
            weekStart = mondayOf(slots[0].toLocalDate())
            screen = "main"
            dlg = null
        }
    }

    BackHandler(enabled = mode != Mode.Browse || screen != "main") {
        if (mode != Mode.Browse) {
            mode = Mode.Browse
            live = null
        } else {
            screen = "main"
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snack) }, containerColor = Cream) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (screen) {
                "unfinished" -> UnfinishedScreen(data, catMap, today, onBack = { screen = "main" }) { dlg = Dlg.UnfAction(it) }
                "settings" -> SettingsScreen(store, onBack = { screen = "main" }, toast = ::toast)
                "pomo" -> PomoScreen(
                    store, data, catMap, pomoOcc,
                    onBack = { screen = "main"; pomoOcc = null },
                    onFeedback = { id -> screen = "main"; pomoOcc = null; dlg = Dlg.Detail(id) },
                    toast = ::toast,
                )
                "stats" -> StatsScreen(store, data, catMap, today, onBack = { screen = "main" })
                "overview" -> OverviewScreen(data, today, onBack = { screen = "main" }) { d -> dayDate = d; screen = "day" }
                "usage" -> UsageScreen(
                    store, data, catMap, today, onBack = { screen = "main" },
                    pickCategory = { cb -> pickFor = cb; dlg = Dlg.AllCats(Purpose.MOVE) },
                    toast = ::toast,
                )
                else -> Column(Modifier.fillMaxSize()) {
                    val isDay = screen == "day"
                    val days = if (isDay) listOf(dayDate) else (0 until 7).map { weekStart.plusDays(it.toLong()) }
                    TopBar(
                        isDay = isDay, dayDate = dayDate, weekStart = weekStart, today = today,
                        unfinishedCount = data.unfinished.size, showDot = data.settings.showMenuDot,
                        onBackFromDay = { screen = "main" },
                        onShift = { dir -> if (isDay) dayDate = dayDate.plusDays(dir.toLong()) else weekStart = weekStart.plusWeeks(dir.toLong()) },
                        onThisWeek = { weekStart = mondayOf(today) },
                        onMenu = { screen = it },
                        pomoActive = data.pomoState != null,
                        showUsage = data.settings.usageEnabled,
                        onPomo = { screen = "pomo" },
                        onTitle = { screen = "overview" },
                    )
                    ModeBanner(mode, live, catMap, data.settings.lookbackDays,
                        onCancel = { mode = Mode.Browse; live = null },
                        onToggleDetailed = { (mode as? Mode.Select)?.let { m -> mode = m.copy(detailed = !m.detailed) } },
                        onNext = {
                            val m = mode as? Mode.Suggest ?: return@ModeBanner
                            val i = (m.index + 1) % m.slots.size
                            mode = m.copy(index = i)
                            if (isDay) dayDate = m.slots[i].toLocalDate() else weekStart = mondayOf(m.slots[i].toLocalDate())
                        },
                        onConfirm = {
                            val m = mode as? Mode.Suggest ?: return@ModeBanner
                            val slot = m.slots[m.index]
                            val dr = m.draft
                            val d = slot.toLocalDate()
                            val a = minOfDay(slot, d)
                            commit("已排入 ${rangeLabel(d, a, a + dr.estimateMin)}") {
                                var x = it.copy(events = it.events + Event(
                                    id = newId(), title = dr.title.trim(), categoryId = dr.categoryId, date = d.toString(),
                                    startMin = a, durationMin = dr.estimateMin, deadline = dr.deadline.toString(),
                                    importance = dr.importance, totalMin = dr.from?.totalMin ?: 0,
                                    doneBeforeMin = dr.from?.doneMin ?: 0, createdAt = System.currentTimeMillis(),
                                ))
                                if (dr.from != null) x = x.copy(unfinished = x.unfinished.filter { f -> f.id != dr.from.id })
                                x
                            }
                            mode = Mode.Browse
                        },
                    )
                    val daysSince = (System.currentTimeMillis() - data.settings.lastBackupAt) / 86_400_000L
                    if (!backupDismissed && data.events.any { it.categoryId != "sleep" } && daysSince >= data.settings.backupIntervalDays) {
                        Banner(Color(0xFFFFF0C2)) {
                            Text("已經 $daysSince 天沒備份了", fontSize = 14.sp, modifier = Modifier.weight(1f))
                            TextButton("去備份") { screen = "settings" }
                            TextButton("✕") { backupDismissed = true }
                        }
                    }
                    val occs = remember(data, days) { occurrences(data, days.first(), days.last()) }
                    val sel = mode as? Mode.Select
                    val sug = mode as? Mode.Suggest
                    CalendarGrid(
                        days = days,
                        range = gridRange(data.settings),
                        occs = occs,
                        cats = catMap,
                        titles = titles,
                        selecting = sel?.purpose,
                        moveDuration = sel?.moveDuration,
                        preview = sug?.let { s ->
                            val slot = s.slots[s.index]
                            Preview(slot.toLocalDate(), minOfDay(slot, slot.toLocalDate()), s.draft.estimateMin)
                        },
                        now = now,
                        wide = isDay,
                        onHeader = { d -> dayDate = d; screen = "day" },
                        onOccTap = { o -> dlg = Dlg.Detail(o.id) },
                        onSelecting = { d, a, b -> live = Triple(d, a, b) },
                        onSelected = ::onSelected,
                        onSwipe = { dir -> if (isDay) dayDate = dayDate.plusDays(dir.toLong()) else weekStart = weekStart.plusWeeks(dir.toLong()) },
                        modifier = Modifier.weight(1f).padding(end = 4.dp),
                    )
                    RegisterBar(
                        data, catMap, titles, now,
                        onPlan = { c -> dlg = Dlg.ChooseHow(c.id) },
                        onLog = { c -> mode = Mode.Select(Purpose.LOG, c.id, detailed = data.settings.logDetailed); live = null },
                        onMore = { p -> dlg = Dlg.AllCats(p) },
                        onNowTap = { id -> dlg = Dlg.Detail(id) },
                    )
                }
            }
        }
    }

    sticker?.let { res -> StickerPop(res) { sticker = null } }

    // ---------- dialogs ----------
    when (val d = dlg) {
        null -> Unit
        is Dlg.ChooseHow -> ChooseHowDialog(
            catLabel(d.categoryId),
            onSelf = { mode = Mode.Select(Purpose.PLAN, d.categoryId); screen = if (screen == "day") "day" else "main"; dlg = null },
            onAuto = { dlg = Dlg.PlanForm(d.categoryId) },
            onDismiss = { dlg = null },
        )
        is Dlg.PlanForm -> PlanFormSheet(d, catLabel(d.categoryId), today, onDismiss = { dlg = null }) { form, deadline ->
            startScheduling(form, deadline)
        }
        is Dlg.NoSlot -> SimpleDialog(
            title = "期限前排不下",
            text = "在期限前找不到 ${fmtDur(d.form.estimateMin)} 的空檔（已經考慮每天任務上限 ${fmtDur(data.settings.dailyTaskCapMin)} 和已排的事情）。",
            confirm = "自己排" to {
                mode = Mode.Select(Purpose.PLAN, d.form.categoryId, d.form.title, d.form.estimateMin, d.form.from)
                screen = "main"
                dlg = null
            },
            dismiss = "改期限" to { dlg = d.form },
        )
        is Dlg.Overlap -> {
            val names = d.occs.joinToString("、") { "${catMap[it.event.categoryId]?.emoji.orEmpty()}${titles[it.event.id].orEmpty()} ${fmtMin(it.startMin)}–${fmtMin(it.endMin)}" }
            if (d.sel.purpose == Purpose.LOG) {
                SimpleDialog(
                    title = "這段時間原本有安排",
                    text = "$names\n\n原本的安排沒做嗎？用這次補登取代後，原本的會記為沒做；任務重疊的部分會放進未完成清單。",
                    confirm = "對，沒做" to {
                        dlg = null
                        if (d.sel.detailed) dlg = Dlg.ConfirmLog(d.sel, d.d, d.a, d.b, d.occs) else saveLog(d.sel, d.d, d.a, d.b, d.occs)
                    },
                    dismiss = "取消" to { dlg = null },
                )
            } else {
                SimpleDialog(
                    title = "這段時間已有安排",
                    text = "$names\n\n要取代它們，還是換個時間？",
                    confirm = "取代" to {
                        if (d.sel.purpose == Purpose.MOVE) {
                            val occ = d.sel.moving?.let { findOcc(data, it) }
                            dlg = null
                            if (occ != null) doMove(d.sel, occ, d.d, d.a, d.occs)
                        } else dlg = Dlg.ConfirmPlan(d.sel, d.d, d.a, d.b, d.occs)
                    },
                    dismiss = "換時間" to { dlg = null },
                )
            }
        }
        is Dlg.ConfirmPlan -> ConfirmPlanDialog(d, catLabel(d.sel.categoryId), onDismiss = { dlg = null }) { title, repeat ->
            dlg = null
            savePlan(d, title, repeat)
        }
        is Dlg.ConfirmLog -> ConfirmLogDialog(d.d, d.a, d.b, catLabel(d.sel.categoryId), onDismiss = { dlg = null }) { title, a, b ->
            dlg = null
            saveLog(d.sel, d.d, a, b, d.replace, title)
        }
        is Dlg.AskPref -> AskPrefDialog(catLabel(d.categoryId), bucketLabel(d.bucket),
            onYes = { strength ->
                store.update { setPref(it, d.categoryId, d.bucket, strength) }
                dlg = null
                toast("之後幫我排會優先放在${bucketLabel(d.bucket)}")
            },
            onNo = {
                store.update { declinePref(it, d.categoryId) }
                dlg = null
            },
        )
        is Dlg.AskNewCat -> SimpleDialog(
            title = "新增分類？",
            text = "「${d.title}」在「其他」裡出現好幾次了，要把它新增成自己的分類嗎？之前的紀錄也會一起移過去。",
            confirm = "新增" to {
                store.update { addCategoryFromTitle(it, d.title) }
                dlg = null
                toast("已新增分類「${d.title}」")
            },
            dismiss = "不用" to {
                store.update { it.copy(declinedNewCats = it.declinedNewCats + d.title) }
                dlg = null
            },
        )
        Dlg.GuardStop -> SimpleDialog(
            title = "今晚停用睡眠守門員？",
            text = "今晚不會再提醒你，明晚會自動恢復。",
            confirm = "今晚停用" to {
                val night = com.tomatime.app.system.Ticks.sleepNow(data, LocalDateTime.now())
                com.tomatime.app.system.Ticks.setGuardOffNight(ctx, night?.id ?: "test")
                GuardianService.stop(ctx)
                dlg = null
                toast("今晚已停用")
            },
            dismiss = "取消" to { dlg = null },
        )
        is Dlg.Detail -> {
            val occ = findOcc(data, d.occId)
            if (occ == null) dlg = null
            else DetailSheet(
                occ, data, catMap[occ.event.categoryId], titles[occ.event.id].orEmpty(), now,
                onDismiss = { dlg = null },
                onStatus = { st ->
                    commit(null) { com.tomatime.app.domain.setStatus(it, occ, st) }
                    dlg = null
                    sticker = when (st) {
                        com.tomatime.app.data.Status.DONE -> R.drawable.mascot_celebrate
                        com.tomatime.app.data.Status.MISSED -> R.drawable.mascot_encourage
                        else -> null
                    }
                },
                onPomo = { screen = "pomo"; pomoOcc = occ.id; dlg = null },
                onPartial = { dlg = Dlg.Remaining(occ.id) },
                onAction = { act ->
                    if (occ.event.repeat != Repeat.NONE) dlg = Dlg.AskScope(occ.id, act)
                    else when (act) {
                        Action.EDIT -> dlg = Dlg.Edit(occ.id, EditScope.ONLY_THIS)
                        Action.MOVE -> {
                            mode = Mode.Select(Purpose.MOVE, occ.event.categoryId, moving = occ.id, moveDuration = occ.durationMin)
                            dlg = null
                        }
                        Action.DELETE -> {
                            commit("已刪除") { applyDelete(it, occ, EditScope.ONLY_THIS) }
                            dlg = null
                        }
                    }
                },
            )
        }
        is Dlg.Remaining -> {
            val occ = findOcc(data, d.occId)
            if (occ == null) dlg = null
            else RemainingDialog(occ.durationMin, onDismiss = { dlg = null }) { rem ->
                commit(null) { com.tomatime.app.domain.setStatus(it, occ, com.tomatime.app.data.Status.PARTIAL, rem) }
                dlg = null
                sticker = R.drawable.mascot_encourage
                toast("剩下的 ${fmtDur(rem)} 已放進未完成清單")
            }
        }
        is Dlg.AskScope -> SimpleDialog(
            title = "這是重複的事情",
            text = "要${when (d.action) { Action.EDIT -> "修改"; Action.MOVE -> "移動"; Action.DELETE -> "刪除" }}哪些？",
            confirm = "這次和之後的" to { act(d, EditScope.THIS_AND_FUTURE, data, { dlg = it }, { mode = it }, ::commit) },
            dismiss = "只有這一次" to { act(d, EditScope.ONLY_THIS, data, { dlg = it }, { mode = it }, ::commit) },
        )
        is Dlg.Edit -> {
            val occ = findOcc(data, d.occId)
            if (occ == null) dlg = null
            else EditDialog(occ, d.scope, data, onDismiss = { dlg = null }) { values ->
                commit("已修改") { applyEdit(it, occ, d.scope, values) }
                dlg = null
            }
        }
        is Dlg.AllCats -> AllCatsDialog(
            data.categories.filter { !it.system },
            extra = if (d.purpose == Purpose.LOG && data.settings.usageEnabled) "📱 從手機使用紀錄補登" to { dlg = null; screen = "usage" } else null,
            onDismiss = { dlg = null; pickFor = null },
        ) { c ->
            when (d.purpose) {
                Purpose.PLAN -> dlg = Dlg.ChooseHow(c.id)
                Purpose.LOG -> {
                    mode = Mode.Select(Purpose.LOG, c.id, detailed = data.settings.logDetailed)
                    if (screen != "day") screen = "main"
                    dlg = null
                }
                Purpose.MOVE -> {
                    pickFor?.invoke(c.id)
                    pickFor = null
                    dlg = null
                }
            }
        }
        is Dlg.UnfAction -> UnfinishedActionDialog(
            d.u,
            onDismiss = { dlg = null },
            onSelf = {
                mode = Mode.Select(Purpose.PLAN, d.u.categoryId, d.u.title, d.u.totalMin - d.u.doneMin, d.u)
                screen = "main"
                dlg = null
            },
            onAuto = {
                dlg = Dlg.PlanForm(
                    d.u.categoryId, d.u.title, (d.u.totalMin - d.u.doneMin).coerceAtLeast(30),
                    d.u.deadline?.let { LocalDate.parse(it) }, d.u.importance, d.u,
                )
            },
            onDone = {
                commit("已標記完成") { it.copy(unfinished = it.unfinished.filter { f -> f.id != d.u.id }) }
                dlg = null
            },
            onDrop = {
                commit("已移除") { it.copy(unfinished = it.unfinished.filter { f -> f.id != d.u.id }) }
                dlg = null
            },
        )
    }
}

private fun act(
    d: Dlg.AskScope,
    scope: EditScope,
    data: AppData,
    setDlg: (Dlg?) -> Unit,
    setMode: (Mode) -> Unit,
    commit: (String?, (AppData) -> AppData) -> Unit,
) {
    val occ = findOcc(data, d.occId) ?: run { setDlg(null); return }
    when (d.action) {
        Action.EDIT -> setDlg(Dlg.Edit(d.occId, scope))
        Action.MOVE -> {
            setMode(Mode.Select(Purpose.MOVE, occ.event.categoryId, moving = occ.id, scope = scope, moveDuration = occ.durationMin))
            setDlg(null)
        }
        Action.DELETE -> {
            commit("已刪除") { applyDelete(it, occ, scope) }
            setDlg(null)
        }
    }
}

@Composable
fun TextButton(text: String, color: Color = Ink, onClick: () -> Unit) {
    Text(
        text,
        color = color,
        fontSize = 15.sp,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

@Composable
fun Banner(color: Color, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(color).padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun TopBar(
    isDay: Boolean,
    dayDate: LocalDate,
    weekStart: LocalDate,
    today: LocalDate,
    unfinishedCount: Int,
    showDot: Boolean,
    onBackFromDay: () -> Unit,
    onShift: (Int) -> Unit,
    onThisWeek: () -> Unit,
    onMenu: (String) -> Unit,
    pomoActive: Boolean,
    showUsage: Boolean,
    onPomo: () -> Unit,
    onTitle: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        if (isDay) {
            TextButton("‹ 週") { onBackFromDay() }
            TextButton("‹") { onShift(-1) }
            Text(dateLabel(dayDate), fontSize = 18.sp, fontWeight = FontWeight.Bold)
            TextButton("›") { onShift(1) }
        } else {
            TextButton("‹") { onShift(-1) }
            Text(
                weekTitle(weekStart), fontSize = 18.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onTitle() }.padding(horizontal = 2.dp),
            )
            TextButton("›") { onShift(1) }
            if (weekStart != mondayOf(today)) TextButton("回到本週", Tomato) { onThisWeek() }
        }
        Spacer(Modifier.weight(1f))
        Box {
            TextButton("🍅") { onPomo() }
            if (pomoActive) Box(Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 4.dp).size(8.dp).clip(CircleShape).background(Leaf))
        }
        Box {
            TextButton("☰") { menu = true }
            if (showDot && unfinishedCount > 0) {
                Box(Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 4.dp).size(8.dp).clip(CircleShape).background(Tomato))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("未完成清單（$unfinishedCount）") }, onClick = { menu = false; onMenu("unfinished") })
                DropdownMenuItem(text = { Text("統計") }, onClick = { menu = false; onMenu("stats") })
                DropdownMenuItem(text = { Text("概覽（兩週／一個月）") }, onClick = { menu = false; onMenu("overview") })
                if (showUsage) DropdownMenuItem(text = { Text("從手機紀錄補登") }, onClick = { menu = false; onMenu("usage") })
                DropdownMenuItem(text = { Text("設定") }, onClick = { menu = false; onMenu("settings") })
            }
        }
    }
}

@Composable
private fun ModeBanner(
    mode: Mode,
    live: Triple<LocalDate, Int, Int>?,
    cats: Map<String, Category>,
    lookback: Int,
    onCancel: () -> Unit,
    onToggleDetailed: () -> Unit,
    onNext: () -> Unit,
    onConfirm: () -> Unit,
) {
    when (mode) {
        Mode.Browse -> Unit
        is Mode.Select -> Banner(Color(0xFFFFE3DC)) {
            val c = cats[mode.categoryId]
            val verb = when (mode.purpose) {
                Purpose.PLAN -> "安排"
                Purpose.LOG -> "補登"
                Purpose.MOVE -> "移動"
            }
            Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                Text("正在$verb：${c?.emoji.orEmpty()} ${mode.title.ifBlank { c?.name.orEmpty() }}", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                val hint = when {
                    live != null -> rangeLabel(live.first, live.second, live.third)
                    mode.purpose == Purpose.MOVE -> "長按要放的位置，拖曳調整"
                    mode.purpose == Purpose.LOG -> "長按表格往下拖曳選時間（可補最近 $lookback 天）"
                    mode.hintMin != null -> "長按表格往下拖曳選時間，建議長度 ${fmtDur(mode.hintMin)}"
                    else -> "長按表格往下拖曳選時間"
                }
                Text(hint, fontSize = if (live != null) 16.sp else 13.sp, color = if (live != null) Tomato else Muted, fontWeight = if (live != null) FontWeight.Bold else FontWeight.Normal)
            }
            if (mode.purpose == Purpose.LOG) Pill(if (mode.detailed) "詳細" else "快捷", mode.detailed) { onToggleDetailed() }
            TextButton("取消") { onCancel() }
        }
        is Mode.Suggest -> Banner(Color(0xFFFFE3DC)) {
            val slot = mode.slots[mode.index]
            val d = slot.toLocalDate()
            val a = minOfDay(slot, d)
            Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                Text("建議時段 ${mode.index + 1}/${mode.slots.size}（${fmtDur(mode.draft.estimateMin)}）", fontSize = 12.sp, color = Muted)
                Text("${dateLabel(d)} ${fmtMin(a)}–${fmtMin(a + mode.draft.estimateMin)}", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Tomato, maxLines = 1)
            }
            if (mode.slots.size > 1) TextButton("換一個") { onNext() }
            Pill("確認", selected = true) { onConfirm() }
            TextButton("✕") { onCancel() }
        }
    }
}

@Composable
private fun RegisterBar(
    data: AppData,
    cats: Map<String, Category>,
    titles: Map<String, String>,
    now: LocalDateTime,
    onPlan: (Category) -> Unit,
    onLog: (Category) -> Unit,
    onMore: (Purpose) -> Unit,
    onNowTap: (String) -> Unit,
) {
    val today = now.toLocalDate()
    val todays = remember(data, today) { occurrences(data, today, today.plusDays(1)).filter { !it.hidden && !it.isSleep } }
    val current = todays.firstOrNull { !it.start.isAfter(now) && it.end.isAfter(now) }
    val next = todays.firstOrNull { it.start.isAfter(now) }
    Row(
        Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CatRow("安排", usageOrder(data, Origin.PLANNED).take(4), round = true, onPick = onPlan) { onMore(Purpose.PLAN) }
            CatRow("補登", usageOrder(data, Origin.LOGGED).take(4), round = false, onPick = onLog) { onMore(Purpose.LOG) }
        }
        Column(
            Modifier
                .width(104.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(PillBg)
                .clickable(enabled = current != null) { current?.let { onNowTap(it.id) } }
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Text("現在", fontSize = 11.sp, color = Muted)
            Text(
                current?.let { "${cats[it.event.categoryId]?.emoji.orEmpty()}${titles[it.event.id].orEmpty().take(3)}" } ?: "—",
                fontSize = 13.sp, maxLines = 1,
            )
            Spacer(Modifier.size(4.dp))
            Text("下個", fontSize = 11.sp, color = Muted)
            Text(
                next?.let { "${fmtMin(it.startMin)} ${cats[it.event.categoryId]?.emoji.orEmpty()}" } ?: "—",
                fontSize = 13.sp, maxLines = 1,
            )
        }
    }
}

@Composable
private fun CatRow(label: String, cats: List<Category>, round: Boolean, onPick: (Category) -> Unit, onMore: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Muted, modifier = Modifier.width(32.dp))
        cats.forEach { c ->
            val col = Color(c.color)
            val shape = if (round) CircleShape else RoundedCornerShape(10.dp)
            Box(
                Modifier
                    .size(40.dp)
                    .clip(shape)
                    .background(col.copy(alpha = 0.18f))
                    .border(1.5.dp, col, shape)
                    .clickable { onPick(c) },
                contentAlignment = Alignment.Center,
            ) { Text(c.emoji, fontSize = 18.sp) }
        }
        Box(
            Modifier.size(40.dp).clip(if (round) CircleShape else RoundedCornerShape(10.dp)).background(PillBg).clickable { onMore() },
            contentAlignment = Alignment.Center,
        ) { Text("⋯", fontSize = 18.sp, color = Muted) }
    }
}
