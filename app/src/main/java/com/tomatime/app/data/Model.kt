package com.tomatime.app.data

import kotlinx.serialization.Serializable

@Serializable
enum class Repeat { NONE, WEEKLY, BIWEEKLY, MONTHLY }

@Serializable
enum class Origin { PLANNED, LOGGED }

@Serializable
enum class Status { DONE, PARTIAL, MISSED }

@Serializable
data class Category(
    val id: String,
    val name: String,
    val emoji: String,
    val color: Long,
    /** Tasks (homework, study...) go to the unfinished list when not done; fixed things (class, sleep) do not. */
    val taskLike: Boolean,
    val minBlockMin: Int = 30,
    /** Hidden from the register bar (sleep). */
    val system: Boolean = false,
)

@Serializable
data class OccStatus(
    val status: Status,
    val remainingMin: Int = 0,
    /** Covered by a later 補登 record: kept for history, hidden on the grid. */
    val replaced: Boolean = false,
)

/**
 * One calendar entry. Times are stored as a start date plus minutes from that day's midnight,
 * so sleep can start at 24:30 (1470) and still belong to the night of [date].
 */
@Serializable
data class Event(
    val id: String,
    val title: String = "",
    val categoryId: String,
    val date: String,
    val startMin: Int,
    val durationMin: Int,
    val repeat: Repeat = Repeat.NONE,
    val until: String? = null,
    val origin: Origin = Origin.PLANNED,
    val deadline: String? = null,
    val importance: Int = 3,
    /** Whole task size when rescheduled from the unfinished list; 0 means "same as duration". */
    val totalMin: Int = 0,
    val doneBeforeMin: Int = 0,
    /** Dates skipped in a repeating series. */
    val deleted: List<String> = emptyList(),
    /** Completion per occurrence date. */
    val statuses: Map<String, OccStatus> = emptyMap(),
    val createdAt: Long = 0,
)

@Serializable
data class Unfinished(
    val id: String,
    val title: String,
    val categoryId: String,
    val totalMin: Int,
    val doneMin: Int,
    val deadline: String? = null,
    val importance: Int = 3,
    val createdAt: Long = 0,
    /** "eventId@date" of the occurrence that produced it, so changing that status removes it again. */
    val sourceKey: String? = null,
)

@Serializable
data class Settings(
    val onboarded: Boolean = false,
    val wakeMin: Int = 7 * 60,
    /** Minutes from midnight; values past 1440 mean after midnight (1500 = 01:00). */
    val sleepMin: Int = 24 * 60,
    val splitWeekend: Boolean = false,
    val weekendWakeMin: Int = 9 * 60,
    val weekendSleepMin: Int = 25 * 60,
    val dailyTaskCapMin: Int = 6 * 60,
    val lookbackDays: Int = 2,
    val bufferMin: Int = 15,
    val backupIntervalDays: Int = 14,
    val lastBackupAt: Long = 0,
    val showMenuDot: Boolean = true,

    // 番茄鐘
    val pomo: PomoCfg = PomoCfg(),
    val pomoByCategory: Map<String, PomoCfg> = emptyMap(),

    // 補登記
    val logDetailed: Boolean = false,
    val logReminder: Boolean = true,

    // 開始前提醒：0 = 關閉
    val remindBeforeMin: Int = 5,
    val remindFixed: Boolean = false,

    // 睡眠守門員：0 輕、1 中、2 強
    val guardEnabled: Boolean = false,
    val guardStrength: Int = 1,
    val emergencyApps: List<String> = emptyList(),

    // 手機使用紀錄
    val usageEnabled: Boolean = false,
    val usageMinMin: Int = 15,

    // 小工具
    val widgetOverviewDays: Int = 7,
    val widgetFullButtons: Boolean = false,
)

@Serializable
data class PomoCfg(
    val focusMin: Int = 25,
    val shortMin: Int = 5,
    val longMin: Int = 20,
    val longEvery: Int = 4,
)

@Serializable
enum class PomoPhase { FOCUS, SHORT_BREAK, LONG_BREAK, WAITING }

/** The running pomodoro, persisted so it survives the app being closed. */
@Serializable
data class PomoState(
    val occId: String? = null,
    val categoryId: String,
    val title: String,
    val phase: PomoPhase,
    val phaseStart: Long,
    val phaseEnd: Long,
    /** Finished focus rounds in this run. */
    val done: Int = 0,
    val interruptions: Int = 0,
)

@Serializable
data class PomoSession(
    val id: String,
    val categoryId: String,
    val occId: String? = null,
    val start: Long,
    val end: Long,
    /** Ran to the end of the focus time. */
    val completed: Boolean,
)

/** Learned "this category usually goes in the morning" with how strongly to keep it there (1–5). */
@Serializable
data class TimePref(val bucket: Int, val strength: Int)

@Serializable
data class SleepNight(
    val date: String,
    val phoneMin: Int = 0,
    val reminders: Int = 0,
    val emergencies: Int = 0,
    val emergencyMin: Int = 0,
)

@Serializable
data class AppData(
    val version: Int = 1,
    val settings: Settings = Settings(),
    val categories: List<Category> = emptyList(),
    val events: List<Event> = emptyList(),
    val unfinished: List<Unfinished> = emptyList(),
    val pomoState: PomoState? = null,
    val pomoSessions: List<PomoSession> = emptyList(),
    /** Last placements per category as time-of-day buckets (0 morning, 1 afternoon, 2 evening). */
    val placements: Map<String, List<Int>> = emptyMap(),
    val timePrefs: Map<String, TimePref> = emptyMap(),
    val declinedPrefs: List<String> = emptyList(),
    /** Phone app package -> category, asked once per app. */
    val appCategories: Map<String, String> = emptyMap(),
    val ignoredApps: List<String> = emptyList(),
    val sleepNights: List<SleepNight> = emptyList(),
    val declinedNewCats: List<String> = emptyList(),
)

object Defaults {
    const val SLEEP = "sleep"

    val categories = listOf(
        Category("class", "上課", "📖", 0xFF5B8DEF, taskLike = false),
        Category("homework", "作業", "📝", 0xFFE8A33B, taskLike = true),
        Category("lab", "實驗", "🔬", 0xFF8E6FD8, taskLike = true, minBlockMin = 120),
        Category("study", "讀書", "📚", 0xFF3FA7A0, taskLike = true),
        Category("sport", "運動", "🏐", 0xFF4CAF6A, taskLike = true),
        Category("tutor", "家教", "👨‍🏫", 0xFFD9774B, taskLike = false),
        Category("leisure", "休閒", "🎮", 0xFFE45C8B, taskLike = false, minBlockMin = 60),
        Category("rest", "休息", "😴", 0xFF9AA7B8, taskLike = false, minBlockMin = 0),
        Category("life", "生活", "🏠", 0xFFB08B62, taskLike = false),
        Category("other", "其他", "📦", 0xFF8A8A8A, taskLike = true),
        Category(SLEEP, "睡覺", "🌙", 0xFF4A5277, taskLike = false, system = true),
    )

    /** Order used by the register bar before there is any usage history. */
    val planOrder = listOf("homework", "study", "lab", "sport", "class", "tutor", "other", "leisure", "rest", "life")
    val logOrder = listOf("leisure", "rest", "study", "life", "homework", "sport", "lab", "class", "tutor", "other")
}
