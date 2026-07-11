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
import com.hiktv.viewer.data.ezviz.EzvizCloud
import com.hiktv.viewer.data.isapi.IsapiClient
import com.hiktv.viewer.data.model.Camera
import com.hiktv.viewer.data.onvif.OnvifPtz
import com.hiktv.viewer.data.ptz.EzvizCloudPtzController
import com.hiktv.viewer.data.ptz.IsapiPtzController
import com.hiktv.viewer.data.ptz.OnvifPtzController
import com.hiktv.viewer.data.ptz.PtzController
import com.hiktv.viewer.data.store.NvrStore
import com.hiktv.viewer.databinding.ActivityFullscreenBinding
import com.hiktv.viewer.player.CameraStream
import com.hiktv.viewer.ui.camera.CameraSettingsActivity
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

    // ---- In-place zoom / pan / PTZ overlay --------------------------------------------------
    // Reuses THIS screen's live decoder/surface instead of launching a second video activity.
    // Opening a second main-stream decoder + surface here was the Amlogic Mali surface-teardown
    // crash (libGLES_mali SIGSEGV on the AWindowHandler thread) and the multi-second switch.
    private enum class Mode { ZOOM, PAN, PTZ }
    private var controlMode: Mode? = null
    private var ptz: PtzController? = null
    private var ptzResolved = false
    private var ptzSupported = false
    private var ptzActive = false
    private var ptzWarned = false
    private var hasDirect = false
    private var isEzviz = false
    private val ptzSpeed = 60
    private val zoomSteps = floatArrayOf(1f, 1.5f, 2f, 2.5f, 3f, 4f, 5f)
    private var zoomIndex = 0
    private var panFx = 0f
    private var panFy = 0f
    private fun panStep(): Float = 0.5f / zoomSteps[zoomIndex]

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
        // If the overlay is open (e.g. we returned from PiP/background while zoomed), the rebuilt
        // surface starts at 1x — reapply the current zoom/pan once it has laid out.
        if (controlMode != null) binding.videoLayout.post { applyTransform() }
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
        // While the zoom/pan/PTZ overlay is open it owns every key (including BACK, which exits it).
        if (controlMode != null) return handleControlKey(event)
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

    /**
     * Enter the zoom / pan / PTZ overlay ON this screen — no new activity, decoder, or surface.
     * Zoom + pan are a GPU transform on the current stream; PTZ is ISAPI/ONVIF/EZVIZ commands.
     */
    private fun openControl(ptzMode: Boolean = false) {
        if (controlMode == null) resolveController()
        controlMode = if (ptzMode && ptzSupported) Mode.PTZ else Mode.ZOOM
        if (!ptzResolved) detectPtz(startInPtz = ptzMode)
        applyTransform()
        updateControlOverlay()
    }

    /** Leave the overlay: reset digital zoom/pan, stop any live PTZ, restore the normal hint. */
    private fun exitControl() {
        if (controlMode == null) return
        if (ptzActive) stopPtz()
        controlMode = null
        zoomIndex = 0; panFx = 0f; panFy = 0f
        applyTransform()
        binding.zoomLabel.visibility = View.GONE
        bindCamera()
    }

    private fun resolveController() {
        val store = NvrStore(this)
        val direct = store.loadDirect(camera.channel)
        val serial = store.ezvizSerial(camera.channel)
        val acct = store.ezvizAccount
        val pass = store.ezvizPassword
        isEzviz = serial != null && acct != null && pass != null
        hasDirect = direct != null || isEzviz
        ptz = when {
            isEzviz -> EzvizCloudPtzController(EzvizCloud(), acct!!, pass!!, serial!!)
            direct != null && store.loadDirectOnvif(camera.channel) ->
                OnvifPtzController(OnvifPtz(direct.host, direct.httpPort, direct.username, direct.password))
            direct != null -> IsapiPtzController(IsapiClient(direct), channel = 1)
            else -> Session.isapi?.let { IsapiPtzController(it, camera.channel) }
        }
        // Reset PTZ availability too: this activity is reused across cameras, so a leftover
        // ptzSupported=true from a previous PTZ camera would wrongly enter PTZ mode on a camera
        // that has none (detectPtz only ever promotes to PTZ, never demotes).
        ptzSupported = false
        ptzResolved = false
    }

    private fun detectPtz(startInPtz: Boolean) {
        val controller = ptz
        if (controller == null) { ptzResolved = true; return }
        if (isEzviz) {
            // EZVIZ is a known pan/tilt unit — offer PTZ immediately; warm the slow login in bg.
            ptzSupported = true; ptzResolved = true
            if (startInPtz && controlMode != null) controlMode = Mode.PTZ
            updateControlOverlay()
            lifecycleScope.launch { runCatching { controller.probe() } }
            return
        }
        lifecycleScope.launch {
            ptzSupported = runCatching { controller.probe() }.getOrDefault(false)
            ptzResolved = true
            if (startInPtz && ptzSupported && controlMode != null) controlMode = Mode.PTZ
            updateControlOverlay()
        }
    }

    /** OK cycles ZOOM → PAN → PTZ → ZOOM (PTZ skipped when unavailable). */
    private fun cycleControlMode() {
        when (controlMode) {
            Mode.ZOOM -> controlMode = Mode.PAN
            Mode.PAN -> if (ptzSupported) {
                resetZoom(); if (ptzActive) stopPtz(); controlMode = Mode.PTZ
            } else {
                if (hasDirect) toast("PTZ not responding — run ‘Test PTZ connection’ in camera settings")
                controlMode = Mode.ZOOM
            }
            Mode.PTZ -> { if (ptzActive) stopPtz(); controlMode = Mode.ZOOM }
            null -> return
        }
        updateControlOverlay()
    }

    /** Route a key event while the overlay is open. Returns true if consumed. */
    private fun handleControlKey(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        if (event.action == KeyEvent.ACTION_UP) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN,
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN,
                KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_MINUS,
                KeyEvent.KEYCODE_ZOOM_IN, KeyEvent.KEYCODE_ZOOM_OUT ->
                    { if (ptzActive) stopPtz(); return true }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN ->
                    { if (controlMode == Mode.PTZ && ptzActive) stopPtz(); return true }
            }
            return true   // swallow other UP events so they don't reach normal handling
        }
        if (event.action != KeyEvent.ACTION_DOWN) return true
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> { exitControl(); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A ->
                { if (event.repeatCount == 0) cycleControlMode(); return true }  // ignore auto-repeat
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_CHANNEL_UP,
            KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_ZOOM_IN -> { onZoomKey(true, event); return true }
            KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN,
            KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_ZOOM_OUT -> { onZoomKey(false, event); return true }
        }
        // Key repeat: allow held panning, but not repeated zoom steps / PTZ bursts.
        if (event.repeatCount > 0 && controlMode != Mode.PAN) return true
        when (controlMode) {
            Mode.PTZ -> when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT  -> sendPtz(-ptzSpeed, 0, 0)
                KeyEvent.KEYCODE_DPAD_RIGHT -> sendPtz(ptzSpeed, 0, 0)
                KeyEvent.KEYCODE_DPAD_UP    -> sendPtz(0, ptzSpeed, 0)
                KeyEvent.KEYCODE_DPAD_DOWN  -> sendPtz(0, -ptzSpeed, 0)
            }
            Mode.ZOOM -> when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> stepZoom(true)
                KeyEvent.KEYCODE_DPAD_DOWN -> stepZoom(false)
            }
            Mode.PAN -> when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> pan(-panStep(), 0f)
                KeyEvent.KEYCODE_DPAD_RIGHT -> pan(panStep(), 0f)
                KeyEvent.KEYCODE_DPAD_UP -> pan(0f, -panStep())
                KeyEvent.KEYCODE_DPAD_DOWN -> pan(0f, panStep())
            }
            null -> {}
        }
        return true
    }

    private fun onZoomKey(zoomIn: Boolean, event: KeyEvent) {
        if (controlMode == Mode.PTZ) {
            if (event.repeatCount == 0) sendPtz(0, 0, if (zoomIn) ptzSpeed else -ptzSpeed)
        } else if (event.repeatCount == 0) {
            stepZoom(zoomIn)
        }
    }

    private fun sendPtz(pan: Int, tilt: Int, zoom: Int) {
        val controller = ptz ?: return
        ptzActive = true
        lifecycleScope.launch {
            val ok = controller.move(pan, tilt, zoom)
            if (!ok && !ptzWarned) {
                ptzWarned = true
                toast("Camera didn't accept PTZ. Set a direct ONVIF connection for it.")
            }
        }
    }

    private fun stopPtz() {
        ptzActive = false
        val controller = ptz ?: return
        lifecycleScope.launch { runCatching { controller.stop() } }
    }

    private fun stepZoom(zoomIn: Boolean) {
        zoomIndex = if (zoomIn) (zoomIndex + 1).coerceAtMost(zoomSteps.lastIndex)
        else (zoomIndex - 1).coerceAtLeast(0)
        if (zoomIndex == 0) { panFx = 0f; panFy = 0f }
        applyTransform()
        updateControlOverlay()
    }

    private fun resetZoom() {
        zoomIndex = 0; panFx = 0f; panFy = 0f
        applyTransform()
        updateControlOverlay()
    }

    private fun pan(dx: Float, dy: Float) {
        panFx = (panFx + dx).coerceIn(-1f, 1f)
        panFy = (panFy + dy).coerceIn(-1f, 1f)
        applyTransform()
    }

    private fun applyTransform() {
        val scale = zoomSteps[zoomIndex]
        val w = binding.videoLayout.width.toFloat()
        val h = binding.videoLayout.height.toFloat()
        val maxDx = (scale - 1f) / 2f * w
        val maxDy = (scale - 1f) / 2f * h
        stream?.setTransform(scale, -panFx * maxDx, -panFy * maxDy)
    }

    private fun updateControlOverlay() {
        val mode = controlMode ?: return
        val scale = zoomSteps[zoomIndex]
        binding.zoomLabel.visibility = View.VISIBLE
        binding.zoomLabel.text = when (mode) {
            Mode.ZOOM -> "ZOOM ${scale}x"
            Mode.PAN -> "PAN ${scale}x"
            Mode.PTZ -> "PTZ"
        }
        binding.hint.visibility = View.VISIBLE
        val nextDigital = if (ptzSupported) "Pan/PTZ" else "Pan"
        binding.hint.text = when (mode) {
            Mode.ZOOM -> "▲▼ / VOL: zoom   ·   OK: switch mode ($nextDigital)   ·   BACK: exit"
            Mode.PAN -> "D-pad: pan the zoomed image   ·   OK: switch mode   ·   BACK: exit"
            Mode.PTZ -> "D-pad: move camera   ·   VOL: zoom   ·   OK: back to zoom   ·   BACK: exit"
        }
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
            if (ptzActive) stopPtz()
            switchHandler.removeCallbacks(startStreamRunnable)
            stream?.release()
            stream = null
        }
        super.onStop()
    }

    override fun onDestroy() {
        if (ptzActive) stopPtz()
        switchHandler.removeCallbacks(startStreamRunnable)
        stream?.release()
        stream = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_CHANNEL = "channel"
    }
}
