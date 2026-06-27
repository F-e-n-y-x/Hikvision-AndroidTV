package com.hiktv.viewer.data.model

/**
 * Connection details for a single Hikvision NVR. All cameras (Hikvision + EZVIZ added
 * to the NVR) are reached through this one device.
 *
 * @param host        LAN IP or hostname of the NVR
 * @param httpPort    ISAPI/HTTP port (default 80)
 * @param rtspPort    RTSP port (default 554)
 * @param username    NVR account (needs Remote: Live View + Playback + PTZ permissions)
 * @param password    NVR account password
 * @param useHttps    use https for ISAPI calls
 */
data class Nvr(
    val host: String,
    val httpPort: Int = 80,
    val rtspPort: Int = 554,
    val username: String,
    val password: String,
    val useHttps: Boolean = false
) {
    val httpScheme: String get() = if (useHttps) "https" else "http"

    val httpBase: String get() = "$httpScheme://$host:$httpPort"

    fun isValid(): Boolean =
        host.isNotBlank() && username.isNotBlank() && httpPort in 1..65535 && rtspPort in 1..65535
}
