package dev.tagmacro

import android.app.Activity
import android.text.format.DateUtils
import android.widget.ImageView
import android.view.Gravity
import java.util.Locale
import android.widget.ListView
import android.widget.HorizontalScrollView
import android.widget.ArrayAdapter
import android.telephony.TelephonyManager
import android.telephony.PhoneNumberUtils
import android.provider.ContactsContract
import android.content.pm.PackageManager
import android.Manifest
import android.app.AlertDialog
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.TagLostException
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.annotation.SuppressLint
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

class MainActivity : Activity() {

    private enum class Tab(val label: String, val icon: String) {
        MACROS("Macros", "bolt"), TAGS("Tags", "tag"), LOG("Log", "clock"), SETTINGS("Settings", "sliders")
    }

    private companion object {
        const val REQ_EXPORT = 41
        const val REQ_IMPORT = 42
        const val REQ_CONTACT = 43
        const val REQ_LOCATION = 44
        const val REQ_EXPORT_CSV = 45
        const val REQ_PERMS = 46
    }

    private lateinit var ui: Ui
    private lateinit var content: FrameLayout
    private lateinit var tabBar: LinearLayout
    private val tabViews = mutableMapOf<Tab, Triple<FrameLayout, ImageView, TextView>>()
    private lateinit var titleView: TextView
    private lateinit var backButton: View
    private lateinit var editorBar: LinearLayout
    private var logView = "runs"
    private var showSignalAdvanced = false
    private var showWaAdvanced = false

    private var tab = Tab.MACROS
    private var editing: Macro? = null
    private var editingIsNew = false
    private var editingSnapshot = ""
    private var scrollView: ScrollView? = null
    private var screenKey = ""

    // Tags tab state
    private val pending = mutableListOf<PendingRecord>()
    private var lastRead = ""
    private var nfcDialog: AlertDialog? = null

    // NFC reader state. The reader callback runs on a binder thread, hence @Volatile.
    @Volatile private var pendingOp: ((Tag) -> String)? = null
    @Volatile private var pendingDone: ((String) -> Unit)? = null
    private var readerOn = false
    private var resumed = false
    /** Serial number of a tag still resting on the phone after a scan; null once it's lifted away. */
    private var heldUid: String? = null

    private var backCallback: Any? = null

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        val p = ui.p

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.background)
        }

        // App bar: back arrow (editor only) and the screen title.
        backButton = ui.iconButton("back", "Back", p.onSurface) { handleBack() }
        titleView = TextView(this).apply {
            setTextColor(p.onSurface)
            textSize = 24f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val appBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = ui.dp(64)
            setPadding(ui.dp(12), ui.dp(4), ui.dp(16), ui.dp(4))
        }
        appBar.addView(backButton)
        appBar.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = ui.dp(8)
        })
        root.addView(appBar)

        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Sticky Save / Test run bar, shown only in the macro editor.
        editorBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(p.surface)
            setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
            elevation = ui.dp(8).toFloat()
            visibility = View.GONE
        }
        root.addView(editorBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Bottom navigation.
        tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(p.surface)
            setPadding(ui.dp(4), ui.dp(10), ui.dp(4), ui.dp(10))
            elevation = ui.dp(8).toFloat()
        }
        for (t in Tab.values()) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                isClickable = true
                contentDescription = t.label
                setOnClickListener { switchTab(t) }
            }
            val pill = FrameLayout(this)
            val icon = ImageView(this)
            pill.addView(icon, FrameLayout.LayoutParams(ui.dp(24), ui.dp(24), Gravity.CENTER))
            item.addView(pill, LinearLayout.LayoutParams(ui.dp(64), ui.dp(32)))
            val label = TextView(this).apply {
                text = t.label
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(0, ui.dp(4), 0, 0)
            }
            item.addView(label)
            tabBar.addView(item, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            tabViews[t] = Triple(pill, icon, label)
        }
        root.addView(tabBar)

        setContentView(root)
        setupEdgeToEdge(this, root, ui.isNight)
        render()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        updateReaderMode()
        render() // refresh permission status after returning from system settings
    }

    override fun onPause() {
        resumed = false
        cancelNfcPrompt()
        updateReaderMode()
        super.onPause()
    }

    private fun switchTab(t: Tab) {
        tab = t
        render()
    }

    // ------------------------------------------------------------------ rendering

    private fun render() {
        val m = editing
        val p = ui.p
        val key = if (m != null) "edit-${m.id}" else tab.name
        val oldY = if (key == screenKey) scrollView?.scrollY ?: 0 else 0
        screenKey = key

        titleView.text = when {
            m != null -> if (editingIsNew) "New macro" else "Edit macro"
            else -> tab.label
        }
        backButton.visibility = if (m != null) View.VISIBLE else View.GONE

        val body: View = when {
            m != null -> editorScreen(m)
            tab == Tab.MACROS -> macrosScreen()
            tab == Tab.TAGS -> tagsScreen()
            tab == Tab.LOG -> logScreen()
            else -> settingsScreen()
        }
        val sv = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
        }
        sv.addView(body)
        content.removeAllViews()
        content.addView(sv)
        scrollView = sv
        if (oldY > 0) sv.post { sv.scrollTo(0, oldY) }

        // "New macro" floating button on the Macros tab.
        if (m == null && tab == Tab.MACROS) {
            val fab = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = ui.pressable(p.primary, 18f)
                elevation = ui.dp(6).toFloat()
                setPadding(ui.dp(18), ui.dp(16), ui.dp(22), ui.dp(16))
                isClickable = true
                contentDescription = "New macro"
                setOnClickListener { chooseTemplate() }
                addView(ImageView(this@MainActivity).apply { setImageDrawable(Glyph("plus", p.onPrimary)) },
                    LinearLayout.LayoutParams(ui.dp(22), ui.dp(22)).apply { marginEnd = ui.dp(10) })
                addView(TextView(this@MainActivity).apply {
                    text = "New macro"
                    textSize = 15f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setTextColor(p.onPrimary)
                })
            }
            content.addView(fab, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END
            ).apply { setMargins(0, 0, ui.dp(16), ui.dp(16)) })
        }

        // Editor: sticky Test run / Save bar.
        editorBar.removeAllViews()
        if (m != null) {
            editorBar.addView(ui.weighted(ui.iconTextButton("play", "Test run") { testRun(m) }))
            editorBar.addView(ui.weighted(ui.iconTextButton("check", "Save", primary = true) {
                if (saveEditing(true)) render()
            }))
            editorBar.visibility = View.VISIBLE
        } else {
            editorBar.visibility = View.GONE
        }

        tabBar.visibility = if (m != null) View.GONE else View.VISIBLE
        for ((t, views) in tabViews) {
            val (pill, icon, label) = views
            val selected = t == tab
            pill.background = if (selected) ui.rounded(p.primaryContainer, 16f) else null
            icon.setImageDrawable(Glyph(t.icon, if (selected) p.onPrimaryContainer else p.subtle))
            label.setTextColor(if (selected) p.onSurface else p.subtle)
            label.typeface = if (selected) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.DEFAULT
            tabBar.getChildAt(t.ordinal).isSelected = selected
        }
        updateBackHandling()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun confirm(title: String, message: String, yes: String, onYes: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(yes) { _, _ -> onYes() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun compact(b: Button): Button = b.apply {
        minWidth = 0
        minimumWidth = 0
        setPadding(ui.dp(12), paddingTop, ui.dp(12), paddingBottom)
    }

    // ------------------------------------------------------------------ back navigation

    private fun updateBackHandling() {
        if (Build.VERSION.SDK_INT >= 33) {
            val want = editing != null
            if (want && backCallback == null) {
                val cb = OnBackInvokedCallback { handleBack() }
                onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
                backCallback = cb
            } else if (!want && backCallback != null) {
                onBackInvokedDispatcher.unregisterOnBackInvokedCallback(backCallback as OnBackInvokedCallback)
                backCallback = null
            }
        }
    }

    // Android 8-12 only. On 13+ the OnBackInvokedCallback above handles back instead.
    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    @SuppressLint("GestureBackNavigation")
    override fun onBackPressed() {
        if (editing != null) handleBack() else super.onBackPressed()
    }

    private fun handleBack() {
        val m = editing ?: return
        if (m.toJson().toString() == editingSnapshot) {
            closeEditor()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Save changes?")
            .setPositiveButton("Save") { _, _ ->
                if (saveEditing(false)) closeEditor()
            }
            .setNegativeButton("Discard") { _, _ -> closeEditor() }
            .setNeutralButton("Keep editing", null)
            .show()
    }

    private fun closeEditor() {
        editing = null
        render()
    }

    // ------------------------------------------------------------------ macros list

    private fun triggerSummary(m: Macro): String = when (m.trigger) {
        TriggerType.NFC_TOKEN -> "Runs from its own NFC tag"
        TriggerType.NFC_UID ->
            if (m.tagUid.isBlank()) "Runs from a tag serial number (not set yet)"
            else "Runs from tag ${NfcTools.prettyUid(normaliseUid(m.tagUid))}"
        TriggerType.MANUAL -> "Runs from the Run button or a shortcut"
    }

    private fun triggerChip(m: Macro): String = when (m.trigger) {
        TriggerType.NFC_TOKEN -> "Own tag"
        TriggerType.NFC_UID -> "Tag by serial"
        TriggerType.MANUAL -> "No tag"
    }

    private fun macroIcon(m: Macro): String {
        val first = m.actions.firstOrNull { it.enabled }?.type ?: return if (m.trigger == TriggerType.MANUAL) "play" else "tag"
        return actionIcon(first)
    }

    private fun macrosScreen(): View {
        val p = ui.p
        val col = ui.column(16)
        val macros = Store.macros()

        val messagingTypes = setOf("SIGNAL", "WHATSAPP", "WHATSAPP_LIVE", "LOCK_SCREEN")
        val needsService = macros.any { m -> m.enabled && m.actions.any { it.enabled && it.type in messagingTypes } }
        if (needsService && !SignalService.isEnabled(this)) {
            val warn = ui.card().apply { background = ui.rounded(p.dangerContainer, 20f) }
            warn.addView(ui.title("Some actions can't run yet").apply { setTextColor(p.onDangerContainer) })
            warn.addView(ui.text("Signal, WhatsApp and lock-screen actions need the TagMacro accessibility service turned on.", 14f)
                .apply { setTextColor(p.onDangerContainer) })
            warn.addView(ui.button("Open Settings", primary = true) { switchTab(Tab.SETTINGS) })
            col.addView(warn)
        }

        if (macros.isEmpty()) {
            val start = ui.card().apply { gravity = Gravity.CENTER_HORIZONTAL }
            start.addView(ui.iconBadge("tag", sizeDp = 64).apply {
                (layoutParams as LinearLayout.LayoutParams).apply { topMargin = ui.dp(8); gravity = Gravity.CENTER_HORIZONTAL }
            })
            start.addView(ui.text("No macros yet", 20f).apply {
                gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
            start.addView(ui.note("A macro is a list of actions that runs when you tap a tag.").apply { gravity = Gravity.CENTER })
            val steps = listOf(
                "Tap New macro and pick a template, or start blank.",
                "Fill in anything marked \"Needs setup\", then Save.",
                "Tap \"Write trigger to a tag\" and hold a sticker to the back of the phone.",
                "Close TagMacro, lift the sticker away, and tap it to run the macro."
            )
            steps.forEachIndexed { i, step ->
                val r = ui.row(ui.badge("${i + 1}"), ui.text(step, 14f).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = ui.dp(4) }
                })
                r.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = ui.dp(if (i == 0) 16 else 10) }
                start.addView(r)
            }
            start.addView(ui.iconTextButton("plus", "Create your first macro", primary = true) { chooseTemplate() }.apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = ui.dp(20) }
            })
            col.addView(start)
        } else {
            val on = macros.count { it.enabled }
            col.addView(ui.note("${macros.size} macro${if (macros.size == 1) "" else "s"}, $on on. Tap one to edit it."))
        }

        for (m in macros) col.addView(macroCard(m))
        col.addView(ui.spacer(88)) // room for the floating button
        return col
    }

    private fun macroCard(m: Macro): View {
        val p = ui.p
        val card = ui.card().apply {
            background = ui.pressable(p.surface, 20f, p.outline, 1)
            isClickable = true
            setOnClickListener { openEditor(m.deepCopy(), isNew = false) }
        }
        val incomplete = firstIncomplete(m) != null
        val acts = m.actions.filter { it.enabled }

        val texts = ui.column(0, 2)
        texts.addView(ui.title(m.name).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) })
        texts.addView(ui.note(
            when (acts.size) {
                0 -> "No actions"
                1 -> Specs.summary(acts[0])
                else -> "${Specs.summary(acts[0])}, and ${acts.size - 1} more"
            }
        ).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
        texts.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = ui.dp(14)
            marginEnd = ui.dp(8)
        }
        val badge = ui.iconBadge(
            macroIcon(m),
            if (m.enabled) p.primaryContainer else p.surfaceVariant,
            if (m.enabled) p.onPrimaryContainer else p.subtle
        )
        val sw = ui.switch(m.enabled, "${m.name} is on") { on ->
            m.enabled = on
            Store.saveMacro(m)
            render()
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(badge)
        top.addView(texts)
        top.addView(sw)
        card.addView(top)
        if (!m.enabled) texts.alpha = 0.6f

        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(54), ui.dp(4), 0, 0)
        }
        fun addChip(v: TextView) {
            chips.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { marginEnd = ui.dp(6) })
        }
        addChip(ui.chip(triggerChip(m)))
        if (incomplete) addChip(ui.chip("Needs setup", p.dangerContainer, p.onDangerContainer))
        else if (m.askFirst) addChip(ui.chip("Asks first"))
        chips.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        chips.addView(ui.iconButton("play", "Run ${m.name} now", p.primary) {
            Runner.run(this, m, "Run button", null, false)
            toast("Running \"${m.name}\"")
        })
        card.addView(chips)
        card.contentDescription = "${m.name}. ${triggerSummary(m)}. ${acts.size} actions." +
            (if (incomplete) " Needs setup." else "") + (if (m.enabled) "" else " Off.")
        return card
    }

    /** Icon for an action type, used in lists and pickers. */
    private fun actionIcon(type: String): String = when (type) {
        "SIGNAL", "WHATSAPP", "SMS", "TOAST" -> "message"
        "WHATSAPP_LIVE", "DIRECTIONS" -> "pin"
        "CALL", "VIBRATE", "AUTO_ROTATE" -> "phone"
        "RINGER", "DND", "ALARM" -> "bell"
        "MEDIA_VOLUME" -> "sliders"
        "MEDIA_CONTROL", "LAUNCH_APP" -> "play"
        "BRIGHTNESS" -> "sun"
        "SCREEN_TIMEOUT", "TIMER", "WAIT" -> "clock"
        "TORCH", "RUN_MACRO" -> "bolt"
        "LOCK_SCREEN" -> "lock"
        "SETTINGS_PANEL" -> "wifi"
        "OPEN_URL" -> "link"
        "SPEAK" -> "text"
        "CLIPBOARD" -> "copy"
        "LOG_ENTRY" -> "book"
        else -> "bolt"
    }

    // ------------------------------------------------------------------ macro editor

    private fun editorScreen(m: Macro): View {
        val p = ui.p
        val col = ui.column(16)

        // Basics
        val basics = ui.sectionCard("Basics")
        basics.addView(ui.label("Name"))
        basics.addView(ui.edit(m.name, "For example: Home safe") { m.name = it })
        basics.addView(ui.toggle("Macro is on", m.enabled, "When off, its tags and shortcuts do nothing.") { m.enabled = it })
        col.addView(basics)

        // Trigger
        val trig = ui.sectionCard("Trigger", "What starts this macro")
        trig.addView(ui.choiceCards(
            listOf(
                Triple(TriggerType.NFC_TOKEN.name, "A tag written by TagMacro",
                    "Recommended. The tag holds only a random code, never your messages or contacts."),
                Triple(TriggerType.NFC_UID.name, "Any tag or card, by serial number",
                    "Works with transit cards, key fobs and tags you can't write to."),
                Triple(TriggerType.MANUAL.name, "No tag",
                    "Run it with the Run button, a home screen shortcut, or another macro.")
            ),
            m.trigger.name
        ) {
            m.trigger = TriggerType.valueOf(it)
            render()
        })
        when (m.trigger) {
            TriggerType.NFC_TOKEN -> {
                trig.addView(ui.iconTextButton("write", "Write trigger to a tag", primary = true) {
                    if (!saveEditing(false)) return@iconTextButton
                    val msg = NdefMessage(arrayOf(NfcTools.triggerRecord(m.token), NfcTools.appRecord(packageName)))
                    startNfc("Hold the tag you want to use for \"${m.name}\".", { tag -> NfcTools.write(tag, msg) })
                }.apply { (layoutParams as? LinearLayout.LayoutParams)?.topMargin = ui.dp(12) })
                trig.addView(ui.note("Saves the macro first. One tag per macro; you can write the same code to several tags."))
                trig.addView(ui.textButton("Give this macro a new code") {
                    confirm(
                        "Give this macro a new code?",
                        "Tags and home screen shortcuts made with the old code will stop working. Use this if a tag was lost.",
                        "New code"
                    ) {
                        m.token = newToken()
                        toast("New code set. Save, then rewrite your tag.")
                    }
                })
            }
            TriggerType.NFC_UID -> {
                trig.addView(ui.label("Serial number"))
                trig.addView(ui.edit(m.tagUid, "Like 04:A2:3B:1C:22:80:00") { m.tagUid = it })
                trig.addView(ui.iconTextButton("tag", "Scan a tag to fill this in", primary = m.tagUid.isBlank()) {
                    startNfc("Hold the tag you want to use.", { tag -> "UID:" + NfcTools.uid(tag) }) { res ->
                        if (res.startsWith("UID:")) {
                            m.tagUid = NfcTools.prettyUid(res.removePrefix("UID:"))
                            render()
                        } else showResult(res)
                    }
                })
                trig.addView(ui.note("Serial numbers aren't secret: anyone can read them and some tags can be cloned. For anything sensitive, use a tag written by TagMacro."))
            }
            TriggerType.MANUAL -> {}
        }
        col.addView(trig)

        // Actions
        val acts = ui.sectionCard("Actions", "They run in order, top to bottom. Tap one to change it.")
        if (m.actions.isEmpty()) {
            acts.addView(ui.text("No actions yet.", 14f).apply { setTextColor(p.subtle); setPadding(0, ui.dp(8), 0, ui.dp(4)) })
        }
        m.actions.forEachIndexed { i, a -> acts.addView(actionRow(m, a, i)) }
        acts.addView(ui.iconTextButton("plus", "Add action", primary = m.actions.isEmpty()) { addActionPicker(m) }
            .apply { (layoutParams as? LinearLayout.LayoutParams)?.topMargin = ui.dp(12) })
        col.addView(acts)

        // Conditions
        val cond = ui.sectionCard("When it may run")
        cond.addView(ui.toggle("Ask before running", m.askFirst, "Shows a confirmation when the tag is tapped.") { m.askFirst = it })
        cond.addView(ui.toggle("Stop at the first failed action", m.stopOnError) { m.stopOnError = it })
        cond.addView(ui.label("Minimum seconds between runs"))
        cond.addView(ui.edit(m.cooldownSec.toString(), "0", number = true) {
            m.cooldownSec = it.toIntOrNull()?.coerceIn(0, 86400) ?: 0
        })
        cond.addView(ui.note("Stops a tag resting against the phone from running the macro over and over."))
        cond.addView(ui.toggle("Only between certain times", m.useTimeWindow) {
            m.useTimeWindow = it
            render()
        })
        if (m.useTimeWindow) {
            val times = ui.row(
                ui.weighted(ui.iconTextButton("clock", "From ${m.windowStart}") { pickTime(m.windowStart) { t -> m.windowStart = t; render() } }),
                ui.weighted(ui.iconTextButton("clock", "Until ${m.windowEnd}") { pickTime(m.windowEnd) { t -> m.windowEnd = t; render() } })
            )
            times.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            cond.addView(times)
            cond.addView(ui.note("If the end is earlier than the start, the window runs overnight."))
        }
        cond.addView(ui.label("Days"))
        val dayNames = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
        cond.addView(ui.toggleChips(dayNames, { i -> (i + 1) in m.days }) { i, on ->
            if (on) m.days.add(i + 1) else m.days.remove(i + 1)
        })
        cond.addView(ui.note("Test run ignores the time, day and cooldown limits, and uses your unsaved changes."))
        col.addView(cond)

        // More
        val more = ui.sectionCard("More")
        more.addView(ui.iconTextButton("home", "Add to home screen") {
            if (saveEditing(false)) pinShortcut(m)
        })
        if (!editingIsNew) {
            more.addView(ui.iconTextButton("copy", "Duplicate macro") {
                if (!saveEditing(false)) return@iconTextButton
                val copy = m.deepCopy()
                copy.id = java.util.UUID.randomUUID().toString()
                copy.token = newToken()
                copy.name = m.name + " (copy)"
                Store.saveMacro(copy)
                openEditor(copy, isNew = false)
                toast("Duplicated. The copy has its own tag code.")
            })
            more.addView(ui.dangerButton("Delete macro") {
                confirm("Delete \"${m.name}\"?", "Its tags and shortcuts will stop working.", "Delete") {
                    Store.deleteMacro(m.id)
                    runCatching {
                        getSystemService(ShortcutManager::class.java)
                            ?.disableShortcuts(listOf("macro-${m.id}"), "This macro was deleted")
                    }
                    closeEditor()
                }
            })
        }
        col.addView(more)
        col.addView(ui.spacer(16))
        return col
    }

    /** One action inside the editor's Actions card. */
    private fun actionRow(m: Macro, a: Action, i: Int): View {
        val p = ui.p
        val problem = actionProblem(a)
        val item = ui.column(12, 4).apply {
            background = ui.pressable(
                if (problem != null) p.dangerContainer else p.background, 16f,
                if (problem != null) p.danger else p.outline, 1
            )
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = ui.dp(10) }
            isClickable = true
            setOnClickListener { editAction(a, m) { render() } }
            contentDescription = "Action ${i + 1}: ${Specs.summary(a)}" + if (problem != null) ". Needs setup: $problem" else ""
        }
        val texts = ui.column(0, 2)
        texts.addView(ui.text(Specs.summary(a), 15f).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        texts.addView(ui.note(Specs.of(a.type)?.label ?: a.type))
        if (problem != null) {
            texts.addView(ui.text("Needs setup: $problem", 13f).apply { setTextColor(p.danger) })
        }
        texts.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = ui.dp(12)
            marginEnd = ui.dp(8)
        }
        if (!a.enabled) texts.alpha = 0.55f
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(ui.iconBadge(actionIcon(a.type),
            if (a.enabled) p.primaryContainer else p.surfaceVariant,
            if (a.enabled) p.onPrimaryContainer else p.subtle, 36))
        head.addView(texts)
        head.addView(ui.switch(a.enabled, "Action ${i + 1} is on") { on -> a.enabled = on; render() })
        item.addView(head)

        val tools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(40), 0, 0, 0)
        }
        tools.addView(ui.iconButton("edit", "Edit action ${i + 1}", p.onSurface) { editAction(a, m) { render() } })
        tools.addView(ui.iconButton("copy", "Copy action ${i + 1}", p.onSurface) {
            m.actions.add(i + 1, Action.fromJson(a.toJson()))
            render()
        })
        if (i > 0) tools.addView(ui.iconButton("up", "Move action ${i + 1} up", p.onSurface) {
            m.actions.add(i - 1, m.actions.removeAt(i))
            render()
        })
        if (i < m.actions.size - 1) tools.addView(ui.iconButton("down", "Move action ${i + 1} down", p.onSurface) {
            m.actions.add(i + 1, m.actions.removeAt(i))
            render()
        })
        tools.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        tools.addView(ui.iconButton("trash", "Remove action ${i + 1}", p.danger) {
            val removed = m.actions.removeAt(i)
            render()
            toast("Removed \"${Specs.summary(removed)}\"")
        })
        item.addView(tools)
        return item
    }

    /** Grouped, searchable list of every action type. */
    private fun addActionPicker(m: Macro) {
        val p = ui.p
        val col = ui.column(20, 0)
        val list = ui.column(0, 0)
        lateinit var dlg: AlertDialog
        fun fill(query: String) {
            list.removeAllViews()
            val q = query.trim()
            var shown = 0
            for ((category, types) in Specs.categories) {
                val specs = types.mapNotNull { Specs.of(it) }.filter {
                    q.isEmpty() || it.label.contains(q, true) || (Specs.descriptions[it.type] ?: "").contains(q, true) ||
                        category.contains(q, true)
                }
                if (specs.isEmpty()) continue
                list.addView(ui.section(category).apply {
                    (layoutParams as LinearLayout.LayoutParams).topMargin = ui.dp(if (shown == 0) 12 else 20)
                })
                for (spec in specs) {
                    shown++
                    list.addView(ui.listRow(
                        spec.label, Specs.descriptions[spec.type],
                        leading = ui.iconBadge(actionIcon(spec.type), p.surfaceVariant, p.primary, 40)
                    ) {
                        dlg.dismiss()
                        val a = Action(spec.type)
                        spec.fields.forEach { f -> if (f.default.isNotEmpty()) a.params[f.key] = f.default }
                        if (spec.fields.isEmpty()) {
                            m.actions.add(a)
                            render()
                        } else {
                            editAction(a, m) {
                                m.actions.add(a)
                                render()
                            }
                        }
                    })
                }
            }
            if (shown == 0) list.addView(ui.note("No actions match \"$q\".").apply { setPadding(0, ui.dp(16), 0, 0) })
        }
        col.addView(ui.edit("", "Search actions") { fill(it) })
        col.addView(list)
        fill("")
        dlg = AlertDialog.Builder(this)
            .setTitle("Add action")
            .setView(ScrollView(this).apply { addView(col) })
            .setNegativeButton("Cancel", null)
            .create()
        dlg.show()
    }

    private fun openEditor(m: Macro, isNew: Boolean) {
        editing = m
        editingIsNew = isNew
        editingSnapshot = if (isNew) "" else m.toJson().toString()
        render()
    }

    /** "New macro": a highlighted Blank option, then templates with icons. */
    private fun chooseTemplate() {
        val p = ui.p
        val col = ui.column(20, 0)
        lateinit var dlg: AlertDialog
        val chevron = { color: Int -> Glyph.view(this, "chevron", color, ui.dp(20)).apply {
            (layoutParams as LinearLayout.LayoutParams).marginEnd = 0
        } }
        col.addView(ui.listRow(
            Templates.blank.title, Templates.blank.description,
            leading = ui.iconBadge("plus", p.primary, p.onPrimary, 44),
            trailing = chevron(p.onPrimaryContainer),
            highlight = true
        ) {
            dlg.dismiss()
            openEditor(Templates.blank.build(), isNew = true)
        })
        col.addView(ui.section("Or start from a template").apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = ui.dp(20)
        })
        col.addView(ui.note("Each one opens in the editor so you can adjust it before saving."))
        for (t in Templates.all) {
            val n = t.build().actions.size
            col.addView(ui.listRow(
                t.title, "${t.description}\n$n action${if (n == 1) "" else "s"}",
                leading = ui.iconBadge(t.icon, p.surfaceVariant, p.primary, 44),
                trailing = chevron(p.subtle)
            ) {
                dlg.dismiss()
                openEditor(t.build(), isNew = true)
            })
        }
        dlg = AlertDialog.Builder(this)
            .setTitle("New macro")
            .setView(ScrollView(this).apply { addView(col) })
            .setNegativeButton("Cancel", null)
            .create()
        dlg.show()
    }

    /** Why an action can't run yet, or null if it's complete. */
    private fun actionProblem(a: Action): String? {
        val spec = Specs.of(a.type) ?: return "unknown action"
        val values = a.params.toMutableMap()
        if (a.type == "ALARM" && values["time"].isNullOrEmpty() && values["hour"] != null) values["time"] = "00:00"
        return Specs.validate(spec, values)?.removeSuffix(".")?.replaceFirstChar { it.lowercase() }
    }

    /** Index and problem of the first enabled action that isn't filled in. */
    private fun firstIncomplete(m: Macro): Pair<Int, String>? {
        m.actions.forEachIndexed { i, a ->
            if (a.enabled) actionProblem(a)?.let { return i to it }
        }
        return null
    }

    private fun testRun(m: Macro) {
        firstIncomplete(m)?.let { (i, problem) ->
            toast("Action ${i + 1} needs setup: $problem")
            return
        }
        val real = m.actions.filter {
            it.enabled && it.type in setOf("SIGNAL", "WHATSAPP", "WHATSAPP_LIVE")
        }
        val go = {
            Runner.run(this, m.deepCopy(), "Test run", null, false)
            toast("Running")
        }
        if (real.isEmpty()) {
            go()
        } else {
            confirm(
                "This really sends",
                real.joinToString("\n") { Specs.summary(it) } +
                    "\n\nA test run sends real messages and shares real locations.",
                "Run it"
            ) { go() }
        }
    }

    /** Saves the macro being edited. Returns false if it isn't valid yet. */
    private fun saveEditing(showToast: Boolean): Boolean {
        val m = editing ?: return false
        m.name = m.name.trim().ifEmpty { "Macro" }
        if (m.trigger == TriggerType.NFC_UID && m.tagUid.isNotBlank()) {
            val u = normaliseUid(m.tagUid)
            if (u.length < 8 || u.any { it !in "0123456789ABCDEF" }) {
                toast("The serial number should be hex digits, like 04:A2:3B:1C")
                return false
            }
        }
        if (m.days.isEmpty()) {
            toast("Pick at least one day")
            return false
        }
        firstIncomplete(m)?.let { (i, problem) ->
            toast("Action ${i + 1} needs setup: $problem")
            return false
        }
        Store.saveMacro(m)
        editingIsNew = false
        editingSnapshot = m.toJson().toString()
        runCatching { getSystemService(ShortcutManager::class.java)?.updateShortcuts(listOf(shortcutFor(m))) }
        if (showToast) toast("Saved")
        return true
    }

    private fun pickTime(current: String, onPick: (String) -> Unit) {
        val mins = Runner.parseHm(current) ?: 480
        TimePickerDialog(this, { _, h, min -> onPick("%02d:%02d".format(h, min)) }, mins / 60, mins % 60, true).show()
    }

    private fun shortcutFor(m: Macro): ShortcutInfo {
        val intent = Intent(this, TriggerActivity::class.java)
            .setAction(TriggerActivity.ACTION_RUN)
            .putExtra(TriggerActivity.EXTRA_MACRO, m.id)
            .putExtra(TriggerActivity.EXTRA_TOKEN, m.token)
        val label = m.name.ifBlank { "Macro" }
        return ShortcutInfo.Builder(this, "macro-${m.id}")
            .setShortLabel(label.take(25))
            .setLongLabel(label.take(60))
            .setIcon(Icon.createWithResource(this, applicationInfo.icon))
            .setIntent(intent)
            .build()
    }

    private fun pinShortcut(m: Macro) {
        val sm = getSystemService(ShortcutManager::class.java)
        if (sm == null || !sm.isRequestPinShortcutSupported) {
            toast("Your home screen app doesn't support pinned shortcuts")
            return
        }
        sm.requestPinShortcut(shortcutFor(m), null)
    }

    // ------------------------------------------------------------------ action editor

    private fun editAction(a: Action, owner: Macro, onSaved: () -> Unit) {
        val spec = Specs.of(a.type) ?: return
        val values = a.params.toMutableMap()
        // Alarms made before the time picker stored hour and minute separately.
        if (a.type == "ALARM" && values["time"].isNullOrEmpty() && values["hour"] != null) {
            values["time"] = "%02d:%02d".format(
                values["hour"]?.toDoubleOrNull()?.toInt() ?: 7, values["minute"]?.toDoubleOrNull()?.toInt() ?: 0
            )
        }
        val col = ui.column(20)

        for (f in spec.fields) {
            col.addView(ui.label(f.label))
            val takesPlaceholders = f.hint.contains(Specs.PLACEHOLDER_HINT)
            when (f.kind) {
                FieldKind.TEXT -> {
                    val e = ui.edit(values[f.key].orEmpty()) { values[f.key] = it }
                    col.addView(e)
                    if (takesPlaceholders) col.addView(placeholderBar(e))
                }
                FieldKind.MULTILINE -> {
                    val e = ui.edit(values[f.key].orEmpty(), multiline = true) { values[f.key] = it }
                    col.addView(e)
                    if (takesPlaceholders) col.addView(placeholderBar(e))
                }
                FieldKind.PHONE -> {
                    val e = ui.edit(values[f.key].orEmpty(), "+44 7700 900123") { values[f.key] = it }
                    e.inputType = InputType.TYPE_CLASS_PHONE
                    col.addView(e)
                    col.addView(ui.button("Choose contact") {
                        pickContact { _, number ->
                            val intl = toInternational(number)
                            e.setText(intl)
                            if (!intl.trim().startsWith("+")) toast("Couldn't work out the country code. Add it, like +44.")
                        }
                    })
                }
                FieldKind.CONTACT_NAME -> {
                    val e = ui.edit(values[f.key].orEmpty()) { values[f.key] = it }
                    col.addView(e)
                    col.addView(ui.button("Choose contact") { pickContact { name, _ -> e.setText(name) } })
                }
                FieldKind.TIME -> {
                    val current = values[f.key]?.takeIf { it.isNotEmpty() } ?: f.default
                    values[f.key] = current
                    val b = ui.button(current) {}
                    b.setOnClickListener {
                        pickTime(values[f.key] ?: f.default) { t ->
                            values[f.key] = t
                            b.text = t
                        }
                    }
                    col.addView(b)
                }
                FieldKind.NUMBER -> col.addView(ui.edit(values[f.key].orEmpty(), number = true, decimal = true) { values[f.key] = it })
                FieldKind.CHOICE -> col.addView(ui.radios(f.options, values[f.key] ?: f.default) { values[f.key] = it })
                FieldKind.APP -> {
                    val shown = ui.text(values["appLabel"] ?: values[f.key] ?: "No app chosen")
                    col.addView(shown)
                    col.addView(ui.button("Choose app") {
                        pickApp { pkg, name ->
                            values[f.key] = pkg
                            values["appLabel"] = name
                            shown.text = name
                        }
                    })
                }
                FieldKind.MACRO -> {
                    val shown = ui.text(values["macroName"] ?: "No macro chosen")
                    col.addView(shown)
                    col.addView(ui.button("Choose macro") {
                        val others = Store.macros().filter { it.id != owner.id }
                        if (others.isEmpty()) {
                            toast("Create another macro first")
                            return@button
                        }
                        AlertDialog.Builder(this)
                            .setTitle("Choose macro")
                            .setItems(others.map { it.name }.toTypedArray()) { _, i ->
                                values[f.key] = others[i].id
                                values["macroName"] = others[i].name
                                shown.text = others[i].name
                            }
                            .show()
                    })
                }
            }
            if (f.hint.isNotEmpty()) col.addView(ui.note(f.hint))
        }

        val sv = ScrollView(this)
        sv.addView(col)
        val dlg = AlertDialog.Builder(this)
            .setTitle(spec.label)
            .setView(sv)
            .setPositiveButton("Done", null)
            .setNegativeButton("Cancel", null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val err = Specs.validate(spec, values)
                if (err != null) {
                    toast(err)
                } else {
                    a.params.clear()
                    a.params.putAll(values)
                    dlg.dismiss()
                    onSaved()
                    requestActionPermissions(a)
                }
            }
        }
        dlg.show()
    }

    /** Asks for SMS or phone permission when an action has just been set to need it. */
    private fun requestActionPermissions(a: Action) {
        val needed = when {
            a.type == "SMS" && a.params["mode"] != "compose" -> Manifest.permission.SEND_SMS
            a.type == "CALL" && a.params["mode"] != "dial" -> Manifest.permission.CALL_PHONE
            else -> return
        }
        if (checkSelfPermission(needed) == PackageManager.PERMISSION_GRANTED) return
        markAsked(needed)
        requestPermissions(arrayOf(needed), REQ_PERMS)
    }

    /** A row of buttons that insert placeholders at the cursor. */
    private fun placeholderBar(target: EditText): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        for (token in Specs.PLACEHOLDERS) {
            row.addView(compact(ui.button(token) {
                val start = target.selectionStart.coerceAtLeast(0)
                target.text.insert(start, token)
                if (token == "{location}" && !Locator.hasPermission(this)) {
                    ensureLocationPermission(
                        "TagMacro needs location permission to fill in {location} when the macro runs."
                    ) {}
                }
            }))
        }
        return HorizontalScrollView(this).apply { addView(row) }
    }

    /** Searchable list of launchable apps. */
    @Suppress("DEPRECATION")
    private fun pickApp(onPick: (String, String) -> Unit) {
        val pm = packageManager
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(query, 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .filter { it.second != packageName }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase() }
        var shown = apps
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, apps.map { it.first }.toMutableList())
        val list = ListView(this)
        list.adapter = adapter
        list.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(380))
        val col = ui.column(12)
        col.addView(ui.edit("", "Search apps") { q ->
            shown = if (q.isBlank()) apps else apps.filter { it.first.contains(q.trim(), ignoreCase = true) }
            adapter.clear()
            adapter.addAll(shown.map { it.first })
        })
        col.addView(list)
        val dlg = AlertDialog.Builder(this)
            .setTitle("Choose app")
            .setView(col)
            .setNegativeButton("Cancel", null)
            .create()
        list.setOnItemClickListener { _, _, pos, _ ->
            shown.getOrNull(pos)?.let { onPick(it.second, it.first) }
            dlg.dismiss()
        }
        dlg.show()
    }

    // ------------------------------------------------------------------ contacts and location

    private var afterContactPick: ((String, String) -> Unit)? = null
    private var afterLocationPermission: (() -> Unit)? = null

    /**
     * Opens the phone's own contact picker. Android lets TagMacro read only the one contact you
     * pick, so no contacts permission is needed.
     */
    private fun pickContact(onPick: (name: String, number: String) -> Unit) {
        afterContactPick = onPick
        try {
            startActivityForResult(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI), REQ_CONTACT)
        } catch (e: Exception) {
            afterContactPick = null
            toast("No contacts app found")
        }
    }

    private fun handlePickedContact(uri: Uri) {
        val cb = afterContactPick ?: return
        afterContactPick = null
        try {
            contentResolver.query(
                uri,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst()) cb(c.getString(0).orEmpty(), c.getString(1).orEmpty())
            }
        } catch (e: Exception) {
            toast("Couldn't read that contact: ${e.message}")
        }
    }

    /** Converts a local number like 07700 900123 to +447700900123 using the SIM's country. */
    private fun toInternational(number: String): String {
        val trimmed = number.trim()
        if (trimmed.startsWith("+")) return trimmed
        val tm = getSystemService(TelephonyManager::class.java)
        val iso = listOf(tm?.simCountryIso, tm?.networkCountryIso, Locale.getDefault().country)
            .firstOrNull { !it.isNullOrBlank() }?.uppercase(Locale.US) ?: return trimmed
        return PhoneNumberUtils.formatNumberToE164(trimmed, iso) ?: trimmed
    }

    private fun ensureLocationPermission(why: String, then: () -> Unit) {
        if (Locator.hasPermission(this)) {
            then()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Location permission")
            .setMessage("$why\n\nTagMacro only checks your location at those moments, and has no internet access to send it anywhere itself.")
            .setPositiveButton("Continue") { _, _ ->
                afterLocationPermission = then
                requestPermissions(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                    REQ_LOCATION
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMS) {
            if (grantResults.any { it != PackageManager.PERMISSION_GRANTED }) {
                toast("Not allowed. The action will fail until you allow it in Settings → Permissions.")
            }
            render()
            return
        }
        if (requestCode != REQ_LOCATION) return
        val then = afterLocationPermission
        afterLocationPermission = null
        if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
            then?.invoke()
        } else if (!shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) {
            AlertDialog.Builder(this)
                .setMessage("Location permission is turned off for TagMacro. You can allow it in App info → Permissions.")
                .setPositiveButton("Open App info") { _, _ ->
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                }
                .setNegativeButton("Not now", null)
                .show()
        } else {
            toast("Location permission not granted")
        }
    }

    /** Gets the current position in the background with a progress dialog, then calls [onFix]. */
    private fun withCurrentLocation(onFix: (Double, Double) -> Unit) {
        ensureLocationPermission("TagMacro needs location permission to fill in where you are.") {
            val progress = AlertDialog.Builder(this)
                .setMessage("Getting your location…")
                .setCancelable(false)
                .show()
            Thread {
                val loc = Locator.current(this)
                runOnUiThread {
                    progress.dismiss()
                    if (loc == null) {
                        toast("Couldn't get a location. Check that Location is turned on.")
                    } else {
                        onFix(loc.latitude, loc.longitude)
                        if (loc.hasAccuracy()) toast("Accurate to about ${loc.accuracy.toInt()} m")
                    }
                }
            }.start()
        }
    }

    // ------------------------------------------------------------------ NFC sessions

    /**
     * Reader mode keeps Android from handing tags to other apps, or to TagMacro's own triggers,
     * while this screen is in front. It's on while a scan is pending, while a just-scanned tag is
     * still on the phone, and all the time the app is open if "Ignore tag taps while TagMacro is
     * open" is on.
     */
    private fun updateReaderMode() {
        val adapter = NfcAdapter.getDefaultAdapter(this) ?: return
        val want = resumed && adapter.isEnabled &&
            (pendingOp != null || heldUid != null || Store.settings().ignoreTagsWhileOpen)
        if (want && !readerOn) {
            val flags = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V
            adapter.enableReaderMode(this, { tag -> onReaderTag(adapter, tag) }, flags, null)
            readerOn = true
        } else if (!want && readerOn) {
            runCatching { adapter.disableReaderMode(this) }
            readerOn = false
        }
    }

    /** Runs on a binder thread. */
    private fun onReaderTag(adapter: NfcAdapter, tag: Tag) {
        val uid = NfcTools.uid(tag)
        TagGuard.holdUntilRemoved(uid)
        val op = pendingOp
        val done = pendingDone
        pendingOp = null
        pendingDone = null
        val result: String? = if (op == null) null else try {
            op(tag)
        } catch (e: TagLostException) {
            "The tag moved away too soon. Hold it still against the phone and try again."
        } catch (e: Exception) {
            "Failed: ${e.message ?: e.javaClass.simpleName}"
        }
        // Ask Android not to deliver this tag again until it has been away for a second.
        val ignored = try {
            adapter.ignore(tag, 1000, { runOnUiThread { tagRemoved(uid) } }, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
            false
        }
        runOnUiThread {
            if (ignored) {
                heldUid = uid
            } else {
                TagGuard.release(uid, 1500) // already gone, or ignore unsupported: short guard only
            }
            if (result != null) {
                dismissNfcPrompt()
                (done ?: { r: String -> showResult(r) }).invoke(result)
            } else {
                toast("TagMacro is open, so this tag didn't run anything. Use Test run to try a macro.")
            }
            updateReaderMode()
        }
    }

    private fun tagRemoved(uid: String) {
        TagGuard.release(uid, 1500)
        if (heldUid == uid) heldUid = null
        updateReaderMode()
    }

    /** Waits for one tag and runs [op] on it (off the main thread), then shows the result. */
    private fun startNfc(prompt: String, op: (Tag) -> String, onDone: (String) -> Unit = { showResult(it) }) {
        val adapter = NfcAdapter.getDefaultAdapter(this)
        if (adapter == null) {
            toast("This phone doesn't have NFC")
            return
        }
        if (!adapter.isEnabled) {
            AlertDialog.Builder(this)
                .setTitle("NFC is off")
                .setMessage("Turn on NFC to use tags.")
                .setPositiveButton("Open NFC settings") { _, _ -> startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        cancelNfcPrompt()
        val msg = if (heldUid != null) "$prompt\n\nIf a tag is already resting on the phone, lift it away first, then tap." else prompt
        nfcDialog = AlertDialog.Builder(this)
            .setTitle("Ready to scan")
            .setMessage(msg)
            .setNegativeButton("Cancel") { _, _ -> cancelNfcPrompt() }
            .setOnCancelListener { cancelNfcPrompt() }
            .show()
        pendingDone = onDone
        pendingOp = op
        updateReaderMode()
    }

    private fun dismissNfcPrompt() {
        nfcDialog?.let {
            it.setOnCancelListener(null)
            it.dismiss()
        }
        nfcDialog = null
    }

    private fun cancelNfcPrompt() {
        pendingOp = null
        pendingDone = null
        dismissNfcPrompt()
        updateReaderMode()
    }

    private fun showResult(msg: String) {
        AlertDialog.Builder(this).setMessage(msg).setPositiveButton("OK", null).show()
    }

    // ------------------------------------------------------------------ tags tab

    private fun tagsScreen(): View {
        val p = ui.p
        val col = ui.column(16)
        val adapter = NfcAdapter.getDefaultAdapter(this)
        if (adapter == null) {
            val c = ui.sectionCard("No NFC", "This phone doesn't have NFC, so it can't read or write tags.")
            col.addView(c)
            return col
        }
        if (!adapter.isEnabled) {
            val c = ui.card().apply { background = ui.rounded(p.dangerContainer, 20f) }
            c.addView(ui.title("NFC is off").apply { setTextColor(p.onDangerContainer) })
            c.addView(ui.text("Turn it on to read, write or tap tags.", 14f).apply { setTextColor(p.onDangerContainer) })
            c.addView(ui.button("Open NFC settings", primary = true) { startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) })
            col.addView(c)
        }

        // Read
        val read = ui.sectionCard("Read a tag", "See what's stored on a tag and which macro it runs.")
        read.addView(ui.iconTextButton("tag", "Scan a tag", primary = true) {
            startNfc("Hold a tag to the back of the phone.", { tag -> NfcTools.describe(tag, Store.macros()) }) {
                lastRead = it
                render()
            }
        })
        if (lastRead.isNotEmpty()) {
            read.addView(ui.codeBlock(lastRead))
            read.addView(ui.row(
                ui.textButton("Copy") {
                    getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Tag", lastRead))
                    toast("Copied")
                },
                ui.textButton("Clear") {
                    lastRead = ""
                    render()
                }
            ))
        }
        col.addView(read)

        // Write
        val write = ui.sectionCard(
            "Write a tag",
            "Links, Wi-Fi, contact cards and the rest work on any phone, with no app needed. Writing replaces everything on the tag."
        )
        if (pending.isEmpty()) {
            write.addView(ui.text("Add one or more records, then write them in one go.", 14f).apply {
                setTextColor(p.subtle)
                setPadding(0, ui.dp(8), 0, 0)
            })
        }
        pending.forEachIndexed { i, r ->
            write.addView(ui.listRow(
                r.label, null,
                leading = ui.iconBadge(recordIcon(r.label), p.surfaceVariant, p.primary, 36),
                trailing = ui.iconButton("trash", "Remove ${r.label}", p.danger) {
                    pending.removeAt(i)
                    render()
                }
            ))
        }
        write.addView(ui.iconTextButton("plus", "Add record", primary = pending.isEmpty()) { addRecordDialog() })
        if (pending.isNotEmpty()) {
            val msg = buildPendingMessage()
            val size = msg.byteArrayLength
            val chips = listOf(144 to "NTAG213", 504 to "NTAG215", 888 to "NTAG216")
            val fit = chips.firstOrNull { size <= it.first }
            write.addView(ui.meter(size.toFloat() / (fit?.first ?: 888)))
            write.addView(ui.note(
                if (fit != null) "$size bytes. Fits a ${fit.second} sticker (${fit.first} bytes) or larger."
                else "$size bytes. That's more than common stickers hold (NTAG216: 888 bytes). Remove a record."
            ))
            write.addView(ui.row(
                ui.weighted(ui.iconTextButton("write", "Write to tag", primary = true) {
                    startNfc("Hold the tag you want to write to.", { tag -> NfcTools.write(tag, msg) })
                }),
                ui.weighted(ui.button("Clear list") {
                    pending.clear()
                    render()
                })
            ).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) })
        }
        col.addView(write)

        // Tools
        val tools = ui.sectionCard("Tag tools")
        tools.addView(ui.iconTextButton("erase", "Erase a tag") {
            startNfc("Hold the tag you want to erase.", { tag -> NfcTools.erase(tag) })
        })
        tools.addView(ui.iconTextButton("edit", "Format a new tag") {
            startNfc("Hold the tag you want to format.", { tag -> NfcTools.format(tag) })
        })
        tools.addView(ui.dangerButton("Lock a tag permanently") { lockDialog() })
        tools.addView(ui.note("Locking can't be undone. Only lock tags in public places where someone could overwrite them."))
        col.addView(tools)
        col.addView(ui.spacer(16))
        return col
    }

    private fun recordIcon(label: String): String = when {
        label.startsWith("Run macro") -> "bolt"
        label.startsWith("Wi-Fi") -> "wifi"
        label.startsWith("Link") -> "link"
        label.startsWith("Call") -> "phone"
        label.startsWith("Email") -> "mail"
        label.startsWith("SMS") -> "message"
        label.startsWith("Location") -> "pin"
        label.startsWith("Contact") -> "card"
        label.startsWith("Open app") -> "play"
        else -> "text"
    }

    /** Trigger records go first, followed by an app record so Android opens TagMacro for them. */
    private fun buildPendingMessage(): NdefMessage {
        val triggers = pending.filter { it.label.startsWith("Run macro") }.map { it.record }
        val others = pending.filterNot { it.label.startsWith("Run macro") }.map { it.record }
        val records = mutableListOf<NdefRecord>()
        records.addAll(triggers)
        records.addAll(others)
        if (triggers.isNotEmpty()) records.add(NfcTools.appRecord(packageName))
        return NdefMessage(records.toTypedArray())
    }

    private fun addRecordDialog() {
        val p = ui.p
        class Kind(val title: String, val desc: String, val icon: String, val go: () -> Unit)
        val groups = listOf(
            "Share" to listOf(
                Kind("Link", "A website, social profile, review page, playlist or payment link", "link") {
                    form("Link", listOf("url" to "Web address")) { v ->
                        var u = v["url"]!!.trim()
                        if (!u.contains(":")) u = "https://$u"
                        add("Link: $u", NfcTools.uriRecord(u))
                    }
                },
                Kind("Wi-Fi network", "Guests tap to join your Wi-Fi. Android only", "wifi") { wifiRecordDialog() },
                Kind("Contact card", "Your name, phone and email, ready to save", "card") {
                    form("Contact card", listOf("name" to "Name", "tel" to "Phone (optional)", "email" to "Email (optional)"),
                        contact = { e, name, n -> e["name"]?.setText(name); e["tel"]?.setText(n) }) { v ->
                        add("Contact: ${v["name"]}", NfcTools.vcardRecord(v["name"]!!, v["tel"]!!, v["email"]!!))
                    }
                },
                Kind("Map location", "Opens a place in the phone's maps app", "pin") { locationRecordDialog() },
                Kind("Plain text", "A note shown on screen, like care instructions or a serial number", "text") {
                    form("Text", listOf("text" to "Text")) { v ->
                        add("Text: ${v["text"]}", NfcTools.textRecord(v["text"]!!))
                    }
                }
            ),
            "Contact someone" to listOf(
                Kind("Phone call", "Opens the dialer with a number, for example an emergency contact", "phone") {
                    form("Phone number", listOf("tel" to "Number"),
                        contact = { e, _, n -> e["tel"]?.setText(n) }) { v ->
                        add("Call ${v["tel"]}", NfcTools.uriRecord("tel:" + v["tel"]!!.trim()))
                    }
                },
                Kind("Text message (SMS)", "Opens a text to a number, with optional message", "message") {
                    form("Text message", listOf("to" to "Number", "body" to "Message (optional)"),
                        contact = { e, _, n -> e["to"]?.setText(n) }) { v ->
                        var u = "sms:" + v["to"]!!.trim()
                        if (v["body"]!!.isNotBlank()) u += "?body=" + Uri.encode(v["body"])
                        add("SMS to ${v["to"]}", NfcTools.uriRecord(u))
                    }
                },
                Kind("Email", "Opens a new email to an address", "mail") {
                    form("Email", listOf("to" to "Address", "subject" to "Subject (optional)")) { v ->
                        var u = "mailto:" + v["to"]!!.trim()
                        if (v["subject"]!!.isNotBlank()) u += "?subject=" + Uri.encode(v["subject"])
                        add("Email ${v["to"]}", NfcTools.uriRecord(u))
                    }
                }
            ),
            "Apps and macros" to listOf(
                Kind("Open an app", "Opens an installed app, or its store page if missing", "play") {
                    pickApp { pkg, name -> add("Open app: $name", NfcTools.appRecord(pkg)) }
                },
                Kind("Run a TagMacro macro", "Only works on phones with TagMacro and this macro", "bolt") {
                    val ms = Store.macros().filter { it.trigger == TriggerType.NFC_TOKEN }
                    if (ms.isEmpty()) {
                        toast("No saved macros use \"A tag written by TagMacro\"")
                    } else {
                        AlertDialog.Builder(this)
                            .setTitle("Run which macro?")
                            .setItems(ms.map { it.name }.toTypedArray()) { _, i ->
                                add("Run macro \"${ms[i].name}\"", NfcTools.triggerRecord(ms[i].token))
                            }
                            .show()
                    }
                }
            )
        )
        val col = ui.column(20, 0)
        lateinit var dlg: AlertDialog
        groups.forEachIndexed { gi, (title, kinds) ->
            col.addView(ui.section(title).apply {
                (layoutParams as LinearLayout.LayoutParams).topMargin = ui.dp(if (gi == 0) 4 else 20)
            })
            for (k in kinds) {
                col.addView(ui.listRow(k.title, k.desc, leading = ui.iconBadge(k.icon, p.surfaceVariant, p.primary, 40)) {
                    dlg.dismiss()
                    k.go()
                })
            }
        }
        dlg = AlertDialog.Builder(this)
            .setTitle("Add record")
            .setView(ScrollView(this).apply { addView(col) })
            .setNegativeButton("Cancel", null)
            .create()
        dlg.show()
    }

    /** Wi-Fi network record (Wi-Fi Alliance WSC format). */
    private fun wifiRecordDialog() {
        var security = "wpa2"
        val col = ui.column(20, 0)
        col.addView(ui.label("Network name (SSID)").apply { (layoutParams as LinearLayout.LayoutParams).topMargin = 0 })
        val ssid = ui.edit("", "MyHomeWiFi")
        col.addView(ssid)
        col.addView(ui.label("Password"))
        val pass = ui.edit("", "At least 8 characters")
        pass.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        col.addView(pass)
        col.addView(ui.label("Security"))
        col.addView(ui.radios(
            listOf("wpa2" to "WPA2 or WPA3 (most home routers)", "wpa" to "WPA / WPA2 mixed (older routers)", "open" to "None (open network)"),
            security
        ) { security = it })
        col.addView(ui.note("Android phones offer to join when they tap the tag. iPhones can't join from Wi-Fi tags. Anyone who reads the tag can see the password, so consider a guest network."))
        val dlg = AlertDialog.Builder(this)
            .setTitle("Wi-Fi network")
            .setView(ScrollView(this).apply { addView(col) })
            .setPositiveButton("Add", null)
            .setNegativeButton("Cancel", null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = ssid.text.toString()
                val pw = pass.text.toString()
                val err = when {
                    name.isBlank() -> "Enter the network name"
                    name.toByteArray(Charsets.UTF_8).size > 32 -> "Network names can be at most 32 bytes"
                    security != "open" && pw.length !in 8..63 -> "Wi-Fi passwords are 8 to 63 characters"
                    else -> null
                }
                if (err != null) {
                    toast(err)
                } else {
                    add("Wi-Fi: $name", NfcTools.wifiRecord(name, pw, security))
                    dlg.dismiss()
                }
            }
        }
        dlg.show()
    }

    /** Adds a record and refreshes. Returns null so it can be used as a form result. */
    private fun add(label: String, r: NdefRecord): String? {
        pending.add(PendingRecord(label, r))
        render()
        return null
    }

    /**
     * Map location record: type "lat, lon", paste a Google or Apple Maps link, or use where you
     * are now. Written as a standard geo: link, so any phone opens it in its maps app.
     */
    private fun locationRecordDialog() {
        val col = ui.column(20)
        col.addView(ui.text("Location"))
        val where = ui.edit("", "51.500729, -0.124625  or a maps link", multiline = true)
        col.addView(where)
        col.addView(ui.note("Paste a Google Maps or Apple Maps link, or coordinates like 51.500729, -0.124625."))
        col.addView(ui.button("Use my current location", primary = true) {
            withCurrentLocation { lat, lon -> where.setText(Locator.format(lat, lon).replace(",", ", ")) }
        })
        col.addView(ui.text("Label (optional)"))
        val label = ui.edit("", "Front door")
        col.addView(label)
        val dlg = AlertDialog.Builder(this)
            .setTitle("Map location")
            .setView(ScrollView(this).apply { addView(col) })
            .setPositiveButton("Add", null)
            .setNegativeButton("Cancel", null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pos = Locator.parse(where.text.toString())
                if (pos == null) {
                    toast("Couldn't find coordinates there. Try a maps link, \"lat, lon\", or Use my current location.")
                    return@setOnClickListener
                }
                val (lat, lon) = pos
                val coords = Locator.format(lat, lon)
                val name = label.text.toString().trim()
                val uri = if (name.isEmpty()) "geo:$coords?q=$coords" else "geo:$coords?q=$coords(" + Uri.encode(name) + ")"
                val shown = if (name.isEmpty()) coords.replace(",", ", ") else "$name (${coords.replace(",", ", ")})"
                add("Location $shown", NfcTools.uriRecord(uri))
                dlg.dismiss()
            }
        }
        dlg.show()
    }

    /** Simple form dialog. The first field is required. onOk returns an error message or null. */
    private fun form(
        title: String,
        fields: List<Pair<String, String>>,
        contact: ((edits: Map<String, EditText>, name: String, number: String) -> Unit)? = null,
        onOk: (Map<String, String>) -> String?
    ) {
        val col = ui.column(20)
        val edits = mutableMapOf<String, EditText>()
        if (contact != null) {
            col.addView(ui.button("Choose contact") { pickContact { name, number -> contact(edits, name, number) } })
        }
        for ((key, label) in fields) {
            col.addView(ui.text(label))
            val e = ui.edit("")
            if (key == "tel" || key == "to" && title == "Text message") e.inputType = InputType.TYPE_CLASS_PHONE
            if (key == "lat" || key == "lon") {
                e.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            }
            col.addView(e)
            edits[key] = e
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(ScrollView(this).apply { addView(col) })
            .setPositiveButton("Add", null)
            .setNegativeButton("Cancel", null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val values = edits.mapValues { it.value.text.toString() }
                if (values[fields.first().first].isNullOrBlank()) {
                    toast("\"${fields.first().second}\" is required")
                    return@setOnClickListener
                }
                val err = try {
                    onOk(values)
                } catch (e: Exception) {
                    e.message ?: "That value can't be written"
                }
                if (err != null) toast(err) else dlg.dismiss()
            }
        }
        dlg.show()
    }

    private fun lockDialog() {
        val e = ui.edit("", "LOCK")
        val col = ui.column(20)
        col.addView(ui.text("Locking makes the tag read-only forever. It can never be changed or erased again, by this or any other app."))
        col.addView(ui.text("Type LOCK to continue."))
        col.addView(e)
        AlertDialog.Builder(this)
            .setTitle("Lock a tag permanently?")
            .setView(col)
            .setPositiveButton("Continue") { _, _ ->
                if (e.text.toString().trim() == "LOCK") {
                    startNfc("Hold the tag you want to lock.", { tag -> NfcTools.lock(tag) })
                } else toast("Not locked")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------------------ log tab

    private fun logScreen(): View {
        val col = ui.column(16)
        col.addView(ui.segmented(listOf("runs" to "Runs", "journal" to "Journal"), logView) {
            logView = it
            render()
        })
        if (logView == "journal") journalView(col) else runsView(col)
        col.addView(ui.spacer(16))
        return col
    }

    private fun runsView(col: LinearLayout) {
        val p = ui.p
        val entries = Store.log()
        if (entries.isEmpty()) {
            val c = ui.sectionCard("Nothing has run yet", "Every time a macro runs, each step and its result appear here. Useful when something doesn't work.")
            col.addView(c)
            return
        }
        col.addView(ui.row(
            ui.note("The last 300 runs, newest first. Kept only on this phone.").apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            },
            ui.textButton("Clear") {
                confirm("Clear the log?", "This removes every run from the list.", "Clear") {
                    Store.clearLog()
                    render()
                }
            }
        ).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = ui.dp(8) }
        })
        val fmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        for (e in entries) {
            val c = ui.card()
            val head = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            head.addView(ui.iconBadge(
                if (e.ok) "check" else "erase",
                if (e.ok) p.primaryContainer else p.dangerContainer,
                if (e.ok) p.onPrimaryContainer else p.danger, 32
            ))
            val texts = ui.column(0, 0)
            texts.addView(ui.title(e.macro).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            texts.addView(ui.note("${if (e.ok) "Done" else "Failed"} · ${fmt.format(Date(e.time))} · ${e.source}"))
            texts.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = ui.dp(12) }
            head.addView(texts)
            c.addView(head)
            c.addView(ui.codeBlock(e.lines.joinToString("\n")))
            col.addView(c)
        }
    }

    private fun journalView(col: LinearLayout) {
        val p = ui.p
        val entries = Store.journal()
        if (entries.isEmpty()) {
            val c = ui.sectionCard(
                "Your journal is empty",
                "Add the \"Record it in the journal\" action to a macro to note when things happen, like feeding a pet, taking a tablet, or arriving at work. The latest time for each shows here."
            )
            c.addView(ui.iconBadge("book", sizeDp = 48).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = ui.dp(12) })
            col.addView(c)
            return
        }
        val now = System.currentTimeMillis()
        fun ago(t: Long) = DateUtils.getRelativeTimeSpanString(t, now, DateUtils.MINUTE_IN_MILLIS).toString()
        val fmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)

        val latest = ui.sectionCard("Last time", "The most recent entry for each thing you record.")
        for (e in entries.distinctBy { it.label.lowercase() }) {
            val count = entries.count { it.label.equals(e.label, ignoreCase = true) }
            latest.addView(ui.listRow(
                e.label, "${ago(e.time)} · ${fmt.format(Date(e.time))} · $count total",
                leading = ui.iconBadge("book", p.primaryContainer, p.onPrimaryContainer, 36)
            ))
        }
        col.addView(latest)

        val all = ui.sectionCard("All entries", "Newest first. Kept only on this phone.")
        for (e in entries.take(200)) {
            val sub = buildString {
                append(fmt.format(Date(e.time)))
                if (e.note.isNotBlank()) append(" · ").append(e.note)
                if (e.macro.isNotBlank()) append(" · ").append(e.macro)
            }
            all.addView(ui.listRow(e.label, sub, trailing = ui.iconButton("trash", "Delete entry", p.subtle) {
                Store.deleteJournal(e)
                render()
            }))
        }
        if (entries.size > 200) all.addView(ui.note("Showing the latest 200 of ${entries.size}. Export to see them all."))
        all.addView(ui.row(
            ui.weighted(ui.iconTextButton("write", "Export CSV") {
                val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("text/csv")
                    .putExtra(Intent.EXTRA_TITLE, "tagmacro-journal.csv")
                startActivityForResult(i, REQ_EXPORT_CSV)
            }),
            ui.weighted(ui.dangerButton("Clear") {
                confirm("Clear the journal?", "This deletes all ${entries.size} entries.", "Clear") {
                    Store.clearJournal()
                    render()
                }
            })
        ).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = ui.dp(12) }
        })
        col.addView(all)
    }

    // ------------------------------------------------------------------ settings tab

    /** One permission inside the Permissions card. */
    private fun permissionBlock(
        card: LinearLayout, title: String, granted: Boolean, why: String,
        button: String, first: Boolean = false, extra: (LinearLayout) -> Unit = {}, open: () -> Unit
    ) {
        if (!first) card.addView(ui.divider())
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, ui.dp(6), 0, 0)
        }
        head.addView(ui.text(title, 15f).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        head.addView(ui.status(if (granted) "Allowed" else "Not allowed", granted))
        card.addView(head)
        card.addView(ui.note(why))
        card.addView(ui.button(button, primary = false, onClick = open).apply {
            if (granted) {
                background = ui.pressable(android.graphics.Color.TRANSPARENT, 24f, ui.p.outline, 1)
            }
        })
        extra(card)
    }

    private fun openLink(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            toast("No app can open this link")
        }
    }

    private fun settingsScreen(): View {
        val p = ui.p
        val col = ui.column(16)
        val s = Store.settings()
        fun save() {
            Store.saveSettings(s)
        }

        // ---- Permissions
        val perms = ui.sectionCard("Permissions", "Each one is optional, and only needed for the actions listed. TagMacro has no internet permission.")
        val a11yOn = SignalService.isEnabled(this)
        permissionBlock(perms, "Accessibility service", a11yOn,
            "For Signal, WhatsApp and Lock the screen. It only receives events from Signal and WhatsApp, and only reads the screen while a macro is sending.",
            "Open accessibility settings", first = true,
            extra = { c ->
                if (!a11yOn) {
                    c.addView(ui.note("If the switch is greyed out as a restricted setting: open App info, tap the ⋮ menu, choose \"Allow restricted settings\", then try again."))
                    c.addView(ui.textButton("Open App info") {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                    })
                }
            }
        ) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }

        val nm = getSystemService(NotificationManager::class.java)
        permissionBlock(perms, "Do Not Disturb access", nm?.isNotificationPolicyAccessGranted == true,
            "For Do Not Disturb, switching the ringer to or from Silent, and turning ringtone or notification volume to 0.",
            "Open Do Not Disturb access"
        ) { startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }

        permissionBlock(perms, "Modify system settings", Settings.System.canWrite(this),
            "For brightness, screen timeout and auto-rotate.",
            "Open \"Modify system settings\""
        ) { startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.fromParts("package", packageName, null))) }

        permissionBlock(perms, "Display over other apps", Settings.canDrawOverlays(this),
            "For macros that open other apps more than once, like setting several alarms. Android silently blocks the later ones without it. TagMacro never draws over your screen.",
            "Open \"Display over other apps\""
        ) { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.fromParts("package", packageName, null))) }

        fun runtime(title: String, why: String, vararg permissions: String) {
            val granted = permissions.any { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
            permissionBlock(perms, title, granted, why, if (granted) "Manage in App info" else "Allow") {
                if (granted || permissions.all { !shouldShowRequestPermissionRationale(it) && wasAsked(it) }) {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                } else {
                    markAsked(*permissions)
                    requestPermissions(arrayOf(*permissions), REQ_PERMS)
                }
            }
        }
        runtime("Location", "For \"Use my current location\" and the {location} placeholder.",
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        runtime("Send SMS", "For text-message actions set to send straight away.", Manifest.permission.SEND_SMS)
        runtime("Phone calls", "For call actions set to call straight away.", Manifest.permission.CALL_PHONE)
        col.addView(perms)

        // ---- Behaviour
        val behaviour = ui.sectionCard("Behaviour")
        behaviour.addView(ui.toggle("Ignore tag taps while TagMacro is open", s.ignoreTagsWhileOpen,
            "Stops macros firing while you're setting things up. A tag you've just read or written never runs anything until you lift it away and tap again.") {
            s.ignoreTagsWhileOpen = it
            save()
            updateReaderMode()
        })
        behaviour.addView(ui.toggle("Show a message when a macro fails", s.toastOnError) { s.toastOnError = it; save() })
        behaviour.addView(ui.toggle("Show a message when a macro finishes", s.toastOnSuccess) { s.toastOnSuccess = it; save() })
        behaviour.addView(ui.label("Pause after opening an app (milliseconds)"))
        behaviour.addView(ui.edit(s.launchSettleMs.toString(), number = true) {
            s.launchSettleMs = (it.toIntOrNull() ?: 1200).coerceIn(0, 10000)
            save()
        })
        behaviour.addView(ui.note("For actions that open another app, like alarms, links and directions. Raise it if a macro that opens the same app several times misses some."))
        col.addView(behaviour)

        // ---- Signal
        val signal = ui.sectionCard("Signal")
        signal.addView(ui.toggle("Chat name must match exactly", s.matchExact) { s.matchExact = it; save() })
        signal.addView(ui.toggle("Press Back to leave a different open chat", s.backOutOfWrongChat) { s.backOutOfWrongChat = it; save() })
        signal.addView(ui.toggle("Go to the home screen after sending", s.goHomeAfterSend) { s.goHomeAfterSend = it; save() })
        signal.addView(ui.label("Give up after (seconds)"))
        signal.addView(ui.edit(s.signalTimeoutSec.toString(), number = true) {
            s.signalTimeoutSec = (it.toIntOrNull() ?: 20).coerceIn(5, 120)
            save()
        })
        signal.addView(ui.toggle("Show advanced options", showSignalAdvanced, "Only needed if Signal isn't in English or an update renamed something.") {
            showSignalAdvanced = it
            render()
        })
        if (showSignalAdvanced) {
            signal.addView(ui.label("Signal app package"))
            signal.addView(ui.edit(s.signalPackage) {
                s.signalPackage = it.trim().ifEmpty { AppSettings.SIGNAL_PACKAGE }
                save()
                SignalService.instance?.refreshPackageFilter()
            })
            signal.addView(ui.note("Leave as ${AppSettings.SIGNAL_PACKAGE} for the official Signal app."))
            signal.addView(ui.label("Send button labels"))
            signal.addView(ui.edit(s.sendLabels) { s.sendLabels = it; save() })
            signal.addView(ui.label("Send button view IDs"))
            signal.addView(ui.edit(s.sendIds) { s.sendIds = it; save() })
            signal.addView(ui.note("Comma-separated. Use the screen capture under Troubleshooting to find the current names."))
        }
        col.addView(signal)

        // ---- WhatsApp
        val wa = ui.sectionCard("WhatsApp")
        wa.addView(ui.label("Which WhatsApp"))
        wa.addView(ui.radios(
            listOf(AppSettings.WHATSAPP_PACKAGE to "WhatsApp", "com.whatsapp.w4b" to "WhatsApp Business"),
            s.waPackage
        ) {
            s.waPackage = it
            save()
            SignalService.instance?.refreshPackageFilter()
        })
        wa.addView(ui.label("Give up on live location after (seconds)"))
        wa.addView(ui.edit(s.waLiveTimeoutSec.toString(), number = true) {
            s.waLiveTimeoutSec = (it.toIntOrNull() ?: 40).coerceIn(10, 180)
            save()
        })
        wa.addView(ui.note("Messages use the Signal timeout above."))
        wa.addView(ui.toggle("Show advanced options", showWaAdvanced, "The words TagMacro looks for on WhatsApp's screens.") {
            showWaAdvanced = it
            render()
        })
        if (showWaAdvanced) {
            fun waField(label: String, value: String, set: (String) -> Unit) {
                wa.addView(ui.label(label))
                wa.addView(ui.edit(value) { set(it); save() })
            }
            waField("Send button labels", s.waSendLabels) { s.waSendLabels = it }
            waField("Send button view IDs", s.waSendIds) { s.waSendIds = it }
            waField("Attach button labels", s.waAttachLabels) { s.waAttachLabels = it }
            waField("Attach button view IDs", s.waAttachIds) { s.waAttachIds = it }
            waField("\"Location\" menu item", s.waLocationLabels) { s.waLocationLabels = it }
            waField("\"Share live location\" item", s.waLiveLabels) { s.waLiveLabels = it }
            waField("Durations (15 min, 1 hour, 8 hours, in that order)", s.waDurationLabels) { s.waDurationLabels = it }
            waField("One-time notices to dismiss", s.waDismissLabels) { s.waDismissLabels = it }
            waField("Text that confirms live location started", s.waLiveConfirm) { s.waLiveConfirm = it }
            waField("Text meaning the number isn't on WhatsApp", s.waNotOnWhatsApp) { s.waNotOnWhatsApp = it }
            wa.addView(ui.note("Comma-separated. Change these only if WhatsApp isn't in English or an update renamed something."))
        }
        col.addView(wa)

        // ---- Troubleshooting
        val trouble = ui.sectionCard("Troubleshooting", "Shows what the accessibility service sees in Signal or WhatsApp, so you can update the labels above. Kept in memory only.")
        trouble.addView(ui.button("Capture the next Signal or WhatsApp screen") {
            if (!SignalService.isEnabled(this)) {
                toast("Turn on the accessibility service first")
                return@button
            }
            SignalService.captureRequested = true
            toast("Now open the Signal or WhatsApp screen you want to inspect, then come back here")
        })
        val cap = SignalService.lastCapture
        if (cap.isNotEmpty()) {
            trouble.addView(ui.codeBlock(cap.take(30000)))
            trouble.addView(ui.row(
                ui.textButton("Copy") {
                    getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Signal screen", cap))
                    toast("Copied")
                },
                ui.textButton("Clear") {
                    SignalService.lastCapture = ""
                    render()
                }
            ))
        }
        col.addView(trouble)

        // ---- Backup
        val backup = ui.sectionCard("Backup", "Macros, settings and the journal in one file. It includes each macro's tag code, so keep it private. Export before updating or reinstalling.")
        backup.addView(ui.row(
            ui.weighted(ui.iconTextButton("write", "Export") {
                val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/json")
                    .putExtra(Intent.EXTRA_TITLE, "tagmacro-backup.json")
                startActivityForResult(i, REQ_EXPORT)
            }),
            ui.weighted(ui.button("Import") {
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*")
                startActivityForResult(i, REQ_IMPORT)
            })
        ).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) })
        col.addView(backup)

        // ---- About
        val about = ui.card()
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(ui.iconBadge("tag", p.primary, p.onPrimary, 52))
        val names = ui.column(0, 0)
        names.addView(ui.text("TagMacro", 20f).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"
        names.addView(ui.note("Version $version" + if (AppInfo.LICENSE.isNotEmpty()) " · ${AppInfo.LICENSE}" else ""))
        names.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = ui.dp(14) }
        head.addView(names)
        about.addView(head)
        about.addView(ui.text("NFC automation without trackers, ads or internet access. Your macros, settings and journal never leave this phone unless you export them.", 14f)
            .apply { setPadding(0, ui.dp(10), 0, 0) })
        if (AppInfo.DONATE_URL.isNotEmpty()) {
            about.addView(ui.iconTextButton("heart", "Support development", primary = true) { openLink(AppInfo.DONATE_URL) }
                .apply { (layoutParams as? LinearLayout.LayoutParams)?.topMargin = ui.dp(14) })
            about.addView(ui.note("TagMacro is free. If it's useful to you, a donation helps keep it maintained."))
        }
        if (AppInfo.SOURCE_URL.isNotEmpty()) {
            about.addView(ui.iconTextButton("code", "Source code") { openLink(AppInfo.SOURCE_URL) })
        }
        if (AppInfo.ISSUES_URL.isNotEmpty()) {
            about.addView(ui.iconTextButton("info", "Report a problem") { openLink(AppInfo.ISSUES_URL) })
        }
        col.addView(about)
        col.addView(ui.spacer(16))
        return col
    }

    private fun wasAsked(permission: String): Boolean =
        getSharedPreferences("runtime", MODE_PRIVATE).getBoolean("asked_$permission", false)

    private fun markAsked(vararg permissions: String) {
        val e = getSharedPreferences("runtime", MODE_PRIVATE).edit()
        permissions.forEach { e.putBoolean("asked_$it", true) }
        e.apply()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CONTACT) {
            val picked = data?.data
            if (resultCode == RESULT_OK && picked != null) handlePickedContact(picked) else afterContactPick = null
            return
        }
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            REQ_EXPORT_CSV -> try {
                contentResolver.openOutputStream(uri, "wt")?.use { it.write(Store.journalCsv().toByteArray(Charsets.UTF_8)) }
                toast("Journal exported")
            } catch (e: Exception) {
                showResult("Export failed: ${e.message}")
            }
            REQ_EXPORT -> try {
                contentResolver.openOutputStream(uri, "wt")?.use { it.write(Store.exportJson().toByteArray(Charsets.UTF_8)) }
                toast("Exported")
            } catch (e: Exception) {
                showResult("Export failed: ${e.message}")
            }
            REQ_IMPORT -> try {
                val text = contentResolver.openInputStream(uri)?.use { String(it.readBytes(), Charsets.UTF_8) }
                    ?: throw IllegalStateException("Couldn't open the file")
                val (macros, settings) = Store.parseBackup(text)
                val journalBackup = Store.parseBackupJournal(text)
                confirm(
                    "Replace everything?",
                    "This replaces all ${Store.macros().size} current macros with the ${macros.size} in the backup, and restores its settings" +
                        (if (journalBackup != null) " and journal (${journalBackup.size} entries)." else "."),
                    "Replace"
                ) {
                    Store.replaceAll(macros, settings, journalBackup)
                    SignalService.instance?.refreshPackageFilter()
                    toast("Imported ${macros.size} macros")
                    render()
                }
            } catch (e: Exception) {
                showResult("Import failed: ${e.message}")
            }
        }
    }
}
