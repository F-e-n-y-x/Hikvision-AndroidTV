package com.hiktv.viewer.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
    private val directRender: Boolean = false,
    private val directDisplay: Boolean = false,
    private val onState: (State) -> Unit = {}
) {

    enum class State { CONNECTING, PLAYING, ERROR }

    private val player = MediaPlayer(PlayerEngine.get(context))
    private val main = Handler(Looper.getMainLooper())
    private val appContext = context.applicationContext

    private var attachedLayout: VLCVideoLayout? = null
    // Read on LibVLC's event thread (scheduleReconnect) and written on the main thread — volatile.
    @Volatile private var released = false
    @Volatile private var stopped = false
    // Read/written from both the event thread and the main thread, so it must be volatile.
    @Volatile private var retryDelay = 1000L
    // A single pending reconnect; replaced (not stacked) on each error so rapid error bursts
    // can't queue several overlapping reconnects that thrash the decoder.
    private var reconnectRunnable: Runnable? = null

    // Liveness watchdog: LibVLC can silently stop delivering frames on a live RTSP stream without
    // ever firing EncounteredError/EndReached (a known demux stall), leaving a grid tile frozen
    // forever until the whole screen is torn down. [lastProgressAt] is bumped on every
    // Playing/TimeChanged event; if it stops advancing while we believe we're playing, [watchdog]
    // forces a stop()/play() reconnect so the tile recovers in place. Read on the event thread and
    // written on both threads — volatile.
    @Volatile private var lastProgressAt = 0L
    private val watchdog = object : Runnable {
        override fun run() {
            if (released || stopped) return
            val idle = SystemClock.elapsedRealtime() - lastProgressAt
            if (lastProgressAt > 0L && idle > STALL_TIMEOUT_MS) {
                lastProgressAt = SystemClock.elapsedRealtime()   // grace window during reconnect
                scheduleReconnect()
            }
            main.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    init {
        // LibVLC fires events on its own native thread; hop to main so callers (and onState
        // consumers) never touch the UI off-thread.
        player.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Playing -> {
                    retryDelay = 1000L
                    lastProgressAt = SystemClock.elapsedRealtime()
                    main.post { onState(State.PLAYING) }
                }
                // Fires continuously while frames actually flow — our liveness signal. When it
                // stops (a silent stall that raises no error), the watchdog reconnects.
                MediaPlayer.Event.TimeChanged ->
                    lastProgressAt = SystemClock.elapsedRealtime()
                MediaPlayer.Event.EncounteredError -> {
                    main.post { onState(State.ERROR) }
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
        // Start the liveness watchdog (clear any stale copy first so it isn't double-posted).
        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, WATCHDOG_INTERVAL_MS)
    }

    private fun play() {
        if (released) return
        onState(State.CONNECTING)
        // Reset the liveness clock so a freshly (re)started stream gets a full grace window before
        // the watchdog can judge it stalled.
        lastProgressAt = SystemClock.elapsedRealtime()
        val media = Media(PlayerEngine.get(appContext), Uri.parse(url)).apply {
            // hardware=false forces software decoding; reserves the scarce H.265 hardware
            // decoder sessions for the fullscreen stream and avoids multi-decoder crashes.
            setHWDecoderEnabled(hardware, false)
            // Single-surface screens on Amlogic use the direct ANativeWindow display (no OpenGL)
            // to avoid the Mali GL crash. NOT used on the multi-tile grid, where the hardware
            // overlay can't clip several SurfaceViews and they'd overlap.
            if (directDisplay) addOption(":vout=android_display")
            // Disable MediaCodec direct (zero-copy) rendering: on many cheap H.265 TV chips the
            // DR path emits all-green frames. The copy path is slightly heavier but renders
            // correctly. Some chips (Amlogic Mi Box) are the opposite — the copy path is too slow
            // and crashes — so [directRender] lets those devices keep zero-copy rendering.
            if (hardware && !directRender) addOption(":no-mediacodec-dr")
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
        val delay = retryDelay
        retryDelay = (retryDelay * 2).coerceAtMost(8000L) // cap backoff at 8s
        main.post {
            // Drop any reconnect already pending so bursts of EncounteredError/EndReached
            // collapse into a single attempt instead of stacking overlapping stop()/play() calls.
            reconnectRunnable?.let { main.removeCallbacks(it) }
            val r = Runnable {
                reconnectRunnable = null
                // Re-check on the main thread: stop()/release() may have run during the backoff,
                // so we must not stop()/play() a released player or a detached surface.
                if (released || stopped) return@Runnable
                runCatching {
                    player.stop()
                    play()
                }
            }
            reconnectRunnable = r
            main.postDelayed(r, delay)
        }
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
        val tv = findTextureView(attachedLayout)
        if (tv != null) {
            tv.scaleX = scale
            tv.scaleY = scale
            tv.translationX = dxPx
            tv.translationY = dyPx
        } else {
            // SurfaceView (e.g. Amlogic): no view transform — fall back to LibVLC centred zoom.
            runCatching { player.scale = if (scale <= 1f) 0f else scale }
        }
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

    private companion object {
        // Reconnect a live tile if no frame has advanced for this long while we believe it's playing.
        const val STALL_TIMEOUT_MS = 7000L
        const val WATCHDOG_INTERVAL_MS = 2500L
    }
}
