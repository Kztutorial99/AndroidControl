package com.kztutorial99.androidconnector

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import rikka.shizuku.Shizuku
import java.util.concurrent.TimeUnit

class ConnectorService : Service() {

    companion object {
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
    private val gson = Gson()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private var serverUrl = ""
    private var token = ""
    private var polling = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var pollThread: Thread? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        serverUrl = intent?.getStringExtra("SERVER_URL") ?: ""
        token = intent?.getStringExtra("TOKEN") ?: ""

        if (serverUrl.isBlank() || token.isBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }

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
        statusCallback?.invoke("Disconnected", false)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─────────────────────────────────────────
    //  POLLING LOOP
    // ─────────────────────────────────────────

    private fun startPolling() {
        polling = true
        pollThread = Thread {
            log("🟢 Polling started → $serverUrl")
            var failCount = 0
            while (polling) {
                try {
                    sendHeartbeat()
                    val cmd = pollCommand()
                    if (cmd != null) {
                        val (cmdId, command, extra) = cmd
                        log("📥 CMD: $command")
                        val (result, type) = executeCommand(command, extra)
                        sendResult(cmdId, command, result, type)
                    }
                    failCount = 0
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    failCount++
                    log("⚠️ Error: ${e.message}")
                    if (failCount > 5) updateNotification("Server unreachable…", false)
                }
                try {
                    Thread.sleep(2000)
                } catch (e: InterruptedException) {
                    break
                }
            }
            log("🔴 Polling stopped")
        }.also { it.isDaemon = true; it.start() }
    }

    // ─────────────────────────────────────────
    //  HEARTBEAT — sends device info to server
    // ─────────────────────────────────────────

    private fun sendHeartbeat() {
        val deviceJson = DeviceInfo.collect(this)
        val body = JsonObject().apply {
            addProperty("token", token)
            add("device", deviceJson)
        }
        post("$serverUrl/api/device/heartbeat", body.toString())
        updateNotification("Connected · polling every 2s", true)
        statusCallback?.invoke("Connected", true)
    }

    // ─────────────────────────────────────────
    //  POLL — fetch pending command
    // ─────────────────────────────────────────

    private data class PendingCmd(val id: String, val command: String, val extra: String?)

    private fun pollCommand(): PendingCmd? {
        val resp = get("$serverUrl/api/device/poll?token=$token") ?: return null
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
            // ── File listing ──
            cmd.startsWith("ls_json:") -> {
                val path = cmd.removePrefix("ls_json:")
                val (type, result) = FileOperations.listDir(path)
                Pair(result, type)
            }

            // ── Read file as base64 ──
            cmd.startsWith("read_b64:") -> {
                val path = cmd.removePrefix("read_b64:")
                Pair(FileOperations.readFileBase64(path), "command_result")
            }

            // ── Read file as text ──
            cmd.startsWith("read_text:") -> {
                val path = cmd.removePrefix("read_text:")
                val lines = cmd.substringAfter("?lines=", "500").toIntOrNull() ?: 500
                Pair(FileOperations.readFileText(path, lines), "command_result")
            }

            // ── Write file from base64 ──
            cmd.startsWith("write_b64:") -> {
                val path = cmd.removePrefix("write_b64:")
                if (extra == null) Pair("ERROR: No data provided", "command_result")
                else Pair(FileOperations.writeFileBase64(path, extra), "command_result")
            }

            // ── Write text file ──
            cmd.startsWith("write_text:") -> {
                val path = cmd.removePrefix("write_text:")
                if (extra == null) Pair("ERROR: No content provided", "command_result")
                else Pair(FileOperations.writeFileText(path, extra), "command_result")
            }

            // ── Create directory ──
            cmd.startsWith("mkdir:") -> {
                Pair(FileOperations.makeDir(cmd.removePrefix("mkdir:")), "command_result")
            }

            // ── Delete file/dir ──
            cmd.startsWith("delete:") -> {
                Pair(FileOperations.deleteFile(cmd.removePrefix("delete:")), "command_result")
            }

            // ── Move / rename ──
            cmd.startsWith("move:") -> {
                val parts = cmd.removePrefix("move:").split(":")
                if (parts.size < 2) Pair("ERROR: move requires src:dst", "command_result")
                else Pair(FileOperations.moveFile(parts[0], parts[1]), "command_result")
            }

            // ── File info ──
            cmd.startsWith("file_info:") -> {
                Pair(FileOperations.getFileInfo(cmd.removePrefix("file_info:")), "command_result")
            }

            // ── Shell command (Runtime.exec) ──
            cmd.startsWith("shell:") -> {
                val command = cmd.removePrefix("shell:")
                Pair(runShell(command), "command_result")
            }

            // ── Shizuku elevated command ──
            cmd.startsWith("shizuku:") -> {
                val command = cmd.removePrefix("shizuku:")
                Pair(runShizuku(command), "command_result")
            }

            // ── pm grant permission ──
            cmd.startsWith("pm_grant:") -> {
                val parts = cmd.removePrefix("pm_grant:").split(":")
                if (parts.size < 2) Pair("ERROR: pm_grant requires pkg:permission", "command_result")
                else Pair(runShizuku("pm grant ${parts[0]} ${parts[1]}"), "command_result")
            }

            // ── pm revoke permission ──
            cmd.startsWith("pm_revoke:") -> {
                val parts = cmd.removePrefix("pm_revoke:").split(":")
                if (parts.size < 2) Pair("ERROR: pm_revoke requires pkg:permission", "command_result")
                else Pair(runShizuku("pm revoke ${parts[0]} ${parts[1]}"), "command_result")
            }

            // ── List installed packages ──
            cmd == "pm_list_packages" || cmd.startsWith("pm_list") -> {
                Pair(runShizuku("pm list packages -3"), "command_result")
            }

            // ── settings get/put ──
            cmd.startsWith("settings_put:") -> {
                val parts = cmd.removePrefix("settings_put:").split(":", limit = 3)
                if (parts.size < 3) Pair("ERROR: settings_put requires namespace:key:value", "command_result")
                else Pair(runShizuku("settings put ${parts[0]} ${parts[1]} ${parts[2]}"), "command_result")
            }

            cmd.startsWith("settings_get:") -> {
                val parts = cmd.removePrefix("settings_get:").split(":", limit = 2)
                if (parts.size < 2) Pair("ERROR: settings_get requires namespace:key", "command_result")
                else Pair(runShizuku("settings get ${parts[0]} ${parts[1]}"), "command_result")
            }

            // ── Device info ──
            cmd == "device_info" -> {
                Pair(DeviceInfo.collect(this).toString(), "command_result")
            }

            // ── Ping / pong ──
            cmd == "ping" -> Pair("pong", "command_result")

            // ── Shizuku status ──
            cmd == "shizuku_status" -> {
                Pair(getShizukuStatus(), "command_result")
            }

            else -> Pair("ERROR: Unknown command: $cmd", "command_result")
        }
    }

    // ─────────────────────────────────────────
    //  SHELL EXECUTION (Runtime.exec — app UID)
    // ─────────────────────────────────────────

    private fun runShell(cmd: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            val out = process.inputStream.bufferedReader().readText()
            val err = process.errorStream.bufferedReader().readText()
            process.waitFor()
            val result = buildString {
                if (out.isNotEmpty()) append(out)
                if (err.isNotEmpty()) append(if (out.isNotEmpty()) "\n[stderr]\n$err" else err)
            }
            result.ifEmpty { "(no output)" }
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }

    // ─────────────────────────────────────────
    //  SHIZUKU EXECUTION (ADB shell UID level)
    // ─────────────────────────────────────────

    private fun runShizuku(cmd: String): String {
        if (!isShizukuAvailable()) {
            return "⚠️ Shizuku not available. Run shell fallback:\n" + runShell(cmd)
        }
        return try {
            val process = Shizuku.newProcess(arrayOf("sh", "-c", cmd), null, null)
            val out = process.inputStream.bufferedReader().readText()
            val err = process.errorStream.bufferedReader().readText()
            process.waitFor()
            val result = buildString {
                if (out.isNotEmpty()) append(out)
                if (err.isNotEmpty()) append(if (out.isNotEmpty()) "\n[stderr]\n$err" else err)
            }
            result.ifEmpty { "(no output)" }
        } catch (e: Exception) {
            "ERROR (Shizuku): ${e.message}"
        }
    }

    private fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.pingBinder() &&
                    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    private fun getShizukuStatus(): String {
        return try {
            val binder = Shizuku.pingBinder()
            val perm = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            buildString {
                appendLine("Shizuku binder: ${if (binder) "✅ OK" else "❌ Not running"}")
                appendLine("Permission granted: ${if (perm) "✅ Yes" else "❌ No"}")
                if (binder) {
                    appendLine("Shizuku version: ${Shizuku.getVersion()}")
                    appendLine("Shizuku UID: ${Shizuku.getUid()}")
                }
                if (!binder) appendLine("\n→ Open Shizuku app and tap Start")
                if (binder && !perm) appendLine("\n→ Open Shizuku app and grant permission to AndroidConnector")
            }
        } catch (e: Exception) {
            "ERROR checking Shizuku: ${e.message}"
        }
    }

    // ─────────────────────────────────────────
    //  SEND RESULT
    // ─────────────────────────────────────────

    private fun sendResult(commandId: String, command: String, result: String, type: String) {
        val body = JsonObject().apply {
            addProperty("token", token)
            addProperty("commandId", commandId)
            addProperty("command", command)
            addProperty("result", result)
            addProperty("exitCode", 0)
            addProperty("type", type)

            // For file_listing type, also include parsed data
            if (type == "file_listing") {
                try {
                    val parsed = JsonParser.parseString(result).asJsonObject
                    add("data", parsed)
                } catch (_: Exception) {}
            }
        }
        post("$serverUrl/api/device/result", body.toString())
    }

    // ─────────────────────────────────────────
    //  HTTP HELPERS
    // ─────────────────────────────────────────

    private fun post(url: String, json: String): String? {
        return try {
            val req = Request.Builder()
                .url(url)
                .post(json.toRequestBody(JSON))
                .build()
            http.newCall(req).execute().use { it.body?.string() }
        } catch (e: Exception) {
            null
        }
    }

    private fun get(url: String): String? {
        return try {
            val req = Request.Builder().url(url).get().build()
            http.newCall(req).execute().use { it.body?.string() }
        } catch (e: Exception) {
            null
        }
    }

    // ─────────────────────────────────────────
    //  NOTIFICATION
    // ─────────────────────────────────────────

    private fun createNotificationChannel() {
        val chan = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.channel_desc)
            setShowBadge(false)
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(chan)
    }

    private fun buildNotification(status: String, connected: Boolean): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, ConnectorService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle("AndroidConnector ${if (connected) "🟢" else "🔴"}")
            .setContentText(status)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_delete, "Stop", stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification(status: String, connected: Boolean) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(status, connected))
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "AndroidConnector:WakeLock"
        ).apply { acquire(12 * 60 * 60 * 1000L) }
    }

    private fun log(msg: String) {
        android.util.Log.d("ConnectorService", msg)
        statusCallback?.invoke(msg, isRunning)
    }
}
