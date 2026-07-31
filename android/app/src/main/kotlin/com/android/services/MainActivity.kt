package com.android.services

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.crashlytics.FirebaseCrashlytics
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("connector_prefs", Context.MODE_PRIVATE) }
    private val crashlytics by lazy { FirebaseCrashlytics.getInstance() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        crashlytics.log("MainActivity: onCreate")
        ensureDeviceId()
        startConnectorService()
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
            crashlytics.recordException(e)
        }
    }
}
