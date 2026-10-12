package dev.tagmacro

import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.UUID

/** How a macro gets started. */
enum class TriggerType(val label: String) {
    NFC_TOKEN("NFC tag written by this app"),
    NFC_UID("Any NFC tag, matched by its serial number"),
    MANUAL("No tag (Run button or home screen shortcut only)")
}

fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it.toInt() and 0xFF) }

/** Random 128-bit code stored on trigger tags and in shortcuts. Not guessable by other apps. */
fun newToken(): String {
    val b = ByteArray(16)
    SecureRandom().nextBytes(b)
    return b.toHex()
}

/** Normalises a serial number typed as "04:A2:..." or "04a2..." to "04A2...". */
fun normaliseUid(s: String): String = s.filter { it.isLetterOrDigit() }.uppercase()

class Action(
    var type: String,
    val params: MutableMap<String, String> = mutableMapOf(),
    var enabled: Boolean = true
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("type", type)
        put("enabled", enabled)
        val p = JSONObject()
        for ((k, v) in params) p.put(k, v)
        put("params", p)
    }

    companion object {
        fun fromJson(o: JSONObject): Action {
            val p = mutableMapOf<String, String>()
            val po = o.optJSONObject("params")
            if (po != null) {
                val keys = po.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    p[k] = po.optString(k)
                }
            }
            return Action(o.optString("type"), p, o.optBoolean("enabled", true))
        }
    }
}

class Macro(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "New macro",
    var enabled: Boolean = true,
    var trigger: TriggerType = TriggerType.NFC_TOKEN,
    var token: String = newToken(),
    var tagUid: String = "",
    var askFirst: Boolean = false,
    var cooldownSec: Int = 3,
    var stopOnError: Boolean = true,
    var useTimeWindow: Boolean = false,
    var windowStart: String = "08:00",
    var windowEnd: String = "22:00",
    /** java.util.Calendar day numbers: SUNDAY = 1 ... SATURDAY = 7 */
    val days: MutableSet<Int> = (1..7).toMutableSet(),
    val actions: MutableList<Action> = mutableListOf()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("enabled", enabled)
        put("trigger", trigger.name)
        put("token", token)
        put("tagUid", tagUid)
        put("askFirst", askFirst)
        put("cooldownSec", cooldownSec)
        put("stopOnError", stopOnError)
        put("useTimeWindow", useTimeWindow)
        put("windowStart", windowStart)
        put("windowEnd", windowEnd)
        put("days", JSONArray().apply { days.sorted().forEach { put(it) } })
        put("actions", JSONArray().apply { actions.forEach { put(it.toJson()) } })
    }

    fun deepCopy(): Macro = fromJson(toJson())

    companion object {
        fun fromJson(o: JSONObject): Macro {
            val m = Macro(
                id = o.optString("id", UUID.randomUUID().toString()),
                name = o.optString("name", "Macro"),
                enabled = o.optBoolean("enabled", true),
                trigger = runCatching { TriggerType.valueOf(o.optString("trigger")) }
                    .getOrDefault(TriggerType.MANUAL),
                token = o.optString("token", "").ifEmpty { newToken() },
                tagUid = o.optString("tagUid", ""),
                askFirst = o.optBoolean("askFirst", false),
                cooldownSec = o.optInt("cooldownSec", 3),
                stopOnError = o.optBoolean("stopOnError", true),
                useTimeWindow = o.optBoolean("useTimeWindow", false),
                windowStart = o.optString("windowStart", "08:00"),
                windowEnd = o.optString("windowEnd", "22:00")
            )
            val d = o.optJSONArray("days")
            if (d != null) {
                m.days.clear()
                for (i in 0 until d.length()) m.days.add(d.optInt(i))
            }
            val a = o.optJSONArray("actions")
            if (a != null) {
                for (i in 0 until a.length()) {
                    val ao = a.optJSONObject(i) ?: continue
                    m.actions.add(Action.fromJson(ao))
                }
            }
            return m
        }
    }
}

class AppSettings(
    var signalPackage: String = SIGNAL_PACKAGE,
    var sendLabels: String = "Send, Send message",
    var sendIds: String = "send_button, conversation_input_send_button",
    var matchExact: Boolean = true,
    var backOutOfWrongChat: Boolean = true,
    var goHomeAfterSend: Boolean = false,
    var signalTimeoutSec: Int = 20,
    var launchSettleMs: Int = 1200,
    var toastOnError: Boolean = true,
    var toastOnSuccess: Boolean = false,
    var ignoreTagsWhileOpen: Boolean = true,
    var waPackage: String = WHATSAPP_PACKAGE,
    var waSendLabels: String = "Send",
    var waSendIds: String = "send",
    var waAttachLabels: String = "Attach",
    var waAttachIds: String = "input_attach_button",
    var waLocationLabels: String = "Location",
    var waLiveLabels: String = "Share live location",
    var waDurationLabels: String = "15 minutes, 1 hour, 8 hours",
    var waDismissLabels: String = "Continue, OK, Got it",
    var waLiveConfirm: String = "Stop sharing",
    var waNotOnWhatsApp: String = "isn't on WhatsApp, is not on WhatsApp",
    var waLiveTimeoutSec: Int = 40
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("signalPackage", signalPackage)
        put("sendLabels", sendLabels)
        put("sendIds", sendIds)
        put("matchExact", matchExact)
        put("backOutOfWrongChat", backOutOfWrongChat)
        put("goHomeAfterSend", goHomeAfterSend)
        put("signalTimeoutSec", signalTimeoutSec)
        put("launchSettleMs", launchSettleMs)
        put("toastOnError", toastOnError)
        put("toastOnSuccess", toastOnSuccess)
        put("ignoreTagsWhileOpen", ignoreTagsWhileOpen)
        put("waPackage", waPackage)
        put("waSendLabels", waSendLabels)
        put("waSendIds", waSendIds)
        put("waAttachLabels", waAttachLabels)
        put("waAttachIds", waAttachIds)
        put("waLocationLabels", waLocationLabels)
        put("waLiveLabels", waLiveLabels)
        put("waDurationLabels", waDurationLabels)
        put("waDismissLabels", waDismissLabels)
        put("waLiveConfirm", waLiveConfirm)
        put("waNotOnWhatsApp", waNotOnWhatsApp)
        put("waLiveTimeoutSec", waLiveTimeoutSec)
    }

    companion object {
        const val SIGNAL_PACKAGE = "org.thoughtcrime.securesms"
        const val WHATSAPP_PACKAGE = "com.whatsapp"

        fun fromJson(o: JSONObject): AppSettings = AppSettings(
            signalPackage = o.optString("signalPackage", SIGNAL_PACKAGE).ifBlank { SIGNAL_PACKAGE },
            sendLabels = o.optString("sendLabels", "Send, Send message"),
            sendIds = o.optString("sendIds", "send_button, conversation_input_send_button"),
            matchExact = o.optBoolean("matchExact", true),
            backOutOfWrongChat = o.optBoolean("backOutOfWrongChat", true),
            goHomeAfterSend = o.optBoolean("goHomeAfterSend", false),
            signalTimeoutSec = o.optInt("signalTimeoutSec", 20).coerceIn(5, 120),
            launchSettleMs = o.optInt("launchSettleMs", 1200).coerceIn(0, 10000),
            toastOnError = o.optBoolean("toastOnError", true),
            toastOnSuccess = o.optBoolean("toastOnSuccess", false),
            ignoreTagsWhileOpen = o.optBoolean("ignoreTagsWhileOpen", true),
            waPackage = o.optString("waPackage", WHATSAPP_PACKAGE).ifBlank { WHATSAPP_PACKAGE },
            waSendLabels = o.optString("waSendLabels", "Send"),
            waSendIds = o.optString("waSendIds", "send"),
            waAttachLabels = o.optString("waAttachLabels", "Attach"),
            waAttachIds = o.optString("waAttachIds", "input_attach_button"),
            waLocationLabels = o.optString("waLocationLabels", "Location"),
            waLiveLabels = o.optString("waLiveLabels", "Share live location"),
            waDurationLabels = o.optString("waDurationLabels", "15 minutes, 1 hour, 8 hours"),
            waDismissLabels = o.optString("waDismissLabels", "Continue, OK, Got it"),
            waLiveConfirm = o.optString("waLiveConfirm", "Stop sharing"),
            waNotOnWhatsApp = o.optString("waNotOnWhatsApp", "isn't on WhatsApp, is not on WhatsApp"),
            waLiveTimeoutSec = o.optInt("waLiveTimeoutSec", 40).coerceIn(10, 180)
        )
    }
}

class LogEntry(
    val time: Long,
    val macro: String,
    val source: String,
    val ok: Boolean,
    val lines: List<String>
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("time", time)
        put("macro", macro)
        put("source", source)
        put("ok", ok)
        put("lines", JSONArray().apply { lines.forEach { put(it) } })
    }

    companion object {
        fun fromJson(o: JSONObject): LogEntry {
            val arr = o.optJSONArray("lines")
            val lines = mutableListOf<String>()
            if (arr != null) for (i in 0 until arr.length()) lines.add(arr.optString(i))
            return LogEntry(
                o.optLong("time"), o.optString("macro"), o.optString("source"),
                o.optBoolean("ok"), lines
            )
        }
    }
}

/** One "Record it in the journal" entry. */
class JournalEntry(val time: Long, val label: String, val note: String, val macro: String) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("time", time)
        put("label", label)
        put("note", note)
        put("macro", macro)
    }

    companion object {
        fun fromJson(o: JSONObject) = JournalEntry(
            o.optLong("time"), o.optString("label"), o.optString("note"), o.optString("macro")
        )
    }
}
