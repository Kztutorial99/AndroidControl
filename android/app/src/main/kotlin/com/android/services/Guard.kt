package com.android.services

import android.content.Context
import android.util.Log

/**
 * Guard — JNI bridge to libguard.so
 *
 * Called from App.attachBaseContext() before any other code runs.
 *
 * Native protection layers:
 *   L1  APK certificate SHA-256 fingerprint pinning  (anti-repack)
 *   L2  Package name verification
 *   L3  Anti-debug   — TracerPid via /proc/self/status
 *   L4  Anti-Frida   — port scan 27042-27045 + /proc/self/maps
 *   L5  Root detect  — su binary paths
 *   L6  Emulator     — ro.kernel.qemu + hardware props
 *
 * On failure → native busy-loop then SIGKILL (blank freeze, no error dialog).
 * Cert check disabled (dev mode) while cert_hash.h contains all-zero placeholder.
 */
internal object Guard {

    private const val TAG = "IWX"

    init {
        try { System.loadLibrary("guard") }
        catch (e: UnsatisfiedLinkError) { Log.w(TAG, "libguard.so not loaded: ${e.message}") }
    }

    @JvmStatic private external fun nativeInit(appCtx: Context): Boolean
    @JvmStatic        external fun nativeGetCertHex(appCtx: Context): String

    /** Call this from Application.attachBaseContext(). Blocks < 100ms. */
    fun init(ctx: Context) {
        try { nativeInit(ctx.applicationContext) }
        catch (e: UnsatisfiedLinkError) { Log.d(TAG, "Guard: native not available (dev/test)") }
    }

    /**
     * Print current APK cert SHA-256 to Logcat.
     * Call once from MainActivity.onCreate() when setting up a new release keystore,
     * copy the printed XOR array into cert_hash.h, then remove this call.
     */
    fun printCertFingerprint(ctx: Context) {
        try {
            val hex = nativeGetCertHex(ctx.applicationContext)
            Log.i(TAG, "══════════════════════════════════════════════")
            Log.i(TAG, "  CERT SHA-256 : $hex")
            val xk = byteArrayOf(0x4B,0x7A,0x39,0x21,0x6D,0x56,0x23,0x72,0x58,0x77,0x32,0x24)
            val enc = hex.chunked(2).mapIndexed { i, b -> (b.toInt(16)) xor (xk[i % 12].toInt() and 0xFF) }
            Log.i(TAG, "  _ce[] values : ${enc.joinToString(", ")}")
            Log.i(TAG, "  → Paste into cert_hash.h & set CERT_HASH_CONFIGURED 1")
            Log.i(TAG, "══════════════════════════════════════════════")
        } catch (e: UnsatisfiedLinkError) { Log.w(TAG, "Guard: native not available") }
    }
}
