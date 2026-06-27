package com.hiktv.viewer.core

import com.hiktv.viewer.data.isapi.IsapiClient
import com.hiktv.viewer.data.model.Camera
import com.hiktv.viewer.data.model.Nvr

/**
 * In-memory hub for the active connection. Holds the NVR, its ISAPI client and the
 * discovered camera list so activities don't have to re-parcel everything between screens.
 */
object Session {

    @Volatile var nvr: Nvr? = null
        private set

    @Volatile var isapi: IsapiClient? = null
        private set

    @Volatile var cameras: List<Camera> = emptyList()

    /** Set by the Settings screen when the grid needs to re-load (cameras/layout/connection changed). */
    @Volatile var gridDirty: Boolean = false

    fun connect(nvr: Nvr) {
        this.nvr = nvr
        this.isapi = IsapiClient(nvr)
    }

    fun cameraByChannel(channel: Int): Camera? = cameras.firstOrNull { it.channel == channel }

    val isReady: Boolean get() = nvr != null && isapi != null
}
