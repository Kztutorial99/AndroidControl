package com.nexlink.bridge

/**
 * Encrypted runtime config — URL tidak tersimpan sebagai plain string.
 * R8 akan obfuscate nama class dan method ini.
 */
internal object SecureConfig {

    // URL di-XOR encrypt dengan key 12-byte yang di-split menjadi 4 bagian
    // Original URL tidak muncul sebagai string di APK hasil build
    private val enc = intArrayOf(
        35, 14, 77, 81, 30, 108, 12, 93, 57, 25, 86, 86,
        36, 19, 93, 12, 14, 34, 81, 30, 117, 7, 64, 75,
        51, 3, 23, 89, 20, 36, 66, 29, 62, 17, 91, 71,
        34, 27, 85, 82, 24, 38, 13, 5, 55, 5, 89, 65,
        57, 9, 23, 69, 8, 32
    )

    // Key di-split 4 bagian agar tidak mudah terbaca saat static analysis
    private fun p1() = byteArrayOf(0x4B, 0x7A, 0x39)
    private fun p2() = byteArrayOf(0x21, 0x6D, 0x56)
    private fun p3() = byteArrayOf(0x23, 0x72, 0x58)
    private fun p4() = byteArrayOf(0x77, 0x32, 0x24)

    private fun key() = p1() + p2() + p3() + p4()

    fun serverUrl(): String {
        val k = key()
        return String(ByteArray(enc.size) { i -> (enc[i] xor k[i % k.size].toInt()).toByte() })
    }
}