package com.android.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Service yang menjalankan LocalHttpServer sebagai captive portal.
 *
 * Diaktifkan via command panel: wifi_portal_start
 * Dihentikan via command panel: wifi_portal_stop
 *
 * Flow:
 *  1. Setup iptables: redirect semua TCP port 80 → port 8080 (butuh root via su)
 *  2. LocalHttpServer listen di port 8080
 *  3. Setiap HTTP request dari client hotspot di-redirect ke halaman portal Vercel
 *  4. Browser client otomatis buka halaman login portal (captive portal popup)
 *  5. Status dilaporkan ke server (/api/wifi-portal/status)
 */
class WifiPortalService : Service() {

    companion object {
        const val CHANNEL_ID   = "wifi_portal_channel"
        const val NOTIF_ID     = 3001
        const val ACTION_STOP  = "WIFI_PORTAL_STOP"
        const val EXTRA_DEVICE = "deviceId"
        const val PORT = 8080

        @Volatile var isRunning = false

        fun start(context: Context, deviceId: String) {
            val intent = Intent(context, WifiPortalService::class.java)
                .putExtra(EXTRA_DEVICE, deviceId)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WifiPortalService::class.java))
        }
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private var httpServer: LocalHttpServer? = null
    private var deviceId = ""

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }

        deviceId = intent?.getStringExtra(EXTRA_DEVICE) ?: ""
        val serverUrl = SecureConfig.serverUrl()
        val portalUrl = "$serverUrl/portal/$deviceId"
        val hotspotIp = getHotspotIp()

        startForeground(NOTIF_ID, buildNotification(
            "WiFi Portal aktif ✅",
            "Redirect port 80→$PORT · IP: $hotspotIp"
        ))

        isRunning = true

        // Redirect semua port 80 & 443 ke port kita via iptables (root)
        applyIptables(add = true)

        httpServer = LocalHttpServer(portalUrl, PORT).also { it.start() }

        reportStatus(serverUrl, active = true, ip = hotspotIp, port = PORT)

        android.util.Log.i("WifiPortalService", "Started — portal: $portalUrl  ip: $hotspotIp:$PORT")
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        httpServer?.stop()
        httpServer = null

        // Bersihkan iptables rules
        applyIptables(add = false)

        val serverUrl = SecureConfig.serverUrl()
        reportStatus(serverUrl, active = false, ip = "", port = 0)
        android.util.Log.i("WifiPortalService", "Stopped — iptables cleaned up")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── iptables redirect ─────────────────────────────────────────────────────
    /**
     * Redirect semua HTTP (80) dan HTTPS (443) → port 8080.
     * Butuh root (su). Kalau device tidak root → iptables gagal tapi portal
     * tetap jalan di port 8080 (user harus akses manual ke IP:8080).
     */
    private fun applyIptables(add: Boolean) {
        val flag = if (add) "-I" else "-D"

        // Rules tanpa filter interface — cover semua interface hotspot
        val rules = listOf(
            "iptables -t nat $flag PREROUTING -p tcp --dport 80 -j REDIRECT --to-port $PORT",
            "iptables -t nat $flag PREROUTING -p tcp --dport 443 -j REDIRECT --to-port $PORT",
            "ip6tables -t nat $flag PREROUTING -p tcp --dport 80 -j REDIRECT --to-port $PORT",
        )

        for (rule in rules) {
            runSuCmd(rule)
        }

        if (add) {
            android.util.Log.i("WifiPortalService", "iptables: port 80/443 → $PORT")
        } else {
            android.util.Log.i("WifiPortalService", "iptables: rules removed")
        }
    }

    private fun runSuCmd(cmd: String): Boolean {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val exited = proc.waitFor(5, TimeUnit.SECONDS)
            if (!exited) { proc.destroyForcibly(); return false }
            val exitCode = proc.exitValue()
            val err = proc.errorStream.bufferedReader().readText().trim()
            android.util.Log.d("WifiPortalService", "su [$cmd] exit=$exitCode ${if (err.isNotEmpty()) "err=$err" else ""}")
            exitCode == 0
        } catch (e: Exception) {
            android.util.Log.w("WifiPortalService", "su failed [$cmd]: ${e.message}")
            false
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun getHotspotIp(): String {
        return try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val ip = wm.dhcpInfo.gateway
            if (ip == 0) "192.168.43.1"
            else "%d.%d.%d.%d".format(ip and 0xFF, ip shr 8 and 0xFF, ip shr 16 and 0xFF, ip shr 24 and 0xFF)
        } catch (_: Exception) { "192.168.43.1" }
    }

    private fun reportStatus(serverUrl: String, active: Boolean, ip: String, port: Int) {
        Thread {
            try {
                val json = """{"active":$active,"ip":"$ip","port":$port}"""
                http.newCall(
                    Request.Builder()
                        .url("$serverUrl/api/wifi-portal/status?deviceId=$deviceId")
                        .post(json.toRequestBody("application/json".toMediaType()))
                        .build()
                ).execute().close()
            } catch (_: Exception) {}
        }.start()
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "WiFi Portal", NotificationManager.IMPORTANCE_LOW)
                .also { it.description = "Captive portal redirect service" }
        )
    }

    private fun buildNotification(title: String, text: String): Notification {
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, WifiPortalService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true).setSilent(true)
            .addAction(android.R.drawable.ic_delete, "Stop Portal", stopPi)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}
