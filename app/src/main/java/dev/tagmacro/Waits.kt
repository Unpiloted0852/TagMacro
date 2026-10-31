package dev.tagmacro

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

/** Where a macro picks up again after a timed wait. */
class ParkedFrame(val macro: Macro, val next: Int, val depth: Int) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("macro", macro.toJson())
        put("next", next)
        put("depth", depth)
    }

    companion object {
        fun fromJson(o: JSONObject) =
            ParkedFrame(Macro.fromJson(o.getJSONObject("macro")), o.optInt("next"), o.optInt("depth"))
    }
}

/**
 * A macro paused in a long Wait, saved to disk so it survives the phone sleeping, the app being
 * killed and restarts. Holds a copy of the macro, so editing the original doesn't change a run
 * in progress. [frames] run in order, innermost first, for waits inside "Run another macro".
 */
class ParkedRun(
    val id: String,
    val name: String,
    val macroIds: Set<String>,
    val frames: List<ParkedFrame>,
    val wakeAt: Long,
    val source: String,
    val tagUid: String?,
    val failed: Boolean,
    val lines: List<String>
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("macroIds", JSONArray(macroIds.toList()))
        put("frames", JSONArray().apply { frames.forEach { put(it.toJson()) } })
        put("wakeAt", wakeAt)
        put("source", source)
        if (tagUid != null) put("tagUid", tagUid)
        put("failed", failed)
        put("lines", JSONArray(lines))
    }

    companion object {
        fun fromJson(o: JSONObject): ParkedRun {
            val ids = o.getJSONArray("macroIds")
            val frames = o.getJSONArray("frames")
            val lines = o.getJSONArray("lines")
            return ParkedRun(
                o.getString("id"), o.getString("name"),
                (0 until ids.length()).map { ids.getString(it) }.toSet(),
                (0 until frames.length()).map { ParkedFrame.fromJson(frames.getJSONObject(it)) },
                o.getLong("wakeAt"), o.optString("source"),
                if (o.has("tagUid")) o.getString("tagUid") else null,
                o.optBoolean("failed"),
                (0 until lines.length()).map { lines.getString(it) }
            )
        }
    }
}

/** The alarm that ends a timed wait, and the countdown notification that goes with it. */
object Waits {
    const val ACTION_WAKE = "dev.tagmacro.WAIT_WAKE"
    const val ACTION_STOP = "dev.tagmacro.WAIT_STOP"
    private const val CHANNEL = "waits"

    fun canExact(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || ctx.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true

    private fun pending(ctx: Context, action: String, id: String): PendingIntent = PendingIntent.getBroadcast(
        ctx, id.hashCode(),
        Intent(ctx, WaitReceiver::class.java).setAction(action).setData(Uri.parse("tagmacro://wait/$id")),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun schedule(ctx: Context, r: ParkedRun) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi = pending(ctx, ACTION_WAKE, r.id)
        try {
            if (canExact(ctx)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.wakeAt, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.wakeAt, pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.wakeAt, pi)
        }
        notify(ctx, r)
    }

    /** Alarms are lost on restart and force stop. An alarm already overdue fires straight away. */
    fun rescheduleAll(ctx: Context) = Store.parked().forEach { schedule(ctx, it) }

    fun clear(ctx: Context, id: String) {
        ctx.getSystemService(AlarmManager::class.java)?.cancel(pending(ctx, ACTION_WAKE, id))
        ctx.getSystemService(NotificationManager::class.java)?.cancel(id.hashCode())
    }

    private fun notify(ctx: Context, r: ParkedRun) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Macro countdowns", NotificationManager.IMPORTANCE_LOW)
        )
        val open = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.let {
            PendingIntent.getActivity(ctx, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        val stop = Notification.Action.Builder(
            Icon.createWithResource(ctx, R.drawable.ic_launcher_foreground), "Stop", pending(ctx, ACTION_STOP, r.id)
        ).build()
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(r.name)
            .setContentText("Next step at " + java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(r.wakeAt))
            .setWhen(r.wakeAt)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(stop)
            .build()
        nm.notify(r.id.hashCode(), n)
    }

    /** "5 h", "1 h 30 min", "2 min", "45 s". */
    fun formatDuration(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return listOfNotNull(
            if (h > 0) "$h h" else null,
            if (m > 0) "$m min" else null,
            if (s > 0 && h == 0L) "$s s" else null
        ).joinToString(" ").ifEmpty { "0 s" }
    }
}

class WaitReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.data?.lastPathSegment
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> Waits.rescheduleAll(context)
            Waits.ACTION_STOP -> if (id != null) Runner.stopParked(context, id)
            Waits.ACTION_WAKE -> if (id != null) {
                // Keeps the phone awake for the rest of the macro, up to Android's limit for a receiver.
                val result = goAsync()
                val done = java.util.concurrent.atomic.AtomicBoolean(false)
                val finish = { if (done.compareAndSet(false, true)) result.finish() }
                Runner.main.postDelayed({ finish() }, 50_000)
                Runner.resume(context, id) { finish() }
            }
        }
    }
}
