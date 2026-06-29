package com.android.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

class SecretCodeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "android.provider.Telephony.SECRET_CODE") return

        // Tampilkan ikon launcher kembali via activity-alias
        AppIcon.show(context)

        // Buka MainActivity
        context.startActivity(
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("PANIC_MODE", true)
            }
        )

        // Auto-sembunyikan lagi setelah 60 detik
        Handler(Looper.getMainLooper()).postDelayed({
            AppIcon.hide(context)
        }, 60_000L)
    }
}
