package com.hiktv.viewer.ui.grid

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import com.hiktv.viewer.core.Session
import com.hiktv.viewer.data.isapi.CamEvent
import com.hiktv.viewer.data.isapi.EventListener
import com.hiktv.viewer.data.model.Camera
import com.hiktv.viewer.util.Notifications
import com.hiktv.viewer.data.store.NvrStore
import com.hiktv.viewer.databinding.ActivityGridBinding
import com.hiktv.viewer.ui.fullscreen.FullscreenActivity
import com.hiktv.viewer.ui.playback.MultiPlaybackActivity
import com.hiktv.viewer.ui.settings.SettingsActivity
import com.hiktv.viewer.ui.setup.SetupActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Home screen: the live camera wall. Paints instantly from the persisted camera list, then
 * refreshes from the NVR. Long-press LEFT (or MENU) opens the full Settings page.
 */
class GridActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGridBinding
    private lateinit var adapter: GridAdapter
    private lateinit var store: NvrStore

    private var columns = 2
    private var leftLongFired = false
    private var downLongFired = false
    private var backAt = 0L
    private var displayed: List<Camera> = emptyList()

    // Fire-and-forget POST_NOTIFICATIONS prompt (result ignored; alerts degrade gracefully).
    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // Bring the wall's streams back after we shed them under memory pressure (see onTrimMemory).
    private val restoreStreams = Runnable {
        if (::adapter.isInitialized && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            adapter.startAllStreams()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGridBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // Live camera wall: keep the TV screensaver from blanking it while it's on screen.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        store = NvrStore(this)

        if (!Session.isReady) { finishToSetup(); return }

        adapter = GridAdapter(this, lifecycleScope, onOpen = { openFullscreen(it) }, onLongPress = { openMenu() })
        binding.recycler.layoutManager = GridLayoutManager(this, columns)
        binding.recycler.adapter = adapter
        binding.recycler.setHasFixedSize(true)
        binding.recycler.itemAnimator = null

        binding.btnEmptyAutoDetect.setOnClickListener { runAutoDetect() }
        binding.btnEmptySettings.setOnClickListener { openMenu() }

        // Double-press BACK to exit (single press shows a hint), via the modern dispatcher.
        onBackPressedDispatcher.addCallback(this) {
            val now = SystemClock.elapsedRealtime()
            if (now - backAt < 2000) {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            } else {
                backAt = now
                Toast.makeText(this@GridActivity, "Press back again to exit", Toast.LENGTH_SHORT).show()
            }
        }

        requestNotificationsIfNeeded()
        loadCameras()
        listenForMotion()
    }

    override fun onStart() {
        super.onStart()
        // Returning to the wall: restart the tile streams we released on leaving. (No-op on
        // first launch — tiles start themselves when their views attach.)
        if (::adapter.isInitialized) adapter.startAllStreams()
    }

    override fun onStop() {
        // Free all decoders/surfaces while another screen (fullscreen, settings) is on top —
        // this is what prevents the "too many surfaces" churn and crash on return.
        binding.recycler.removeCallbacks(restoreStreams)
        if (::adapter.isInitialized) adapter.releaseAllStreams()
        super.onStop()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // The system is critically low on RAM while we're foreground and about to start killing
        // apps. Shed the live decoders now (the biggest native consumer on a software-decoded wall,
        // made worse by largeHeap) so we're a smaller LMK target; tiles fall back to their cached
        // snapshots, and we restore the streams once the pressure spike passes.
        if (level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL &&
            ::adapter.isInitialized) {
            adapter.releaseAllStreams()
            binding.recycler.removeCallbacks(restoreStreams)
            binding.recycler.postDelayed(restoreStreams, 4000)
        }
    }

    override fun onResume() {
        super.onResume()
        if (Session.gridDirty) {
            Session.gridDirty = false
            loadCameras()
        }
    }

    private fun loadCameras() {
        val cached = if (Session.cameras.isNotEmpty()) Session.cameras else store.loadCameras()
        if (cached.isNotEmpty()) { Session.cameras = cached; applyCameras(cached) }
        else { setLoading(true); binding.emptyPanel.visibility = View.GONE }

        lifecycleScope.launch {
            val cams = Session.isapi?.discoverCameras().orEmpty()
            if (cams.isEmpty() && Session.cameras.isEmpty()) { showEmpty(); return@launch }
            if (cams.isNotEmpty() && cams != displayed) {
                Session.cameras = cams
                store.saveCameras(cams)
                store.cachedChannelCount = cams.size
                applyCameras(cams)
            }
        }
    }

    private fun showEmpty() {
        setLoading(false)
        binding.recycler.visibility = View.GONE
        binding.emptyPanel.visibility = View.VISIBLE
        binding.btnEmptyAutoDetect.requestFocus()
    }

    private fun applyCameras(cams: List<Camera>) {
        displayed = cams
        setLoading(false)
        binding.emptyPanel.visibility = View.GONE
        binding.recycler.visibility = View.VISIBLE
        // Grid software-decodes by default. NOTE on Amlogic: hardware decode greens on the
        // multi-tile GL path (only the single-surface overlay is clean, and that can't show 4
        // tiles), so the grid MUST stay software there — even if the user picks "Hardware" mode.
        // Only explicit "Hardware" mode (1), and never on Amlogic, opts the grid into hardware.
        adapter.hardware = store.decoderMode == 1 && !com.hiktv.viewer.util.DeviceQuirks.isAmlogic
        columns = columnsFor(cams.size)
        (binding.recycler.layoutManager as GridLayoutManager).spanCount = columns
        adapter.submit(cams)
        sizeTilesToFill(cams.size)
        binding.recycler.post { binding.recycler.requestFocus() }
    }

    /** Honor the user's layout preference; otherwise a square-ish auto grid. */
    private fun columnsFor(count: Int): Int {
        val pref = store.gridColumns
        return when {
            pref == 1 -> 1
            pref in 2..6 -> pref
            count <= 1 -> 1
            else -> ceil(sqrt(count.toDouble())).toInt().coerceIn(2, 5)
        }
    }

    /** Divide the visible height exactly across rows (account for padding + 2dp tile margins). */
    private fun sizeTilesToFill(count: Int) {
        binding.recycler.post {
            val rows = ceil(count / columns.toDouble()).toInt().coerceAtLeast(1)
            val h = binding.recycler.height - binding.recycler.paddingTop - binding.recycler.paddingBottom
            if (h > 0) {
                val rowMarginPx = (2 * resources.displayMetrics.density).toInt()  // 1dp top + 1dp bottom
                adapter.tileHeight = ((h / rows) - rowMarginPx).coerceAtLeast(100)
                adapter.notifyDataSetChanged()
            }
        }
    }

    private fun listenForMotion() {
        val isapi = Session.isapi ?: return
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    try {
                        EventListener(isapi).events()
                            .collect { event -> onCameraEvent(event) }
                    } catch (c: CancellationException) {
                        throw c
                    } catch (_: Exception) {
                    }
                    delay(3000)
                }
            }
        }
    }

    private fun runAutoDetect() {
        setLoading(true)
        binding.emptyPanel.visibility = View.GONE
        lifecycleScope.launch {
            val cams = Session.isapi?.autoDetectByProbe().orEmpty()
            if (cams.isEmpty()) { showEmpty() } else {
                Session.cameras = cams
                store.saveCameras(cams)
                store.cachedChannelCount = cams.size
                applyCameras(cams)
            }
        }
    }

    private fun requestNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            runCatching {
                notifPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private val hideBanner = Runnable { binding.alertBanner.visibility = View.GONE }

    /** Badge the tile for every event; for user-selected cameras also banner + notify. */
    private fun onCameraEvent(e: CamEvent) {
        adapter.flash(binding.recycler, e.channel, e.label)
        if (e.channel in store.alertChannels) {
            val name = Session.cameraByChannel(e.channel)?.name ?: "Camera ${e.channel}"
            binding.alertBanner.text = "⚠  ${e.label} — $name"
            binding.alertBanner.visibility = View.VISIBLE
            binding.alertBanner.removeCallbacks(hideBanner)
            binding.alertBanner.postDelayed(hideBanner, 5000)
            Notifications.event(this, e.channel, name, e.label)
        }
    }

    private fun openFullscreen(cam: Camera) {
        startActivity(Intent(this, FullscreenActivity::class.java)
            .putExtra(FullscreenActivity.EXTRA_CHANNEL, cam.channel))
    }

    private fun openMenu() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_INFO) {
            openMenu(); return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /** Long-press LEFT opens Settings; long-press DOWN opens synced multi-camera playback.
     *  A short press still moves focus between tiles. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (event.repeatCount == 0) leftLongFired = false
                    val held = event.eventTime - event.downTime >= ViewConfiguration.getLongPressTimeout()
                    if (!leftLongFired && (event.isLongPress || held)) {
                        leftLongFired = true
                        openMenu()
                        return true
                    }
                }
                KeyEvent.ACTION_UP -> if (leftLongFired) { leftLongFired = false; return true }
            }
        }
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (event.repeatCount == 0) downLongFired = false
                    val held = event.eventTime - event.downTime >= ViewConfiguration.getLongPressTimeout()
                    if (!downLongFired && (event.isLongPress || held)) {
                        downLongFired = true
                        openMultiPlayback()
                        return true
                    }
                }
                KeyEvent.ACTION_UP -> if (downLongFired) { downLongFired = false; return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun openMultiPlayback() {
        if (Session.cameras.any { it.online }) {
            startActivity(Intent(this, MultiPlaybackActivity::class.java))
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.progress.visibility = if (loading) View.VISIBLE else View.GONE
    }

    private fun finishToSetup() {
        startActivity(Intent(this, SetupActivity::class.java))
        finish()
    }

}
