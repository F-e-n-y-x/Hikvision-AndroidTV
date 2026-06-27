package com.hiktv.viewer.data.onvif

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Minimal ONVIF PTZ client (SOAP 1.2 + WS-UsernameToken digest). Drives PTZ on cameras the
 * Hikvision NVR won't proxy — e.g. EZVIZ units with ONVIF enabled on their own LAN IP.
 *
 * Notes that make this actually work on real cameras (EZVIZ in particular):
 *  - The operation's WS-Action is put in the Content-Type, which strict stacks require.
 *  - The WS-Security "Created" timestamp is taken from the *camera's* clock (fetched first,
 *    unauthenticated), so digest auth doesn't fail on clock skew between TV and camera.
 *  - SOAP Faults are surfaced as exceptions so the diagnostic can show the real reason.
 *
 * Velocity values are ONVIF's normalised -1.0..1.0.
 */
class OnvifPtz(
    private val host: String,
    private val port: Int,
    private val user: String,
    private val pass: String
) {
    private val deviceUrl = "http://$host:$port/onvif/device_service"
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private var mediaXAddr: String? = null
    private var ptzXAddr: String? = null
    private var profileToken: String? = null
    private var clockOffsetMs = 0L
    @Volatile private var ready = false

    suspend fun ensureReady(): Boolean = withContext(Dispatchers.IO) {
        if (ready) return@withContext true
        runCatching { prepare() }.getOrDefault(false)
    }

    /** Throws with a readable message on the first step that fails (used by diagnose()). */
    private fun prepare(): Boolean {
        syncClock()
        val caps = soap(deviceUrl, DEVICE_NS, "GetCapabilities",
            "<GetCapabilities><Category>All</Category></GetCapabilities>")
        mediaXAddr = xaddrFor(caps, "Media") ?: "http://$host:$port/onvif/Media"
        ptzXAddr = xaddrFor(caps, "PTZ") ?: "http://$host:$port/onvif/PTZ"

        val profiles = soap(mediaXAddr!!, MEDIA_NS, "GetProfiles", "<GetProfiles/>")
        profileToken = Regex("Profiles[^>]*token=\"([^\"]+)\"").find(profiles)?.groupValues?.get(1)
            ?: Regex("token=\"([^\"]+)\"").find(profiles)?.groupValues?.get(1)
        ready = profileToken != null
        return ready
    }

    suspend fun continuousMove(panX: Float, tiltY: Float, zoom: Float): Boolean =
        withContext(Dispatchers.IO) {
            if (!ensureReady()) return@withContext false
            val body = """
                <ContinuousMove>
                  <ProfileToken>$profileToken</ProfileToken>
                  <Velocity>
                    <PanTilt x="$panX" y="$tiltY" xmlns="http://www.onvif.org/ver10/schema"/>
                    <Zoom x="$zoom" xmlns="http://www.onvif.org/ver10/schema"/>
                  </Velocity>
                </ContinuousMove>
            """.trimIndent()
            runCatching { soap(ptzXAddr!!, PTZ_NS, "ContinuousMove", body); true }.getOrDefault(false)
        }

    suspend fun stop(): Boolean = withContext(Dispatchers.IO) {
        if (!ready || ptzXAddr == null || profileToken == null) return@withContext false
        val body = """
            <Stop>
              <ProfileToken>$profileToken</ProfileToken>
              <PanTilt>true</PanTilt>
              <Zoom>true</Zoom>
            </Stop>
        """.trimIndent()
        runCatching { soap(ptzXAddr!!, PTZ_NS, "Stop", body); true }.getOrDefault(false)
    }

    /** Step-by-step report so the user can see exactly where ONVIF fails (auth, profile, move). */
    suspend fun diagnose(): String = withContext(Dispatchers.IO) {
        buildString {
            append("ONVIF: http://$host:$port\nUser: ${user.ifBlank { "(none)" }}\n\n")
            val timed = runCatching { syncClock(); clockOffsetMs }
            append("• Camera clock: ${timed.fold({ "ok (skew ${it / 1000}s)" }, { "FAILED — ${reason(it)}" })}\n")

            val caps = runCatching {
                soap(deviceUrl, DEVICE_NS, "GetCapabilities", "<GetCapabilities><Category>All</Category></GetCapabilities>")
            }
            append("• Capabilities/auth: ${caps.fold({ "ok" }, { "FAILED — ${reason(it)}" })}\n")
            caps.getOrNull()?.let {
                mediaXAddr = xaddrFor(it, "Media") ?: "http://$host:$port/onvif/Media"
                ptzXAddr = xaddrFor(it, "PTZ") ?: "http://$host:$port/onvif/PTZ"
                append("    PTZ service: $ptzXAddr\n")
            }

            val prof = runCatching { soap(mediaXAddr ?: "http://$host:$port/onvif/Media", MEDIA_NS, "GetProfiles", "<GetProfiles/>") }
            prof.getOrNull()?.let {
                profileToken = Regex("Profiles[^>]*token=\"([^\"]+)\"").find(it)?.groupValues?.get(1)
            }
            append("• Media profile: ${if (profileToken != null) "ok ($profileToken)" else "FAILED — ${prof.exceptionOrNull()?.let(::reason) ?: "no profile token"}"}\n")

            if (profileToken != null && ptzXAddr != null) {
                ready = true
                val mv = runCatching {
                    soap(ptzXAddr!!, PTZ_NS, "ContinuousMove",
                        "<ContinuousMove><ProfileToken>$profileToken</ProfileToken><Velocity>" +
                            "<PanTilt x=\"0.3\" y=\"0\" xmlns=\"http://www.onvif.org/ver10/schema\"/>" +
                            "<Zoom x=\"0\" xmlns=\"http://www.onvif.org/ver10/schema\"/></Velocity></ContinuousMove>")
                }
                Thread.sleep(400)
                runCatching { stop() }
                append("• Test move: ${mv.fold({ "ok — camera should have nudged right" }, { "FAILED — ${reason(it)}" })}\n")
            }
            append("\nIf auth failed: EZVIZ needs a separate ONVIF user/password set in the EZVIZ app (not the device password).")
        }
    }

    private fun reason(t: Throwable) = t.message ?: t.javaClass.simpleName

    // ---- clock sync (unauthenticated) -------------------------------------

    private fun syncClock() {
        val resp = soap(deviceUrl, DEVICE_NS, "GetSystemDateAndTime", "<GetSystemDateAndTime/>", auth = false)
        val utc = Regex("UTCDateTime>(.*?)</[\\w:]*UTCDateTime>", RegexOption.DOT_MATCHES_ALL)
            .find(resp)?.groupValues?.get(1) ?: return
        fun n(tag: String) = Regex("$tag>\\s*(\\d+)\\s*</").find(utc)?.groupValues?.get(1)?.toIntOrNull()
        val y = n("Year") ?: return; val mo = n("Month") ?: return; val d = n("Day") ?: return
        val h = n("Hour") ?: 0; val mi = n("Minute") ?: 0; val s = n("Second") ?: 0
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear(); set(y, mo - 1, d, h, mi, s)
        }
        clockOffsetMs = cal.timeInMillis - System.currentTimeMillis()
    }

    // ---- SOAP plumbing -----------------------------------------------------

    private fun xaddrFor(caps: String, service: String): String? {
        val block = Regex("$service>(.*?)</[\\w:]*$service>", RegexOption.DOT_MATCHES_ALL)
            .find(caps)?.groupValues?.get(1) ?: return null
        return Regex("XAddr>(.*?)</[\\w:]*XAddr>").find(block)?.groupValues?.get(1)?.trim()
    }

    private fun soap(url: String, ns: String, op: String, bodyInner: String, auth: Boolean = true): String {
        val header = if (auth) "<s:Header>${securityHeader()}</s:Header>" else ""
        val envelope = """
            <?xml version="1.0" encoding="UTF-8"?>
            <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope">
              $header<s:Body xmlns="$ns">$bodyInner</s:Body>
            </s:Envelope>
        """.trimIndent()
        val contentType = "application/soap+xml; charset=utf-8; action=\"$ns/$op\""
        val req = Request.Builder()
            .url(url)
            .post(envelope.toRequestBody(contentType.toMediaType()))
            .build()
        client.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            // SOAP faults come back as HTTP 400/500 with a <Fault> body; surface the reason.
            val fault = Regex("(?:Reason|faultstring)>.*?<.*?Text[^>]*>(.*?)</", RegexOption.DOT_MATCHES_ALL)
                .find(text)?.groupValues?.get(1)?.trim()
                ?: Regex("<[\\w:]*Text[^>]*>(.*?)</", RegexOption.DOT_MATCHES_ALL).find(text)?.groupValues?.get(1)?.trim()
            if (!r.isSuccessful) error(fault?.takeIf { it.isNotBlank() } ?: "HTTP ${r.code}")
            return text
        }
    }

    /** WS-UsernameToken, PasswordDigest = Base64(SHA1(nonce + created + password)), camera clock. */
    private fun securityHeader(): String {
        val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val created = ISO.format(Date(System.currentTimeMillis() + clockOffsetMs))
        val sha = MessageDigest.getInstance("SHA-1").apply {
            update(nonce); update(created.toByteArray(Charsets.UTF_8)); update(pass.toByteArray(Charsets.UTF_8))
        }.digest()
        val digestB64 = android.util.Base64.encodeToString(sha, android.util.Base64.NO_WRAP)
        val nonceB64 = android.util.Base64.encodeToString(nonce, android.util.Base64.NO_WRAP)
        return """
            <Security s:mustUnderstand="1" xmlns="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd">
              <UsernameToken>
                <Username>$user</Username>
                <Password Type="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest">$digestB64</Password>
                <Nonce EncodingType="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary">$nonceB64</Nonce>
                <Created xmlns="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd">$created</Created>
              </UsernameToken>
            </Security>
        """.trimIndent()
    }

    private companion object {
        const val DEVICE_NS = "http://www.onvif.org/ver10/device/wsdl"
        const val MEDIA_NS = "http://www.onvif.org/ver10/media/wsdl"
        const val PTZ_NS = "http://www.onvif.org/ver20/ptz/wsdl"
        val ISO: SimpleDateFormat
            get() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
    }
}
