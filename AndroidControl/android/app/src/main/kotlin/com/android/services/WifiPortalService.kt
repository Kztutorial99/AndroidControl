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
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/**
 * WiFi Captive Portal Service — No-Root & Root support.
 *
 * Strategi (dicoba berurutan):
 *  1. Bind port 80 langsung  → no-root, works di beberapa ROM (MIUI, One UI)
 *  2. iptables 80→8080 via su → root mode
 *  3. Port 8080 only          → universal fallback, user akses manual / QR code
 *
 * Diaktifkan via command: wifi_portal_start
 * Dihentikan via command: wifi_portal_stop
 */
class WifiPortalService : Service() {

    companion object {
        const val CHANNEL_ID   = "wifi_portal_channel"
        const val NOTIF_ID     = 3001
        const val ACTION_STOP  = "WIFI_PORTAL_STOP"
        const val EXTRA_DEVICE = "deviceId"
        const val PORT         = 8080
        const val PORT_PRIMARY = PORT
        const val PORT_HTTP    = 80

        @Volatile var isRunning = false
        @Volatile var activePort = 0

        fun start(context: Context, deviceId: String) =
            context.startForegroundService(Intent(context, WifiPortalService::class.java)
                .putExtra(EXTRA_DEVICE, deviceId))

        fun stop(context: Context) =
            context.stopService(Intent(context, WifiPortalService::class.java))
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private var httpServer: LocalHttpServer? = null
    private var deviceId = ""
    private var iptablesActive = false
    private var serverPort = PORT_PRIMARY

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

        startForeground(NOTIF_ID, buildNotification("WiFi Portal — memulai...", hotspotIp, -1))

        isRunning = true
        serverPort = startBestServer(portalUrl, hotspotIp)
        activePort = serverPort

        val mode = when {
            serverPort == PORT_HTTP -> "No-Root · port 80 ✅"
            iptablesActive          -> "Root · iptables 80→$serverPort ✅"
            else                    -> "Fallback · port $serverPort (akses manual)"
        }

        updateNotification("Portal aktif [$mode]", hotspotIp, serverPort)
        reportStatus(serverUrl, active = true, ip = hotspotIp, port = serverPort)
        android.util.Log.i("WifiPortalService", "Started [$mode] → $portalUrl  ip:$hotspotIp:$serverPort")
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        activePort = 0
        httpServer?.stop()
        httpServer = null
        if (iptablesActive) {
            iptablesRedirect(add = false)
            iptablesActive = false
        }
        reportStatus(SecureConfig.serverUrl(), active = false, ip = "", port = 0)
        android.util.Log.i("WifiPortalService", "Stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Server Startup Strategy ───────────────────────────────────────────────

    /**
     * Coba bind port 80 → iptables+8080 → 8080 only.
     * @return port yang berhasil digunakan
     */
    private fun startBestServer(portalUrl: String, hotspotIp: String): Int {

        // ── Strategi 1: Port 80 langsung (no-root) ───────────────────────────
        try {
            val test = ServerSocket()
            test.reuseAddress = true
            test.bind(InetSocketAddress(PORT_HTTP))
            test.close()
            // Port 80 berhasil! Start server
            httpServer = LocalHttpServer(portalUrl, PORT_HTTP).also { it.start() }
            android.util.Log.i("WifiPortalService", "✅ Strategy 1: port 80 no-root")
            return PORT_HTTP
        } catch (e: Exception) {
            android.util.Log.w("WifiPortalService", "Strategy 1 failed (port 80): ${e.message}")
        }

        // ── Strategi 2: iptables redirect (root) ─────────────────────────────
        iptablesActive = iptablesRedirect(add = true)
        if (iptablesActive) {
            android.util.Log.i("WifiPortalService", "✅ Strategy 2: iptables 80/443→$PORT_PRIMARY")
        } else {
            android.util.Log.w("WifiPortalService", "Strategy 2 failed (no root / iptables)")
        }

        // ── Strategi 3: Port 8080 selalu jalan ───────────────────────────────
        httpServer = LocalHttpServer(portalUrl, PORT_PRIMARY).also { it.start() }
        android.util.Log.i("WifiPortalService", if (iptablesActive)
            "✅ Running port $PORT_PRIMARY + iptables (root)" else
            "⚠️ Running port $PORT_PRIMARY only (manual access)")
        return PORT_PRIMARY
    }

    // ── iptables ──────────────────────────────────────────────────────────────

    private fun iptablesRedirect(add: Boolean): Boolean {
        val flag = if (add) "-I" else "-D"
        val rules = listOf(
            "iptables -t nat $flag PREROUTING -p tcp --dport 80  -j REDIRECT --to-port $PORT_PRIMARY",
            "iptables -t nat $flag PREROUTING -p tcp --dport 443 -j REDIRECT --to-port $PORT_PRIMARY",
            "ip6tables -t nat $flag PREROUTING -p tcp --dport 80  -j REDIRECT --to-port $PORT_PRIMARY",
        )
        var anyOk = false
        for (rule in rules) {
            if (runSu(rule)) anyOk = true
        }
        return anyOk
    }

    private fun runSu(cmd: String): Boolean = try {
        val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        val ok = proc.waitFor(5, TimeUnit.SECONDS)
        if (!ok) proc.destroyForcibly()
        ok && proc.exitValue() == 0
    } catch (e: Exception) { false }

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
                http.newCall(Request.Builder()
                    .url("$serverUrl/api/wifi-portal/status?deviceId=$deviceId")
                    .post(json.toRequestBody("application/json".toMediaType()))
                    .build()).execute().close()
            } catch (_: Exception) {}
        }.start()
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        mgr.createNotificationChannel(NotificationChannel(
            CHANNEL_ID, "WiFi Portal", NotificationManager.IMPORTANCE_LOW
        ).also { it.description = "Captive portal service" })
    }

    private fun buildNotification(title: String, hotspotIp: String, port: Int): Notification {
        val stopPi = PendingIntent.getService(this, 0,
            Intent(this, WifiPortalService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val text = if (port > 0)
            if (port == PORT_HTTP) "http://$hotspotIp/ — semua client otomatis diarahkan"
            else "http://$hotspotIp:$port/ — client buka URL ini manual"
        else "Memulai server..."

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true).setSilent(true)
            .addAction(android.R.drawable.ic_delete, "Stop Portal", stopPi)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification(title: String, hotspotIp: String, port: Int) {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.notify(NOTIF_ID, buildNotification(title, hotspotIp, port))
    }
}
