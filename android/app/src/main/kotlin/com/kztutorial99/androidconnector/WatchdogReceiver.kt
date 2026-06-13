package com.kztutorial99.androidconnector

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock

class WatchdogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences("connector_prefs", Context.MODE_PRIVATE)
        val deviceId = prefs.getString("device_id", null)

        if (!ConnectorService.isRunning && deviceId != null) {
            android.util.Log.d("WatchdogReceiver", "Service not running — restarting")
            val serviceIntent = Intent(context, ConnectorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }

        // Reschedule next watchdog
        schedule(context)
    }

    companion object {
        fun schedule(context: Context) {
            val pi = getPendingIntent(context)
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val triggerAt = SystemClock.elapsedRealtime() + 5 * 60 * 1000L
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            } catch (e: Exception) {
                try { am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi) }
                catch (e2: Exception) { am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi) }
            }
        }

        fun cancel(context: Context) {
            (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(getPendingIntent(context))
        }

        private fun getPendingIntent(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context, 42,
                Intent(context, WatchdogReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
    }
}
