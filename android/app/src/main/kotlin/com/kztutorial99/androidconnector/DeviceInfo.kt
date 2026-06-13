package com.kztutorial99.androidconnector

import android.annotation.SuppressLint
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
import android.telephony.TelephonyManager
import com.google.gson.JsonObject
import java.io.RandomAccessFile
import java.net.Inet4Address
import java.net.NetworkInterface

object DeviceInfo {

    @SuppressLint("MissingPermission", "HardwareIds")
    fun collect(context: Context): JsonObject {
        val json = JsonObject()

        // ── Baterai ────────────────────────────────────────────────────────────
        val battIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: 100
        val battPct = if (level >= 0 && scale > 0) (level * 100 / scale).toString() else "--"
        val status = battIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        json.addProperty("battery", battPct)
        json.addProperty("batteryStatus", if (isCharging) "Charging" else "Discharging")

        // ── Info Perangkat ─────────────────────────────────────────────────────
        json.addProperty("model", "${Build.MANUFACTURER} ${Build.MODEL}")
        json.addProperty("androidVersion", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        json.addProperty("hostname", Build.HOST ?: "--")
        json.addProperty("kernel", System.getProperty("os.version") ?: "--")
        json.addProperty("screenState", "Active")
        json.addProperty("brand", Build.BRAND ?: "--")
        json.addProperty("device", Build.DEVICE ?: "--")
        json.addProperty("product", Build.PRODUCT ?: "--")
        json.addProperty("fingerprint", Build.FINGERPRINT?.take(60) ?: "--")

        // ── Jaringan ───────────────────────────────────────────────────────────
        json.addProperty("ip", getIPAddress())
        json.addProperty("networkType", getNetworkType(context))

        // ── SIM & Telepon ──────────────────────────────────────────────────────
        collectSimInfo(context, json)

        // ── Storage ────────────────────────────────────────────────────────────
        try {
            val stat = StatFs(Environment.getExternalStorageDirectory().path)
            val totalGb = stat.totalBytes / (1024f * 1024f * 1024f)
            val freeGb = stat.availableBytes / (1024f * 1024f * 1024f)
            json.addProperty("storage", "%.1f".format(totalGb - freeGb))
            json.addProperty("storageFree", "%.1f GB".format(freeGb))
        } catch (_: Exception) {
            json.addProperty("storage", "--")
            json.addProperty("storageFree", "--")
        }

        // ── RAM ────────────────────────────────────────────────────────────────
        try {
            var totalKb = 0L; var freeKb = 0L
            RandomAccessFile("/proc/meminfo", "r").use { reader ->
                repeat(5) {
                    val line = reader.readLine() ?: return@repeat
                    when {
                        line.startsWith("MemTotal:")     -> totalKb = line.split("\\s+".toRegex())[1].toLong()
                        line.startsWith("MemAvailable:") -> freeKb  = line.split("\\s+".toRegex())[1].toLong()
                    }
                }
            }
            json.addProperty("memTotal", "%.0f MB".format(totalKb / 1024f))
            json.addProperty("memFree",  "%.0f MB".format(freeKb / 1024f))
        } catch (_: Exception) {
            json.addProperty("memTotal", "--")
            json.addProperty("memFree", "--")
        }

        // ── CPU ────────────────────────────────────────────────────────────────
        json.addProperty("cpuUsage", getCpuUsage())

        // ── Uptime ─────────────────────────────────────────────────────────────
        val upMs = SystemClock.elapsedRealtime()
        json.addProperty("uptime", "${upMs / 3_600_000}h ${(upMs % 3_600_000) / 60_000}m")

        return json
    }

    @SuppressLint("MissingPermission", "HardwareIds")
    private fun collectSimInfo(context: Context, json: JsonObject) {
        try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

            // IMEI / Device ID
            val imei = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    tm.getImei(0) ?: tm.getImei(1) ?: tm.meid ?: "--"
                } else {
                    @Suppress("DEPRECATION")
                    tm.deviceId ?: "--"
                }
            } catch (_: Exception) { "--" }
            json.addProperty("imei", imei)

            // Nomor telepon (sering kosong kalau operator blokir)
            val phone = try { tm.line1Number?.takeIf { it.isNotBlank() } ?: "--" } catch (_: Exception) { "--" }
            json.addProperty("phoneNumber", phone)

            // Operator SIM
            json.addProperty("simOperator", tm.simOperatorName?.takeIf { it.isNotBlank() } ?: "--")
            json.addProperty("simCountry", tm.simCountryIso?.uppercase()?.takeIf { it.isNotBlank() } ?: "--")
            json.addProperty("networkOperator", tm.networkOperatorName?.takeIf { it.isNotBlank() } ?: "--")

            // SIM Serial (restricted Android 10+)
            val simSerial = try { tm.simSerialNumber?.takeIf { it.isNotBlank() } ?: "--" } catch (_: Exception) { "--" }
            json.addProperty("simSerial", simSerial)

            // Jumlah slot SIM
            val simSlots = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try { tm.phoneCount.toString() } catch (_: Exception) { "--" }
            } else "--"
            json.addProperty("simSlots", simSlots)

            // Status SIM
            val simState = when (tm.simState) {
                TelephonyManager.SIM_STATE_ABSENT       -> "Tidak ada SIM"
                TelephonyManager.SIM_STATE_PIN_REQUIRED -> "Butuh PIN"
                TelephonyManager.SIM_STATE_PUK_REQUIRED -> "Butuh PUK"
                TelephonyManager.SIM_STATE_READY        -> "Siap"
                TelephonyManager.SIM_STATE_NOT_READY    -> "Belum siap"
                TelephonyManager.SIM_STATE_UNKNOWN      -> "Tidak diketahui"
                else                                     -> "Lainnya"
            }
            json.addProperty("simState", simState)

            // Generasi jaringan (2G/3G/4G/5G)
            val netGen = try {
                @Suppress("DEPRECATION")
                when (tm.networkType) {
                    TelephonyManager.NETWORK_TYPE_GPRS,
                    TelephonyManager.NETWORK_TYPE_EDGE,
                    TelephonyManager.NETWORK_TYPE_CDMA,
                    TelephonyManager.NETWORK_TYPE_1xRTT  -> "2G"
                    TelephonyManager.NETWORK_TYPE_UMTS,
                    TelephonyManager.NETWORK_TYPE_HSDPA,
                    TelephonyManager.NETWORK_TYPE_HSUPA,
                    TelephonyManager.NETWORK_TYPE_HSPA,
                    TelephonyManager.NETWORK_TYPE_HSPAP,
                    TelephonyManager.NETWORK_TYPE_EVDO_0,
                    TelephonyManager.NETWORK_TYPE_EVDO_A,
                    TelephonyManager.NETWORK_TYPE_EVDO_B -> "3G"
                    TelephonyManager.NETWORK_TYPE_LTE    -> "4G LTE"
                    TelephonyManager.NETWORK_TYPE_NR     -> "5G"
                    else                                  -> "--"
                }
            } catch (_: Exception) { "--" }
            json.addProperty("networkGeneration", netGen)

            // Roaming
            json.addProperty("roaming", if (tm.isNetworkRoaming) "Ya" else "Tidak")

            // MCC + MNC (kode jaringan)
            val mccMnc = tm.networkOperator?.takeIf { it.length >= 5 }
                ?.let { "${it.take(3)}-${it.drop(3)}" } ?: "--"
            json.addProperty("mccMnc", mccMnc)

        } catch (_: Exception) {
            for (field in listOf("imei","phoneNumber","simOperator","simCountry","networkOperator",
                "simSerial","simSlots","simState","networkGeneration","roaming","mccMnc")) {
                json.addProperty(field, "--")
            }
        }
    }

    private fun getIPAddress(): String {
        return try {
            NetworkInterface.getNetworkInterfaces()?.toList()
                ?.flatMap { it.inetAddresses.toList() }
                ?.firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
                ?.hostAddress ?: "--"
        } catch (_: Exception) { "--" }
    }

    private fun getNetworkType(ctx: Context): String {
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return "None") ?: return "Unknown"
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)     -> "WiFi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile Data"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                else -> "Unknown"
            }
        } catch (_: Exception) { "--" }
    }

    private fun getCpuUsage(): String {
        return try {
            fun readCpu(): Pair<Long, Long> {
                RandomAccessFile("/proc/stat", "r").use { r ->
                    val parts = r.readLine().trim().split("\\s+".toRegex())
                    val values = parts.drop(1).take(8).map { it.toLong() }
                    return Pair(values[3] + values[4], values.sum())
                }
            }
            val (idle1, total1) = readCpu()
            Thread.sleep(400)
            val (idle2, total2) = readCpu()
            val dTotal = total2 - total1
            val dIdle = idle2 - idle1
            if (dTotal == 0L) return "--"
            "${((dTotal - dIdle) * 100 / dTotal).toInt()}%"
        } catch (_: Exception) { "--" }
    }
}
