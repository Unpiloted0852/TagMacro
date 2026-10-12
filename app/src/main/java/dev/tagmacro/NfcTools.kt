package dev.tagmacro

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.tech.MifareClassic
import android.nfc.tech.MifareUltralight
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import java.nio.charset.Charset

class PendingRecord(val label: String, val record: NdefRecord)

/**
 * Tags that were just read, written, erased, formatted or locked inside the app.
 * TriggerActivity won't run anything for them until they've been lifted away, so writing a tag
 * never fires its old macro or the one just written.
 */
object TagGuard {
    private val until = HashMap<String, Long>()

    /** Held while the tag stays on the phone. Capped in case the removal callback never arrives. */
    @Synchronized
    fun holdUntilRemoved(uid: String) {
        until[uid] = System.currentTimeMillis() + 60_000
    }

    /** The tag was lifted away: allow it again after a short grace period. */
    @Synchronized
    fun release(uid: String, graceMs: Long) {
        until[uid] = System.currentTimeMillis() + graceMs
    }

    @Synchronized
    fun isHeld(uid: String): Boolean {
        val t = until[uid] ?: return false
        if (System.currentTimeMillis() > t) {
            until.remove(uid)
            return false
        }
        return true
    }
}

object NfcTools {
    /** MIME type of the record this app writes to trigger tags. */
    const val MIME = "application/vnd.dev.tagmacro"

    fun uid(tag: Tag): String = tag.id.toHex()

    fun prettyUid(hex: String): String = hex.chunked(2).joinToString(":")

    /** Trigger codes stored on the tag (from the data Android read when the tag was tapped). */
    fun tokensOn(tag: Tag): List<String> {
        val msg = Ndef.get(tag)?.cachedNdefMessage ?: return emptyList()
        return tokensIn(msg)
    }

    fun tokensIn(msg: NdefMessage): List<String> = msg.records
        .filter { it.tnf == NdefRecord.TNF_MIME_MEDIA && it.toMimeType() == MIME }
        .map { String(it.payload, Charsets.US_ASCII).trim() }

    fun triggerRecord(token: String): NdefRecord = NdefRecord.createMime(MIME, token.toByteArray(Charsets.US_ASCII))

    fun appRecord(pkg: String): NdefRecord = NdefRecord.createApplicationRecord(pkg)

    fun textRecord(text: String): NdefRecord = NdefRecord.createTextRecord("en", text)

    fun uriRecord(uri: String): NdefRecord = NdefRecord.createUri(uri)

    fun vcardRecord(name: String, phone: String, email: String): NdefRecord {
        val sb = StringBuilder("BEGIN:VCARD\r\nVERSION:3.0\r\n")
        sb.append("FN:").append(name.trim()).append("\r\n")
        if (phone.isNotBlank()) sb.append("TEL:").append(phone.trim()).append("\r\n")
        if (email.isNotBlank()) sb.append("EMAIL:").append(email.trim()).append("\r\n")
        sb.append("END:VCARD\r\n")
        return NdefRecord.createMime("text/vcard", sb.toString().toByteArray(Charsets.UTF_8))
    }

    const val WIFI_MIME = "application/vnd.wfa.wsc"

    /**
     * Wi-Fi network record in the Wi-Fi Alliance "Wi-Fi Simple Configuration" format. Android
     * offers to join the network when such a tag is tapped; iPhones don't read this format.
     * security: "wpa2" (WPA2/WPA3 personal), "wpa" (WPA/WPA2 mixed) or "open".
     */
    fun wifiRecord(ssid: String, password: String, security: String): NdefRecord =
        NdefRecord.createMime(WIFI_MIME, wifiPayload(ssid, password, security))

    fun wifiPayload(ssid: String, password: String, security: String): ByteArray {
        val (auth, encr) = when (security) {
            "open" -> 0x0001 to 0x0001       // Open, no encryption
            "wpa" -> 0x0022 to 0x000C        // WPA-PSK | WPA2-PSK, TKIP | AES
            else -> 0x0020 to 0x0008         // WPA2-PSK, AES
        }
        val inner = java.io.ByteArrayOutputStream()
        fun tlv(out: java.io.ByteArrayOutputStream, type: Int, value: ByteArray) {
            out.write(type shr 8); out.write(type and 0xFF)
            out.write(value.size shr 8); out.write(value.size and 0xFF)
            out.write(value)
        }
        fun u16(v: Int) = byteArrayOf((v shr 8).toByte(), (v and 0xFF).toByte())
        tlv(inner, 0x1026, byteArrayOf(1))                                  // Network Index
        tlv(inner, 0x1045, ssid.toByteArray(Charsets.UTF_8))                // SSID
        tlv(inner, 0x1003, u16(auth))                                      // Authentication Type
        tlv(inner, 0x100F, u16(encr))                                      // Encryption Type
        tlv(inner, 0x1027, if (security == "open") ByteArray(0) else password.toByteArray(Charsets.UTF_8)) // Network Key
        tlv(inner, 0x1020, ByteArray(6) { 0xFF.toByte() })                 // MAC Address: any
        val outer = java.io.ByteArrayOutputStream()
        tlv(outer, 0x100E, inner.toByteArray())                            // Credential
        return outer.toByteArray()
    }

    /** Reads the network name and security from a Wi-Fi record, for display. Never shows the password. */
    fun describeWifi(payload: ByteArray): String {
        var ssid = "?"
        var auth = -1
        fun walk(b: ByteArray, from: Int, to: Int) {
            var i = from
            while (i + 4 <= to) {
                val type = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
                val len = ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
                val start = i + 4
                if (start + len > to) return
                when (type) {
                    0x100E -> walk(b, start, start + len)
                    0x1045 -> ssid = String(b, start, len, Charsets.UTF_8)
                    0x1003 -> if (len == 2) auth = ((b[start].toInt() and 0xFF) shl 8) or (b[start + 1].toInt() and 0xFF)
                }
                i = start + len
            }
        }
        walk(payload, 0, payload.size)
        val sec = when {
            auth == 0x0001 -> "open"
            auth and 0x0020 != 0 -> "WPA2"
            auth and 0x0002 != 0 -> "WPA"
            else -> "secured"
        }
        return "Wi-Fi network \"$ssid\" ($sec)"
    }

    /** Human-readable report, similar to NFC Tools' "Read" screen. Does I/O: call off the main thread. */
    fun describe(tag: Tag, macros: List<Macro>): String {
        val sb = StringBuilder()
        val uid = uid(tag)
        sb.append("Serial number: ").append(prettyUid(uid)).append('\n')
        sb.append("Technologies: ").append(tag.techList.joinToString { it.substringAfterLast('.') }).append('\n')

        MifareUltralight.get(tag)?.let {
            val t = when (it.type) {
                MifareUltralight.TYPE_ULTRALIGHT -> "MIFARE Ultralight / NTAG"
                MifareUltralight.TYPE_ULTRALIGHT_C -> "MIFARE Ultralight C"
                else -> "MIFARE Ultralight family"
            }
            sb.append("Chip family: ").append(t).append('\n')
        }
        MifareClassic.get(tag)?.let {
            sb.append("Chip family: MIFARE Classic, ").append(it.size).append(" bytes\n")
        }

        val ndef = Ndef.get(tag)
        if (ndef != null) {
            try {
                ndef.connect()
                sb.append("NDEF type: ").append(ndef.type.substringAfterLast('.')).append('\n')
                sb.append("Capacity: ").append(ndef.maxSize).append(" bytes\n")
                sb.append("Writable: ").append(if (ndef.isWritable) "yes" else "no (locked)").append('\n')
                sb.append("Can be locked: ").append(if (ndef.canMakeReadOnly()) "yes" else "no").append('\n')
                val msg = ndef.ndefMessage
                if (msg == null) {
                    sb.append("\nThe tag is empty.\n")
                } else {
                    sb.append("Used: ").append(msg.byteArrayLength).append(" bytes\n\n")
                    msg.records.forEachIndexed { i, r ->
                        sb.append("Record ").append(i + 1).append(": ").append(describeRecord(r)).append('\n')
                    }
                }
            } finally {
                runCatching { ndef.close() }
            }
        } else if (NdefFormatable.get(tag) != null) {
            sb.append("\nNot formatted yet. Writing to it will format it.\n")
        } else {
            sb.append("\nThis tag doesn't support NDEF, so it can't be written.\n")
        }

        val byUid = macros.filter { it.trigger == TriggerType.NFC_UID && normaliseUid(it.tagUid) == uid }
        val tokens = tokensOn(tag)
        val byToken = macros.filter { it.trigger == TriggerType.NFC_TOKEN && it.token in tokens }
        val matched = (byUid + byToken).distinctBy { it.id }
        sb.append('\n')
        if (matched.isEmpty()) sb.append("No macro uses this tag.")
        else sb.append("Runs: ").append(matched.joinToString { "\"${it.name}\"" })
        return sb.toString()
    }

    fun describeRecord(r: NdefRecord): String = try {
        when {
            r.tnf == NdefRecord.TNF_EMPTY -> "Empty"
            r.tnf == NdefRecord.TNF_WELL_KNOWN && r.type.contentEquals(NdefRecord.RTD_TEXT) ->
                "Text: \"${parseText(r.payload)}\""
            r.tnf == NdefRecord.TNF_WELL_KNOWN && r.type.contentEquals(NdefRecord.RTD_URI) ->
                "Link: ${r.toUri()}"
            r.tnf == NdefRecord.TNF_ABSOLUTE_URI -> "URI: ${r.toUri()}"
            r.tnf == NdefRecord.TNF_MIME_MEDIA -> {
                val mime = r.toMimeType() ?: "unknown"
                when {
                    mime == MIME -> "TagMacro trigger"
                    mime == WIFI_MIME -> describeWifi(r.payload)
                    mime.startsWith("text/") -> "$mime: ${String(r.payload, Charsets.UTF_8).take(200)}"
                    else -> "$mime (${r.payload.size} bytes)"
                }
            }
            r.tnf == NdefRecord.TNF_EXTERNAL_TYPE && String(r.type, Charsets.US_ASCII) == "android.com:pkg" ->
                "Opens Android app: ${String(r.payload, Charsets.US_ASCII)}"
            else -> "Other (TNF ${r.tnf}, ${r.payload.size} bytes)"
        }
    } catch (e: Exception) {
        "Unreadable record"
    }

    private fun parseText(payload: ByteArray): String {
        if (payload.isEmpty()) return ""
        val status = payload[0].toInt()
        val charset: Charset = if (status and 0x80 != 0) Charsets.UTF_16 else Charsets.UTF_8
        val langLen = status and 0x3F
        if (1 + langLen > payload.size) return ""
        return String(payload, 1 + langLen, payload.size - 1 - langLen, charset)
    }

    /** Writes a message, formatting the tag first if needed. Does I/O: call off the main thread. */
    fun write(tag: Tag, message: NdefMessage): String {
        val size = message.byteArrayLength
        val ndef = Ndef.get(tag)
        if (ndef != null) {
            try {
                ndef.connect()
                if (!ndef.isWritable) return "This tag is locked (read-only)."
                if (size > ndef.maxSize) return "Too much data: needs $size bytes, the tag holds ${ndef.maxSize}."
                ndef.writeNdefMessage(message)
                return "Written: $size of ${ndef.maxSize} bytes used."
            } finally {
                runCatching { ndef.close() }
            }
        }
        val fmt = NdefFormatable.get(tag)
        if (fmt != null) {
            try {
                fmt.connect()
                fmt.format(message)
                return "Formatted and written ($size bytes)."
            } finally {
                runCatching { fmt.close() }
            }
        }
        return "This tag doesn't support NDEF, so it can't be written."
    }

    fun erase(tag: Tag): String {
        val empty = NdefMessage(arrayOf(NdefRecord(NdefRecord.TNF_EMPTY, null, null, null)))
        val result = write(tag, empty)
        return if (result.startsWith("Written") || result.startsWith("Formatted")) "Erased." else result
    }

    fun format(tag: Tag): String {
        val fmt = NdefFormatable.get(tag)
            ?: return if (Ndef.get(tag) != null) "Already formatted. Use Erase to clear it." else
                "This tag can't be formatted for NDEF."
        try {
            fmt.connect()
            fmt.format(null)
            return "Formatted."
        } finally {
            runCatching { fmt.close() }
        }
    }

    fun lock(tag: Tag): String {
        val ndef = Ndef.get(tag) ?: return "This tag has no NDEF data to lock."
        try {
            ndef.connect()
            if (!ndef.isWritable) return "Already locked."
            if (!ndef.canMakeReadOnly()) return "This tag type can't be locked."
            return if (ndef.makeReadOnly()) "Locked permanently." else "The tag refused to lock."
        } finally {
            runCatching { ndef.close() }
        }
    }
}
