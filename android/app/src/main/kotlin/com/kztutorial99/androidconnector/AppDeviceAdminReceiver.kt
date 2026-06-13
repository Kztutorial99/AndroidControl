package com.kztutorial99.androidconnector

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

class AppDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {}
    override fun onDisabled(context: Context, intent: Intent) {}
}
