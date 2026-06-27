package com.hiktv.viewer.ui.playback

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.hiktv.viewer.core.Session
import com.hiktv.viewer.data.RtspUrls
import com.hiktv.viewer.data.model.Camera
import com.hiktv.viewer.data.store.NvrStore
import com.hiktv.viewer.databinding.ActivityPlaybackBinding
import com.hiktv.viewer.player.PlayerEngine
import kotlinx.coroutines.launch
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * NVR playback with a scrubbable 24-hour timeline, tuned for a standard Mi remote (D-pad only).
 *
 *   ◀ ▶ scrub · OK play/pause · ▲ ▼ jump ±1 hour · MENU show/hide the bar
 *
 * Times are "NVR clock" pseudo-epoch millis (the NVR labels local wall-time with a 'Z', so we
 * treat its labels as UTC consistently for search, display and playback URLs) and shown in 12h.
 * While playing, the playhead advances on a wall-clock anchor (RTSP "tracks" playback doesn't
 * report a reliable media position), and the bar auto-minimizes after a few seconds so it
 * doesn't cover the picture.
 */
class PlaybackActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlaybackBinding
    private lateinit var camera: Camera
    private var player: MediaPlayer? = null

    private val day = 86_400_000L
    private var windowStart = 0L
    private var windowEnd = 0L
    private var playhead = 0L
    private var playFrom = 0L
    private var playing = false

    /** Wall-clock anchor: pseudo-time [playFrom] corresponds to elapsedRealtime [playAnchorElapsed]. */
    private var playAnchorElapsed = 0L

    private val ui = Handler(Looper.getMainLooper())
    // 12-hour clock with AM/PM.
    private val clock = SimpleDateFormat("hh:mm:ss a", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    private val dayFmt = SimpleDateFormat("EEE, dd MMM", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    private val minimizeBar = Runnable { setBarMinimized(true) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlaybackBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val channel = intent.getIntExtra(EXTRA_CHANNEL, -1)
        val cam = Session.cameraByChannel(channel)
        if (cam == null || Session.nvr == null) { finish(); return }
        camera = cam
        binding.title.text = "Playback · ${cam.name}"

        // Today's window in NVR clock: pseudo-now = true-now + local offset; floor to day.
        val pseudoNow = System.currentTimeMillis() +
            TimeZone.getDefault().getOffset(System.currentTimeMillis())
        windowStart = (pseudoNow / day) * day
        windowEnd = windowStart + day
        playhead = pseudoNow.coerceIn(windowStart, windowEnd)

        binding.timeline.setWindow(windowStart, windowEnd)
        binding.timeline.setPlayhead(playhead)
        binding.dayLabel.text = dayFmt.format(Date(windowStart))
        updateTimeLabel()
        loadSegments()
    }

    private fun loadSegments() {
        binding.status.visibility = View.VISIBLE
        binding.status.text = "Loading recordings…"
        lifecycleScope.launch {
            val segs = Session.isapi?.searchRecordings(camera.channel, windowStart, windowEnd).orEmpty()
            binding.timeline.setSegments(segs)
            binding.status.text = if (segs.isEmpty()) "No recordings today" else ""
            binding.status.visibility = if (segs.isEmpty()) View.VISIBLE else View.GONE
            // Start a couple of minutes before the end of the latest recording — NOT its very
            // last frame, which would hit end-of-recording instantly and show a black screen.
            segs.lastOrNull()?.let {
                playhead = (it.endMs - 120_000L).coerceIn(it.startMs, windowEnd)
            }
            binding.timeline.setPlayhead(playhead)
            updateTimeLabel()
        }
    }

    // ---- Controls ----------------------------------------------------------

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Any control press wakes the bar back up and restarts the auto-minimize timer.
        if (keyCode != KeyEvent.KEYCODE_BACK) revealBar()
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { scrub(-stepFor(event)); true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { scrub(stepFor(event)); true }
            KeyEvent.KEYCODE_DPAD_UP -> { scrub(3600_000L); true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { scrub(-3600_000L); true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ->
                { onOk(); true }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO ->
                { setBarMinimized(binding.controlBar.visibility == View.VISIBLE); true }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    /** Hold to scrub faster: 30 s per tap, growing to 5 min when the key repeats. */
    private fun stepFor(event: KeyEvent): Long =
        if (event.repeatCount > 6) 300_000L else if (event.repeatCount > 2) 120_000L else 30_000L

    private fun scrub(deltaMs: Long) {
        if (playing) pausePlayback()
        playhead = (playhead + deltaMs).coerceIn(windowStart, windowEnd)
        binding.timeline.setPlayhead(playhead)
        updateTimeLabel()
    }

    private fun onOk() {
        val p = player
        if (playing && p != null) { pausePlayback(); return }
        if (!playing && p != null && playhead == playFrom) { resumePlayback(); return }
        startPlaybackFrom(playhead)
    }

    private fun startPlaybackFrom(fromPseudo: Long) {
        val nvr = Session.nvr ?: return
        releasePlayer()
        binding.status.visibility = View.VISIBLE
        binding.status.text = "Loading…"
        val url = RtspUrls.playback(nvr, camera, Date(fromPseudo), Date(windowEnd))
        val hw = NvrStore(this).decoderMode != 2
        val mp = MediaPlayer(PlayerEngine.get(this))
        mp.attachViews(binding.videoLayout, null, false, false)
        mp.setEventListener { e ->
            when (e.type) {
                MediaPlayer.Event.Playing -> binding.status.post {
                    binding.status.visibility = View.GONE
                    // Re-anchor the clock the moment frames actually start flowing.
                    playFrom = fromPseudo
                    playAnchorElapsed = SystemClock.elapsedRealtime()
                }
                MediaPlayer.Event.EncounteredError ->
                    binding.status.post { binding.status.visibility = View.VISIBLE; binding.status.text = "No recording here" }
                MediaPlayer.Event.EndReached -> binding.status.post {
                    pausePlayback()
                    binding.status.visibility = View.VISIBLE
                    binding.status.text = "End of recording — scrub back (◀) then OK"
                }
            }
        }
        val media = Media(PlayerEngine.get(this), Uri.parse(url)).apply {
            setHWDecoderEnabled(hw, false)
            // Recordings don't need ultra-low latency; a moderate cache + frame-dropping make
            // playback smooth on weak TV SoCs instead of stuttering, while still starting fast.
            addOption(":network-caching=800")
            addOption(":rtsp-tcp")
            addOption(":clock-jitter=0")
            addOption(":clock-synchro=0")
            addOption(":drop-late-frames")
            addOption(":skip-frames")
            addOption(":avcodec-skiploopfilter=4")
        }
        mp.media = media
        media.release()
        mp.play()
        player = mp
        playFrom = fromPseudo
        playAnchorElapsed = SystemClock.elapsedRealtime()
        playing = true
        startPlayheadUpdater()
    }

    private fun resumePlayback() {
        player?.play()
        playFrom = playhead
        playAnchorElapsed = SystemClock.elapsedRealtime()
        playing = true
        startPlayheadUpdater()
    }

    private fun pausePlayback() {
        player?.pause()
        playing = false
        ui.removeCallbacks(advance)
        revealBar()
    }

    /** Advance the playhead from the wall-clock anchor (playback runs at 1×). */
    private val advance = object : Runnable {
        override fun run() {
            if (!playing) return
            val elapsed = SystemClock.elapsedRealtime() - playAnchorElapsed
            playhead = (playFrom + elapsed).coerceIn(windowStart, windowEnd)
            binding.timeline.setPlayhead(playhead)
            updateTimeLabel()
            ui.postDelayed(this, 500)
        }
    }

    private fun startPlayheadUpdater() {
        ui.removeCallbacks(advance)
        ui.postDelayed(advance, 500)
    }

    private fun updateTimeLabel() {
        val t = clock.format(Date(playhead))
        binding.playheadTime.text = t
        binding.compactTime.text = t
    }

    // ---- Auto-minimizing bar ----------------------------------------------

    private fun revealBar() {
        setBarMinimized(false)
        ui.removeCallbacks(minimizeBar)
        // Only auto-hide while actually playing; keep it up while paused/scrubbing.
        if (playing) ui.postDelayed(minimizeBar, 5000)
    }

    private fun setBarMinimized(minimized: Boolean) {
        binding.controlBar.visibility = if (minimized) View.GONE else View.VISIBLE
        binding.compactTime.visibility = if (minimized && playing) View.VISIBLE else View.GONE
        if (!minimized) {
            ui.removeCallbacks(minimizeBar)
            if (playing) ui.postDelayed(minimizeBar, 5000)
        }
    }

    private fun releasePlayer() {
        ui.removeCallbacks(advance)
        player?.let {
            runCatching { it.stop() }
            runCatching { it.detachViews() }
            runCatching { it.release() }
        }
        player = null
        playing = false
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        releasePlayer()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_CHANNEL = "channel"
    }
}
