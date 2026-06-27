package com.hiktv.viewer.player

import android.content.Context
import org.videolan.libvlc.LibVLC

/**
 * Single shared LibVLC instance for the whole app. Creating one native engine and reusing
 * it across every tile keeps memory and CPU down on weak TV SoCs.
 *
 * The global options below are the low-latency / low-CPU baseline; per-stream tuning
 * (caching size, audio) is applied as Media options in [CameraStream].
 */
object PlayerEngine {

    @Volatile
    private var libVlc: LibVLC? = null

    fun get(context: Context): LibVLC =
        libVlc ?: synchronized(this) {
            libVlc ?: LibVLC(context.applicationContext, buildOptions()).also { libVlc = it }
        }

    private fun buildOptions(): ArrayList<String> = arrayListOf<String>().apply {
        // Amlogic (Mi Box / S905) crashes in the Mali OpenGL video-output path (SIGSEGV in
        // libGLES_mali on the AWindowHandler thread) when the surface re-inits. Use the direct
        // ANativeWindow display (hardware overlay, no GL) on those chips to avoid the crash.
        if (com.hiktv.viewer.util.DeviceQuirks.isAmlogic) add("--vout=android_display")
        addAll(baseOptions())
    }

    private fun baseOptions(): ArrayList<String> = arrayListOf(
        // Transport: TCP is artifact-free over LAN; pulls reliable frames from the NVR.
        "--rtsp-tcp",
        // Hardware decode wherever the SoC offers it (H.264 + H.265). This is the single
        // biggest win for low-end CPUs.
        "--avcodec-hw=any",
        // Stay in real time: throw away frames we can't show on schedule instead of
        // building up a lagging backlog.
        "--drop-late-frames",
        "--skip-frames",
        // Don't try to smooth the clock; we want "now", not "smooth".
        "--clock-jitter=0",
        "--clock-synchro=0",
        // Trim everything that costs CPU but adds nothing on a CCTV wall.
        "--no-audio-time-stretch",
        "--no-snapshot-preview",
        "--no-video-title-show",
        "--no-osd",
        "--no-stats",
        "--no-spu",            // no subtitle rendering
        // Quiet logs in production; flip to "-vv" while debugging streams.
        "-v"
    )

    /** Call on full app shutdown if desired. Normally we keep the engine alive. */
    fun release() {
        synchronized(this) {
            libVlc?.release()
            libVlc = null
        }
    }
}
