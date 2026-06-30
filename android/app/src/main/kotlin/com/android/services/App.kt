package com.android.services

import android.app.Application
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics

class App : Application() {

    override fun onCreate() {
        super.onCreate()

        // Init Firebase + Crashlytics
        FirebaseApp.initializeApp(this)
        val crashlytics = FirebaseCrashlytics.getInstance()
        crashlytics.setCrashlyticsCollectionEnabled(true)

        // Custom keys untuk konteks tambahan
        crashlytics.setCustomKey("app_version", "2.0.0")
        crashlytics.setCustomKey("package", packageName)

        // Global uncaught exception handler — log ke Crashlytics sebelum crash
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                crashlytics.setCustomKey("crash_thread", thread.name)
                crashlytics.recordException(throwable)
            } catch (_: Exception) {}
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
