package com.hiktv.viewer

import android.app.Application

/**
 * Process-wide singletons. Kept tiny on purpose: the heavy LibVLC instance is created
 * lazily and shared so we never spin up more than one native engine.
 */
class HikApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: HikApp
            private set
    }
}
