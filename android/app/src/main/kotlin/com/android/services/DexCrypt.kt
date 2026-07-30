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
 * Key CDC v2 TIDAK disimpan di APK.
 * Diambil runtime dari server via KeyFetcher (challenge-response).
 *
 * Usage:
 *   val loader = DexCrypt.load(ctx) ?: return
 *   val clazz  = loader.loadClass("com.android.services.SomeClass")
 *
 * Asset: assets/classes.dex.enc
 * Builder: android/scripts/enc_dex.py (key dari env CDC_STATIC_KEY)
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

    /**
     * nativeDecrypt — JNI ke dex_crypt.cpp
     * runtimeKey: key dari server (NOT hardcoded di APK)
     */
    @JvmStatic
    private external fun nativeDecrypt(
        encBytes  : ByteArray,
        certSha256: ByteArray,
        pkgName   : String,
        runtimeKey: ByteArray   // fetched at runtime — tidak ada di APK
    ): ByteArray?

    /**
     * Load encrypted DEX dari assets.
     * Returns null di dev mode (asset tidak ada) atau jika key fetch gagal.
     * BLOCKING — panggil dari background thread.
     */
    fun load(ctx: Context): ClassLoader? {
        val assetNames = runCatching { ctx.assets.list("") }.getOrNull() ?: return null
        if (ASSET !in assetNames) {
            Log.d(TAG, "dev mode — $ASSET not found, skip")
            return null
        }

        return runCatching {
            val enc  = ctx.assets.open(ASSET).use { it.readBytes() }
            Log.i(TAG, "enc ${enc.size}b")

            val cert = certSha256(ctx)

            // ── Fetch runtime key dari server ──────────────────────────────
            // Key tidak pernah ada di APK, tidak ada di disk, hanya di RAM sesaat
            val runtimeKey = KeyFetcher.fetch(cert)
            if (runtimeKey == null) {
                Log.e(TAG, "key fetch failed — abort")
                return null
            }

            val dec = nativeDecrypt(enc, cert, ctx.packageName, runtimeKey)

            // Zero key dari memory ASAP setelah decrypt
            runtimeKey.fill(0)

            if (dec == null) {
                Log.e(TAG, "decrypt failed — wrong key / tampered APK")
                return null
            }

            Log.i(TAG, "ok ${dec.size}b")
            InMemoryDexClassLoader(ByteBuffer.wrap(dec), ctx.classLoader)

        }.onFailure {
            Log.e(TAG, "ex: ${it.message}")
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
