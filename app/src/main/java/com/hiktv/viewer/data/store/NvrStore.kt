package com.hiktv.viewer.data.store

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.hiktv.viewer.data.model.Camera
import com.hiktv.viewer.data.model.Nvr

/**
 * Persists the single NVR connection. Credentials are stored in
 * EncryptedSharedPreferences (AES-256) so they are not left in plaintext on the device.
 */
class NvrStore(context: Context) {

    private val prefs: SharedPreferences = run {
        val key = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "nvr_secure_prefs",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun save(nvr: Nvr) {
        prefs.edit()
            .putString(K_HOST, nvr.host)
            .putInt(K_HTTP, nvr.httpPort)
            .putInt(K_RTSP, nvr.rtspPort)
            .putString(K_USER, nvr.username)
            .putString(K_PASS, nvr.password)
            .putBoolean(K_HTTPS, nvr.useHttps)
            .apply()
    }

    fun load(): Nvr? {
        val host = prefs.getString(K_HOST, null) ?: return null
        return Nvr(
            host = host,
            httpPort = prefs.getInt(K_HTTP, 80),
            rtspPort = prefs.getInt(K_RTSP, 554),
            username = prefs.getString(K_USER, "") ?: "",
            password = prefs.getString(K_PASS, "") ?: "",
            useHttps = prefs.getBoolean(K_HTTPS, false)
        )
    }

    fun hasNvr(): Boolean = prefs.contains(K_HOST)

    fun clear() = prefs.edit().clear().apply()

    /** Cached channel count so the grid can render instantly before discovery completes. */
    var cachedChannelCount: Int
        get() = prefs.getInt(K_CH_COUNT, 0)
        set(value) = prefs.edit().putInt(K_CH_COUNT, value).apply()

    /** Persist the last-known camera list so the grid paints immediately on next launch. */
    fun saveCameras(cams: List<Camera>) {
        val encoded = cams.joinToString("\n") {
            "${it.channel}\t${it.name.replace("\t", " ").replace("\n", " ")}\t${it.online}\t${it.ptzSupported}"
        }
        prefs.edit().putString(K_CAMS, encoded).apply()
    }

    fun loadCameras(): List<Camera> {
        val raw = prefs.getString(K_CAMS, null)?.takeIf { it.isNotBlank() } ?: return emptyList()
        return raw.lineSequence().mapNotNull { line ->
            val p = line.split("\t")
            val ch = p.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
            Camera(
                channel = ch,
                name = p.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: "Camera $ch",
                online = p.getOrNull(2)?.toBooleanStrictOrNull() ?: true,
                ptzSupported = p.getOrNull(3)?.toBooleanStrictOrNull() ?: false
            )
        }.toList()
    }

    /** Default grid layout: 0 = auto, otherwise the chosen column count. */
    var gridColumns: Int
        get() = prefs.getInt(K_COLS, 0)
        set(value) = prefs.edit().putInt(K_COLS, value).apply()

    /**
     * Video decoding mode:
     *   0 = Balanced  — grid tiles software-decode, fullscreen hardware (default; avoids
     *                   exhausting the TV's limited H.265 hardware-decoder sessions).
     *   1 = Hardware  — everything hardware (best performance; may crash on cheap H.265 TVs).
     *   2 = Software  — everything software (most compatible, highest CPU).
     */
    var decoderMode: Int
        get() = prefs.getInt(K_DECODER, 0)
        set(value) = prefs.edit().putInt(K_DECODER, value).apply()

    /**
     * Video render path. false (default) = compatible copy path (`:no-mediacodec-dr`) which fixes
     * all-green H.265 on chips like the Xiaomi MiTV. true = direct/zero-copy rendering, which is
     * far faster and is what laggy/crashing boxes (e.g. Amlogic Mi Box) need. Per-device.
     */
    var directRender: Boolean
        get() = prefs.getBoolean(K_DIRECT_RENDER, false)
        set(value) = prefs.edit().putBoolean(K_DIRECT_RENDER, value).apply()

    /** Channels the user wants motion/area alerts for (banner + notification). Empty = none. */
    var alertChannels: Set<Int>
        get() = prefs.getString(K_ALERTS, "").orEmpty()
            .split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()
        set(value) = prefs.edit().putString(K_ALERTS, value.joinToString(",")).apply()

    /**
     * Per-camera *direct* connection (the camera's own IP, not the NVR). Lets the app talk
     * straight to a camera's ISAPI for PTZ/zoom when the NVR proxy doesn't expose control
     * (typical for EZVIZ PTZ units). Stored encrypted, one entry per channel. null = clear.
     * The direct camera is addressed as its local channel 1.
     */
    fun saveDirect(channel: Int, direct: Nvr?, onvif: Boolean = false) {
        val e = prefs.edit()
        if (direct == null) {
            e.remove(directKey(channel))
        } else {
            e.putString(
                directKey(channel),
                listOf(
                    direct.host, direct.httpPort, direct.rtspPort,
                    direct.username, direct.password, direct.useHttps, onvif
                ).joinToString("\t")
            )
        }
        e.apply()
    }

    fun loadDirect(channel: Int): Nvr? {
        val raw = prefs.getString(directKey(channel), null)?.takeIf { it.isNotBlank() } ?: return null
        val p = raw.split("\t")
        val host = p.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return null
        return Nvr(
            host = host,
            httpPort = p.getOrNull(1)?.toIntOrNull() ?: 80,
            rtspPort = p.getOrNull(2)?.toIntOrNull() ?: 554,
            username = p.getOrNull(3).orEmpty(),
            password = p.getOrNull(4).orEmpty(),
            useHttps = p.getOrNull(5)?.toBooleanStrictOrNull() ?: false
        )
    }

    /** Whether the per-camera direct connection should use ONVIF (vs. Hikvision ISAPI). */
    fun loadDirectOnvif(channel: Int): Boolean {
        val raw = prefs.getString(directKey(channel), null) ?: return false
        return raw.split("\t").getOrNull(6)?.toBooleanStrictOrNull() ?: false
    }

    private fun directKey(channel: Int) = "direct_$channel"

    // ---- EZVIZ cloud PTZ (account is global; serial is per-camera) ----------

    var ezvizAccount: String?
        get() = prefs.getString("ezviz_account", null)?.takeIf { it.isNotBlank() }
        set(v) = prefs.edit().putString("ezviz_account", v).apply()

    var ezvizPassword: String?
        get() = prefs.getString("ezviz_password", null)?.takeIf { it.isNotBlank() }
        set(v) = prefs.edit().putString("ezviz_password", v).apply()

    fun ezvizSerial(channel: Int): String? =
        prefs.getString("ezviz_serial_$channel", null)?.takeIf { it.isNotBlank() }

    fun setEzvizSerial(channel: Int, serial: String?) =
        prefs.edit().putString("ezviz_serial_$channel", serial).apply()

    // ---- Backup / restore (all settings as JSON) ---------------------------

    /** Serialize every stored setting (NVR, cameras, layout, alerts, direct/EZVIZ PTZ) to JSON. */
    fun exportJson(): String {
        val obj = org.json.JSONObject()
        for ((k, v) in prefs.all) if (v != null) obj.put(k, v)
        return obj.toString()
    }

    /** Replace all settings from a JSON backup produced by [exportJson]. */
    fun importJson(json: String) {
        val obj = org.json.JSONObject(json)
        val e = prefs.edit()
        e.clear()
        for (key in obj.keys()) {
            when (val v = obj.get(key)) {
                is Boolean -> e.putBoolean(key, v)
                is Int -> e.putInt(key, v)
                // org.json widens whole numbers to Long. Every numeric pref in this app is read
                // via getInt, so keep in-range values as Int (reading a Long back via getInt would
                // throw ClassCastException). Only widen to Long when the value genuinely overflows
                // Int — better than the old code, which silently truncated via toInt().
                is Long -> if (v in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
                               e.putInt(key, v.toInt()) else e.putLong(key, v)
                is Double -> if (v == Math.floor(v) && !v.isInfinite() &&
                                 v >= Int.MIN_VALUE.toDouble() && v <= Int.MAX_VALUE.toDouble())
                                 e.putInt(key, v.toInt()) else e.putString(key, v.toString())
                else -> e.putString(key, v.toString())
            }
        }
        e.apply()
    }

    companion object {
        private const val K_HOST = "host"
        private const val K_HTTP = "http_port"
        private const val K_RTSP = "rtsp_port"
        private const val K_USER = "user"
        private const val K_PASS = "pass"
        private const val K_HTTPS = "https"
        private const val K_CH_COUNT = "ch_count"
        private const val K_CAMS = "cameras"
        private const val K_COLS = "grid_cols"
        private const val K_DECODER = "decoder_mode"
        private const val K_DIRECT_RENDER = "direct_render"
        private const val K_ALERTS = "alert_channels"
    }
}
