package com.hiktv.viewer.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * One live RTSP stream bound to one on-screen video surface. Owns a single MediaPlayer.
 *
 * Latency vs. CPU is controlled by [networkCachingMs]:
 *   - grid tiles use a small cache on the sub-stream (~250 ms) so the wall stays in sync,
 *   - fullscreen uses an even smaller cache on the main stream (~150 ms) for "live" feel.
 *
 * Auto-reconnects with backoff when the NVR drops the stream (camera reboot, network blip).
 */
class CameraStream(
    context: Context,
    private val url: String,
    private val networkCachingMs: Int,
    private val muted: Boolean,
    private val hardware: Boolean = true,
    private val useTextureView: Boolean = false,
    private val highQuality: Boolean = false,
    private val onState: (State) -> Unit = {}
) {

    enum class State { CONNECTING, PLAYING, ERROR }

    private val player = MediaPlayer(PlayerEngine.get(context))
    private val main = Handler(Looper.getMainLooper())
    private val appContext = context.applicationContext

    private var attachedLayout: VLCVideoLayout? = null
    private var released = false
    private var stopped = false
    private var retryDelay = 1000L

    init {
        player.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Playing -> {
                    retryDelay = 1000L
                    onState(State.PLAYING)
                }
                MediaPlayer.Event.EncounteredError -> {
                    onState(State.ERROR)
                    scheduleReconnect()
                }
                MediaPlayer.Event.EndReached -> scheduleReconnect()
            }
        }
        player.volume = if (muted) 0 else 100
    }

    /**
     * Attach to a surface and begin playback. [startDelayMs] staggers startup across tiles so
     * the hardware decoder isn't asked to allocate many sessions in the same instant (which
     * crashes cheap H.265 SoCs).
     */
    fun start(layout: VLCVideoLayout, startDelayMs: Long = 0) {
        if (released) return
        stopped = false
        attachedLayout = layout
        // SurfaceView is cheaper on weak GPUs; the control screen uses a TextureView so the
        // image can be scaled/translated for digital zoom + pan.
        player.attachViews(layout, null, false, useTextureView)
        if (startDelayMs <= 0) play()
        else main.postDelayed({ if (!released) play() }, startDelayMs)
    }

    private fun play() {
        if (released) return
        onState(State.CONNECTING)
        val media = Media(PlayerEngine.get(appContext), Uri.parse(url)).apply {
            // hardware=false forces software decoding; reserves the scarce H.265 hardware
            // decoder sessions for the fullscreen stream and avoids multi-decoder crashes.
            setHWDecoderEnabled(hardware, false)
            // Disable MediaCodec direct (zero-copy) rendering: on many cheap H.265 TV chips the
            // DR path emits all-green frames. The copy path is slightly heavier but renders
            // correctly, and is required for TextureView transforms (zoom/pan) too.
            if (hardware) addOption(":no-mediacodec-dr")
            addOption(":network-caching=$networkCachingMs")
            addOption(":rtsp-tcp")
            addOption(":clock-jitter=0")
            addOption(":clock-synchro=0")
            if (highQuality) {
                // Sharpest possible image (for zoom/detail): full in-loop deblocking, keep frames.
                addOption(":avcodec-skiploopfilter=0")
            } else {
                // Stay realtime on weak SoCs: drop late/extra frames and skip deblocking instead
                // of juddering or building latency.
                addOption(":drop-late-frames")
                addOption(":skip-frames")
                addOption(":avcodec-skip-frame=0")
                addOption(":avcodec-skiploopfilter=4")
            }
            if (muted) addOption(":no-audio")
        }
        player.media = media
        media.release()
        player.play()
    }

    private fun scheduleReconnect() {
        if (released || stopped) return
        main.postDelayed({
            if (released) return@postDelayed
            runCatching {
                player.stop()
                play()
            }
        }, retryDelay)
        retryDelay = (retryDelay * 2).coerceAtMost(8000L) // cap backoff at 8s
    }

    /** Toggle audio at runtime (only effective when the stream was created with audio). */
    fun setMuted(m: Boolean) {
        runCatching { player.volume = if (m) 0 else 100 }
    }

    /**
     * Digital zoom + pan, applied as a transform on the TextureView (so it works only when the
     * stream was created with useTextureView = true). [scale] ≥ 1 enlarges; [dxPx]/[dyPx] shift
     * the enlarged image to pan around it. GPU-cheap — no second decode.
     */
    fun setTransform(scale: Float, dxPx: Float, dyPx: Float) {
        val tv = findTextureView(attachedLayout) ?: return
        tv.scaleX = scale
        tv.scaleY = scale
        tv.translationX = dxPx
        tv.translationY = dyPx
    }

    /** Centred digital zoom (no pan). */
    fun setZoom(factor: Float) = setTransform(if (factor <= 1f) 1f else factor, 0f, 0f)

    private fun findTextureView(v: View?): TextureView? {
        if (v is TextureView) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findTextureView(v.getChildAt(i))?.let { return it }
        return null
    }

    /** Stop drawing but keep the object reusable (used when a tile scrolls off-screen). */
    fun stop() {
        stopped = true
        main.removeCallbacksAndMessages(null)
        runCatching { player.stop() }
        attachedLayout?.let { runCatching { player.detachViews() } }
        attachedLayout = null
    }

    fun release() {
        released = true
        main.removeCallbacksAndMessages(null)
        runCatching { player.stop() }
        runCatching { player.detachViews() }
        runCatching { player.setEventListener(null) }
        runCatching { player.release() }
    }
}
