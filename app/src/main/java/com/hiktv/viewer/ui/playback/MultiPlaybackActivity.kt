package com.hiktv.viewer.ui.playback

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.hiktv.viewer.core.Session
import com.hiktv.viewer.data.RtspUrls
import com.hiktv.viewer.data.isapi.IsapiClient
import com.hiktv.viewer.data.model.Camera
import com.hiktv.viewer.data.store.NvrStore
import com.hiktv.viewer.databinding.ActivityMultiplaybackBinding
import com.hiktv.viewer.player.PlayerEngine
import kotlinx.coroutines.launch
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.ceil

/**
 * Synchronized multi-camera playback: every online camera plays the SAME point on one shared
 * 24-hour timeline. Scrub once and all cameras jump together; OK plays/pauses them all.
 *
 *   ◀ ▶ scrub all · OK play/pause all · ▲ ▼ ±1 hour
 *
 * Heavy on weak TVs (N high-res recordings at once) — starts are staggered and streams are
 * released when the screen leaves the foreground.
 */
class MultiPlaybackActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMultiplaybackBinding
    private val cams = ArrayList<Camera>()
    private val cells = ArrayList<VLCVideoLayout>()
    private val players = ArrayList<MediaPlayer?>()

    private val day = 86_400_000L
    private var windowStart = 0L
    private var windowEnd = 0L
    private var playhead = 0L
    private var playFrom = 0L
    private var playing = false
    private var anchorElapsed = 0L
    private var hasPlayed = false
    private var playGen = 0

    // Ignore the leftover ▼ key-up/repeats from the grid long-press that launched us, so that
    // press doesn't immediately scrub. Real remote commands register after this.
    private var acceptKeysAt = 0L

    private val ui = Handler(Looper.getMainLooper())
    private val clock = SimpleDateFormat("hh:mm:ss a", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    private val dayFmt = SimpleDateFormat("EEE, dd MMM", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMultiplaybackBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // Synced playback video: keep the screen awake while watching.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val online = Session.cameras.filter { it.online }
        if (online.isEmpty() || Session.nvr == null) { finish(); return }
        // Cap simultaneous full-res decoders: N main-stream H.264/H.265 players at once exhausts
        // MediaCodec on weak TVs and can crash/reboot them. The cap is device-aware (probed HW
        // decoder-instance limit, or a modest bound on the software-decoded Amlogic path).
        val cellCap = cellCapFor()
        cams += online.take(cellCap)
        if (online.size > cellCap) {
            android.widget.Toast.makeText(
                this, "Showing first $cellCap of ${online.size} cameras", android.widget.Toast.LENGTH_LONG
            ).show()
        }

        buildGrid()

        val pseudoNow = System.currentTimeMillis() +
            TimeZone.getDefault().getOffset(System.currentTimeMillis())
        windowStart = (pseudoNow / day) * day
        windowEnd = windowStart + day
        playhead = pseudoNow.coerceIn(windowStart, windowEnd)

        binding.timeline.setWindow(windowStart, windowEnd)
        binding.timeline.setPlayhead(playhead)
        binding.dayLabel.text = dayFmt.format(Date(windowStart))
        updateTimeLabel()
        acceptKeysAt = SystemClock.elapsedRealtime() + 800
        loadSegments()
    }

    /** How many cameras we can safely decode at once on this device (never more than MAX_CELLS). */
    private fun cellCapFor(): Int {
        val cap = if (com.hiktv.viewer.util.DeviceQuirks.isAmlogic) {
            4   // software-decoded on Amlogic (see playAllFrom): bound by CPU, keep it modest
        } else {
            // Hardware: never request more concurrent HW decoders than the SoC actually supports.
            minOf(com.hiktv.viewer.util.DecoderCaps.maxHevcInstances,
                  com.hiktv.viewer.util.DecoderCaps.maxAvcInstances)
        }
        return minOf(MAX_CELLS, cap.coerceAtLeast(1))
    }

    private fun buildGrid() {
        val cols = columnsFor(cams.size)
        val rows = ceil(cams.size.toFloat() / cols).toInt()
        binding.grid.columnCount = cols
        binding.grid.rowCount = rows
        val m = (2 * resources.displayMetrics.density).toInt()
        cams.forEachIndexed { i, cam ->
            val cell = FrameLayout(this)
            val vlc = VLCVideoLayout(this)
            cell.addView(vlc, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            val name = TextView(this).apply {
                text = cam.name
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 11f
                setBackgroundResource(com.hiktv.viewer.R.drawable.bg_name_pill)
                setPadding(m * 3, m, m * 3, m)
            }
            cell.addView(name, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.BOTTOM or Gravity.END; setMargins(m, m, m, m) })

            // If the last camera is alone on its row, let it span the leftover columns so no
            // space is wasted (e.g. 3 cams: cam3 spans the full bottom row).
            val colInRow = i % cols
            val lastAlone = i == cams.size - 1 && cams.size % cols != 0
            val span = if (lastAlone) cols - colInRow else 1
            val lp = GridLayout.LayoutParams().apply {
                width = 0; height = 0
                columnSpec = GridLayout.spec(colInRow, span, span.toFloat())
                rowSpec = GridLayout.spec(i / cols, 1f)
                setGravity(Gravity.FILL)     // make each cell fill its grid area (not wrap small)
                setMargins(m, m, m, m)
            }
            binding.grid.addView(cell, lp)
            cells += vlc
            players += null
        }
    }

    private fun columnsFor(n: Int) = when {
        n <= 1 -> 1; n <= 4 -> 2; n <= 9 -> 3; else -> 4
    }

    private fun loadSegments() {
        lifecycleScope.launch {
            val all = ArrayList<IsapiClient.Segment>()
            val isapi = Session.isapi
            if (isapi != null) for (c in cams) {
                all += runCatching { isapi.searchRecordings(c.channel, windowStart, windowEnd) }.getOrDefault(emptyList())
            }
            binding.timeline.setSegments(all.sortedBy { it.startMs })
            all.maxOfOrNull { it.endMs }?.let {
                playhead = (it - 120_000L).coerceIn(windowStart, windowEnd)
                binding.timeline.setPlayhead(playhead)
                updateTimeLabel()
            }
            // Auto-start so the wall plays immediately (no extra button press needed).
            playAllFrom(playhead)
        }
    }

    // ---- Controls ----------------------------------------------------------

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Swallow only the leftover ▼ from the grid long-press that launched us — not every key
        // (so an immediate BACK still works).
        if (SystemClock.elapsedRealtime() < acceptKeysAt && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) return true
        return onCmd(keyCode, event)
    }

    private fun onCmd(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_LEFT -> { scrub(-stepFor(event)); true }
        KeyEvent.KEYCODE_DPAD_RIGHT -> { scrub(stepFor(event)); true }
        KeyEvent.KEYCODE_DPAD_UP -> { scrub(3600_000L); true }
        KeyEvent.KEYCODE_DPAD_DOWN -> { scrub(-3600_000L); true }
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ->
            { onOk(); true }
        else -> super.onKeyDown(keyCode, event)
    }

    private fun stepFor(event: KeyEvent): Long =
        if (event.repeatCount > 6) 300_000L else if (event.repeatCount > 2) 120_000L else 30_000L

    private fun scrub(deltaMs: Long) {
        pauseAll()
        releasePlayers()
        playhead = (playhead + deltaMs).coerceIn(windowStart, windowEnd)
        binding.timeline.setPlayhead(playhead)
        updateTimeLabel()
    }

    private fun onOk() {
        when {
            playing -> pauseAll()
            players.any { it != null } -> resumeAll()
            else -> playAllFrom(playhead)
        }
    }

    private fun playAllFrom(fromPseudo: Long) {
        val nvr = Session.nvr ?: return
        releasePlayers()
        val store = NvrStore(this)
        val amlogic = com.hiktv.viewer.util.DeviceQuirks.isAmlogic
        // Amlogic greens hardware H.265 on the multi-tile GL path (same rule as the grid; the
        // single-surface :vout overlay can't clip N cells either), so force SOFTWARE here regardless
        // of decoder mode. Elsewhere honour the user's Software setting.
        val hw = store.decoderMode != 2 && !amlogic
        val directRender = store.directRender || amlogic
        playFrom = fromPseudo
        anchorElapsed = SystemClock.elapsedRealtime()
        playing = true
        hasPlayed = true
        // Token this play session so stale staggered play() runnables from a prior session (e.g.
        // after a scrub → releasePlayers) can't call play() on an already-released MediaPlayer.
        val gen = ++playGen
        binding.status.text = "Playing ${cams.size} cameras…"
        cams.forEachIndexed { i, cam ->
            val url = RtspUrls.playback(nvr, cam, Date(fromPseudo), Date(windowEnd))
            val mp = MediaPlayer(PlayerEngine.get(this))
            mp.attachViews(cells[i], null, false, false)
            val media = Media(PlayerEngine.get(this), Uri.parse(url)).apply {
                setHWDecoderEnabled(hw, false)
                if (hw && !directRender) addOption(":no-mediacodec-dr")   // copy path: green-fix on MiTV
                addOption(":network-caching=1500")
                addOption(":rtsp-tcp")
                addOption(":drop-late-frames")
                addOption(":skip-frames")
                addOption(":avcodec-skiploopfilter=4")
            }
            mp.media = media
            media.release()
            players[i] = mp
            // Stagger so we don't allocate every decoder in the same instant (crash-prone on weak TVs).
            ui.postDelayed({ if (playing && gen == playGen) runCatching { mp.play() } }, i * 300L)
        }
        startUpdater()
    }

    private fun resumeAll() {
        playFrom = playhead
        anchorElapsed = SystemClock.elapsedRealtime()
        playing = true
        players.forEach { runCatching { it?.play() } }
        binding.status.text = ""
        startUpdater()
    }

    private fun pauseAll() {
        playing = false
        ui.removeCallbacks(advance)
        players.forEach { runCatching { it?.pause() } }
    }

    private val advance = object : Runnable {
        override fun run() {
            if (!playing) return
            playhead = (playFrom + (SystemClock.elapsedRealtime() - anchorElapsed)).coerceIn(windowStart, windowEnd)
            binding.timeline.setPlayhead(playhead)
            updateTimeLabel()
            ui.postDelayed(this, 500)
        }
    }

    private fun startUpdater() {
        ui.removeCallbacks(advance)
        ui.postDelayed(advance, 500)
    }

    private fun updateTimeLabel() {
        binding.playheadTime.text = clock.format(Date(playhead))
    }

    private fun releasePlayers() {
        playGen++                       // invalidate any pending staggered play() runnables
        ui.removeCallbacks(advance)
        for (i in players.indices) {
            players[i]?.let {
                runCatching { it.setEventListener(null) }
                runCatching { it.stop() }
                runCatching { it.detachViews() }
                runCatching { it.release() }
            }
            players[i] = null
        }
        playing = false
    }

    // Hoisted so onStop can cancel it: without the STARTED guard + cancel, a fast onStart→onStop
    // inside the 250ms window would fire this while backgrounded and allocate up to MAX_CELLS
    // decoders off-screen — the exact MediaCodec-exhaustion path this screen is capped to avoid.
    private val resumeRunnable = Runnable {
        if (players.all { it == null } && !isFinishing &&
            lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
            playAllFrom(playhead)
        }
    }

    override fun onStart() {
        super.onStart()
        // Resume after returning from background (we released everything in onStop).
        if (hasPlayed && cells.isNotEmpty() && players.all { it == null } && !isFinishing) {
            binding.grid.postDelayed(resumeRunnable, 250)
        }
    }

    override fun onStop() {
        binding.grid.removeCallbacks(resumeRunnable)
        releasePlayers()
        super.onStop()
    }

    override fun onDestroy() {
        binding.grid.removeCallbacks(resumeRunnable)
        ui.removeCallbacksAndMessages(null)
        releasePlayers()
        super.onDestroy()
    }

    companion object {
        /** Max cameras decoded at once in synced playback (guards weak TVs from MediaCodec exhaustion). */
        private const val MAX_CELLS = 9
    }
}
