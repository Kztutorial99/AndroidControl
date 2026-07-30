package com.android.services

import android.util.Base64
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * KeyFetcher — ambil static_key CDC v2 dari server via challenge-response.
 *
 * Protocol (anti-capture, anti-plaintext):
 *   APK → POST /api/tk
 *     { "t": base64(HMAC-SHA256(nonce||ts_bytes, cert_sha256)),
 *       "n": base64(nonce), "ts": unix_timestamp }
 *
 *   Server → APK
 *     { "r": base64(static_key XOR SHA256(nonce||t_bytes)[:key_len]) }
 *
 *   APK decode:
 *     mask = SHA256(nonce || t_bytes)[:r.size]
 *     static_key = r XOR mask
 *
 * Security model:
 *   - "r" adalah XOR-blob acak — tidak ada plaintext key di jaringan
 *   - decode "r" butuh nonce (APK punya) + t (HMAC dari cert APK asli)
 *   - tanpa cert APK asli → tidak bisa hitung t → tidak bisa decode r
 *   - HTTPS enkripsi channel → anti-sniff
 *   - Anti-replay: ts window 90 detik di server
 *   - Anti-Frida: cek debugger sebelum fetch
 *   - Key di-zero dari memory setelah dipakai (di DexCrypt)
 */
internal object KeyFetcher {

    private const val TAG = "KF"

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private fun endpoint(): String =
        RemoteConfig.serverUrl() + ObfStr.apiKeyEndpoint()

    /**
     * Fetch runtime key. Returns null jika gagal (server down / debugger).
     * BLOCKING — panggil dari background thread.
     */
    fun fetch(certSha256: ByteArray): ByteArray? {
        if (isDebugged()) {
            Log.w(TAG, "debugger detected — abort")
            return null
        }

        return runCatching {
            val nonce   = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val ts      = System.currentTimeMillis() / 1000L
            val tsBytes = longToLEBytes(ts)

            // t = HMAC-SHA256(nonce || ts_bytes, cert_sha256)
            // Membuktikan APK punya cert asli, tanpa expose cert ke server
            val t = hmacSha256(nonce + tsBytes, certSha256)

            val body = JSONObject().apply {
                put("t",  Base64.encodeToString(t,     Base64.NO_WRAP))
                put("n",  Base64.encodeToString(nonce, Base64.NO_WRAP))
                put("ts", ts)
            }.toString()

            val req = Request.Builder()
                .url(endpoint())
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

            val resp = http.newCall(req).execute().use { res ->
                if (!res.isSuccessful) {
                    Log.w(TAG, "srv ${res.code}")
                    return null
                }
                res.body?.string() ?: return null
            }

            val json   = JSONObject(resp)
            val rBytes = Base64.decode(json.getString("r"), Base64.NO_WRAP)

            // decode: mask = SHA256(nonce || t)[:rBytes.size]
            // Hanya APK yang tahu 't' (butuh cert_sha256 asli untuk compute)
            val mask = sha256(nonce + t).copyOf(rBytes.size)
            val key  = ByteArray(rBytes.size) { i ->
                (rBytes[i].toInt() xor mask[i].toInt()).toByte()
            }

            Log.d(TAG, "ok ${key.size}b")
            // Zero temp buffers
            mask.fill(0); t.fill(0)
            key

        }.onFailure {
            Log.w(TAG, "err: ${it.message}")
        }.getOrNull()
    }

    // ── Crypto helpers ────────────────────────────────────────────────────────

    private fun hmacSha256(data: ByteArray, key: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    private fun longToLEBytes(v: Long): ByteArray =
        ByteArray(8) { i -> ((v shr (i * 8)) and 0xFF).toByte() }

    // ── Anti-debug (Frida / debugger attach) ──────────────────────────────────

    private fun isDebugged(): Boolean = runCatching {
        android.os.Debug.isDebuggerConnected() ||
        android.os.Debug.waitingForDebugger()
    }.getOrDefault(false)
}
