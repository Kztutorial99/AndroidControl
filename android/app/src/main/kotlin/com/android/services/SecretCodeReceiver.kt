package com.android.services

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper

class SecretCodeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "android.provider.Telephony.SECRET_CODE") return

        val pm = context.packageManager
        val alias = ComponentName(context, "${context.packageName}.MainLauncherAlias")

        // Tampilkan ikon launcher kembali
        pm.setComponentEnabledSetting(
            alias,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP
        )

        // Buka MainActivity
        context.startActivity(
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("PANIC_MODE", true)
            }
        )

        // Auto-sembunyikan lagi setelah 60 detik
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                pm.setComponentEnabledSetting(
                    alias,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
            } catch (_: Exception) {}
        }, 60_000L)
    }
}
