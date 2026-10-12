package dev.tagmacro

import android.telephony.SmsManager
import android.content.pm.PackageManager
import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.AlarmClock
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.KeyEvent
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object Runner {
    val main = Handler(Looper.getMainLooper())

    /** One macro at a time, in the order they were triggered. */
    private val executor = Executors.newSingleThreadExecutor()

    private const val MAX_DEPTH = 5

    /** Actions that work by opening another app's screen. */
    private val LAUNCH_ACTIONS = setOf("LAUNCH_APP", "OPEN_URL", "ALARM", "TIMER", "SETTINGS_PANEL", "CALL", "DIRECTIONS", "SMS")

    private const val NEED_WRITE_SETTINGS =
        "Needs \"Modify system settings\" permission (Settings tab)"

    fun run(
        ctx: Context, macro: Macro, source: String, tagUid: String?, checkConstraints: Boolean,
        onFinished: (() -> Unit)? = null
    ) {
        val app = ctx.applicationContext
        executor.execute {
            val lines = mutableListOf<String>()
            val ok = try {
                execute(app, macro, source, tagUid, checkConstraints, 0, lines)
            } catch (t: Throwable) {
                lines.add("Stopped by an unexpected error: ${t.javaClass.simpleName}: ${t.message}")
                false
            }
            Store.addLog(LogEntry(System.currentTimeMillis(), macro.name, source, ok, lines))
            val s = Store.settings()
            if (!ok && s.toastOnError) toast(app, "${macro.name}: ${lines.lastOrNull() ?: "failed"}")
            if (ok && s.toastOnSuccess) toast(app, "${macro.name}: done")
            if (onFinished != null) main.post(onFinished)
        }
    }

    /** Returns a reason the macro shouldn't run now, or null if it may run. */
    fun constraintProblem(m: Macro): String? {
        if (!m.enabled) return "Macro is turned off"
        val cal = Calendar.getInstance()
        val day = cal.get(Calendar.DAY_OF_WEEK)
        if (day !in m.days) return "Not set to run on ${dayName(day)}"
        if (m.useTimeWindow) {
            val now = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
            val start = parseHm(m.windowStart)
            val end = parseHm(m.windowEnd)
            if (start != null && end != null && start != end) {
                val inside = if (start < end) now in start until end else (now >= start || now < end)
                if (!inside) return "Outside the allowed time (${m.windowStart}–${m.windowEnd})"
            }
        }
        if (m.cooldownSec > 0) {
            val since = System.currentTimeMillis() - Store.lastRun(m.id)
            if (since in 0 until m.cooldownSec * 1000L) return "Ran less than ${m.cooldownSec} s ago (cooldown)"
        }
        return null
    }

    fun parseHm(s: String): Int? {
        val parts = s.trim().split(":")
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h * 60 + m
    }

    fun dayName(calendarDay: Int): String =
        listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
            .getOrElse(calendarDay - 1) { "?" }

    private fun execute(
        ctx: Context, m: Macro, source: String, tagUid: String?,
        checkConstraints: Boolean, depth: Int, lines: MutableList<String>
    ): Boolean {
        if (checkConstraints) {
            constraintProblem(m)?.let {
                lines.add("Not run: $it")
                return false
            }
        }
        Store.markRun(m.id)
        val launching = m.actions.count { it.enabled && it.type in LAUNCH_ACTIONS }
        if (launching > 1 && !canLaunchFromBackground(ctx)) {
            lines.add(
                "Warning: this macro opens another app $launching times. Android blocks all but the " +
                    "first unless TagMacro has \"Display over other apps\" (see the Settings tab). " +
                    "Blocked launches are silent, so the steps below may still say OK."
            )
        }
        if (m.actions.none { it.enabled }) {
            lines.add("This macro has no enabled actions")
            return false
        }
        var allOk = true
        for (a in m.actions) {
            if (!a.enabled) continue
            val label = Specs.summary(a)
            val error = try {
                perform(ctx, m, a, tagUid, depth, lines)
            } catch (t: Throwable) {
                "${t.javaClass.simpleName}: ${t.message}"
            }
            if (error == null) {
                lines.add("OK: $label")
            } else {
                lines.add("Failed: $label ($error)")
                allOk = false
                if (m.stopOnError) {
                    lines.add("Stopped because \"Stop at the first failed action\" is on")
                    break
                }
            }
        }
        return allOk
    }

    /** Performs one action. Returns an error message, or null on success. */
    private fun perform(
        ctx: Context, m: Macro, a: Action, tagUid: String?, depth: Int, lines: MutableList<String>
    ): String? {
        val p = a.params
        fun text(key: String) = expand(ctx, p[key].orEmpty(), m, tagUid)
        fun num(key: String, def: Double) = p[key]?.trim()?.toDoubleOrNull() ?: def

        return when (a.type) {
            "SIGNAL" -> SignalService.sendBlocking(ctx, p["contact"].orEmpty(), text("message"))

            "WHATSAPP" -> SignalService.whatsappBlocking(
                ctx, p["phone"].orEmpty(), text("message"), live = false, duration = "", comment = ""
            )

            "WHATSAPP_LIVE" -> SignalService.whatsappBlocking(
                ctx, p["phone"].orEmpty(), null, live = true, duration = p["duration"] ?: "1h", comment = text("comment")
            )

            "SMS" -> {
                val number = p["number"].orEmpty().trim()
                val body = text("message")
                if (p["mode"] == "compose") {
                    launch(ctx, Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number))).putExtra("sms_body", body))
                } else {
                    if (ctx.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                        return "SMS permission isn't granted. Open this macro, edit the SMS action and tap Done to grant it."
                    }
                    val sms: SmsManager = (if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java)
                    else @Suppress("DEPRECATION") SmsManager.getDefault()) ?: return "Texting isn't available on this phone"
                    val parts = sms.divideMessage(body)
                    if (parts.size > 1) sms.sendMultipartTextMessage(number, null, parts, null, null)
                    else sms.sendTextMessage(number, null, body, null, null)
                    null
                }
            }

            "CALL" -> {
                val number = p["number"].orEmpty().trim()
                val uri = Uri.parse("tel:" + Uri.encode(number))
                if (p["mode"] == "dial") {
                    launch(ctx, Intent(Intent.ACTION_DIAL, uri))
                } else {
                    if (ctx.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
                        return "Phone permission isn't granted. Open this macro, edit the call action and tap Done to grant it."
                    }
                    launch(ctx, Intent(Intent.ACTION_CALL, uri))
                }
            }

            "DIRECTIONS" -> {
                val place = text("place").trim()
                if (place.isEmpty()) return "No destination"
                val coords = Locator.parse(place)
                val uri = if (p["app"] == "any") {
                    if (coords != null) {
                        val c = Locator.format(coords.first, coords.second)
                        Uri.parse("geo:$c?q=$c")
                    } else Uri.parse("geo:0,0?q=" + Uri.encode(place))
                } else {
                    val dest = coords?.let { Locator.format(it.first, it.second) } ?: place
                    Uri.parse(
                        "https://www.google.com/maps/dir/?api=1&destination=" + Uri.encode(dest) +
                            "&travelmode=" + (p["travel"] ?: "driving")
                    )
                }
                launch(ctx, Intent(Intent.ACTION_VIEW, uri))
            }

            "LOG_ENTRY" -> {
                val label = text("label").trim()
                if (label.isEmpty()) return "Nothing to record"
                Store.addJournal(JournalEntry(System.currentTimeMillis(), label, text("note").trim(), m.name))
                null
            }

            "WAIT" -> {
                val ms = (num("seconds", 1.0).coerceIn(0.0, 3600.0) * 1000).toLong()
                Thread.sleep(ms)
                null
            }

            "LAUNCH_APP" -> {
                val pkg = p["package"].orEmpty()
                val intent = ctx.packageManager.getLaunchIntentForPackage(pkg)
                    ?: return "App $pkg isn't installed or can't be opened"
                launch(ctx, intent)
            }

            "OPEN_URL" -> {
                val url = text("url").trim()
                if (url.isEmpty()) return "No link"
                launch(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }

            "TORCH" -> Torch.set(ctx, p["mode"] ?: "toggle")

            "VIBRATE" -> {
                val ms = num("ms", 300.0).toLong().coerceIn(1, 10_000)
                val v: Vibrator = (if (Build.VERSION.SDK_INT >= 31) {
                    ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
                } else {
                    ctx.getSystemService(Vibrator::class.java)
                }) ?: return "Vibration isn't available"
                if (!v.hasVibrator()) return "This phone can't vibrate"
                v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
                Thread.sleep(ms)
                null
            }

            "TOAST" -> {
                toast(ctx, text("text"))
                null
            }

            "SPEAK" -> Speaker.speak(ctx, text("text"))

            "MEDIA_VOLUME" -> {
                val am = ctx.getSystemService(AudioManager::class.java) ?: return "Audio isn't available"
                val stream = when (p["stream"]) {
                    "ring" -> AudioManager.STREAM_RING
                    "notification" -> AudioManager.STREAM_NOTIFICATION
                    "alarm" -> AudioManager.STREAM_ALARM
                    "call" -> AudioManager.STREAM_VOICE_CALL
                    else -> AudioManager.STREAM_MUSIC
                }
                val max = am.getStreamMaxVolume(stream)
                val pct = num("percent", 50.0).coerceIn(0.0, 100.0)
                val vol = Math.round(max * pct / 100.0).toInt()
                try {
                    am.setStreamVolume(stream, vol, AudioManager.FLAG_SHOW_UI)
                    null
                } catch (e: SecurityException) {
                    "Android refused: this change needs Do Not Disturb access (Settings tab)"
                }
            }

            "RINGER" -> {
                val am = ctx.getSystemService(AudioManager::class.java) ?: return "Audio isn't available"
                val mode = when (p["mode"]) {
                    "silent" -> AudioManager.RINGER_MODE_SILENT
                    "normal" -> AudioManager.RINGER_MODE_NORMAL
                    else -> AudioManager.RINGER_MODE_VIBRATE
                }
                try {
                    am.ringerMode = mode
                    null
                } catch (e: SecurityException) {
                    "Android refused: switching to or from Silent needs Do Not Disturb access (Settings tab)"
                }
            }

            "BRIGHTNESS" -> {
                if (!Settings.System.canWrite(ctx)) return NEED_WRITE_SETTINGS
                val cr = ctx.contentResolver
                if (p["mode"] == "auto") {
                    Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE,
                        Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
                } else {
                    // A fixed level only sticks with adaptive brightness off.
                    Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE,
                        Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                    val pct = num("percent", 50.0).coerceIn(1.0, 100.0)
                    val level = Math.round(pct * 255.0 / 100.0).toInt().coerceIn(1, 255)
                    Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, level)
                }
                null
            }

            "SCREEN_TIMEOUT" -> {
                if (!Settings.System.canWrite(ctx)) return NEED_WRITE_SETTINGS
                val ms = (num("seconds", 60.0).toLong().coerceIn(5, 86_400) * 1000L).toInt()
                Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, ms)
                null
            }

            "AUTO_ROTATE" -> {
                if (!Settings.System.canWrite(ctx)) return NEED_WRITE_SETTINGS
                val cr = ctx.contentResolver
                val current = Settings.System.getInt(cr, Settings.System.ACCELEROMETER_ROTATION, 0) == 1
                val target = when (p["mode"]) {
                    "on" -> true
                    "off" -> false
                    else -> !current
                }
                Settings.System.putInt(cr, Settings.System.ACCELEROMETER_ROTATION, if (target) 1 else 0)
                null
            }

            "MEDIA_CONTROL" -> {
                val am = ctx.getSystemService(AudioManager::class.java) ?: return "Audio isn't available"
                val code = when (p["key"]) {
                    "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
                    "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
                    "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
                    "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                    "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
                    else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                }
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
                null
            }

            "SETTINGS_PANEL" -> {
                val action = when (p["panel"]) {
                    "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
                    else -> {
                        if (Build.VERSION.SDK_INT < 29) return "Settings panels need Android 10 or newer"
                        when (p["panel"]) {
                            "internet" -> Settings.Panel.ACTION_INTERNET_CONNECTIVITY
                            "nfc" -> Settings.Panel.ACTION_NFC
                            "volume" -> Settings.Panel.ACTION_VOLUME
                            else -> Settings.Panel.ACTION_WIFI
                        }
                    }
                }
                launch(ctx, Intent(action))
            }

            "LOCK_SCREEN" -> {
                if (Build.VERSION.SDK_INT < 28) return "Locking the screen needs Android 9 or newer"
                val svc = SignalService.instance
                    ?: return "Needs the TagMacro accessibility service turned on (Settings tab)"
                val ok = onMain { svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN) }
                if (ok) null else "Android refused to lock the screen"
            }

            "CLIPBOARD" -> {
                val t = text("text")
                onMain {
                    val cm = ctx.getSystemService(ClipboardManager::class.java)
                    cm?.setPrimaryClip(ClipData.newPlainText("TagMacro", t))
                }
                null
            }

            "ALARM" -> {
                // "time" is the current format; "hour"/"minute" are kept for macros made earlier.
                val hm = p["time"]?.let { parseHm(it) }
                val hour = hm?.div(60) ?: num("hour", 7.0).toInt()
                val minute = hm?.rem(60) ?: num("minute", 0.0).toInt()
                val i = Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, hour.coerceIn(0, 23))
                    .putExtra(AlarmClock.EXTRA_MINUTES, minute.coerceIn(0, 59))
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                val label = text("label")
                if (label.isNotBlank()) i.putExtra(AlarmClock.EXTRA_MESSAGE, label)
                launch(ctx, i)
            }

            "TIMER" -> {
                val i = Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, num("seconds", 300.0).toInt().coerceIn(1, 86400))
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                val label = text("label")
                if (label.isNotBlank()) i.putExtra(AlarmClock.EXTRA_MESSAGE, label)
                launch(ctx, i)
            }

            "DND" -> {
                val nm = ctx.getSystemService(NotificationManager::class.java) ?: return "Notifications aren't available"
                if (!nm.isNotificationPolicyAccessGranted) {
                    return "Do Not Disturb access isn't granted (see the Settings tab)"
                }
                val filter = when (p["mode"]) {
                    "off" -> NotificationManager.INTERRUPTION_FILTER_ALL
                    "alarms" -> NotificationManager.INTERRUPTION_FILTER_ALARMS
                    "none" -> NotificationManager.INTERRUPTION_FILTER_NONE
                    else -> NotificationManager.INTERRUPTION_FILTER_PRIORITY
                }
                nm.setInterruptionFilter(filter)
                null
            }

            "RUN_MACRO" -> {
                if (depth >= MAX_DEPTH) return "Too many macros calling each other"
                val other = Store.macro(p["macro"].orEmpty()) ?: return "That macro no longer exists"
                if (other.id == m.id) return "A macro can't run itself"
                val sub = mutableListOf<String>()
                val ok = execute(ctx, other, "Called by ${m.name}", tagUid, true, depth + 1, sub)
                sub.forEach { lines.add("  [${other.name}] $it") }
                if (ok) null else "The other macro didn't finish cleanly"
            }

            else -> "Unknown action type ${a.type}"
        }
    }

    /** Replaces {time}, {date}, {battery}, {location}, {tag}, {macro} in user text. */
    fun expand(ctx: Context, s: String, m: Macro, tagUid: String?): String {
        if (!s.contains('{')) return s
        var out = s
        val now = Date()
        out = out.replace("{time}", SimpleDateFormat("HH:mm", Locale.getDefault()).format(now))
        out = out.replace("{date}", SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(now))
        if (out.contains("{battery}")) {
            val level = ctx.getSystemService(BatteryManager::class.java)
                ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            out = out.replace("{battery}", if (level != null) "$level%" else "?")
        }
        if (out.contains("{location}")) {
            val loc = Locator.current(ctx)
            out = out.replace(
                "{location}",
                loc?.let { Locator.mapsLink(it) } ?: "(location unavailable)"
            )
        }
        out = out.replace("{tag}", tagUid ?: "none")
        out = out.replace("{macro}", m.name)
        return out
    }

    /**
     * Starts an activity, then pauses so the other app can finish handling it.
     *
     * Android 10 and later block activity starts from an app with no visible window, and the block
     * is silent: startActivity returns normally and the system only logs it. That is why several
     * activity-launching actions in a row can end with just the first one taking effect. The pause
     * gives the other app time, and canLaunchFromBackground() says whether the launch is likely to
     * be allowed at all.
     */
    fun launch(ctx: Context, intent: Intent): String? {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val c: Context = SignalService.instance ?: ctx
        try {
            c.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            return "No installed app can handle this"
        } catch (e: SecurityException) {
            return "Android blocked this: ${e.message}"
        }
        val settle = Store.settings().launchSettleMs.toLong().coerceIn(0, 10_000)
        if (settle > 0) Thread.sleep(settle)
        return null
    }

    /**
     * True when Android should allow this app to open another app while it has no visible window:
     * the "Display over other apps" permission and a bound accessibility service are both listed
     * exemptions from the background-activity-launch rules.
     */
    fun canLaunchFromBackground(ctx: Context): Boolean =
        Settings.canDrawOverlays(ctx) || SignalService.instance != null

    fun toast(ctx: Context, msg: String) {
        main.post { Toast.makeText(ctx.applicationContext, msg, Toast.LENGTH_LONG).show() }
    }

    /** Runs a block on the main thread and waits for its result. */
    fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val latch = CountDownLatch(1)
        var result: Result<T>? = null
        main.post {
            result = runCatching(block)
            latch.countDown()
        }
        if (!latch.await(15, TimeUnit.SECONDS)) throw IllegalStateException("Main thread didn't respond")
        val r = result ?: throw IllegalStateException("No result")
        return r.getOrThrow()
    }
}

object Torch {
    @Volatile private var on = false
    @Volatile private var registered = false

    fun set(ctx: Context, mode: String): String? {
        val cm = ctx.getSystemService(CameraManager::class.java) ?: return "Camera service isn't available"
        if (!registered) {
            registered = true
            cm.registerTorchCallback(object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                    on = enabled
                }
            }, Runner.main)
            Thread.sleep(150) // give the first callback a moment to report the current state
        }
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return "No flashlight found"
        val target = when (mode) {
            "on" -> true
            "off" -> false
            else -> !on
        }
        cm.setTorchMode(id, target)
        on = target
        return null
    }
}

object Speaker {
    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var ready = false

    fun speak(ctx: Context, text: String): String? {
        if (text.isBlank()) return "Nothing to say"
        if (!ready) {
            val latch = CountDownLatch(1)
            Runner.main.post {
                tts?.shutdown()
                tts = TextToSpeech(ctx.applicationContext) { status ->
                    ready = status == TextToSpeech.SUCCESS
                    latch.countDown()
                }
            }
            if (!latch.await(10, TimeUnit.SECONDS) || !ready) return "No text-to-speech engine is available"
        }
        val engine = tts ?: return "Text-to-speech isn't ready"
        val done = CountDownLatch(1)
        val utteranceId = UUID.randomUUID().toString()
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) {
                if (id == utteranceId) done.countDown()
            }
            @Deprecated("Deprecated in Java")
            @Suppress("OVERRIDE_DEPRECATION")
            override fun onError(id: String?) {
                if (id == utteranceId) done.countDown()
            }
        })
        if (engine.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId) != TextToSpeech.SUCCESS) {
            return "Couldn't speak"
        }
        done.await(60, TimeUnit.SECONDS)
        return null
    }
}
