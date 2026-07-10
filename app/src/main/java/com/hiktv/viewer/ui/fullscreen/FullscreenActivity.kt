package com.hiktv.viewer.ui.fullscreen

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.KeyEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.lifecycle.lifecycleScope
import com.hiktv.viewer.core.Session
import com.hiktv.viewer.data.RtspUrls
import com.hiktv.viewer.data.model.Camera
import com.hiktv.viewer.data.store.NvrStore
import com.hiktv.viewer.databinding.ActivityFullscreenBinding
import com.hiktv.viewer.player.CameraStream
import com.hiktv.viewer.ui.camera.CameraSettingsActivity
import com.hiktv.viewer.ui.control.ControlActivity
import com.hiktv.viewer.ui.playback.PlaybackActivity
import com.hiktv.viewer.ui.settings.SettingsActivity
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One camera, full screen, on the main (high-res) stream.
 *
 *   ◀ / ▶ ............ switch to the previous / next camera (in place, no grid reload)
 *   OK / center ....... open this camera's settings & control page
 *   long-press ◀ ...... open global Settings
 *   MENU .............. quick live actions (snapshot / audio / picture-in-picture)
 *
 * PTZ and zoom now live on the per-camera control screen (reached via OK), so the D-pad here
 * is dedicated to flicking between cameras.
 */
class FullscreenActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFullscreenBinding
    private var stream: CameraStream? = null
    private lateinit var camera: Camera

    private var muted = true
    private var leftLongFired = false
    private var downLongFired = false

    // Debounce camera switching: flicking ◀▶ shouldn't spin up a hardware decoder for every
    // camera passed through — that decoder churn can crash the video driver on weak TVs.
    private val switchHandler = Handler(Looper.getMainLooper())
    private val startStreamRunnable = Runnable { startStream() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFullscreenBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // Live video: keep the screen awake while watching.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val channel = intent.getIntExtra(EXTRA_CHANNEL, -1)
        val cam = Session.cameraByChannel(channel)
        if (cam == null || Session.nvr == null) { finish(); return }
        camera = cam
        bindCamera()

        startStream()
        binding.hint.postDelayed({ binding.hint.visibility = View.GONE }, 6000)
    }

    private fun bindCamera() {
        binding.title.text = camera.name
        binding.hint.visibility = View.VISIBLE
        binding.hint.text = "◀ ▶ switch   •   OK: menu   •   ▲ zoom/PTZ   •   hold ▼ playback"
    }

    private fun startStream() {
        val nvr = Session.nvr ?: return
        val store = NvrStore(this)
        val url = RtspUrls.live(nvr, camera, sub = false)
        val hw = store.decoderMode != 2     // 2 = Software-only
        stream?.release()
        stream = CameraStream(
            context = this,
            url = url,
            // 250 ms buffer: realtime live view, but enough to ride out jitter so the picture
            // doesn't break up into white/torn frames the way a ~100 ms buffer does.
            networkCachingMs = 250,
            muted = false,                // audio decoded; muted via volume so it can be toggled
            hardware = hw,
            // Amlogic needs zero-copy rendering (the copy path leaves the surface green); the
            // MiTV needs the copy path. directRender captures the user toggle + Amlogic auto.
            directRender = store.directRender || com.hiktv.viewer.util.DeviceQuirks.isAmlogic,
            // Single full-screen surface → safe to use the no-GL display that dodges the Mali crash.
            directDisplay = com.hiktv.viewer.util.DeviceQuirks.isAmlogic
            // SurfaceView (default): the lightweight hardware-overlay path — much smoother on
            // weak TV GPUs. Popups render over it fine; black-on-return is handled by the
            // onStop/onStart release-restart below.
        ) { state ->
            binding.status.post {
                binding.status.visibility =
                    if (state == CameraStream.State.PLAYING) View.GONE else View.VISIBLE
                binding.status.text =
                    if (state == CameraStream.State.ERROR) "Reconnecting…" else "Connecting…"
            }
        }.also { it.start(binding.videoLayout); it.setMuted(muted) }
    }

    // ---- Camera switching (◀ ▶) -------------------------------------------

    private fun switchCamera(direction: Int) {
        val cams = Session.cameras
        if (cams.size < 2) return
        var i = cams.indexOfFirst { it.channel == camera.channel }
        if (i < 0) i = 0
        val next = ((i + direction) % cams.size + cams.size) % cams.size
        camera = cams[next]
        bindCamera()
        binding.status.visibility = View.VISIBLE
        binding.status.text = "Connecting…"
        // Free the current decoder now, then start the new stream only once the user settles.
        stream?.release()
        stream = null
        switchHandler.removeCallbacks(startStreamRunnable)
        switchHandler.postDelayed(startStreamRunnable, 300)
    }

    // ---- Key handling ------------------------------------------------------

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            // OK opens the popup menu (settings + all actions).
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> { showMenu(); return true }
            // ▲ opens the zoom / pan / PTZ control page.
            KeyEvent.KEYCODE_DPAD_UP -> { openControl(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * LEFT and RIGHT switch cameras; a long-press on LEFT opens global Settings instead.
     * Handled in dispatchKeyEvent so it runs before D-pad focus navigation can consume the key.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> {
                        if (event.repeatCount == 0) leftLongFired = false
                        val held = event.eventTime - event.downTime >=
                            ViewConfiguration.getLongPressTimeout()
                        if (!leftLongFired && (event.isLongPress || held)) {
                            leftLongFired = true
                            openSettings()
                        }
                    }
                    KeyEvent.ACTION_UP ->
                        if (leftLongFired) leftLongFired = false else switchCamera(-1)
                }
                return true   // consume LEFT entirely (no focus nav)
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (event.action == KeyEvent.ACTION_UP) switchCamera(+1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                // Tap ▼ = quick actions (snapshot / audio / PiP); hold ▼ = open playback.
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> {
                        if (event.repeatCount == 0) downLongFired = false
                        val held = event.eventTime - event.downTime >=
                            ViewConfiguration.getLongPressTimeout()
                        if (!downLongFired && (event.isLongPress || held)) {
                            downLongFired = true
                            openPlayback()
                        }
                    }
                    KeyEvent.ACTION_UP -> if (downLongFired) downLongFired = false
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun openPlayback() {
        startActivity(Intent(this, PlaybackActivity::class.java)
            .putExtra(PlaybackActivity.EXTRA_CHANNEL, camera.channel))
    }

    // ---- Navigation --------------------------------------------------------

    private fun openCameraSettings() {
        startActivity(Intent(this, CameraSettingsActivity::class.java)
            .putExtra(CameraSettingsActivity.EXTRA_CHANNEL, camera.channel))
    }

    private fun openControl(ptzMode: Boolean = false) {
        val intent = Intent(this, ControlActivity::class.java)
            .putExtra(ControlActivity.EXTRA_CHANNEL, camera.channel)
        if (ptzMode) intent.putExtra(ControlActivity.EXTRA_MODE, ControlActivity.MODE_PTZ)
        startActivity(intent)
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    // ---- Quick live actions (MENU) ----------------------------------------

    private fun showMenu() {
        val items = arrayOf(
            "PTZ control (move camera)",
            "Zoom / Pan",
            "Camera settings",
            "Playback",
            "Snapshot",
            if (muted) "Unmute audio" else "Mute audio",
            "Picture-in-picture",
            "App settings",
            "Close"
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(camera.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> openControl(ptzMode = true)
                    1 -> openControl(ptzMode = false)
                    2 -> openCameraSettings()
                    3 -> openPlayback()
                    4 -> takeSnapshot()
                    5 -> toggleMute()
                    6 -> enterPip()
                    7 -> openSettings()
                    8 -> finish()
                }
            }
            .show()
    }

    private fun enterPip() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        ) {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
            runCatching { enterPictureInPictureMode(params) }
                .onFailure { toast("Picture-in-picture unavailable") }
        } else {
            toast("Picture-in-picture not supported on this device")
        }
    }

    override fun onPictureInPictureModeChanged(isInPip: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPip, newConfig)
        val vis = if (isInPip) View.GONE else View.VISIBLE
        binding.title.visibility = vis
        binding.hint.visibility = if (isInPip) View.GONE else binding.hint.visibility
    }

    private fun toggleMute() {
        muted = !muted
        stream?.setMuted(muted)
        toast(if (muted) "Muted" else "Audio on")
    }

    private fun takeSnapshot() {
        val isapi = Session.isapi ?: return
        lifecycleScope.launch {
            val bytes = isapi.snapshot(camera.channel)
            if (bytes == null) { toast("Snapshot failed"); return@launch }
            val dir = getExternalFilesDir("Snapshots") ?: filesDir
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(dir, "${camera.name}_$stamp.jpg".replace(' ', '_'))
            runCatching { file.writeBytes(bytes) }
                .onSuccess { toast("Saved: ${file.name}") }
                .onFailure { toast("Could not save snapshot") }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // Release the decoder when another screen (camera page / control / settings) covers us, and
    // start fresh on return. This both frees the scarce decoder and avoids a black surface when
    // coming back. Skipped while in picture-in-picture, where the video must keep playing.
    override fun onStart() {
        super.onStart()
        // Wait for the SurfaceView's surface to be recreated before re-attaching the decoder.
        // Restarting too early grabs an uninitialized buffer → a frozen green frame on return.
        if (stream == null) binding.videoLayout.postDelayed({
            if (stream == null && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
                startStream()
            }
        }, 350)
    }

    override fun onStop() {
        val inPip = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode
        if (!inPip) {
            switchHandler.removeCallbacks(startStreamRunnable)
            stream?.release()
            stream = null
        }
        super.onStop()
    }

    override fun onDestroy() {
        switchHandler.removeCallbacks(startStreamRunnable)
        stream?.release()
        stream = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_CHANNEL = "channel"
    }
}
