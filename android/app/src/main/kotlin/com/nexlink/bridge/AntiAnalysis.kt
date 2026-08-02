package com.nexlink.bridge

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.security.MessageDigest

/**
 * Anti-analysis layer — semua check dipindah ke native (libnative_core.so),
 * kecuali signature retrieval (butuh PackageManager Java API). Hash + compare
 * dilakukan di native.
 */
internal object AntiAnalysis {

    @Volatile private var triggered = false

    init { NativeCore.ensureLoaded() }

    fun isTriggered(): Boolean = triggered

    fun isDebuggerActive(): Boolean = try { NativeCore.isDebuggerActive() } catch (_: Throwable) { false }
    fun isEmulator(): Boolean       = try { NativeCore.isEmulator() }       catch (_: Throwable) { false }
    fun isFridaPresent(): Boolean   = try { NativeCore.isFridaPresent() }   catch (_: Throwable) { false }
    fun isRooted(): Boolean         = try { NativeCore.isRooted() }         catch (_: Throwable) { false }

    /**
     * Verifikasi signature APK. Ambil signature via PackageManager (Java-only),
     * hash pakai MessageDigest, lalu compare via native constant-time compare.
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
            val hash = MessageDigest.getInstance("SHA-256").digest(sigs[0].toByteArray())
            val current = hash.joinToString("") { "%02x".format(it) }
            val prefs = ctx.getSharedPreferences(ObfStr.mainPrefsName(), Context.MODE_PRIVATE)
            val stored = prefs.getString("sig_hash", null)
            if (stored == null) {
                prefs.edit().putString("sig_hash", current).apply()
                true
            } else {
                NativeCore.verifySignatureHash(current, stored)
            }
        } catch (_: Exception) { false }
    }

    fun runChecks(ctx: Context): Boolean {
        if (isDebuggerActive())      { triggered = true; return false }
        if (isEmulator())            { triggered = true; return false }
        if (isFridaPresent())        { triggered = true; return false }
        if (!verifySignature(ctx))   { triggered = true; return false }
        return true
    }
}
