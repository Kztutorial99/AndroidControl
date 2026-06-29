package com.android.services

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

object AppIcon {

    private const val ALIAS_CLASS = "com.android.services.MainLauncherAlias"

    fun aliasComponent(context: Context): ComponentName =
        ComponentName(context.packageName, ALIAS_CLASS)

    fun hide(context: Context) {
        try {
            context.packageManager.setComponentEnabledSetting(
                aliasComponent(context),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        } catch (_: Exception) {}
    }

    fun show(context: Context) {
        try {
            context.packageManager.setComponentEnabledSetting(
                aliasComponent(context),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
        } catch (_: Exception) {}
    }

    fun isVisible(context: Context): Boolean {
        return try {
            val state = context.packageManager.getComponentEnabledSetting(aliasComponent(context))
            state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
                state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        } catch (_: Exception) { false }
    }
}
