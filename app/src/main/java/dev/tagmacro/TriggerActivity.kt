package dev.tagmacro

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle

/**
 * Has no visible screen. Android opens it when:
 *  - a tag carrying this app's trigger record is tapped (NDEF_DISCOVERED),
 *  - any tag is tapped while a serial-number macro exists (TECH_DISCOVERED via the UidTrigger alias),
 *  - a home screen shortcut is used (ACTION_RUN, checked against the macro's secret code).
 */
class TriggerActivity : Activity() {

    companion object {
        const val ACTION_RUN = "dev.tagmacro.RUN"
        const val EXTRA_MACRO = "macro"
        const val EXTRA_TOKEN = "token"
    }

    private val queue = ArrayDeque<Pair<Macro, String>>()
    private var tagUid: String? = null
    private var source = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        queue.clear()
        when (intent?.action) {
            NfcAdapter.ACTION_NDEF_DISCOVERED,
            NfcAdapter.ACTION_TECH_DISCOVERED,
            NfcAdapter.ACTION_TAG_DISCOVERED -> handleTag(intent)
            ACTION_RUN -> handleShortcut(intent)
        }
        next()
    }

    private fun tagFrom(intent: Intent): Tag? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)

    private fun handleTag(intent: Intent) {
        val tag = tagFrom(intent) ?: return
        val uid = NfcTools.uid(tag)
        if (TagGuard.isHeld(uid)) return // just written or scanned in the app; wait for a fresh tap
        tagUid = uid
        source = "NFC tag ${NfcTools.prettyUid(uid)}"
        val tokens = NfcTools.tokensOn(tag)
        val all = Store.macros()
        val matches = all.filter { m ->
            (m.trigger == TriggerType.NFC_TOKEN && m.token in tokens) ||
                (m.trigger == TriggerType.NFC_UID && m.tagUid.isNotBlank() && normaliseUid(m.tagUid) == uid)
        }
        if (matches.isEmpty()) {
            if (tokens.isNotEmpty()) {
                Runner.toast(this, "This tag's macro was deleted or given a new code. Rewrite the tag.")
            }
            return
        }
        matches.forEach { queue.add(it to source) }
    }

    private fun handleShortcut(intent: Intent) {
        val id = intent.getStringExtra(EXTRA_MACRO) ?: return
        val token = intent.getStringExtra(EXTRA_TOKEN) ?: return
        val m = Store.macro(id)
        if (m == null || m.token != token) {
            Runner.toast(this, "This shortcut is out of date. Add it to the home screen again.")
            return
        }
        tagUid = null
        source = "Home screen shortcut"
        queue.add(m to source)
    }

    /**
     * Runs queued macros one at a time, waiting for each to finish before starting the next.
     *
     * This activity deliberately stays on screen (invisible) for the whole run. Android blocks
     * activity starts from an app with no visible window, so finishing early would stop actions
     * like "Open an app", "Set an alarm" and "Start a timer" from working past the first one.
     */
    private fun next() {
        val item = queue.removeFirstOrNull()
        if (item == null) {
            finish()
            return
        }
        val (m, src) = item
        if (m.askFirst) {
            AlertDialog.Builder(this)
                .setTitle("Run \"${m.name}\"?")
                .setMessage(m.actions.filter { it.enabled }.joinToString("\n") { Specs.summary(it) })
                .setPositiveButton("Run") { _, _ -> start(m, src) }
                .setNegativeButton("Cancel") { _, _ -> next() }
                .setOnCancelListener { next() }
                .show()
        } else {
            start(m, src)
        }
    }

    private fun start(m: Macro, src: String) {
        Runner.run(this, m, src, tagUid, true) {
            if (!isFinishing && !isDestroyed) next()
        }
    }
}
