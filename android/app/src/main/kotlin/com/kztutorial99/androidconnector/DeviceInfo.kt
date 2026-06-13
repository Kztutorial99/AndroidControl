package com.kztutorial99.androidconnector

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import com.google.gson.JsonObject
import java.io.RandomAccessFile
import java.net.Inet4Address
import java.net.NetworkInterface

object DeviceInfo {

    fun collect(context: Context): JsonObject {
        val json = JsonObject()

        // Battery
        val battIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: 100
        val battPct = if (level >= 0 && scale > 0) (level * 100 / scale).toString() else "--"
        val status = battIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        json.addProperty("battery", battPct)
        json.addProperty("batteryStatus", if (isCharging) "Charging" else "Discharging")

        // Device
        json.addProperty("model", "${Build.MANUFACTURER} ${Build.MODEL}")
        json.addProperty("androidVersion", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        json.addProperty("hostname", Build.HOST ?: "--")
        json.addProperty("kernel", System.getProperty("os.version") ?: "--")
        json.addProperty("screenState", "Active")

        // Network
        json.addProperty("ip", getIPAddress())
        json.addProperty("networkType", getNetworkType(context))

        // External storage
        try {
            val stat = StatFs(Environment.getExternalStorageDirectory().path)
            val totalGb = stat.totalBytes / (1024f * 1024f * 1024f)
            val freeGb = stat.availableBytes / (1024f * 1024f * 1024f)
            val usedGb = totalGb - freeGb
            json.addProperty("storage", "%.1f".format(usedGb))
            json.addProperty("storageFree", "%.1f GB".format(freeGb))
        } catch (e: Exception) {
            json.addProperty("storage", "--")
            json.addProperty("storageFree", "--")
        }

        // RAM from /proc/meminfo
        try {
            var totalKb = 0L
            var freeKb = 0L
            RandomAccessFile("/proc/meminfo", "r").use { reader ->
                repeat(5) {
                    val line = reader.readLine() ?: return@repeat
                    when {
                        line.startsWith("MemTotal:") ->
                            totalKb = line.split("\\s+".toRegex())[1].toLong()
                        line.startsWith("MemAvailable:") ->
                            freeKb = line.split("\\s+".toRegex())[1].toLong()
                    }
                }
            }
            json.addProperty("memTotal", "%.0f MB".format(totalKb / 1024f))
            json.addProperty("memFree", "%.0f MB".format(freeKb / 1024f))
        } catch (e: Exception) {
            json.addProperty("memTotal", "--")
            json.addProperty("memFree", "--")
        }

        // CPU usage (two samples, 400ms apart)
        json.addProperty("cpuUsage", getCpuUsage())

        // Uptime
        val upMs = SystemClock.elapsedRealtime()
        val h = upMs / 3_600_000
        val m = (upMs % 3_600_000) / 60_000
        json.addProperty("uptime", "${h}h ${m}m")

        return json
    }

    private fun getIPAddress(): String {
        return try {
            NetworkInterface.getNetworkInterfaces()?.toList()
                ?.flatMap { it.inetAddresses.toList() }
                ?.firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
                ?.hostAddress ?: "--"
        } catch (e: Exception) { "--" }
    }

    private fun getNetworkType(ctx: Context): String {
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return "None") ?: return "Unknown"
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile Data"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                else -> "Unknown"
            }
        } catch (e: Exception) { "--" }
    }

    private fun getCpuUsage(): String {
        return try {
            fun readCpu(): Pair<Long, Long> {
                RandomAccessFile("/proc/stat", "r").use { r ->
                    val parts = r.readLine().trim().split("\\s+".toRegex())
                    val values = parts.drop(1).take(8).map { it.toLong() }
                    val idle = values[3] + values[4]
                    val total = values.sum()
                    return Pair(idle, total)
                }
            }
            val (idle1, total1) = readCpu()
            Thread.sleep(400)
            val (idle2, total2) = readCpu()
            val dTotal = total2 - total1
            val dIdle = idle2 - idle1
            if (dTotal == 0L) return "--"
            val usage = ((dTotal - dIdle) * 100 / dTotal).toInt()
            "$usage%"
        } catch (e: Exception) { "--" }
    }
}
