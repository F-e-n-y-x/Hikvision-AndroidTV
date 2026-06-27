package com.hiktv.viewer.ui.grid

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.hiktv.viewer.core.Session
import com.hiktv.viewer.data.RtspUrls
import com.hiktv.viewer.data.model.Camera
import com.hiktv.viewer.databinding.ItemCameraBinding
import com.hiktv.viewer.player.CameraStream
import com.hiktv.viewer.util.SnapshotCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Renders one live sub-stream per camera tile. Each tile shows a cached snapshot instantly,
 * then the live stream takes over once its first frame arrives. Streams are created on attach
 * and released on detach, so only on-screen cameras consume CPU/network.
 */
class GridAdapter(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onOpen: (Camera) -> Unit,
    private val onLongPress: () -> Unit
) : RecyclerView.Adapter<GridAdapter.Holder>() {

    private val cameras = ArrayList<Camera>()

    /** Pixel height for each tile so the grid fills the screen without scrolling; 0 = default. */
    var tileHeight = 0

    /** Whether grid tiles use hardware decoding (false = software, the safe default for H.265 walls). */
    var hardware = false

    fun submit(list: List<Camera>) {
        cameras.clear()
        cameras.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemCameraBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun getItemCount(): Int = cameras.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val cam = cameras[position]
        holder.camera = cam
        if (tileHeight > 0) {
            holder.itemView.layoutParams = holder.itemView.layoutParams.apply {
                height = tileHeight
            }
        }
        holder.binding.cameraName.text = cam.name
        holder.binding.statusText.text = if (cam.online) "Connecting…" else "Offline"
        holder.binding.statusText.visibility = View.VISIBLE
        val dot = androidx.core.content.ContextCompat.getColor(
            context, if (cam.online) com.hiktv.viewer.R.color.online else com.hiktv.viewer.R.color.offline
        )
        holder.binding.onlineDot.backgroundTintList = android.content.res.ColorStateList.valueOf(dot)

        // Instant preview: paint the last cached snapshot immediately (before any stream).
        val cached = SnapshotCache.load(context, cam.channel)
        if (cached != null) {
            holder.binding.preview.setImageBitmap(cached)
            holder.binding.preview.alpha = if (cam.online) 1f else 0.35f
            holder.binding.preview.visibility = View.VISIBLE
        } else {
            holder.binding.preview.setImageDrawable(null)
            holder.binding.preview.visibility = View.GONE
        }

        holder.binding.root.setOnClickListener { if (cam.online) onOpen(cam) }
        holder.binding.root.setOnLongClickListener { onLongPress(); true }
    }

    private val attached = LinkedHashSet<Holder>()

    override fun onViewAttachedToWindow(holder: Holder) {
        attached.add(holder)
        holder.startStream()
    }

    override fun onViewDetachedFromWindow(holder: Holder) {
        attached.remove(holder)
        holder.stopStream()
    }

    /** Release every on-screen stream (call when the grid leaves the foreground). */
    fun releaseAllStreams() {
        attached.forEach { it.stopStream() }
    }

    /** Restart every on-screen stream (call when the grid returns to the foreground). */
    fun startAllStreams() {
        attached.forEach { it.startStream() }
    }

    /** Briefly badge a tile when the NVR reports an event (motion / line / intrusion) on it. */
    fun flash(recycler: RecyclerView, channel: Int, label: String) {
        val index = cameras.indexOfFirst { it.channel == channel }
        if (index < 0) return
        val holder = recycler.findViewHolderForAdapterPosition(index) as? Holder ?: return
        holder.binding.motionBadge.text = label.uppercase()
        holder.binding.motionBadge.visibility = View.VISIBLE
        holder.binding.motionBadge.removeCallbacks(holder.clearMotion)
        holder.binding.motionBadge.postDelayed(holder.clearMotion, 4000)
    }

    inner class Holder(val binding: ItemCameraBinding) : RecyclerView.ViewHolder(binding.root) {
        var camera: Camera? = null
        private var stream: CameraStream? = null
        private var snapshotJob: kotlinx.coroutines.Job? = null

        val clearMotion = Runnable { binding.motionBadge.visibility = View.GONE }

        init {
            binding.root.isFocusable = true
            binding.root.isFocusableInTouchMode = false
        }

        fun startStream() {
            val cam = camera ?: return
            val nvr = Session.nvr ?: return
            if (!cam.online) return
            stream?.release()
            refreshSnapshot(cam.channel)
            // Grid uses the low-res sub-stream + a small cache: keeps many tiles realtime
            // on a weak CPU without flooding the network.
            val url = RtspUrls.live(nvr, cam, sub = true)
            stream = CameraStream(
                context = context,
                url = url,
                networkCachingMs = 100,    // low latency for a realtime wall (LAN)
                muted = true,
                hardware = hardware
            ) { state ->
                binding.statusText.post {
                    val playing = state == CameraStream.State.PLAYING
                    binding.statusText.visibility = if (playing) View.GONE else View.VISIBLE
                    if (playing) binding.preview.visibility = View.GONE   // reveal live video
                    if (state == CameraStream.State.ERROR) binding.statusText.text = "Reconnecting…"
                }
            }.also {
                // Stagger startup so we don't allocate many decoders at the same instant.
                val pos = bindingAdapterPosition.coerceAtLeast(0)
                it.start(binding.videoLayout, pos * 150L)
            }
        }

        /** Pull a fresh JPEG snapshot, cache it, and show it until the live frame arrives. */
        private fun refreshSnapshot(channel: Int) {
            snapshotJob?.cancel()
            snapshotJob = scope.launch {
                val bytes = withContext(Dispatchers.IO) { Session.isapi?.snapshot(channel) } ?: return@launch
                SnapshotCache.save(context, channel, bytes)
                val bmp = withContext(Dispatchers.IO) { SnapshotCache.decode(bytes) } ?: return@launch
                if (camera?.channel != channel) return@launch          // recycled to another camera
                binding.preview.setImageBitmap(bmp)
                binding.preview.alpha = 1f
                if (binding.statusText.visibility == View.VISIBLE) {
                    binding.preview.visibility = View.VISIBLE           // still connecting → show preview
                }
            }
        }

        fun stopStream() {
            snapshotJob?.cancel()
            snapshotJob = null
            stream?.release()
            stream = null
        }
    }
}
