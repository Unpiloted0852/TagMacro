package dev.tagmacro

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Gets the phone's position with Android's own LocationManager, so no Google libraries are
 * involved. Only used when you tap "Use my current location" or a message contains {location}.
 */
object Locator {

    fun hasPermission(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /**
     * Asks every enabled location source at once and returns the first fix, or the most recent
     * known position if none arrives in time. Blocking: never call on the main thread.
     */
    @SuppressLint("MissingPermission")
    fun current(ctx: Context, timeoutMs: Long = 12_000): Location? {
        if (!hasPermission(ctx)) return null
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        val candidates = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31 && lm.hasProvider(LocationManager.FUSED_PROVIDER)) {
            candidates.add(LocationManager.FUSED_PROVIDER)
        }
        candidates.add(LocationManager.GPS_PROVIDER)
        candidates.add(LocationManager.NETWORK_PROVIDER)
        val providers = candidates.filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) return null

        val latch = CountDownLatch(1)
        val lock = Any()
        var fix: Location? = null
        fun accept(loc: Location?) {
            if (loc == null) return
            synchronized(lock) { if (fix == null) fix = loc }
            latch.countDown()
        }

        val cancel = CancellationSignal()
        val listeners = mutableListOf<LocationListener>()
        for (p in providers) {
            try {
                if (Build.VERSION.SDK_INT >= 30) {
                    lm.getCurrentLocation(p, cancel, ctx.mainExecutor) { accept(it) }
                } else {
                    val l = object : LocationListener {
                        override fun onLocationChanged(location: Location) = accept(location)
                        override fun onProviderEnabled(provider: String) {}
                        override fun onProviderDisabled(provider: String) {}
                        @Deprecated("Deprecated in Java")
                        @Suppress("OVERRIDE_DEPRECATION")
                        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                    }
                    listeners.add(l)
                    @Suppress("DEPRECATION")
                    lm.requestSingleUpdate(p, l, Looper.getMainLooper())
                }
            } catch (e: Exception) {
                // provider unavailable on this phone; try the others
            }
        }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        cancel.cancel()
        listeners.forEach { runCatching { lm.removeUpdates(it) } }

        synchronized(lock) { fix }?.let { return it }
        return providers
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    fun format(lat: Double, lon: Double): String = String.format(Locale.US, "%.6f,%.6f", lat, lon)

    fun mapsLink(loc: Location): String = "https://maps.google.com/?q=" + format(loc.latitude, loc.longitude)

    /**
     * Reads a position from "51.5007, -0.1246", "geo:51.5,-0.12", or a pasted Google Maps /
     * Apple Maps link. Returns null if it can't find valid coordinates.
     */
    fun parse(text: String): Pair<Double, Double>? {
        val t = text.replace("%2C", ",", ignoreCase = true).replace("%20", " ")
        val num = """(-?\d{1,3}(?:\.\d+)?)"""
        val patterns = listOf(
            Regex("""@$num,\s*$num"""),                                      // .../@51.5,-0.12,17z
            Regex("""[?&](?:q|ll|sll|query|center|daddr|destination)=$num,\s*$num"""),
            Regex("""!3d$num!4d$num"""),                                     // Google place data
            Regex("""^\s*(?:geo:)?$num\s*,\s*$num""")                        // plain "lat, lon"
        )
        for (p in patterns) {
            val m = p.find(t) ?: continue
            val lat = m.groupValues[1].toDoubleOrNull() ?: continue
            val lon = m.groupValues[2].toDoubleOrNull() ?: continue
            if (lat in -90.0..90.0 && lon in -180.0..180.0) return lat to lon
        }
        return null
    }
}
