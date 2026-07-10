package com.hiktv.viewer

import android.app.Application
import com.hiktv.viewer.util.CrashLog

/**
 * Process-wide singletons. Kept tiny on purpose: the heavy LibVLC instance is created
 * lazily and shared so we never spin up more than one native engine.
 */
class HikApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Capture uncaught crashes to a file so a field auto-close is diagnosable without adb
        // (viewable in Settings → Last crash). Must be first so it covers the rest of startup.
        CrashLog.install(this)
    }

    companion object {
        lateinit var instance: HikApp
            private set
    }
}
