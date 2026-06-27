package com.hiktv.viewer.data.model

/**
 * One NVR channel (= one physical camera). On a Hikvision NVR the RTSP "channel id" in
 * the stream URL is built from this [channel] number:  channel * 100 + streamType.
 *
 *   channel 1  -> 101 (main) / 102 (sub)
 *   channel 12 -> 1201 (main) / 1202 (sub)
 */
data class Camera(
    val channel: Int,
    val name: String,
    val online: Boolean = true,
    val ptzSupported: Boolean = false
) {
    val mainStreamCode: Int get() = channel * 100 + 1
    val subStreamCode: Int get() = channel * 100 + 2
}
