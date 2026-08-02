package com.nexlink.bridge

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AntiAnalysis.runChecks(this)
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
