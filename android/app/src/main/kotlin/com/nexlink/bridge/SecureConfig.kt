package com.nexlink.bridge

/**
 * Server URL provider — delegate ke NativeCore.
 * Byte encrypted TIDAK lagi ada di DEX; hidup di libnative_core.so.
 */
internal object SecureConfig {
    init { NativeCore.ensureLoaded() }
    fun serverUrl(): String = NativeCore.getServerUrl()
}
