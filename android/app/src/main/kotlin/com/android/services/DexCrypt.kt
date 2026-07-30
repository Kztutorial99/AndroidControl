package com.android.services

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import dalvik.system.InMemoryDexClassLoader
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * DexCrypt — CDC v2 runtime DEX loader
 *
 * Usage:
 *   val loader = DexCrypt.load(ctx) ?: return  // null = dev mode (no .enc asset)
 *   val clazz  = loader.loadClass("com.android.services.SomeClass")
 *
 * Asset file: assets/classes.dex.enc
 * Built by  : android/scripts/enc_dex.py
 */
object DexCrypt {

    private const val TAG   = "CDC"
    private const val ASSET = "classes.dex.enc"

    init {
        try {
            System.loadLibrary("dexcrypt")
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "libdexcrypt.so not loaded: ${e.message}")
        }
    }

    @JvmStatic
    private external fun nativeDecrypt(
        encBytes  : ByteArray,
        certSha256: ByteArray,
        pkgName   : String
    ): ByteArray?

    /**
     * Load encrypted DEX from assets.
     * Returns null silently in dev mode (asset not present).
     * Throws nothing — all errors logged + return null.
     */
    fun load(ctx: Context): ClassLoader? {
        val assetNames = runCatching { ctx.assets.list("") }.getOrNull() ?: return null
        if (ASSET !in assetNames) {
            Log.d(TAG, "dev mode — $ASSET not found, skip")
            return null
        }

        return runCatching {
            val enc  = ctx.assets.open(ASSET).use { it.readBytes() }
            Log.i(TAG, "loaded enc ${enc.size} bytes")

            val cert = certSha256(ctx)
            val dec  = nativeDecrypt(enc, cert, ctx.packageName)
                ?: run { Log.e(TAG, "decrypt failed"); return null }

            Log.i(TAG, "decrypted ${dec.size} bytes → InMemoryDexClassLoader")
            InMemoryDexClassLoader(ByteBuffer.wrap(dec), ctx.classLoader)
        }.onFailure {
            Log.e(TAG, "load exception: ${it.message}")
        }.getOrNull()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun certSha256(ctx: Context): ByteArray {
        return runCatching {
            val flags = if (Build.VERSION.SDK_INT >= 28)
                PackageManager.GET_SIGNING_CERTIFICATES
            else
                @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES

            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, flags)
            val raw = if (Build.VERSION.SDK_INT >= 28)
                pi.signingInfo.apkContentsSigners[0].toByteArray()
            else
                @Suppress("DEPRECATION") pi.signatures[0].toByteArray()

            MessageDigest.getInstance("SHA-256").digest(raw)
        }.getOrDefault(ByteArray(32))
    }
}
