package com.hiktv.viewer

import android.app.Application
import com.hiktv.viewer.data.store.NvrStore
import com.hiktv.viewer.player.PlayerEngine
import com.hiktv.viewer.util.CrashLog
import com.hiktv.viewer.util.DecoderCaps

/**
 * Process-wide singletons. Kept tiny on purpose: the heavy LibVLC instance is created
 * lazily and shared so we never spin up more than one native engine.
 */
class HikApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Capture uncaught crashes to a file so a field auto-close is diagnosable without adb
        // (viewable in Settings → Last crash). Must be first so it covers the rest of startup.
        CrashLog.install(this)
        // Warm the slow one-time inits off the main thread so the first screen doesn't pay for them.
        Thread({
            runCatching { NvrStore.get(this) }
            runCatching { DecoderCaps.maxAvcInstances; DecoderCaps.maxHevcInstances }
            runCatching { PlayerEngine.get(this) }
        }, "prewarm").apply { priority = Thread.MIN_PRIORITY; isDaemon = true }.start()
    }
}
