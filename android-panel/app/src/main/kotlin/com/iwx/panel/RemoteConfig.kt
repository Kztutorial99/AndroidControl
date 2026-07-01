package com.iwx.panel

import java.net.URL

internal object RemoteConfig {

    private const val CONFIG_URL =
        "https://raw.githubusercontent.com/Kztutorial99/AndroidControl/main/android-config.json"
    private const val FALLBACK = "https://iwx-android-control.netlify.app"

    @Volatile private var cached: String? = null

    fun serverUrl(): String = cached ?: FALLBACK

    fun fetch(): String {
        return try {
            val text = URL(CONFIG_URL).openStream().use { it.readBytes().toString(Charsets.UTF_8) }
            val match = Regex(""""serverUrl"\s*:\s*"([^"]+)"""").find(text)
            val url   = match?.groupValues?.getOrNull(1)?.trim()?.trimEnd('/')
            if (!url.isNullOrBlank() && url.startsWith("http")) {
                cached = url
                url
            } else FALLBACK
        } catch (_: Exception) { FALLBACK }
    }
}
