package com.android.services

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.UserManager

class AppDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(context, AppDeviceAdminReceiver::class.java)
        if (dpm.isDeviceOwnerApp(context.packageName)) {
            dpm.setUninstallBlocked(admin, context.packageName, true)
        }
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        return "Menonaktifkan administrator akan menghapus perlindungan sistem. Ini tidak disarankan."
    }

    override fun onDisabled(context: Context, intent: Intent) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        try { dpm.lockNow() } catch (_: Exception) {}
    }

    companion object {
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

        fun setBlockUninstall(context: Context, block: Boolean): String {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = getComponentName(context)
            return when {
                !dpm.isAdminActive(admin) ->
                    "ERROR: Device Admin tidak aktif. Buka Settings > Security > Device Admin > aktifkan app ini."
                dpm.isDeviceOwnerApp(context.packageName) -> {
                    if (block) {
                        dpm.addUserRestriction(admin, UserManager.DISALLOW_UNINSTALL_APPS)
                        dpm.addUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT)
                        dpm.setUninstallBlocked(admin, context.packageName, true)
                    } else {
                        dpm.clearUserRestriction(admin, UserManager.DISALLOW_UNINSTALL_APPS)
                        dpm.clearUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT)
                        dpm.setUninstallBlocked(admin, context.packageName, false)
                    }
                    if (block) "BLOCK_ACTIVE: Semua app di HP terproteksi — tombol Uninstall dinonaktifkan sistem"
                    else "BLOCK_INACTIVE: Proteksi uninstall dilepas — uninstall kembali normal"
                }
                else -> {
                    if (block) {
                        "ADMIN_ONLY: Device Admin aktif tapi bukan Device Owner. Block parsial aktif. Untuk block PENUH semua app: jalankan via ADB: adb shell dpm set-device-owner com.android.services/.AppDeviceAdminReceiver"
                    } else {
                        "ADMIN_ONLY: Proteksi parsial dilepas."
                    }
                }
            }
        }
    }
}

