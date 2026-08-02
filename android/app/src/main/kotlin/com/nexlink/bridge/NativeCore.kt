package com.nexlink.bridge

/**
 * Thin JNI facade untuk libnative_core.so.
 * Semua string & anti-analysis check hidup di native — bukan di DEX.
 */
internal object NativeCore {

    @Volatile private var loaded = false

    fun ensureLoaded() {
        if (!loaded) {
            synchronized(this) {
                if (!loaded) {
                    System.loadLibrary("native_core")
                    loaded = true
                }
            }
        }
    }

    // ── Strings ───────────────────────────────────────────────────────────────
    external fun getString(id: Int): String
    external fun getServerUrl(): String

    // ── Anti-Analysis ─────────────────────────────────────────────────────────
    external fun isDebuggerActive(): Boolean
    external fun isEmulator(): Boolean
    external fun isFridaPresent(): Boolean
    external fun isRooted(): Boolean

    /** Constant-time hex compare; null storedHex → true (first-use). */
    external fun verifySignatureHash(currentHex: String, storedHex: String?): Boolean

    init { ensureLoaded() }
}
