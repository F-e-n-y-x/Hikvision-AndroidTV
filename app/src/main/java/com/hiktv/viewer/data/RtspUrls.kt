package com.hiktv.viewer.data

import com.hiktv.viewer.data.model.Camera
import com.hiktv.viewer.data.model.Nvr
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Builds the RTSP URLs the player consumes. Credentials are embedded in the URL because
 * LibVLC authenticates that way; the URL never leaves the LAN.
 */
object RtspUrls {

    /** Live stream. [sub] = true selects the low-res sub-stream used in the grid. */
    fun live(nvr: Nvr, camera: Camera, sub: Boolean): String {
        val code = if (sub) camera.subStreamCode else camera.mainStreamCode
        return "rtsp://${cred(nvr)}@${nvr.host}:${nvr.rtspPort}/Streaming/Channels/$code"
    }

    /**
     * Recorded playback between two instants (Hikvision "tracks" endpoint).
     * Times are sent as UTC in the ISO-ish form Hikvision expects: yyyyMMdd'T'HHmmss'Z'.
     */
    fun playback(nvr: Nvr, camera: Camera, startUtc: Date, endUtc: Date): String {
        val trackId = camera.channel * 100 + 1
        val s = HIK_TIME.format(startUtc)
        val e = HIK_TIME.format(endUtc)
        return "rtsp://${cred(nvr)}@${nvr.host}:${nvr.rtspPort}/Streaming/tracks/$trackId" +
            "?starttime=$s&endtime=$e"
    }

    private fun cred(nvr: Nvr): String {
        val u = URLEncoder.encode(nvr.username, "UTF-8")
        val p = URLEncoder.encode(nvr.password, "UTF-8")
        return "$u:$p"
    }

    private val HIK_TIME: SimpleDateFormat
        get() = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
}
