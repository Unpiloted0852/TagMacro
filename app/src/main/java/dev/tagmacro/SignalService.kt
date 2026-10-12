package dev.tagmacro

import android.accessibilityservice.AccessibilityService
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The only component with screen access. It operates Signal and WhatsApp. Safeguards:
 *  - Android only sends it events from the Signal and WhatsApp packages (set in
 *    accessibility_config.xml and re-applied from Settings in onServiceConnected).
 *  - It does nothing unless a macro has just armed it with a send request, or you asked for a
 *    one-off screen capture from the Settings tab.
 *  - It ignores any screen that doesn't belong to Signal or WhatsApp, and waits (never clicks)
 *    while any other window, such as a system permission prompt, is in front.
 *  - It keeps nothing on disk. A capture lives in memory until the app process ends.
 *  - Separately, the "Lock the screen" action asks it to press the system lock button
 *    (performGlobalAction). That reads nothing from the screen.
 */
class SignalService : AccessibilityService() {

    companion object {
        @Volatile var instance: SignalService? = null
        @Volatile var captureRequested = false
        @Volatile var lastCapture: String = ""

        fun isEnabled(ctx: Context): Boolean {
            val enabled = Settings.Secure.getString(
                ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val cn = ComponentName(ctx, SignalService::class.java)
            val names = setOf(cn.flattenToString(), cn.flattenToShortString())
            return enabled.split(':').any { entry -> names.any { it.equals(entry, ignoreCase = true) } }
        }

        /** Called from the macro thread. Blocks until sent, failed, or timed out. Returns an error or null. */
        fun sendBlocking(ctx: Context, contact: String, message: String): String? {
            val svc = instance ?: return "Turn on the TagMacro accessibility service first (Settings tab)"
            if (contact.isBlank()) return "No chat name set"
            if (message.isEmpty()) return "Message is empty"
            val s = Store.settings()
            val launch = ctx.packageManager.getLaunchIntentForPackage(s.signalPackage)
                ?: return "Signal (${s.signalPackage}) isn't installed"
            val job = SignalJob(contact.trim(), message, s, System.currentTimeMillis() + s.signalTimeoutSec * 1000L)
            Runner.main.post { svc.begin(job) }
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                svc.startActivity(launch)
            } catch (e: Exception) {
                Runner.main.post { svc.abort(job, "Couldn't open Signal") }
                return "Couldn't open Signal: ${e.message}"
            }
            if (!job.latch.await(s.signalTimeoutSec + 5L, TimeUnit.SECONDS)) {
                Runner.main.post { svc.abort(job, "Timed out") }
                return "Timed out waiting for Signal"
            }
            return job.error
        }

        /**
         * Sends a WhatsApp message, or starts sharing live location, to a phone number.
         * The chat is opened with WhatsApp's click-to-chat link, which also pre-fills the text box.
         * For live location the pre-filled text is a random marker: seeing it proves the right chat
         * opened, and it is cleared before anything else happens.
         */
        fun whatsappBlocking(
            ctx: Context, phone: String, message: String?, live: Boolean, duration: String, comment: String
        ): String? {
            val svc = instance ?: return "Turn on the TagMacro accessibility service first (Settings tab)"
            val digits = phone.filter { it.isDigit() }
            if (digits.length < 7) return "The phone number looks incomplete. Use international format with the country code."
            if (!live && message.isNullOrEmpty()) return "Message is empty"
            val s = Store.settings()
            val prefill = if (live) "TagMacro-" + newToken().take(10) else message.orEmpty()
            val timeout = if (live) s.waLiveTimeoutSec else s.signalTimeoutSec
            val job = WaJob(s, System.currentTimeMillis() + timeout * 1000L, digits, prefill, live, duration, comment)
            Runner.main.post { svc.begin(job) }
            val openError = svc.openWhatsAppChat(s.waPackage, digits, prefill)
            if (openError != null) {
                Runner.main.post { svc.abort(job, openError) }
                return openError
            }
            if (!job.latch.await(timeout + 5L, TimeUnit.SECONDS)) {
                Runner.main.post { svc.abort(job, "Timed out") }
                return "Timed out waiting for WhatsApp"
            }
            return job.error
        }
    }

    /** One request from a macro. The macro thread waits on [latch]; [error] is null on success. */
    abstract class Job(val s: AppSettings, val deadline: Long) {
        val latch = CountDownLatch(1)
        @Volatile var error: String? = null
        var stage = "waiting for the app to open"
        val startedAt = System.currentTimeMillis()
    }

    class SignalJob(val contact: String, val message: String, s: AppSettings, deadline: Long) : Job(s, deadline) {
        var chatVerified = false
        var backs = 0
        var chatClickAt = 0L
        var textAttempts = 0
        var sendClickAt = 0L
        var sendAttempts = 0
    }

    enum class WaPhase { OPEN, CLEAR, ATTACH, LOCATION, LIVE, DURATION, COMMENT, SEND, VERIFY }

    class WaJob(
        s: AppSettings, deadline: Long,
        val digits: String, val prefill: String, val live: Boolean,
        val duration: String, val comment: String
    ) : Job(s, deadline) {
        var phase = WaPhase.OPEN
        var phaseAt = startedAt
        var retries = 0
        var lastDismissAt = 0L
        var sendClickAt = 0L
        var sendAttempts = 0
    }

    private val handler = Handler(Looper.getMainLooper())
    private var job: Job? = null
    private var pending = false
    private val tick = Runnable {
        pending = false
        step()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        refreshPackageFilter()
    }

    private fun watchedPackages(): Array<String> {
        val s = Store.settings()
        return arrayOf(s.signalPackage, s.waPackage)
    }

    /** Re-applies the package filter after the Signal or WhatsApp package setting changes. */
    fun refreshPackageFilter() {
        val info = serviceInfo ?: return
        info.packageNames = watchedPackages()
        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (job != null) schedule(80)
        if (captureRequested) {
            val root = rootInActiveWindow ?: return
            if (root.packageName?.toString() in watchedPackages()) capture(root)
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        shutdown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun shutdown() {
        job?.let { finish(it, "The accessibility service was turned off") }
        if (instance === this) instance = null
    }

    // ---------- job control (main thread) ----------

    private fun begin(j: Job) {
        job?.let { finish(it, "Replaced by a newer send request") }
        job = j
        schedule(400)
    }

    private fun abort(j: Job, msg: String) {
        if (job === j) finish(j, msg)
    }

    private fun finish(j: Job, error: String?) {
        j.error = error
        if (job === j) job = null
        handler.removeCallbacks(tick)
        pending = false
        if (error == null && j.s.goHomeAfterSend) performGlobalAction(GLOBAL_ACTION_HOME)
        j.latch.countDown()
    }

    private fun schedule(delayMs: Long) {
        if (pending) return
        pending = true
        handler.postDelayed(tick, delayMs)
    }

    private fun step() {
        val j = job ?: return
        val now = System.currentTimeMillis()
        if (now > j.deadline) {
            finish(j, "Timed out while ${j.stage}")
            return
        }
        when (j) {
            is SignalJob -> signalStep(j, now)
            is WaJob -> waStep(j, now)
        }
    }

    // ---------- Signal ----------

    private fun signalStep(j: SignalJob, now: Long) {
        val root = rootInActiveWindow
        if (root == null || root.packageName?.toString() != j.s.signalPackage) {
            j.stage = "waiting for Signal to open"
            schedule(300)
            return
        }
        val compose = findCompose(root)
        if (compose == null) handleChatList(j, root, now) else handleChat(j, root, compose, now)
    }

    private fun handleChatList(j: SignalJob, root: AccessibilityNodeInfo, now: Long) {
        if (j.chatClickAt > 0 && now - j.chatClickAt < 2500) {
            j.stage = "waiting for the chat to open"
            schedule(250)
            return
        }
        val node = findContactInList(root, j)
        if (node == null) {
            j.stage = "looking for \"${j.contact}\" in the chat list"
            schedule(400)
            return
        }
        if (click(node)) {
            j.chatClickAt = now
            j.stage = "opening the chat"
        }
        schedule(600)
    }

    private fun handleChat(j: SignalJob, root: AccessibilityNodeInfo, compose: AccessibilityNodeInfo, now: Long) {
        if (!j.chatVerified) {
            if (titleMatches(root, compose, j)) {
                j.chatVerified = true
            } else if (j.s.backOutOfWrongChat && j.backs < 4) {
                j.backs++
                j.chatClickAt = 0
                j.stage = "leaving a different chat"
                performGlobalAction(GLOBAL_ACTION_BACK)
                schedule(700)
                return
            } else {
                finish(j, "The open chat doesn't appear to be \"${j.contact}\"")
                return
            }
        }

        if (j.sendClickAt == 0L) {
            if (currentText(compose) != j.message) {
                if (j.textAttempts >= 3) {
                    finish(j, "Couldn't type into the message box")
                    return
                }
                j.textAttempts++
                val args = Bundle()
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, j.message)
                compose.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                j.stage = "typing the message"
                schedule(400)
                return
            }
            val send = findSend(root, j.s)
            if (send == null) {
                j.stage = "looking for the send button"
                schedule(400)
                return
            }
            if (click(send)) {
                j.sendClickAt = now
                j.stage = "checking the message was sent"
                schedule(700)
            } else {
                schedule(400)
            }
            return
        }

        // After pressing send, the message box should no longer hold our text.
        if (currentText(compose) != j.message) {
            finish(j, null)
            return
        }
        if (now - j.sendClickAt > 2500) {
            if (j.sendAttempts < 1) {
                j.sendAttempts++
                j.sendClickAt = 0L
                schedule(200)
            } else {
                finish(j, "Pressed send, but the text is still in the message box")
            }
            return
        }
        schedule(300)
    }

    // ---------- WhatsApp ----------

    /** Opens a WhatsApp chat by phone number with text pre-filled. Returns an error or null. */
    fun openWhatsAppChat(pkg: String, digits: String, text: String): String? {
        val links = listOf(
            "https://wa.me/$digits?text=" + Uri.encode(text),
            "whatsapp://send?phone=$digits&text=" + Uri.encode(text)
        )
        for (link in links) {
            try {
                startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                return null
            } catch (e: ActivityNotFoundException) {
                // try the next link form
            } catch (e: SecurityException) {
                return "Android blocked opening WhatsApp: ${e.message}"
            }
        }
        return "WhatsApp ($pkg) isn't installed, or it didn't accept the chat link"
    }

    private fun list(csv: String) = csv.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /** WhatsApp windows, active one first. Empty unless WhatsApp is the app in front. */
    private fun waRoots(pkg: String): List<AccessibilityNodeInfo> {
        val active = rootInActiveWindow ?: return emptyList()
        if (active.packageName?.toString() != pkg) return emptyList()
        val out = mutableListOf(active)
        try {
            for (w in windows) {
                val r = w.root ?: continue
                if (r.packageName?.toString() == pkg && r != active) out.add(r)
            }
        } catch (e: Exception) {
            // window list unavailable; the active window is enough in most cases
        }
        return out
    }

    private fun matchesLabel(n: AccessibilityNodeInfo, labels: List<String>): Boolean {
        val desc = n.contentDescription?.toString()?.trim().orEmpty()
        val txt = n.text?.toString()?.trim().orEmpty()
        return labels.any { it.equals(desc, ignoreCase = true) || it.equals(txt, ignoreCase = true) }
    }

    private fun findLabelled(
        roots: List<AccessibilityNodeInfo>, labels: List<String>, ids: List<String>
    ): AccessibilityNodeInfo? {
        for (r in roots) {
            val hit = walk(r).firstOrNull { n ->
                if (!n.isVisibleToUser || !n.isEnabled || n.isEditable) return@firstOrNull false
                val id = n.viewIdResourceName?.substringAfter(":id/").orEmpty()
                matchesLabel(n, labels) || (id.isNotEmpty() && id in ids)
            }
            if (hit != null) return hit
        }
        return null
    }

    private fun anyTextContains(roots: List<AccessibilityNodeInfo>, needles: List<String>): Boolean =
        roots.any { r ->
            walk(r).any { n ->
                val t = (n.text?.toString().orEmpty() + " " + n.contentDescription?.toString().orEmpty())
                needles.any { t.contains(it, ignoreCase = true) }
            }
        }

    private fun setText(n: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun advance(j: WaJob, next: WaPhase) {
        j.phase = next
        j.phaseAt = System.currentTimeMillis()
    }

    private fun waStep(j: WaJob, now: Long) {
        val s = j.s
        val roots = waRoots(s.waPackage)
        if (roots.isEmpty()) {
            j.stage = if (now - j.startedAt < 4000) "waiting for WhatsApp to open"
            else "waiting for WhatsApp to be in front (another window, such as a permission prompt, may be covering it)"
            schedule(300)
            return
        }
        if (now - j.startedAt < 1200) { // let WhatsApp switch away from any chat that was already open
            schedule(300)
            return
        }
        if (anyTextContains(roots, list(s.waNotOnWhatsApp))) {
            finish(j, "That number isn't on WhatsApp")
            return
        }
        if (j.live) waLiveStep(j, roots, now) else waMessageStep(j, roots, now)
    }

    private fun waMessageStep(j: WaJob, roots: List<AccessibilityNodeInfo>, now: Long) {
        val compose = findCompose(roots[0])
        if (j.sendClickAt == 0L) {
            if (compose == null) {
                j.stage = "waiting for the chat to open"
                schedule(400)
                return
            }
            // Only send once WhatsApp has put OUR text in the box: that proves the right chat is open.
            if (currentText(compose).trim() != j.prefill.trim()) {
                j.stage = "waiting for WhatsApp to fill in the message"
                schedule(400)
                return
            }
            val send = findLabelled(roots, list(j.s.waSendLabels), list(j.s.waSendIds))
            if (send == null) {
                j.stage = "looking for the send button"
                schedule(400)
                return
            }
            if (click(send)) {
                j.sendClickAt = now
                j.stage = "checking the message was sent"
            }
            schedule(700)
            return
        }
        if (compose != null && currentText(compose).trim() != j.prefill.trim()) {
            finish(j, null)
            return
        }
        if (now - j.sendClickAt > 3000) {
            if (j.sendAttempts < 1) {
                j.sendAttempts++
                j.sendClickAt = 0L
            } else {
                finish(j, "Pressed send, but the message is still in the box")
                return
            }
        }
        schedule(300)
    }

    private fun waLiveStep(j: WaJob, roots: List<AccessibilityNodeInfo>, now: Long) {
        val s = j.s
        when (j.phase) {
            WaPhase.OPEN -> {
                j.stage = "waiting for the chat to open"
                val compose = findCompose(roots[0])
                if (compose == null || !currentText(compose).contains(j.prefill)) {
                    schedule(400)
                    return
                }
                setText(compose, "") // right chat confirmed: remove the marker
                advance(j, WaPhase.CLEAR)
                schedule(400)
            }
            WaPhase.CLEAR -> {
                j.stage = "clearing the message box"
                val compose = findCompose(roots[0])
                if (compose != null && currentText(compose).contains(j.prefill)) {
                    if (now - j.phaseAt > 3000) {
                        finish(j, "Couldn't clear the message box, so stopped before sharing")
                        return
                    }
                    setText(compose, "")
                    schedule(400)
                    return
                }
                advance(j, WaPhase.ATTACH)
                schedule(300)
            }
            WaPhase.ATTACH -> clickPhase(j, roots, now, list(s.waAttachLabels), list(s.waAttachIds),
                WaPhase.LOCATION, "opening the attach menu")
            WaPhase.LOCATION -> clickPhase(j, roots, now, list(s.waLocationLabels), emptyList(),
                WaPhase.LIVE, "choosing Location")
            WaPhase.LIVE -> clickPhase(j, roots, now, list(s.waLiveLabels), emptyList(),
                WaPhase.DURATION, "choosing Share live location")
            WaPhase.DURATION -> {
                val labels = list(s.waDurationLabels)
                val idx = when (j.duration) { "15m" -> 0; "8h" -> 2; else -> 1 }
                val label = labels.getOrNull(idx)
                if (label == null) {
                    finish(j, "The live-location duration labels in Settings need three entries")
                    return
                }
                clickPhase(j, roots, now, listOf(label), emptyList(), WaPhase.COMMENT, "choosing how long to share")
            }
            WaPhase.COMMENT -> {
                if (j.comment.isNotBlank()) {
                    val field = roots.firstNotNullOfOrNull { r -> walk(r).firstOrNull { it.isEditable && it.isVisibleToUser } }
                    if (field != null) setText(field, j.comment)
                }
                advance(j, WaPhase.SEND)
                schedule(400)
            }
            WaPhase.SEND -> clickPhase(j, roots, now, list(s.waSendLabels), list(s.waSendIds),
                WaPhase.VERIFY, "pressing send")
            WaPhase.VERIFY -> {
                j.stage = "checking the live location appeared in the chat"
                if (findCompose(roots[0]) != null && anyTextContains(roots, list(s.waLiveConfirm))) {
                    finish(j, null)
                    return
                }
                if (now - j.phaseAt > 8000) {
                    finish(j, "Pressed send, but couldn't see the live location in the chat. Check WhatsApp.")
                    return
                }
                schedule(500)
            }
        }
    }

    private fun previous(p: WaPhase): WaPhase? = when (p) {
        WaPhase.LOCATION -> WaPhase.ATTACH
        WaPhase.LIVE -> WaPhase.LOCATION
        WaPhase.DURATION -> WaPhase.LIVE
        else -> null
    }

    /**
     * Clicks the element for this step and moves on. If it isn't there yet, dismisses one-time
     * WhatsApp notices ("Continue", "OK"), and if a step stays stuck, retries the step before it.
     */
    private fun clickPhase(
        j: WaJob, roots: List<AccessibilityNodeInfo>, now: Long,
        labels: List<String>, ids: List<String>, next: WaPhase, stage: String
    ) {
        j.stage = stage
        val target = findLabelled(roots, labels, ids)
        if (target != null && click(target)) {
            advance(j, next)
            schedule(900)
            return
        }
        val notice = findLabelled(roots, list(j.s.waDismissLabels), emptyList())
        if (notice != null && now - j.lastDismissAt > 1500) {
            click(notice)
            j.lastDismissAt = now
            schedule(800)
            return
        }
        val back = previous(j.phase)
        if (back != null && now - j.phaseAt > 6000 && j.retries < 2) {
            j.retries++
            advance(j, back)
        }
        schedule(400)
    }

    // ---------- screen reading helpers ----------

    private fun bounds(n: AccessibilityNodeInfo): Rect = Rect().also { n.getBoundsInScreen(it) }

    private fun currentText(n: AccessibilityNodeInfo): String {
        if (Build.VERSION.SDK_INT >= 26 && n.isShowingHintText) return ""
        return n.text?.toString() ?: ""
    }

    private fun label(n: AccessibilityNodeInfo): String =
        (n.text?.toString() ?: n.contentDescription?.toString() ?: "").trim()

    private fun norm(s: String) = s.trim().replace(Regex("\\s+"), " ")

    private fun nameMatches(candidate: String, j: SignalJob): Boolean {
        val a = norm(candidate)
        val b = norm(j.contact)
        return if (j.s.matchExact) a.equals(b, ignoreCase = true) else a.contains(b, ignoreCase = true)
    }

    private fun walk(root: AccessibilityNodeInfo, limit: Int = 2500): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty() && out.size < limit) {
            val n = queue.removeFirst()
            out.add(n)
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return out
    }

    /** The message box: an editable field in the lower half of the screen. */
    private fun findCompose(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val screen = bounds(root)
        val half = screen.top + screen.height() / 2
        return walk(root).firstOrNull { it.isEditable && it.isVisibleToUser && bounds(it).top > half }
    }

    /** The chat title: the contact's name near the top, above the message box. */
    private fun titleMatches(root: AccessibilityNodeInfo, compose: AccessibilityNodeInfo, j: SignalJob): Boolean {
        val screen = bounds(root)
        val limit = screen.top + (screen.height() * 0.22f).toInt()
        val composeTop = bounds(compose).top
        return root.findAccessibilityNodeInfosByText(j.contact).any { n ->
            if (n.isEditable || !n.isVisibleToUser) return@any false
            val b = bounds(n)
            b.top < limit && b.bottom <= composeTop && nameMatches(label(n), j)
        }
    }

    private fun findContactInList(root: AccessibilityNodeInfo, j: SignalJob): AccessibilityNodeInfo? =
        root.findAccessibilityNodeInfosByText(j.contact).firstOrNull { n ->
            !n.isEditable && n.isVisibleToUser && nameMatches(label(n), j)
        }

    private fun findSend(root: AccessibilityNodeInfo, s: AppSettings): AccessibilityNodeInfo? {
        val labels = s.sendLabels.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val ids = s.sendIds.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        return walk(root).firstOrNull { n ->
            if (!n.isVisibleToUser || !n.isEnabled || n.isEditable) return@firstOrNull false
            val desc = n.contentDescription?.toString()?.trim().orEmpty()
            val txt = n.text?.toString()?.trim().orEmpty()
            val id = n.viewIdResourceName?.substringAfter(":id/").orEmpty()
            labels.any { it.equals(desc, ignoreCase = true) || it.equals(txt, ignoreCase = true) } ||
                (id.isNotEmpty() && ids.any { it == id })
        }
    }

    /** Clicks the node, or the nearest clickable parent. */
    private fun click(n: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = n
        repeat(8) {
            val c = cur ?: return false
            if (c.isClickable && c.isEnabled) return c.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            cur = c.parent
        }
        return false
    }

    // ---------- diagnostics ----------

    private fun capture(root: AccessibilityNodeInfo) {
        captureRequested = false
        val sb = StringBuilder()
        sb.append("Captured ").append(java.util.Date()).append('\n')
        sb.append("Package: ").append(root.packageName).append("\n\n")
        var count = 0
        fun dump(n: AccessibilityNodeInfo, depth: Int) {
            if (count++ > 600) return
            val b = bounds(n)
            sb.append("  ".repeat(depth.coerceAtMost(20)))
            sb.append(n.className?.toString()?.substringAfterLast('.') ?: "?")
            n.viewIdResourceName?.let { sb.append(" id=").append(it.substringAfter(":id/")) }
            n.text?.let { sb.append(" text=\"").append(it.toString().take(40)).append('"') }
            n.contentDescription?.let { sb.append(" desc=\"").append(it.toString().take(40)).append('"') }
            if (n.isClickable) sb.append(" [click]")
            if (n.isEditable) sb.append(" [edit]")
            sb.append(" (").append(b.left).append(',').append(b.top).append(")-(")
                .append(b.right).append(',').append(b.bottom).append(")\n")
            for (i in 0 until n.childCount) n.getChild(i)?.let { dump(it, depth + 1) }
        }
        dump(root, 0)
        lastCapture = sb.toString()
        Runner.toast(this, "TagMacro captured the Signal screen. See Settings.")
    }
}
