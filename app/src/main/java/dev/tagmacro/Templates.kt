package dev.tagmacro

/** Starting points offered by "New macro". Blank fields show as "Needs setup" in the editor. */
object Templates {

    /** [icon] is a Glyph name. */
    class Template(val title: String, val description: String, val icon: String, val build: () -> Macro)

    /** An action with the spec's defaults filled in, then [values] on top. */
    fun act(type: String, vararg values: Pair<String, String>): Action {
        val a = Action(type)
        Specs.of(type)?.fields?.forEach { f -> if (f.default.isNotEmpty()) a.params[f.key] = f.default }
        values.forEach { (k, v) -> a.params[k] = v }
        return a
    }

    private fun macro(name: String, vararg actions: Action) = Macro(name = name).apply { this.actions.addAll(actions) }

    /** The empty starting point, shown separately from the templates. */
    val blank = Template("Blank macro", "Start from scratch and add your own actions", "plus") { Macro() }

    val all: List<Template> = listOf(
        Template("Quiet time", "Silent ringer, Do Not Disturb, dim screen", "moon") {
            macro(
                "Quiet time",
                act("RINGER", "mode" to "silent"),
                act("DND", "mode" to "priority"),
                act("BRIGHTNESS", "mode" to "level", "percent" to "15")
            )
        },

        Template("Back to normal", "Ringer on, Do Not Disturb off, adaptive brightness", "sun") {
            macro(
                "Back to normal",
                act("DND", "mode" to "off"),
                act("RINGER", "mode" to "normal"),
                act("BRIGHTNESS", "mode" to "auto")
            )
        },

        Template("Bedtime", "7:00 alarm, Do Not Disturb, low brightness", "bell") {
            macro(
                "Bedtime",
                act("ALARM", "time" to "07:00", "label" to "Wake up"),
                act("DND", "mode" to "priority"),
                act("BRIGHTNESS", "mode" to "level", "percent" to "5")
            )
        },

        Template("Home safe (WhatsApp)", "Tell someone you're home, with the time", "home") {
            macro("Home safe", act("WHATSAPP", "message" to "Home safe at {time}"))
        },

        Template("Home safe (Signal)", "Tell someone you're home, with the time", "home") {
            macro("Home safe", act("SIGNAL", "message" to "Home safe at {time}"))
        },

        Template("Home safe (SMS)", "Text someone you're home, no messaging app needed", "message") {
            macro("Home safe", act("SMS", "message" to "Home safe at {time}"))
        },

        Template("Share my live location", "WhatsApp live location for 1 hour", "pin") {
            macro("Share live location", act("WHATSAPP_LIVE", "duration" to "1h"))
        },

        Template("I'm here", "Send a map link to where you are, on WhatsApp", "pin") {
            macro("I'm here", act("WHATSAPP", "message" to "I'm here: {location}"))
        },

        Template("Quick call", "Tap to phone someone, handy for family members", "phone") {
            macro("Call", act("CALL"))
        },

        Template("Check-in log", "Record when something happens, like feeding a pet", "book") {
            macro("Check-in", act("LOG_ENTRY", "label" to "Fed the cat"), act("VIBRATE", "ms" to "150"))
        },

        Template("In the car", "Open your maps app, then start music", "car") {
            macro(
                "In the car",
                act("LAUNCH_APP"),
                act("WAIT", "seconds" to "3"),
                act("MEDIA_CONTROL", "key" to "play")
            )
        },

        Template("Kitchen timer", "10-minute timer", "clock") {
            macro("Kitchen timer", act("TIMER", "seconds" to "600", "label" to "Kitchen timer"))
        }
    )
}
