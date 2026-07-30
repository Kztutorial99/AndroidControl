package com.android.services.modules

/**
 * DexModule — runtime-loaded module interface.
 * Encrypted and loaded dynamically by DexModuleLoader.
 */
object DexModule {
    @JvmStatic fun init(): Boolean = true
    @JvmStatic fun getVersion(): String = "2.0.0"
}
