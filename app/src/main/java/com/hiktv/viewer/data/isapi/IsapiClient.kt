package com.hiktv.viewer.data.isapi

import com.burgstaller.okhttp.AuthenticationCacheInterceptor
import com.burgstaller.okhttp.CachingAuthenticatorDecorator
import com.burgstaller.okhttp.digest.CachingAuthenticator
import com.burgstaller.okhttp.digest.Credentials
import com.burgstaller.okhttp.digest.DigestAuthenticator
import com.hiktv.viewer.data.model.Camera
import com.hiktv.viewer.data.model.Nvr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Thin Hikvision ISAPI wrapper. One instance per NVR. All network work is on Dispatchers.IO.
 *
 * Auth is HTTP Digest (ISAPI default). For https we trust the NVR's self-signed cert,
 * which is normal for LAN security devices.
 */
class IsapiClient(private val nvr: Nvr) {

    private val client: OkHttpClient = buildClient(nvr)

    // Short request/response calls (discovery, PTZ, snapshot, config, diagnostics) get a bounded
    // overall callTimeout so a rebooting/half-open NVR can't stall each call for the full 35s
    // alert-stream read — otherwise a re-discovery after a Wi-Fi drop can freeze ~70-140s. The long
    // read stays on [client], which the event listener reuses for the streaming alertStream.
    private val shortClient: OkHttpClient = client.newBuilder()
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    // ---- Discovery ---------------------------------------------------------

    /**
     * Lists the NVR's cameras with their real names and online state.
     *
     * - **InputProxy/channels** is the authoritative list and carries the configured names
     *   ("Front Gate 1", "Back Door", …) — it's parsed first.
     * - **Streaming/channels** only lists channels that currently have a live stream, so we
     *   use it to mark each camera online/offline (a camera with no stream is offline).
     *
     * Falls back to the streaming list (generic names), analog inputs, then per-channel
     * probing for firmware where InputProxy is unavailable.
     */
    suspend fun discoverCameras(): List<Camera> = withContext(Dispatchers.IO) {
        val proxyCams = runCatching { parseChannels(get("/ISAPI/ContentMgmt/InputProxy/channels")) }
            .getOrDefault(emptyList())
        val onlineSet = runCatching { streamingChannelSet(get("/ISAPI/Streaming/channels")) }
            .getOrDefault(emptySet())

        if (proxyCams.isNotEmpty()) {
            return@withContext proxyCams
                .map { it.copy(online = onlineSet.isEmpty() || it.channel in onlineSet) }
                .sortedBy { it.channel }
        }

        runCatching { parseStreamingChannels(get("/ISAPI/Streaming/channels")) }
            .getOrDefault(emptyList())
            .let { if (it.isNotEmpty()) return@withContext it }

        runCatching { parseChannels(get("/ISAPI/System/Video/inputs/channels")) }
            .getOrDefault(emptyList())
            .let { if (it.isNotEmpty()) return@withContext it }

        autoDetectByProbe()
    }

    /** True only if the channel exposes a PTZ unit (capabilities returns 200). Fixed cameras
     *  return 4xx, so this cleanly tells PTZ cameras from fixed ones. */
    suspend fun supportsPtz(channel: Int): Boolean = withContext(Dispatchers.IO) {
        getOrNull("/ISAPI/PTZCtrl/channels/$channel/capabilities") != null
    }

    /**
     * The LAN IP the NVR has on file for a camera (from InputProxy), so the app can offer a
     * one-tap "direct connection" for PTZ. Returns null if the NVR doesn't expose it.
     */
    suspend fun cameraIp(channel: Int): String? = withContext(Dispatchers.IO) {
        val xml = getOrNull("/ISAPI/ContentMgmt/InputProxy/channels") ?: return@withContext null
        val blocks = Regex("<InputProxyChannel\\b[^>]*>(.*?)</InputProxyChannel>", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml).map { it.groupValues[1] }
        for (block in blocks) {
            val id = Regex("<id>\\s*(\\d+)\\s*</id>").find(block)?.groupValues?.get(1)?.toIntOrNull()
            if (id == channel) {
                return@withContext Regex("<ipAddress>(.*?)</ipAddress>").find(block)
                    ?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
            }
        }
        null
    }

    /**
     * The EZVIZ cloud device serial for a channel = the last 9 chars of the NVR's stored
     * serialNumber (e.g. "...CCRRBC5654797" -> "BC5654797"). Lets the app pre-fill it for
     * EZVIZ cloud PTZ without the user reading it off the camera.
     */
    suspend fun cameraSerial(channel: Int): String? = withContext(Dispatchers.IO) {
        val xml = getOrNull("/ISAPI/ContentMgmt/InputProxy/channels") ?: return@withContext null
        val blocks = Regex("<InputProxyChannel\\b[^>]*>(.*?)</InputProxyChannel>", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml).map { it.groupValues[1] }
        for (block in blocks) {
            val id = Regex("<id>\\s*(\\d+)\\s*</id>").find(block)?.groupValues?.get(1)?.toIntOrNull()
            if (id == channel) {
                val full = Regex("<serialNumber>(.*?)</serialNumber>").find(block)
                    ?.groupValues?.get(1)?.trim().orEmpty()
                return@withContext full.takeIf { it.length >= 9 }?.takeLast(9)
            }
        }
        null
    }

    /** Channel numbers that currently have a live stream (= online), from Streaming/channels. */
    private fun streamingChannelSet(xml: String): Set<Int> {
        val set = LinkedHashSet<Int>()
        Regex("<id>\\s*(\\d+)\\s*</id>").findAll(xml).forEach {
            val rawId = it.groupValues[1].toIntOrNull() ?: return@forEach
            set += if (rawId >= 100) rawId / 100 else rawId
        }
        return set
    }

    /**
     * Auto-detect channels by asking the NVR for each channel's streaming config
     * (/ISAPI/Streaming/channels/{ch}01). A 200 means the channel exists. Stops after a
     * run of empty channels once at least one was found.
     */
    suspend fun autoDetectByProbe(maxChannels: Int = 32): List<Camera> = withContext(Dispatchers.IO) {
        val found = ArrayList<Camera>()
        var consecutiveMiss = 0
        for (ch in 1..maxChannels) {
            val body = getOrNull("/ISAPI/Streaming/channels/${ch}01")
            if (body != null) {
                val name = Regex("<channelName>(.*?)</channelName>").find(body)
                    ?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
                    ?: "Camera ${ch.toString().padStart(2, '0')}"
                found += Camera(channel = ch, name = name, online = true, ptzSupported = true)
                consecutiveMiss = 0
            } else {
                consecutiveMiss++
                if (found.isNotEmpty() && consecutiveMiss >= 4) break
            }
        }
        found
    }

    /** Parse /ISAPI/Streaming/channels: each <StreamingChannel> has an id like 101 (ch1 main). */
    private fun parseStreamingChannels(xml: String): List<Camera> {
        val byChannel = LinkedHashMap<Int, String>()
        val blocks = Regex("<StreamingChannel\\b[^>]*>(.*?)</StreamingChannel>", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml).map { it.groupValues[1] }
        for (block in blocks) {
            val rawId = Regex("<id>\\s*(\\d+)\\s*</id>").find(block)?.groupValues?.get(1)?.toIntOrNull()
                ?: continue
            // id = channel*100 + streamType. Recover the channel number.
            val channel = if (rawId >= 100) rawId / 100 else rawId
            val name = Regex("<channelName>(.*?)</channelName>").find(block)?.groupValues?.get(1)?.trim()
                ?.takeIf { it.isNotEmpty() }
            // Prefer the name from the main stream (id ending in 01); don't overwrite with sub-stream.
            if (channel !in byChannel || (rawId % 100 == 1 && name != null)) {
                byChannel[channel] = name ?: byChannel[channel]
                    ?: "Camera ${channel.toString().padStart(2, '0')}"
            }
        }
        return byChannel.entries
            .map { Camera(channel = it.key, name = it.value, online = true, ptzSupported = true) }
            .sortedBy { it.channel }
    }

    private fun parseChannels(xml: String): List<Camera> {
        val cameras = ArrayList<Camera>()
        // Each camera is one <...Channel ...>..</...Channel> block; pull id + name out of it.
        val blocks = Regex("<([A-Za-z]*Channel)\\b[^>]*>(.*?)</\\1>", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml).map { it.groupValues[2] }
        for (block in blocks) {
            val id = Regex("<id>\\s*(\\d+)\\s*</id>").find(block)?.groupValues?.get(1)?.toIntOrNull()
                ?: continue
            val name = Regex("<(?:name|channelName)>(.*?)</(?:name|channelName)>").find(block)
                ?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
                ?: "Camera ${id.toString().padStart(2, '0')}"
            val online = Regex("<online>(.*?)</online>").find(block)?.groupValues?.get(1)
                ?.trim()?.equals("true", true) ?: true
            cameras += Camera(channel = id, name = name, online = online, ptzSupported = true)
        }
        return cameras.distinctBy { it.channel }.sortedBy { it.channel }
    }

    /**
     * Runs the discovery endpoints and reports HTTP status + a short body snippet for each,
     * so connection/permission problems can be diagnosed on-screen without a PC.
     */
    suspend fun diagnose(): String = withContext(Dispatchers.IO) {
        val paths = listOf(
            "/ISAPI/System/deviceInfo",
            "/ISAPI/Streaming/channels",
            "/ISAPI/ContentMgmt/InputProxy/channels",
            "/ISAPI/System/Video/inputs/channels"
        )
        buildString {
            append("NVR: ${nvr.httpBase}\n\n")
            for (p in paths) {
                val line = runCatching {
                    val req = Request.Builder().url("${nvr.httpBase}$p").get().build()
                    shortClient.newCall(req).execute().use { r ->
                        val snippet = r.body?.string().orEmpty().replace("\\s+".toRegex(), " ").take(160)
                        "HTTP ${r.code}  ${if (snippet.isBlank()) "(empty)" else snippet}"
                    }
                }.getOrElse { "ERROR ${it.message ?: it.javaClass.simpleName}" }
                append("• $p\n  $line\n\n")
            }
        }
    }

    // ---- PTZ ---------------------------------------------------------------

    /** Continuous PTZ move. Values are -100..100. Send (0,0,0) to stop. */
    suspend fun ptzContinuous(channel: Int, pan: Int, tilt: Int, zoom: Int): Boolean =
        withContext(Dispatchers.IO) {
            val body = """
                <PTZData><pan>$pan</pan><tilt>$tilt</tilt><zoom>$zoom</zoom></PTZData>
            """.trimIndent()
            runCatching {
                put("/ISAPI/PTZCtrl/channels/$channel/continuous", body)
                true
            }.getOrDefault(false)
        }

    /**
     * Stop continuous PTZ. Retried up to 3× because a dropped stop leaves the camera moving
     * (runaway pan/tilt) — the same safety the ONVIF and EZVIZ controllers already apply on stop.
     */
    suspend fun ptzStop(channel: Int): Boolean = withContext(Dispatchers.IO) {
        repeat(3) { if (ptzContinuous(channel, 0, 0, 0)) return@withContext true }
        false
    }

    // ---- Stream optimization (smooth, low-CPU live wall) -------------------

    /**
     * Reconfigure a channel's **sub-stream** (the grid wall's source) for cheap, artifact-free
     * decoding on weak TV CPUs:
     *   - H.264 @ 15 fps — LibVLC and weak chips handle it far better than H.265 sub-streams;
     *   - **SmartCodec (H.264+/H.265+) OFF** — the smart codec is the top cause of third-party
     *     freezes/artifacts on this NVR family; LibVLC wants a plain profile;
     *   - **CBR** + a short **GOP (~2x fps)** so a lost keyframe recovers in ~2s instead of a
     *     multi-second freeze.
     * Only fields the firmware already exposes are changed (never inserts unknown tags); the
     * main / full-screen stream is untouched. The change is then re-read and verified, because
     * Hikvision firmware can return 200 OK while silently ignoring fields.
     */
    suspend fun optimizeSubStream(channel: Int): Boolean = withContext(Dispatchers.IO) {
        val id = channel * 100 + 2
        val xml = getOrNull("/ISAPI/Streaming/channels/$id") ?: return@withContext false

        val out = xml
            .let { setTag(it, "videoCodecType", "H.264") }
            .let { setTag(it, "maxFrameRate", "1500") }             // 15 fps (centi-fps)
            .let { setTag(it, "videoQualityControlType", "CBR") }   // steady bitrate: fewer stalls
            .let { setTag(it, "GovLength", "30") }                  // GOP ~2x fps: fast recovery
            .let { disableSmartCodec(it) }                          // H.264+/H.265+ off

        val accepted = runCatching { put("/ISAPI/Streaming/channels/$id", out); true }.getOrDefault(false)
        if (!accepted) return@withContext false

        // Verify the codec swap + smart-codec-off actually took (firmware may silently ignore a field).
        val after = getOrNull("/ISAPI/Streaming/channels/$id") ?: return@withContext true
        val codecOk = Regex("<videoCodecType>\\s*H\\.264", RegexOption.IGNORE_CASE).containsMatchIn(after)
        val smartOff = !Regex("<SmartCodec>.*?<enabled>\\s*true",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).containsMatchIn(after)
        codecOk && smartOff
    }

    /** Replace the body of <tag>…</tag> with [value] — only when the tag exists (never inserts). */
    private fun setTag(xml: String, tag: String, value: String): String =
        Regex("<$tag>.*?</$tag>", RegexOption.DOT_MATCHES_ALL).replace(xml, "<$tag>$value</$tag>")

    /** Turn Hikvision SmartCodec (H.264+/H.265+) off, if the element is present in the config. */
    private fun disableSmartCodec(xml: String): String =
        Regex("(<SmartCodec>.*?<enabled>).*?(</enabled>)", RegexOption.DOT_MATCHES_ALL)
            .replace(xml) { "${it.groupValues[1]}false${it.groupValues[2]}" }

    // ---- Snapshot ----------------------------------------------------------

    /** JPEG of the current frame for a channel, or null on failure. */
    suspend fun snapshot(channel: Int): ByteArray? = withContext(Dispatchers.IO) {
        val code = channel * 100 + 1
        runCatching {
            val req = Request.Builder()
                .url("${nvr.httpBase}/ISAPI/Streaming/channels/$code/picture")
                .get().build()
            shortClient.newCall(req).execute().use { r ->
                if (r.isSuccessful) r.body?.bytes() else null
            }
        }.getOrNull()
    }

    // ---- Recording search (for the playback timeline) ----------------------

    /** A recorded segment, as absolute epoch milliseconds. */
    data class Segment(val startMs: Long, val endMs: Long)

    /**
     * Search recorded segments for a channel between [startMs] and [endMs] (epoch millis),
     * via Hikvision ContentMgmt search. Returns the segments the NVR has on disk.
     */
    suspend fun searchRecordings(channel: Int, startMs: Long, endMs: Long): List<Segment> =
        withContext(Dispatchers.IO) {
            val track = channel * 100 + 1
            val body = """
                <?xml version="1.0" encoding="utf-8"?>
                <CMSearchDescription>
                  <searchID>${UUID.randomUUID()}</searchID>
                  <trackList><trackID>$track</trackID></trackList>
                  <timeSpanList><timeSpan>
                    <startTime>${utc(startMs)}</startTime>
                    <endTime>${utc(endMs)}</endTime>
                  </timeSpan></timeSpanList>
                  <maxResults>200</maxResults>
                  <searchResultPostion>0</searchResultPostion>
                </CMSearchDescription>
            """.trimIndent()
            runCatching { parseSegments(post("/ISAPI/ContentMgmt/search", body)) }
                .getOrDefault(emptyList())
        }

    private fun parseSegments(xml: String): List<Segment> {
        val items = Regex("<searchMatchItem>(.*?)</searchMatchItem>", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml).map { it.groupValues[1] }
        val out = ArrayList<Segment>()
        for (item in items) {
            val s = Regex("<startTime>(.*?)</startTime>").find(item)?.groupValues?.get(1)?.let(::parseHikTime)
            val e = Regex("<endTime>(.*?)</endTime>").find(item)?.groupValues?.get(1)?.let(::parseHikTime)
            if (s != null && e != null && e > s) out += Segment(s, e)
        }
        return out.sortedBy { it.startMs }
    }

    /** Parse "2026-06-27T16:51:55+05:30" / "...Z" into absolute epoch millis (offset-aware). */
    private fun parseHikTime(raw: String): Long? {
        val s = raw.trim()
        if (s.length < 19) return null
        val dt = s.substring(0, 19)
        val asUtc = runCatching {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .parse(dt)?.time
        }.getOrNull() ?: return null
        val tz = s.substring(19)
        val offsetMs = when {
            tz.isEmpty() || tz.startsWith("Z") -> 0L
            else -> {
                val sign = if (tz[0] == '-') -1 else 1
                val hh = tz.drop(1).substringBefore(":").toIntOrNull() ?: 0
                val mm = tz.substringAfter(":", "0").toIntOrNull() ?: 0
                sign * (hh * 3600_000L + mm * 60_000L)
            }
        }
        return asUtc - offsetMs
    }

    private fun utc(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(java.util.Date(ms))

    // ---- Connectivity check ------------------------------------------------

    /** Returns null on success, or a human-readable error for the setup screen. */
    suspend fun testConnection(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("${nvr.httpBase}/ISAPI/System/deviceInfo")
                .get().build()
            shortClient.newCall(req).execute().use { r ->
                when {
                    r.isSuccessful -> null
                    r.code == 401 -> "Wrong username or password"
                    else -> "NVR responded with HTTP ${r.code}"
                }
            }
        }.getOrElse { e -> "Cannot reach NVR: ${e.message ?: e.javaClass.simpleName}" }
    }

    // ---- HTTP plumbing -----------------------------------------------------

    private fun get(path: String): String {
        val req = Request.Builder().url("${nvr.httpBase}$path").get().build()
        shortClient.newCall(req).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code} for $path")
            return r.body?.string().orEmpty()
        }
    }

    /** GET that returns the body on 2xx, or null on any failure/non-2xx (used for probing). */
    private fun getOrNull(path: String): String? = runCatching {
        val req = Request.Builder().url("${nvr.httpBase}$path").get().build()
        shortClient.newCall(req).execute().use { r ->
            if (r.isSuccessful) r.body?.string().orEmpty() else null
        }
    }.getOrNull()

    private fun put(path: String, xml: String) {
        val req = Request.Builder()
            .url("${nvr.httpBase}$path")
            .put(xml.toRequestBody("application/xml".toMediaType()))
            .build()
        shortClient.newCall(req).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code} for $path")
        }
    }

    private fun post(path: String, xml: String): String {
        val req = Request.Builder()
            .url("${nvr.httpBase}$path")
            .post(xml.toRequestBody("application/xml".toMediaType()))
            .build()
        shortClient.newCall(req).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code} for $path")
            return r.body?.string().orEmpty()
        }
    }

    /** Exposed so the event listener can reuse the authenticated client. */
    internal fun httpClient(): OkHttpClient = client
    internal fun base(): String = nvr.httpBase

    private fun buildClient(nvr: Nvr): OkHttpClient {
        val auth = DigestAuthenticator(Credentials(nvr.username, nvr.password))
        val authCache = ConcurrentHashMap<String, CachingAuthenticator>()
        val builder = OkHttpClient.Builder()
            .authenticator(CachingAuthenticatorDecorator(auth, authCache))
            .addInterceptor(AuthenticationCacheInterceptor(authCache))
            .connectTimeout(6, TimeUnit.SECONDS)
            // Finite read timeout so a quiet alert stream eventually unblocks and the
            // listener can reconnect (it loops on its own). 35 s comfortably exceeds the
            // NVR's keep-alive interval.
            .readTimeout(35, TimeUnit.SECONDS)

        if (nvr.useHttps) trustSelfSigned(builder)
        return builder.build()
    }

    /** LAN NVRs ship self-signed certs; trust them so https://<nvr> works without manual import. */
    private fun trustSelfSigned(builder: OkHttpClient.Builder) {
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, t: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, t: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }
        builder.sslSocketFactory(ssl.socketFactory, tm)
        builder.hostnameVerifier(HostnameVerifier { _, _ -> true })
    }
}
