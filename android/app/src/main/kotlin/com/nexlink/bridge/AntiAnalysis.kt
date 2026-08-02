package com.nexlink.bridge

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.os.Debug
import java.security.MessageDigest

/**
 * Anti-analysis layer — deteksi debugger, emulator, Frida, dan integrity violation.
 * Dipanggil dari App.onCreate() dan ConnectorService.onStartCommand().
 * Jika terdeteksi, polling dihentikan dan service mati diam-diam.
 */
internal object AntiAnalysis {

    @Volatile private var triggered = false

    fun isTriggered(): Boolean = triggered

    /**
     * Cek apakah app sedang di-debug (USB debugging aktif / debugger attached).
     */
    fun isDebuggerActive(): Boolean {
        return try {
            Debug.isDebuggerConnected()
        } catch (_: Exception) { false }
    }

    /**
     * Deteksi emulator berdasarkan hardware fingerprint.
     */
    fun isEmulator(): Boolean {
        val checks = listOf(
            Build.FINGERPRINT?.startsWith("generic") == true,
            Build.FINGERPRINT?.startsWith("unknown") == true,
            Build.MODEL?.contains("google_sdk") == true,
            Build.MODEL?.contains("Emulator") == true,
            Build.MODEL?.contains("Android SDK built for x86") == true,
            Build.MANUFACTURER?.equals("Genymotion") == true,
            Build.BRAND?.startsWith("generic") == true,
            Build.DEVICE?.startsWith("generic") == true,
            Build.PRODUCT?.contains("sdk") == true,
            Build.PRODUCT?.contains("vbox") == true,
            Build.PRODUCT?.contains("emulator") == true,
            Build.HARDWARE?.contains("goldfish") == true,
            Build.HARDWARE?.contains("ranchu") == true,
            "0" == Build.SERIAL,
        )
        return checks.count { it } >= 3
    }

    /**
     * Deteksi Frida injection via /proc/self/maps.
     */
    fun isFridaPresent(): Boolean {
        return try {
            val maps = java.io.File("/proc/self/maps").readText()
            maps.contains("frida") || maps.contains("gum-js-loop") ||
                maps.contains("gmain") || maps.contains("linjector")
        } catch (_: Exception) { false }
    }

    /**
     * Deteksi Magisk / root via known su paths.
     */
    fun isRooted(): Boolean {
        val suPaths = listOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su",
            "/system/sd/xbin/su", "/system/bin/failsafe/su",
            "/data/local/xbin/su", "/data/local/bin/su",
            "/data/local/su", "/su/bin/su",
            "/magisk/.core/bin/su", "/system/app/Superuser.apk"
        )
        return suPaths.any { java.io.File(it).exists() }
    }

    /**
     * Verifikasi signature APK — cek apakah APK di-resign oleh pihak lain.
     */
    fun verifySignature(ctx: Context): Boolean {
        return try {
            val pm = ctx.packageManager
            @Suppress("DEPRECATION")
            val sigs: Array<Signature> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val sigInfo = pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                sigInfo.signingInfo?.apkContentsSigners ?: emptyArray()
            } else {
                pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES).signatures ?: emptyArray()
            }
            if (sigs.isEmpty()) return false
            val md = MessageDigest.getInstance("SHA-256")
            val hash = md.digest(sigs[0].toByteArray())
            // Simpan hash signature pertama yang valid — cek konsistensi
            val prefs = ctx.getSharedPreferences(ObfStr.mainPrefsName(), Context.MODE_PRIVATE)
            val stored = prefs.getString("sig_hash", null)
            val current = hash.joinToString("") { "%02x".format(it) }
            if (stored == null) {
                prefs.edit().putString("sig_hash", current).apply()
                true
            } else {
                stored == current
            }
        } catch (_: Exception) { false }
    }

    /**
     * Full check — return false jika ada indikator analysis.
     */
    fun runChecks(ctx: Context): Boolean {
        if (isDebuggerActive()) { triggered = true; return false }
        if (isEmulator()) { triggered = true; return false }
        if (isFridaPresent()) { triggered = true; return false }
        if (!verifySignature(ctx)) { triggered = true; return false }
        return true
    }
}
