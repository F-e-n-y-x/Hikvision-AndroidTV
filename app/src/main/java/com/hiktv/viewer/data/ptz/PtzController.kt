package com.hiktv.viewer.data.ptz

import com.hiktv.viewer.data.ezviz.EzvizCloud
import com.hiktv.viewer.data.isapi.IsapiClient
import com.hiktv.viewer.data.onvif.OnvifPtz

/**
 * One PTZ surface for the UI, regardless of how the camera is actually reached:
 * through the NVR's ISAPI proxy, straight to the camera over ISAPI, or over ONVIF.
 * Move values are -100..100 (the ISAPI scale); ONVIF normalises them to -1.0..1.0.
 */
interface PtzController {
    /** True if this camera can actually be driven (capabilities present / services reachable). */
    suspend fun probe(): Boolean
    suspend fun move(pan: Int, tilt: Int, zoom: Int): Boolean
    suspend fun stop(): Boolean
}

/** PTZ over Hikvision ISAPI — either the NVR (channel = camera channel) or a direct camera (channel 1). */
class IsapiPtzController(
    private val client: IsapiClient,
    private val channel: Int
) : PtzController {
    override suspend fun probe() = runCatching { client.supportsPtz(channel) }.getOrDefault(false)
    override suspend fun move(pan: Int, tilt: Int, zoom: Int) =
        client.ptzContinuous(channel, pan, tilt, zoom)
    override suspend fun stop() = client.ptzStop(channel)
}

/** PTZ over ONVIF (for cameras the NVR won't proxy, e.g. EZVIZ with ONVIF enabled). */
class OnvifPtzController(private val onvif: OnvifPtz) : PtzController {
    override suspend fun probe() = onvif.ensureReady()
    override suspend fun move(pan: Int, tilt: Int, zoom: Int) =
        onvif.continuousMove(pan / 100f, tilt / 100f, zoom / 100f)
    override suspend fun stop() = onvif.stop()
}

/**
 * PTZ via the EZVIZ cloud — the only way to move EZVIZ cameras (CS-H8c) that expose no local
 * control. Pan/tilt only (these cameras have no optical zoom). Continuous: START on press,
 * STOP on release.
 */
class EzvizCloudPtzController(
    private val cloud: EzvizCloud,
    private val account: String,
    private val password: String,
    private val serial: String
) : PtzController {
    private var lastCommand = "UP"

    override suspend fun probe(): Boolean =
        cloud.sessionId != null || cloud.login(account, password) == null

    override suspend fun move(pan: Int, tilt: Int, zoom: Int): Boolean {
        val command = when {
            pan < 0 -> "LEFT"; pan > 0 -> "RIGHT"
            tilt > 0 -> "UP"; tilt < 0 -> "DOWN"
            else -> return true            // zoom unsupported on EZVIZ pan/tilt cams
        }
        lastCommand = command
        if (cloud.sessionId == null && cloud.login(account, password) != null) return false
        return cloud.ptz(serial, command, "START")
    }

    override suspend fun stop(): Boolean = cloud.ptz(serial, lastCommand, "STOP")
}
