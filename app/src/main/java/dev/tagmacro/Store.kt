package dev.tagmacro

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
    }
}

/**
 * Everything is stored as JSON in the app's private files directory.
 * Nothing is sent anywhere: the app does not hold the INTERNET permission.
 */
object Store {
    private const val UID_ALIAS = "dev.tagmacro.UidTrigger"
    private const val MAX_LOG = 300

    private lateinit var app: Context
    private val lock = Any()
    private var macros: MutableList<Macro>? = null
    private var settings: AppSettings? = null
    private var log: MutableList<LogEntry>? = null
    private var journal: MutableList<JournalEntry>? = null
    private const val MAX_JOURNAL = 5000

    fun init(ctx: Context) {
        if (!::app.isInitialized) app = ctx.applicationContext
    }

    private fun file(name: String) = AtomicFile(File(app.filesDir, name))

    private fun readJson(name: String): String? = try {
        String(file(name).readFully(), Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }

    private fun writeJson(name: String, text: String) {
        val f = file(name)
        val out = f.startWrite()
        try {
            out.write(text.toByteArray(Charsets.UTF_8))
            f.finishWrite(out)
        } catch (e: Exception) {
            f.failWrite(out)
            throw e
        }
    }

    // ---------- macros ----------

    private fun loadMacros(): MutableList<Macro> {
        macros?.let { return it }
        val list = mutableListOf<Macro>()
        readJson("macros.json")?.let { txt ->
            runCatching {
                val arr = JSONArray(txt)
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { list.add(Macro.fromJson(it)) }
            }
        }
        macros = list
        return list
    }

    /** Returns copies, so callers can't change stored data by accident. */
    fun macros(): List<Macro> = synchronized(lock) { loadMacros().map { it.deepCopy() } }

    fun macro(id: String): Macro? = synchronized(lock) { loadMacros().firstOrNull { it.id == id }?.deepCopy() }

    fun saveMacro(m: Macro) = synchronized(lock) {
        val list = loadMacros()
        val i = list.indexOfFirst { it.id == m.id }
        if (i >= 0) list[i] = m.deepCopy() else list.add(m.deepCopy())
        persistMacros()
    }

    fun deleteMacro(id: String) = synchronized(lock) {
        loadMacros().removeAll { it.id == id }
        persistMacros()
    }

    fun replaceAll(newMacros: List<Macro>, newSettings: AppSettings?, newJournal: List<JournalEntry>? = null) = synchronized(lock) {
        macros = newMacros.map { it.deepCopy() }.toMutableList()
        persistMacros()
        if (newSettings != null) saveSettings(newSettings)
        if (newJournal != null) {
            journal = newJournal.toMutableList()
            persistJournal()
        }
    }

    /** The journal from a backup file, or null if the backup has none (older versions). */
    fun parseBackupJournal(text: String): List<JournalEntry>? {
        val arr = JSONObject(text).optJSONArray("journal") ?: return null
        val list = mutableListOf<JournalEntry>()
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { list.add(JournalEntry.fromJson(it)) }
        return list
    }

    private fun persistMacros() {
        val arr = JSONArray()
        loadMacros().forEach { arr.put(it.toJson()) }
        writeJson("macros.json", arr.toString(2))
        updateUidTriggerComponent()
    }

    /**
     * The "any tag by serial number" listener is only switched on while at least one enabled
     * macro uses it, so the app doesn't compete with other NFC apps for ordinary tags.
     */
    private fun updateUidTriggerComponent() {
        val needed = loadMacros().any { it.enabled && it.trigger == TriggerType.NFC_UID && it.tagUid.isNotBlank() }
        val state = if (needed) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        runCatching {
            app.packageManager.setComponentEnabledSetting(
                ComponentName(app.packageName, UID_ALIAS), state, PackageManager.DONT_KILL_APP
            )
        }
    }

    // ---------- settings ----------

    fun settings(): AppSettings = synchronized(lock) {
        settings?.let { return AppSettings.fromJson(it.toJson()) }
        val s = readJson("settings.json")?.let { runCatching { AppSettings.fromJson(JSONObject(it)) }.getOrNull() }
            ?: AppSettings()
        settings = s
        AppSettings.fromJson(s.toJson())
    }

    fun saveSettings(s: AppSettings) = synchronized(lock) {
        settings = AppSettings.fromJson(s.toJson())
        writeJson("settings.json", s.toJson().toString(2))
    }

    // ---------- run log ----------

    private fun loadLog(): MutableList<LogEntry> {
        log?.let { return it }
        val list = mutableListOf<LogEntry>()
        readJson("log.json")?.let { txt ->
            runCatching {
                val arr = JSONArray(txt)
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { list.add(LogEntry.fromJson(it)) }
            }
        }
        log = list
        return list
    }

    fun addLog(e: LogEntry) = synchronized(lock) {
        val list = loadLog()
        list.add(0, e)
        while (list.size > MAX_LOG) list.removeAt(list.size - 1)
        writeJson("log.json", JSONArray().apply { list.forEach { put(it.toJson()) } }.toString())
    }

    fun log(): List<LogEntry> = synchronized(lock) { loadLog().toList() }

    fun clearLog() = synchronized(lock) {
        loadLog().clear()
        writeJson("log.json", "[]")
    }

    // ---------- journal ----------

    private fun loadJournal(): MutableList<JournalEntry> {
        journal?.let { return it }
        val list = mutableListOf<JournalEntry>()
        readJson("journal.json")?.let { txt ->
            runCatching {
                val arr = JSONArray(txt)
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { list.add(JournalEntry.fromJson(it)) }
            }
        }
        journal = list
        return list
    }

    private fun persistJournal() {
        writeJson("journal.json", JSONArray().apply { loadJournal().forEach { put(it.toJson()) } }.toString())
    }

    /** Newest first. */
    fun journal(): List<JournalEntry> = synchronized(lock) { loadJournal().toList() }

    fun addJournal(e: JournalEntry) = synchronized(lock) {
        val list = loadJournal()
        list.add(0, e)
        while (list.size > MAX_JOURNAL) list.removeAt(list.size - 1)
        persistJournal()
    }

    fun deleteJournal(e: JournalEntry) = synchronized(lock) {
        loadJournal().removeAll { it.time == e.time && it.label == e.label }
        persistJournal()
    }

    fun clearJournal() = synchronized(lock) {
        loadJournal().clear()
        persistJournal()
    }

    /** Spreadsheet-friendly export, oldest first. */
    fun journalCsv(): String = synchronized(lock) {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        fun q(v: String) = "\"" + v.replace("\"", "\"\"") + "\""
        val sb = StringBuilder("time,what,note,macro\n")
        for (e in loadJournal().asReversed()) {
            sb.append(q(fmt.format(java.util.Date(e.time)))).append(',')
                .append(q(e.label)).append(',').append(q(e.note)).append(',').append(q(e.macro)).append('\n')
        }
        sb.toString()
    }

    // ---------- cooldown bookkeeping ----------

    private fun prefs() = app.getSharedPreferences("runtime", Context.MODE_PRIVATE)

    fun lastRun(id: String): Long = prefs().getLong("last_$id", 0L)

    fun markRun(id: String) {
        prefs().edit().putLong("last_$id", System.currentTimeMillis()).apply()
    }

    // ---------- backup ----------

    fun exportJson(): String = synchronized(lock) {
        JSONObject().apply {
            put("format", "tagmacro-backup")
            put("version", 1)
            put("macros", JSONArray().apply { loadMacros().forEach { put(it.toJson()) } })
            put("settings", settings().toJson())
            put("journal", JSONArray().apply { loadJournal().forEach { put(it.toJson()) } })
        }.toString(2)
    }

    /** Parses a backup without applying it. Throws if the file isn't a TagMacro backup. */
    fun parseBackup(text: String): Pair<List<Macro>, AppSettings?> {
        val o = JSONObject(text)
        require(o.optString("format") == "tagmacro-backup") { "Not a TagMacro backup file" }
        val arr = o.optJSONArray("macros") ?: JSONArray()
        val list = mutableListOf<Macro>()
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { list.add(Macro.fromJson(it)) }
        val s = o.optJSONObject("settings")?.let { AppSettings.fromJson(it) }
        return list to s
    }
}
