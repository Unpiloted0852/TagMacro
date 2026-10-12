package dev.tagmacro

enum class FieldKind { TEXT, MULTILINE, NUMBER, CHOICE, APP, MACRO, PHONE, CONTACT_NAME, TIME }

class Field(
    val key: String,
    val label: String,
    val kind: FieldKind,
    val default: String = "",
    val required: Boolean = false,
    val hint: String = "",
    val options: List<Pair<String, String>> = emptyList(), // value to label
    val min: Double = Double.NEGATIVE_INFINITY,
    val max: Double = Double.POSITIVE_INFINITY
)

class ActionSpec(
    val type: String,
    val label: String,
    val fields: List<Field>,
    val summary: (Map<String, String>) -> String
)

object Specs {
    const val PLACEHOLDER_HINT =
        "Tap a button below to insert live values. {location} becomes a map link to where you are."

    /** Values that can be inserted into message text; replaced when the macro runs. */
    val PLACEHOLDERS = listOf("{time}", "{date}", "{location}", "{battery}", "{tag}", "{macro}")

    private fun p(m: Map<String, String>, k: String) = m[k].orEmpty()
    fun streamName(s: String) = when (s) {
        "ring" -> "Ringtone"
        "notification" -> "Notification"
        "alarm" -> "Alarm"
        "call" -> "Call"
        else -> "Media"
    }

    private fun short(s: String, n: Int = 40) = if (s.length > n) s.take(n - 1) + "…" else s

    val all: List<ActionSpec> = listOf(
        ActionSpec(
            "SIGNAL", "Send a Signal message",
            listOf(
                Field("contact", "Chat name", FieldKind.CONTACT_NAME, required = true,
                    hint = "Exactly as it appears in your Signal chat list. Choosing a contact fills in the name from your phone's contacts, which Signal normally uses. Pinning the chat makes it easier to find."),
                Field("message", "Message", FieldKind.MULTILINE, required = true, hint = PLACEHOLDER_HINT)
            )
        ) { "Signal to ${p(it, "contact")}: \"${short(p(it, "message"))}\"" },

        ActionSpec(
            "WHATSAPP", "Send a WhatsApp message",
            listOf(
                Field("phone", "Phone number", FieldKind.PHONE, required = true,
                    hint = "International format with the country code, like +44 7700 900123. Choosing a contact adds the country code for you."),
                Field("message", "Message", FieldKind.MULTILINE, required = true, hint = PLACEHOLDER_HINT)
            )
        ) { "WhatsApp to ${p(it, "phone")}: \"${short(p(it, "message"))}\"" },

        ActionSpec(
            "WHATSAPP_LIVE", "Share live location on WhatsApp",
            listOf(
                Field("phone", "Phone number", FieldKind.PHONE, required = true,
                    hint = "International format with the country code, like +44 7700 900123. Choosing a contact adds the country code for you."),
                Field("duration", "Share for", FieldKind.CHOICE, default = "1h",
                    options = listOf("15m" to "15 minutes", "1h" to "1 hour", "8h" to "8 hours")),
                Field("comment", "Comment (optional)", FieldKind.TEXT,
                    hint = "WhatsApp needs location permission granted beforehand; the macro can't answer a permission prompt. " + PLACEHOLDER_HINT)
            )
        ) {
            val d = when (p(it, "duration")) { "15m" -> "15 min"; "8h" -> "8 h"; else -> "1 h" }
            "Live location to ${p(it, "phone")} for $d"
        },

        ActionSpec(
            "SMS", "Send a text message (SMS)",
            listOf(
                Field("number", "Phone number", FieldKind.PHONE, required = true,
                    hint = "Choosing a contact fills this in."),
                Field("message", "Message", FieldKind.MULTILINE, required = true, hint = PLACEHOLDER_HINT),
                Field("mode", "How", FieldKind.CHOICE, default = "send",
                    options = listOf(
                        "send" to "Send it straight away (asks for SMS permission once)",
                        "compose" to "Open my messaging app with it ready, and I'll press send"
                    ),
                    hint = "Your carrier's normal text rates apply.")
            )
        ) {
            val how = if (p(it, "mode") == "compose") "Draft SMS" else "SMS"
            "$how to ${p(it, "number")}: \"${short(p(it, "message"))}\""
        },

        ActionSpec(
            "CALL", "Call a number",
            listOf(
                Field("number", "Phone number", FieldKind.PHONE, required = true,
                    hint = "Choosing a contact fills this in."),
                Field("mode", "How", FieldKind.CHOICE, default = "call",
                    options = listOf(
                        "call" to "Start the call straight away (asks for phone permission once)",
                        "dial" to "Open the dialer with the number, and I'll press call"
                    ))
            )
        ) { (if (p(it, "mode") == "dial") "Dial " else "Call ") + p(it, "number") },

        ActionSpec(
            "DIRECTIONS", "Directions to a place",
            listOf(
                Field("place", "Destination", FieldKind.TEXT, required = true,
                    hint = "An address, a place name, or coordinates like 51.5007, -0.1246."),
                Field("app", "Open in", FieldKind.CHOICE, default = "google",
                    options = listOf(
                        "google" to "Google Maps, with turn-by-turn directions",
                        "any" to "Any maps app (shows the place; you start navigation)"
                    )),
                Field("travel", "Travel by", FieldKind.CHOICE, default = "driving",
                    options = listOf("driving" to "Car", "walking" to "Walking", "bicycling" to "Bike", "transit" to "Public transport"),
                    hint = "Used by Google Maps only.")
            )
        ) { "Directions to ${short(p(it, "place"))}" },

        ActionSpec(
            "LOG_ENTRY", "Record it in the journal",
            listOf(
                Field("label", "What happened", FieldKind.TEXT, required = true,
                    hint = "For example: Fed the cat, Took medication, Arrived at work. The Journal shows the last time each one happened."),
                Field("note", "Note (optional)", FieldKind.TEXT, hint = PLACEHOLDER_HINT)
            )
        ) { "Journal: ${short(p(it, "label"))}" },

        ActionSpec(
            "WAIT", "Wait",
            listOf(Field("seconds", "Seconds", FieldKind.NUMBER, default = "2", required = true, min = 0.0, max = 3600.0))
        ) { "Wait ${p(it, "seconds")} s" },

        ActionSpec(
            "LAUNCH_APP", "Open an app",
            listOf(Field("package", "App", FieldKind.APP, required = true))
        ) { "Open ${p(it, "appLabel").ifEmpty { p(it, "package") }}" },

        ActionSpec(
            "OPEN_URL", "Open a link",
            listOf(Field("url", "Link", FieldKind.TEXT, required = true,
                hint = "https://…, tel:…, mailto:…, geo:… The app that opens it does any network access."))
        ) { "Open ${short(p(it, "url"))}" },

        ActionSpec(
            "TORCH", "Flashlight",
            listOf(Field("mode", "Flashlight", FieldKind.CHOICE, default = "toggle",
                options = listOf("on" to "Turn on", "off" to "Turn off", "toggle" to "Toggle")))
        ) { "Flashlight ${p(it, "mode")}" },

        ActionSpec(
            "VIBRATE", "Vibrate",
            listOf(Field("ms", "Milliseconds", FieldKind.NUMBER, default = "300", required = true, min = 1.0, max = 10000.0))
        ) { "Vibrate ${p(it, "ms")} ms" },

        ActionSpec(
            "TOAST", "Show a short on-screen message",
            listOf(Field("text", "Text", FieldKind.MULTILINE, required = true, hint = PLACEHOLDER_HINT))
        ) { "Show \"${short(p(it, "text"))}\"" },

        ActionSpec(
            "SPEAK", "Speak text aloud",
            listOf(Field("text", "Text", FieldKind.MULTILINE, required = true, hint = PLACEHOLDER_HINT))
        ) { "Say \"${short(p(it, "text"))}\"" },

        ActionSpec(
            "MEDIA_VOLUME", "Set a volume",
            listOf(
                Field("stream", "Which volume", FieldKind.CHOICE, default = "music",
                    options = listOf(
                        "music" to "Media",
                        "ring" to "Ringtone",
                        "notification" to "Notifications",
                        "alarm" to "Alarms",
                        "call" to "Calls"
                    )),
                Field("percent", "Volume (%)", FieldKind.NUMBER, default = "50", required = true, min = 0.0, max = 100.0,
                    hint = "Turning ringtone or notifications to 0 needs Do Not Disturb access.")
            )
        ) { "${streamName(p(it, "stream"))} volume ${p(it, "percent")}%" },

        ActionSpec(
            "RINGER", "Ringer mode",
            listOf(Field("mode", "Mode", FieldKind.CHOICE, default = "vibrate",
                options = listOf("normal" to "Sound", "vibrate" to "Vibrate", "silent" to "Silent"),
                hint = "Switching to or from Silent needs Do Not Disturb access (Settings tab)."))
        ) { "Ringer: ${p(it, "mode")}" },

        ActionSpec(
            "BRIGHTNESS", "Screen brightness",
            listOf(
                Field("mode", "Mode", FieldKind.CHOICE, default = "level",
                    options = listOf("level" to "Set a level (turns off adaptive brightness)", "auto" to "Adaptive brightness on")),
                Field("percent", "Level (%)", FieldKind.NUMBER, default = "50", required = true, min = 1.0, max = 100.0,
                    hint = "Needs \"Modify system settings\" (Settings tab). Android's slider isn't linear, so 50% here may not sit halfway along it.")
            )
        ) { if (p(it, "mode") == "auto") "Adaptive brightness on" else "Brightness ${p(it, "percent")}%" },

        ActionSpec(
            "SCREEN_TIMEOUT", "Screen timeout",
            listOf(Field("seconds", "Turn the screen off after", FieldKind.CHOICE, default = "60",
                options = listOf(
                    "15" to "15 seconds", "30" to "30 seconds", "60" to "1 minute", "120" to "2 minutes",
                    "300" to "5 minutes", "600" to "10 minutes", "1800" to "30 minutes"
                ),
                hint = "Needs \"Modify system settings\" (Settings tab)."))
        ) { "Screen timeout ${p(it, "seconds")} s" },

        ActionSpec(
            "AUTO_ROTATE", "Auto-rotate",
            listOf(Field("mode", "Auto-rotate", FieldKind.CHOICE, default = "toggle",
                options = listOf("on" to "Turn on", "off" to "Turn off", "toggle" to "Toggle"),
                hint = "Needs \"Modify system settings\" (Settings tab)."))
        ) { "Auto-rotate ${p(it, "mode")}" },

        ActionSpec(
            "MEDIA_CONTROL", "Media control",
            listOf(Field("key", "Button", FieldKind.CHOICE, default = "playpause",
                options = listOf(
                    "playpause" to "Play / pause", "play" to "Play", "pause" to "Pause",
                    "next" to "Next track", "previous" to "Previous track", "stop" to "Stop"
                ),
                hint = "Goes to whichever app is playing or last played media."))
        ) { "Media: ${p(it, "key")}" },

        ActionSpec(
            "SETTINGS_PANEL", "Show a settings panel",
            listOf(Field("panel", "Panel", FieldKind.CHOICE, default = "wifi",
                options = listOf(
                    "wifi" to "Wi-Fi", "internet" to "Internet (Wi-Fi and mobile data)",
                    "nfc" to "NFC", "volume" to "Volume", "bluetooth" to "Bluetooth settings"
                ),
                hint = "Android doesn't let apps switch Wi-Fi or Bluetooth themselves, so this opens the control for you to tap."))
        ) { "Show ${p(it, "panel")} panel" },

        ActionSpec(
            "LOCK_SCREEN", "Lock the screen",
            listOf()
        ) { "Lock the screen" },

        ActionSpec(
            "CLIPBOARD", "Copy text to the clipboard",
            listOf(Field("text", "Text", FieldKind.MULTILINE, required = true, hint = PLACEHOLDER_HINT))
        ) { "Copy \"${short(p(it, "text"))}\"" },

        ActionSpec(
            "ALARM", "Set an alarm",
            listOf(
                Field("time", "Time", FieldKind.TIME, default = "07:00", required = true),
                Field("label", "Label", FieldKind.TEXT)
            )
        ) {
            val t = p(it, "time").ifEmpty {
                "%d:%02d".format(p(it, "hour").toDoubleOrNull()?.toInt() ?: 0, p(it, "minute").toDoubleOrNull()?.toInt() ?: 0)
            }
            val label = p(it, "label")
            if (label.isBlank()) "Alarm at $t" else "Alarm at $t ($label)"
        },

        ActionSpec(
            "TIMER", "Start a timer",
            listOf(
                Field("seconds", "Seconds", FieldKind.NUMBER, default = "300", required = true, min = 1.0, max = 86400.0),
                Field("label", "Label", FieldKind.TEXT)
            )
        ) { "Timer ${p(it, "seconds")} s" },

        ActionSpec(
            "DND", "Do Not Disturb",
            listOf(Field("mode", "Mode", FieldKind.CHOICE, default = "priority",
                options = listOf(
                    "off" to "Off (allow everything)",
                    "priority" to "Priority only",
                    "alarms" to "Alarms only",
                    "none" to "Total silence"
                )))
        ) { "Do Not Disturb: ${p(it, "mode")}" },

        ActionSpec(
            "RUN_MACRO", "Run another macro",
            listOf(Field("macro", "Macro", FieldKind.MACRO, required = true))
        ) { "Run macro \"${p(it, "macroName").ifEmpty { "?" }}\"" }
    )

    /** Groups for the "Add action" picker, in display order: title to action types. */
    val categories: List<Pair<String, List<String>>> = listOf(
        "Messages and calls" to listOf("SIGNAL", "WHATSAPP", "WHATSAPP_LIVE", "SMS", "CALL"),
        "Sound and Do Not Disturb" to listOf("RINGER", "DND", "MEDIA_VOLUME", "MEDIA_CONTROL"),
        "Screen and device" to listOf("BRIGHTNESS", "SCREEN_TIMEOUT", "AUTO_ROTATE", "TORCH", "LOCK_SCREEN", "SETTINGS_PANEL"),
        "Apps, links and places" to listOf("LAUNCH_APP", "OPEN_URL", "DIRECTIONS"),
        "Time" to listOf("ALARM", "TIMER", "WAIT"),
        "Feedback and notes" to listOf("TOAST", "SPEAK", "VIBRATE", "CLIPBOARD", "LOG_ENTRY"),
        "Macros" to listOf("RUN_MACRO")
    )

    /** One-line explanations shown in the "Add action" picker. */
    val descriptions: Map<String, String> = mapOf(
        "SIGNAL" to "Types and sends a message in a Signal chat",
        "WHATSAPP" to "Sends a message to a phone number on WhatsApp",
        "WHATSAPP_LIVE" to "Starts sharing your live location in a WhatsApp chat",
        "SMS" to "Texts a number, or opens a ready-to-send message",
        "CALL" to "Phones a number, or opens it in the dialer",
        "RINGER" to "Sound, vibrate or silent",
        "DND" to "Priority only, alarms only, total silence, or off",
        "MEDIA_VOLUME" to "Media, ringtone, notification, alarm or call volume",
        "MEDIA_CONTROL" to "Play, pause, next or previous track",
        "BRIGHTNESS" to "A fixed level, or adaptive brightness",
        "SCREEN_TIMEOUT" to "How long before the screen turns off",
        "AUTO_ROTATE" to "Turn screen rotation on or off",
        "TORCH" to "Turn the flashlight on, off, or toggle it",
        "LOCK_SCREEN" to "Locks the phone immediately",
        "SETTINGS_PANEL" to "Pops up the Wi-Fi, internet, NFC or volume panel",
        "LAUNCH_APP" to "Opens any installed app",
        "OPEN_URL" to "Opens a web page or any link",
        "DIRECTIONS" to "Opens your maps app with directions",
        "ALARM" to "Adds an alarm to your clock app",
        "TIMER" to "Starts a countdown in your clock app",
        "WAIT" to "Pauses before the next action",
        "TOAST" to "A brief message at the bottom of the screen",
        "SPEAK" to "Reads text out loud",
        "VIBRATE" to "A short buzz, to confirm the tap",
        "CLIPBOARD" to "Puts text on the clipboard, ready to paste",
        "LOG_ENTRY" to "Notes the time something happened, for check-ins and habits",
        "RUN_MACRO" to "Runs all the actions of another macro"
    )

    fun of(type: String): ActionSpec? = all.firstOrNull { it.type == type }

    fun summary(a: Action): String = of(a.type)?.summary?.invoke(a.params) ?: "Unknown action ${a.type}"

    /** Returns an error message, or null if the values are acceptable. */
    fun validate(spec: ActionSpec, values: Map<String, String>): String? {
        for (f in spec.fields) {
            val v = values[f.key].orEmpty().trim()
            if (f.required && v.isEmpty()) return "\"${f.label}\" is required."
            if (f.kind == FieldKind.TIME && v.isNotEmpty() && Runner.parseHm(v) == null) {
                return "\"${f.label}\" must be a time like 07:30."
            }
            if (f.key == "number" && v.isNotEmpty() && v.count { it.isDigit() } < 3) {
                return "Enter a phone number."
            }
            if (f.key == "phone" && v.isNotEmpty()) {
                val digits = v.count { it.isDigit() }
                if (digits !in 7..15) return "Enter the full number with the country code, like +44 7700 900123."
            }
            if (f.kind == FieldKind.NUMBER && v.isNotEmpty()) {
                val d = v.toDoubleOrNull() ?: return "\"${f.label}\" must be a number."
                if (d < f.min || d > f.max) return "\"${f.label}\" must be between ${fmt(f.min)} and ${fmt(f.max)}."
            }
        }
        return null
    }

    private fun fmt(d: Double) = if (d == Math.floor(d)) d.toLong().toString() else d.toString()
}
