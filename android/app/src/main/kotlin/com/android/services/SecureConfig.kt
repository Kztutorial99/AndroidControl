package com.android.services

/**
 * Encrypted runtime config — URL tidak tersimpan sebagai plain string.
 * R8 akan obfuscate nama class dan method ini.
 */
internal object SecureConfig {

    // URL di-XOR encrypt dengan key 12-byte yang di-split menjadi 4 bagian
    // Original URL tidak muncul sebagai string di APK hasil build
    private val enc = intArrayOf(
        35, 14, 77, 81, 30, 108, 12, 93, 49, 0, 74, 9,
        42, 20, 93, 83, 2, 63, 71, 95, 59, 24, 92, 80,
        57, 21, 85, 15, 3, 51, 87, 30, 49, 17, 75, 10,
        42, 10, 73
    )

    // Key di-split 4 bagian agar tidak mudah terbaca saat static analysis
    private fun p1() = byteArrayOf(0x4B, 0x7A, 0x39)   // "Kz9"
    private fun p2() = byteArrayOf(0x21, 0x6D, 0x56)   // "!mV"
    private fun p3() = byteArrayOf(0x23, 0x72, 0x58)   // "#rX"
    private fun p4() = byteArrayOf(0x77, 0x32, 0x24)   // "w2$"

    private fun key() = p1() + p2() + p3() + p4()

    fun serverUrl(): String {
        val k = key()
        return String(ByteArray(enc.size) { i -> (enc[i] xor k[i % k.size].toInt()).toByte() })
    }
}
