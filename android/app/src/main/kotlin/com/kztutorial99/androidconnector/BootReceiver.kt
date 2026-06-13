package com.kztutorial99.androidconnector

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences("connector_prefs", Context.MODE_PRIVATE)
        val autoStart = prefs.getBoolean("auto_start", false)
        val serverUrl = prefs.getString("server_url", "") ?: ""
        val token = prefs.getString("token", "") ?: ""

        if (autoStart && serverUrl.isNotBlank() && token.isNotBlank()) {
            val serviceIntent = Intent(context, ConnectorService::class.java).apply {
                putExtra("SERVER_URL", serverUrl)
                putExtra("TOKEN", token)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}
