package com.hiktv.viewer.ui.link

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.hiktv.viewer.core.Session
import com.hiktv.viewer.data.store.NvrStore
import com.hiktv.viewer.ui.fullscreen.FullscreenActivity
import com.hiktv.viewer.ui.grid.GridActivity
import com.hiktv.viewer.ui.playback.PlaybackActivity
import com.hiktv.viewer.ui.setup.SetupActivity

/**
 * Deep-link entry point for other apps (e.g. a home screen) — no UI, routes and finishes:
 *
 *   hiktv://camera/<channel>    live fullscreen for that camera
 *   hiktv://playback/<channel>  recordings for that camera
 *   hiktv://grid                the camera wall
 *
 * Open to any caller on purpose (home apps signed with another key, `adb shell am start`), so the
 * URI is treated as untrusted input: an unknown host or a channel that isn't one of this NVR's
 * cameras falls back to the wall, and nothing is read back out — it only opens screens.
 */
class LinkActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(route(intent?.data))
        finish()
    }

    private fun route(uri: Uri?): Intent {
        // Cold start through a link: Setup normally restores the session, so do the same here.
        if (!Session.isReady) {
            val store = NvrStore.get(this)
            val nvr = store.load() ?: return Intent(this, SetupActivity::class.java)
            Session.connect(nvr)
            Session.cameras = store.loadCameras()
        }
        val cam = uri?.pathSegments?.firstOrNull()?.toIntOrNull()?.let { Session.cameraByChannel(it) }
        return when {
            cam != null && cam.online && uri?.host == "camera" ->
                Intent(this, FullscreenActivity::class.java).putExtra(FullscreenActivity.EXTRA_CHANNEL, cam.channel)
            cam != null && cam.online && uri?.host == "playback" ->
                Intent(this, PlaybackActivity::class.java).putExtra(PlaybackActivity.EXTRA_CHANNEL, cam.channel)
            else -> Intent(this, GridActivity::class.java)
        }
    }
}
