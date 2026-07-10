package com.hiktv.viewer.util

import android.media.MediaCodecList
import android.media.MediaFormat

/**
 * Probes how many concurrent HARDWARE video-decoder sessions the SoC actually supports, so callers
 * can cap simultaneous hardware streams instead of silently exceeding the chip's limit. Cheap TV
 * SoCs commonly allow only 2-4 concurrent HEVC sessions; exceeding it black-screens/greens tiles or
 * crashes. Values are probed once and cached, with a conservative fallback when the query fails.
 */
object DecoderCaps {

    /** Max concurrent H.265 (HEVC) hardware-decoder instances, clamped to a safe range. */
    val maxHevcInstances: Int by lazy { maxInstances(MediaFormat.MIMETYPE_VIDEO_HEVC) }

    /** Max concurrent H.264 (AVC) hardware-decoder instances, clamped to a safe range. */
    val maxAvcInstances: Int by lazy { maxInstances(MediaFormat.MIMETYPE_VIDEO_AVC) }

    private fun maxInstances(mime: String): Int = runCatching {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        var best = 0
        for (info in list.codecInfos) {
            if (info.isEncoder) continue
            if (info.supportedTypes.none { it.equals(mime, ignoreCase = true) }) continue
            // Only the hardware decoder's limit matters — skip Google/AOSP software fallbacks.
            val name = info.name.lowercase()
            val software = name.startsWith("omx.google") || name.startsWith("c2.android") ||
                name.contains("google") || name.contains(".sw.")
            if (software) continue
            val caps = runCatching { info.getCapabilitiesForType(mime) }.getOrNull() ?: continue
            if (caps.maxSupportedInstances > best) best = caps.maxSupportedInstances
        }
        best
    }.getOrDefault(0).let { if (it <= 0) 2 else it.coerceIn(1, 16) }
}
