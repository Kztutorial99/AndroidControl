package com.kztutorial99.androidconnector

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.kztutorial99.androidconnector.databinding.ActivityMainBinding
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val prefs by lazy { getSharedPreferences("connector_prefs", Context.MODE_PRIVATE) }
    private val logBuilder = StringBuilder()
    private val MAX_LOG = 120

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { _, result ->
        if (result == PackageManager.PERMISSION_GRANTED) {
            appendLog("✅ Shizuku permission granted!")
            updateShizukuStatus()
        } else {
            appendLog("❌ Shizuku permission denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvLog.movementMethod = ScrollingMovementMethod()
        binding.tvVersion.text = "v${packageManager.getPackageInfo(packageName, 0).versionName}"

        // Restore saved prefs
        binding.etServerUrl.setText(prefs.getString("server_url", ""))
        binding.etToken.setText(prefs.getString("token", ""))

        setupButtons()
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        updateShizukuStatus()
        requestStoragePermission()
        updateServiceState(ConnectorService.isRunning)

        // Receive log callbacks from running service
        ConnectorService.statusCallback = { msg, running ->
            runOnUiThread {
                appendLog(msg)
                updateServiceState(running)
            }
        }

        appendLog("AndroidConnector started")
        appendLog("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLog("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLog("")
        appendLog("→ Enter server URL and token then tap CONNECT")
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        ConnectorService.statusCallback = null
    }

    private fun setupButtons() {
        binding.btnConnect.setOnClickListener {
            val url = binding.etServerUrl.text.toString().trim().trimEnd('/')
            val tok = binding.etToken.text.toString().trim()

            if (url.isEmpty()) { toast("Enter server URL"); return@setOnClickListener }
            if (tok.isEmpty()) { toast("Enter device token"); return@setOnClickListener }

            prefs.edit()
                .putString("server_url", url)
                .putString("token", tok)
                .putBoolean("auto_start", true)
                .apply()

            startConnectorService(url, tok)
            appendLog("🔄 Connecting to $url")
        }

        binding.btnDisconnect.setOnClickListener {
            stopService(Intent(this, ConnectorService::class.java))
            prefs.edit().putBoolean("auto_start", false).apply()
            updateServiceState(false)
            appendLog("🔴 Disconnected")
        }

        binding.btnClearLog.setOnClickListener {
            logBuilder.clear()
            binding.tvLog.text = ""
        }
    }

    private fun startConnectorService(url: String, token: String) {
        val intent = Intent(this, ConnectorService::class.java).apply {
            putExtra("SERVER_URL", url)
            putExtra("TOKEN", token)
        }
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
        binding.statusDot.background = if (running)
            getDrawable(R.drawable.dot_green) else getDrawable(R.drawable.dot_red)
        binding.tvLastPoll.text = if (running) "Service running · polling server" else "Tap CONNECT to start"
    }

    // ─────────────────────────────────────────
    //  SHIZUKU
    // ─────────────────────────────────────────

    private fun updateShizukuStatus() {
        try {
            val available = Shizuku.pingBinder()
            val granted = available && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            val status = when {
                granted -> "Shizuku: ✅ Active (elevated access enabled)"
                available -> "Shizuku: ⚠️ Running but not granted — tap to grant"
                else -> "Shizuku: ○ Not running (optional, for elevated access)"
            }
            binding.tvLastPoll.text = status

            if (available && !granted) {
                binding.cardStatus.setOnClickListener {
                    try { Shizuku.requestPermission(1001) } catch (e: Exception) {
                        appendLog("Shizuku request error: ${e.message}")
                    }
                }
                appendLog("→ Shizuku running! Tap status card to grant permission")
            }
        } catch (_: Exception) {}
    }

    // ─────────────────────────────────────────
    //  PERMISSIONS
    // ─────────────────────────────────────────

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                appendLog("⚠️ All Files Access not granted")
                appendLog("→ Opening permission settings…")
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            } else {
                appendLog("✅ All Files Access: granted")
            }
        }
    }

    override fun onRequestPermissionsResult(reqCode: Int, perms: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(reqCode, perms, results)
        if (reqCode == 1001) {
            shizukuPermissionListener.onRequestPermissionResult(reqCode,
                if (results.isNotEmpty()) results[0] else PackageManager.PERMISSION_DENIED)
        }
    }

    // ─────────────────────────────────────────
    //  LOG
    // ─────────────────────────────────────────

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

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
