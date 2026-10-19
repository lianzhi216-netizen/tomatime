package com.tomatime.app.data

import android.content.Context
import com.tomatime.app.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Plain-text error log the user can export and paste back for debugging. */
object ErrorLog {
    private const val MAX_BYTES = 200_000L
    private var file: File? = null

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "errors.txt")
    }

    @Synchronized
    fun log(message: String, t: Throwable? = null) {
        val f = file ?: return
        try {
            if (f.length() > MAX_BYTES) f.writeText(f.readText().takeLast((MAX_BYTES / 2).toInt()))
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            f.appendText("[$time] v${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE}) $message\n")
            if (t != null) f.appendText(t.stackTraceToString() + "\n")
        } catch (_: Throwable) {
        }
    }

    fun read(): String = try {
        file?.takeIf { it.exists() }?.readText().orEmpty().ifBlank { "（沒有錯誤紀錄）" }
    } catch (t: Throwable) {
        "讀取失敗：$t"
    }
}

/**
 * All app data lives in one JSON file. Every write goes to a temp file first and is then renamed,
 * so a crash mid-write never leaves a half-written file; the previous version is kept as .bak.
 */
class Store(ctx: Context) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }
    private val dir = ctx.filesDir
    private val file = File(dir, "data.json")
    private val tmp = File(dir, "data.json.tmp")
    private val bak = File(dir, "data.json.bak")
    private val backupsDir = File(dir, "backups").apply { mkdirs() }
    private val prefs = ctx.getSharedPreferences("meta", Context.MODE_PRIVATE)

    private val _data = MutableStateFlow(load())
    val data: StateFlow<AppData> = _data

    /** Called after every change (alarms, notifications and widgets follow the data). */
    var onChanged: ((AppData) -> Unit)? = null

    private fun notifyChanged(d: AppData) {
        try {
            onChanged?.invoke(d)
        } catch (t: Throwable) {
            ErrorLog.log("更新提醒或小工具失敗", t)
        }
    }

    init {
        backupBeforeUpdate()
    }

    private fun load(): AppData {
        for (f in listOf(file, bak)) {
            if (!f.exists()) continue
            try {
                return normalize(json.decodeFromString(AppData.serializer(), f.readText()))
            } catch (t: Throwable) {
                ErrorLog.log("讀取 ${f.name} 失敗", t)
            }
        }
        return normalize(AppData())
    }

    /** Adds any default categories missing from older data. */
    private fun normalize(d: AppData): AppData {
        val have = d.categories.map { it.id }.toSet()
        val missing = Defaults.categories.filter { it.id !in have }
        return if (missing.isEmpty()) d else d.copy(categories = d.categories + missing)
    }

    private fun backupBeforeUpdate() {
        val last = prefs.getInt("lastVersionCode", -1)
        if (last != BuildConfig.VERSION_CODE) {
            if (file.exists() && last != -1) snapshot("before-v${BuildConfig.VERSION_CODE}")
            prefs.edit().putInt("lastVersionCode", BuildConfig.VERSION_CODE).apply()
        }
    }

    /** Copies the current data into backups/, keeping the newest 10. */
    fun snapshot(label: String) {
        try {
            if (!file.exists()) return
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            file.copyTo(File(backupsDir, "data-$stamp-$label.json"), overwrite = true)
            backupsDir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(10)?.forEach { it.delete() }
        } catch (t: Throwable) {
            ErrorLog.log("自動備份失敗", t)
        }
    }

    @Synchronized
    fun update(transform: (AppData) -> AppData) {
        val next = try {
            transform(_data.value)
        } catch (t: Throwable) {
            ErrorLog.log("資料更新失敗", t)
            return
        }
        save(next)
        _data.value = next
        notifyChanged(next)
    }

    @Synchronized
    fun replace(d: AppData) {
        save(d)
        _data.value = d
        notifyChanged(d)
    }

    /** Restore from an imported backup; the current data is snapshotted first so it can be undone. */
    @Synchronized
    fun restore(d: AppData) {
        snapshot("before-restore")
        replace(normalize(d))
    }

    private fun save(d: AppData) {
        try {
            tmp.writeText(json.encodeToString(AppData.serializer(), d))
            if (file.exists()) file.copyTo(bak, overwrite = true)
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        } catch (t: Throwable) {
            ErrorLog.log("儲存失敗", t)
        }
    }

    fun exportJson(): String = json.encodeToString(AppData.serializer(), _data.value)

    /** Returns null when the text is not a Tomatime backup. */
    fun decode(text: String): AppData? = try {
        val d = json.decodeFromString(AppData.serializer(), text)
        if (d.categories.isEmpty() && d.events.isEmpty()) null else d
    } catch (t: Throwable) {
        ErrorLog.log("備份檔格式不符", t)
        null
    }
}
