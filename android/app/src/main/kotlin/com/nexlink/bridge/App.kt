package com.nexlink.bridge

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Global uncaught exception handler
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
