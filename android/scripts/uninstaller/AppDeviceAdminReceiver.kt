package com.android.services

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.UserManager

/**
 * UNINSTALLER BUILD — semua proteksi dimatikan.
 * Install APK ini sebagai "update" → admin bisa dinonaktifkan normal.
 */
class AppDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {}
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        "Administrator akan dinonaktifkan."
    override fun onDisabled(context: Context, intent: Intent) {}

    companion object {
        val SETTINGS_PACKAGES   = emptySet<String>()
        val ADMIN_PAGE_KEYWORDS = emptyList<String>()
        fun getComponentName(context: Context) =
            ComponentName(context, AppDeviceAdminReceiver::class.java)
        fun isAdminActive(context: Context): Boolean {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            return dpm.isAdminActive(getComponentName(context))
        }
        fun isDeviceOwner(context: Context): Boolean {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            return dpm.isDeviceOwnerApp(context.packageName)
        }
        fun setBlockUninstall(context: Context, block: Boolean): String = "UNINSTALLER_BUILD"
    }
}
