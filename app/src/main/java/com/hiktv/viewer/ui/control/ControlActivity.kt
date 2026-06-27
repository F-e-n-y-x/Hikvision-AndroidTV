package com.hiktv.viewer.ui.control

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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
import com.hiktv.viewer.databinding.ActivityControlBinding
import com.hiktv.viewer.player.CameraStream
import kotlinx.coroutines.launch

/**
 * Live control for one camera on a standard Mi remote (D-pad + OK + Back + Volume + Menu).
 *
 * OK is the mode selector (the Mi remote's "m"/menu key isn't reliably delivered to apps):
 *   ZOOM ──OK──▶ PAN ──OK──▶ PTZ ──OK──▶ ZOOM …   (PTZ skipped when the camera has none)
 *
 * Per mode the D-pad does:
 *   ZOOM  ▲▼ / VOL = zoom in-out
 *   PAN   ◀▶▲▼     = pan around the zoomed image
 *   PTZ   ◀▶▲▼     = move the camera · VOL = optical zoom
 *
 * PTZ is only offered when the camera reports it (via the NVR, or a per-camera direct ISAPI /
 * ONVIF connection — the path that unlocks PTZ on units the NVR won't proxy, e.g. EZVIZ).
 */
class ControlActivity : AppCompatActivity() {

    private enum class Mode { ZOOM, PAN, PTZ }

    private lateinit var binding: ActivityControlBinding
    private lateinit var camera: Camera
    private var stream: CameraStream? = null

    private var ptz: PtzController? = null
    private var ptzSupported = false
    private var ptzActive = false
    private var warned = false
    private var hasDirect = false

    private var mode = Mode.ZOOM

    private val ptzSpeed = 60
    private val zoomSteps = floatArrayOf(1f, 1.5f, 2f, 2.5f, 3f, 4f, 5f)
    private var zoomIndex = 0
    private var panFx = 0f     // -1..1 fraction of available horizontal pan
    private var panFy = 0f

    /** Finer pan steps at higher zoom so 4–5x is precise (more presses to cross the frame). */
    private fun panStep(): Float = 0.5f / zoomSteps[zoomIndex]

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityControlBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val channel = intent.getIntExtra(EXTRA_CHANNEL, -1)
        val cam = Session.cameraByChannel(channel)
        if (cam == null || Session.nvr == null) { finish(); return }
        camera = cam
        binding.title.text = cam.name

        resolveController()
        startStream()
        detectPtz()
        updateOverlay()
    }

    private fun resolveController() {
        val store = NvrStore(this)
        val direct = store.loadDirect(camera.channel)
        val serial = store.ezvizSerial(camera.channel)
        val acct = store.ezvizAccount
        val pass = store.ezvizPassword
        val ezviz = serial != null && acct != null && pass != null
        hasDirect = direct != null || ezviz
        ptz = when {
            ezviz ->
                EzvizCloudPtzController(EzvizCloud(), acct!!, pass!!, serial!!)
            direct != null && store.loadDirectOnvif(camera.channel) ->
                OnvifPtzController(OnvifPtz(direct.host, direct.httpPort, direct.username, direct.password))
            direct != null ->
                IsapiPtzController(IsapiClient(direct), channel = 1)
            else ->
                Session.isapi?.let { IsapiPtzController(it, camera.channel) }
        }
    }

    private fun startStream() {
        val nvr = Session.nvr ?: return
        val url = RtspUrls.live(nvr, camera, sub = false)
        val hw = NvrStore(this).decoderMode != 2
        stream?.release()
        stream = CameraStream(
            context = this,
            url = url,
            networkCachingMs = 500,    // a bit more buffer for stable, artifact-free zoomed frames
            muted = true,
            hardware = hw,
            useTextureView = true,     // required for digital zoom + pan transforms
            highQuality = true         // full-detail decode (no frame/deblock skipping) for zoom
        ) { state ->
            binding.status.post {
                binding.status.visibility =
                    if (state == CameraStream.State.PLAYING) View.GONE else View.VISIBLE
                binding.status.text =
                    if (state == CameraStream.State.ERROR) "Reconnecting…" else "Connecting…"
            }
        }.also { it.start(binding.videoLayout) }
    }

    private fun detectPtz() {
        val controller = ptz ?: return
        lifecycleScope.launch {
            ptzSupported = runCatching { controller.probe() }.getOrDefault(false)
            updateOverlay()
        }
    }

    // ---- Mode selector (OK) -----------------------------------------------

    /** OK cycles ZOOM → PAN → PTZ → ZOOM (PTZ skipped when unavailable). */
    private fun cycleMode() {
        when (mode) {
            Mode.ZOOM -> mode = Mode.PAN
            Mode.PAN -> if (ptzSupported) {
                resetZoom(); if (ptzActive) stopPtz(); mode = Mode.PTZ
            } else {
                if (hasDirect) toast("PTZ not responding — run ‘Test PTZ connection’ in camera settings")
                mode = Mode.ZOOM
            }
            Mode.PTZ -> { if (ptzActive) stopPtz(); mode = Mode.ZOOM }
        }
        updateOverlay()
    }

    // ---- D-pad / volume ----------------------------------------------------

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                cycleMode(); return true
            }
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_CHANNEL_UP,
            KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_ZOOM_IN -> { onZoom(true, event); return true }
            KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN,
            KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_ZOOM_OUT -> { onZoom(false, event); return true }
        }
        if (event.repeatCount > 0 && mode != Mode.PAN) return true
        when (mode) {
            Mode.PTZ -> when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT  -> { sendPtz(-ptzSpeed, 0, 0); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { sendPtz(ptzSpeed, 0, 0); return true }
                KeyEvent.KEYCODE_DPAD_UP    -> { sendPtz(0, ptzSpeed, 0); return true }
                KeyEvent.KEYCODE_DPAD_DOWN  -> { sendPtz(0, -ptzSpeed, 0); return true }
            }
            Mode.ZOOM -> when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> { stepZoom(true); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { stepZoom(false); return true }
            }
            Mode.PAN -> when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { pan(-panStep(), 0f); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { pan(panStep(), 0f); return true }
                KeyEvent.KEYCODE_DPAD_UP -> { pan(0f, -panStep()); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { pan(0f, panStep()); return true }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN,
            KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_MINUS,
            KeyEvent.KEYCODE_ZOOM_IN, KeyEvent.KEYCODE_ZOOM_OUT -> {
                if (ptzActive) stopPtz()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN ->
                if (mode == Mode.PTZ && ptzActive) { stopPtz(); return true }
        }
        return super.onKeyUp(keyCode, event)
    }

    /** Volume / zoom keys: optical zoom in PTZ mode, digital zoom otherwise. */
    private fun onZoom(zoomIn: Boolean, event: KeyEvent) {
        if (mode == Mode.PTZ) {
            if (event.repeatCount == 0) sendPtz(0, 0, if (zoomIn) ptzSpeed else -ptzSpeed)
        } else if (event.repeatCount == 0) {
            stepZoom(zoomIn)
        }
    }

    // ---- PTZ ---------------------------------------------------------------

    private fun sendPtz(pan: Int, tilt: Int, zoom: Int) {
        val controller = ptz ?: return
        ptzActive = true
        lifecycleScope.launch {
            val ok = controller.move(pan, tilt, zoom)
            if (!ok && !warned) {
                warned = true
                toast("Camera didn't accept PTZ. Set a direct ONVIF connection for it.")
            }
        }
    }

    private fun stopPtz() {
        ptzActive = false
        val controller = ptz ?: return
        lifecycleScope.launch { controller.stop() }
    }

    // ---- Digital zoom + pan ------------------------------------------------

    private fun stepZoom(zoomIn: Boolean) {
        zoomIndex = if (zoomIn) (zoomIndex + 1).coerceAtMost(zoomSteps.lastIndex)
        else (zoomIndex - 1).coerceAtLeast(0)
        if (zoomIndex == 0) { panFx = 0f; panFy = 0f }
        applyTransform()
        updateOverlay()
    }

    private fun resetZoom() {
        zoomIndex = 0; panFx = 0f; panFy = 0f
        applyTransform()
        updateOverlay()
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
        // Pressing RIGHT reveals the right side → shift the image left (negative translation).
        stream?.setTransform(scale, -panFx * maxDx, -panFy * maxDy)
    }

    // ---- Overlay -----------------------------------------------------------

    private fun updateOverlay() {
        val scale = zoomSteps[zoomIndex]
        binding.zoomLabel.visibility = View.VISIBLE
        binding.zoomLabel.text = when (mode) {
            Mode.ZOOM -> "ZOOM ${scale}x"
            Mode.PAN -> "PAN ${scale}x"
            Mode.PTZ -> "PTZ"
        }
        val nextDigital = if (ptzSupported) "Pan/PTZ" else "Pan"
        binding.hint.text = when (mode) {
            Mode.ZOOM -> "▲▼ / VOL: zoom   ·   OK: switch mode ($nextDigital)   ·   BACK: exit"
            Mode.PAN -> "D-pad: pan the zoomed image   ·   OK: switch mode   ·   BACK: exit"
            Mode.PTZ -> "D-pad: move camera   ·   VOL: zoom   ·   OK: back to zoom   ·   BACK: exit"
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        if (ptzActive) stopPtz()
        stream?.release()
        stream = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_CHANNEL = "channel"
    }
}
