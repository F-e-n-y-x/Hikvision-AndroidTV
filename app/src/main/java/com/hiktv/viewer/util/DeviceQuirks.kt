package com.hiktv.viewer.util

import android.os.Build

/**
 * Per-chip workarounds. Amlogic TV boxes (e.g. Mi Box, S905 family) reliably produce green /
 * corner-cropped frames when LibVLC hardware-decodes H.265 — a long-standing VLC bug
 * (code.videolan.org/videolan/vlc/-/issues/26931). They decode H.264 perfectly, though, which
 * is why the H.264 sub-stream wall looks fine. So on these devices we steer live view to the
 * sub-stream and avoid hardware H.265.
 */
object DeviceQuirks {

    val isAmlogic: Boolean by lazy {
        val hw = (Build.HARDWARE + " " + Build.BOARD).lowercase()
        hw.contains("amlogic") ||
            prop("ro.hardware").contains("amlogic") ||
            prop("ro.board.platform") in AMLOGIC_PLATFORMS
    }

    private val AMLOGIC_PLATFORMS = setOf(
        "oneday", "gxl", "gxbb", "gxm", "g12a", "g12b", "sm1", "txl", "t962", "tm2", "sc2"
    )

    private fun prop(key: String): String = runCatching {
        @Suppress("PrivateApi")
        val c = Class.forName("android.os.SystemProperties")
        (c.getMethod("get", String::class.java).invoke(null, key) as String).lowercase()
    }.getOrDefault("")
}
