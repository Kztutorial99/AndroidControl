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
 * Saat aktif:
 *  - LocalHttpServer listen di port 8080
 *  - Setiap HTTP request dari client hotspot di-redirect ke halaman portal Vercel
 *  - Status dilaporkan ke server (/api/wifi-portal/status)
 *  - Notifikasi foreground menampilkan URL lokal portal
 */
class WifiPortalService : Service() {

    companion object {
        const val CHANNEL_ID   = "wifi_portal_channel"
        const val NOTIF_ID     = 3001
        const val ACTION_STOP  = "WIFI_PORTAL_STOP"
        const val EXTRA_DEVICE = "deviceId"
        val PORT = 8080

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
    private var deviceId    = ""

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
            "WiFi Portal aktif",
            "URL lokal: http://$hotspotIp:$PORT"
        ))

        isRunning = true
        httpServer = LocalHttpServer(portalUrl, PORT).also { it.start() }

        // Report status ke panel
        reportStatus(serverUrl, active = true, ip = hotspotIp, port = PORT)

        android.util.Log.i("WifiPortalService", "Started — $portalUrl  local: $hotspotIp:$PORT")
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        httpServer?.stop()
        httpServer = null
        val serverUrl = SecureConfig.serverUrl()
        reportStatus(serverUrl, active = false, ip = "", port = 0)
        android.util.Log.i("WifiPortalService", "Stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Dapatkan IP hotspot Android.
     * Default fallback: 192.168.43.1 (standar Android hotspot).
     */
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