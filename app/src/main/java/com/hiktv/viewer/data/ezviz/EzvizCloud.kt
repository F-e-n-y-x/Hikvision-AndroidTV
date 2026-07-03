package com.hiktv.viewer.data.ezviz

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Minimal EZVIZ cloud client for PTZ — the only way EZVIZ cameras (e.g. CS-H8c) can be moved,
 * since they expose no local ONVIF/ISAPI. This mirrors what the EZVIZ phone app does (and the
 * open-source pyEzviz library): log in to the EZVIZ account, then send PTZ start/stop commands
 * to the cloud, which relays them to the camera.
 *
 * The API is undocumented and may change; failures are reported, not crashed.
 */
class EzvizCloud(initialApiDomain: String = "apiieu.ezvizlife.com") {

    // Rewritten on region redirect during login() and read by ptzRaw()/listDevices() on other
    // IO threads, so it must be volatile to publish across threads.
    @Volatile private var apiDomain: String = initialApiDomain

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    @Volatile var sessionId: String? = null
        private set

    /** Returns null on success, or a human-readable error. Follows EZVIZ region redirects. */
    suspend fun login(account: String, password: String): String? = withContext(Dispatchers.IO) {
        val md5 = md5Hex(password)
        var domain = apiDomain
        repeat(3) {
            val form = FormBody.Builder()
                .add("account", account)
                .add("password", md5)
                .add("featureCode", FEATURE_CODE)
                .add("msgType", "0")
                .add("bizType", "")
                .add("cuName", "SGFzc2lv")
                .build()
            val resp = runCatching {
                client.newCall(base("https://$domain/v3/users/login/v5").post(form).build())
                    .execute().use { it.body?.string().orEmpty() }
            }.getOrElse { return@withContext "Cannot reach EZVIZ: ${it.message}" }

            val code = int(resp, "code")
            when (code) {
                200 -> {
                    sessionId = str(resp, "sessionId")
                    str(resp, "apiDomain")?.let { apiDomain = it }
                    return@withContext if (sessionId != null) null else "Logged in but no session returned"
                }
                1100 -> { // region redirect: retry against the returned domain
                    val d = str(resp, "apiDomain") ?: return@withContext "Region redirect missing domain"
                    domain = d; apiDomain = d
                }
                6002 -> return@withContext "EZVIZ needs an SMS/email verification code (2FA). Turn off 2FA for this account to use cloud PTZ."
                1013, 1014, 101, 1001 -> return@withContext "Wrong EZVIZ email or password"
                else -> return@withContext "EZVIZ login failed (code $code)"
            }
        }
        "EZVIZ login failed (too many region redirects)"
    }

    /** command = UP/DOWN/LEFT/RIGHT, action = START/STOP, speed 1..10. */
    suspend fun ptz(serial: String, command: String, action: String, speed: Int = 5): Boolean =
        withContext(Dispatchers.IO) {
            val body = ptzRaw(serial, command, action, speed) ?: return@withContext false
            int(body, "code") == 200
        }

    private fun ptzRaw(serial: String, command: String, action: String, speed: Int): String? {
        val sid = sessionId ?: return null
        val form = FormBody.Builder()
            .add("command", command)
            .add("action", action)
            .add("channelNo", "1")
            .add("speed", speed.coerceIn(1, 10).toString())
            .add("uuid", UUID.randomUUID().toString())
            .add("serial", serial)
            .build()
        return runCatching {
            client.newCall(
                base("https://$apiDomain/v3/devices/$serial/ptzControl")
                    .addHeader("sessionId", sid).put(form).build()
            ).execute().use { it.body?.string().orEmpty() }
        }.getOrNull()
    }

    /** Sends a tiny test move and reports the cloud's exact result (for the Test PTZ button). */
    suspend fun diagnosePtz(serial: String): String = withContext(Dispatchers.IO) {
        if (sessionId == null) return@withContext "Not logged in"
        val body = ptzRaw(serial, "LEFT", "START", 3) ?: return@withContext "PTZ request failed (network)"
        ptzRaw(serial, "LEFT", "STOP", 3)
        val code = int(body, "code")
        val msg = str(body, "message") ?: str(body, "msg")
        when (code) {
            200 -> "PTZ OK — the camera should have nudged left. D-pad will work."
            20002 -> "code 20002: device not found — the serial '$serial' is wrong (use Auto-fill EZVIZ serial)."
            20006, 20007, 20008 -> "code $code: camera offline / unreachable from the cloud."
            60020 -> "code 60020: this camera reports NO PTZ support in the cloud."
            else -> "PTZ response code $code ${msg.orEmpty()}".trim()
        }
    }

    /** Logged-in user's cameras: list of (serial, name). */
    suspend fun listDevices(): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val sid = sessionId ?: return@withContext emptyList()
        val url = "https://$apiDomain/v3/userdevices/v1/resources/pagelist" +
            "?groupId=-1&limit=50&offset=0&filter=CLOUD,STATUS,FEATURE,P2P,CONNECTION,SWITCH,CHANNEL"
        val body = runCatching {
            client.newCall(base(url).addHeader("sessionId", sid).get().build())
                .execute().use { it.body?.string().orEmpty() }
        }.getOrNull() ?: return@withContext emptyList()
        val out = ArrayList<Pair<String, String>>()
        runCatching {
            val arr = org.json.JSONObject(body).optJSONArray("deviceInfos") ?: return@runCatching
            for (i in 0 until arr.length()) {
                val d = arr.getJSONObject(i)
                val serial = d.optString("deviceSerial").takeIf { it.isNotBlank() } ?: continue
                val name = d.optString("name").takeIf { it.isNotBlank() } ?: serial
                out += serial to name
            }
        }
        out
    }

    private fun base(url: String) = Request.Builder()
        .url(url)
        .addHeader("clientType", "3")
        .addHeader("customno", "1000001")
        .addHeader("appId", "ys7")
        .addHeader("clientNo", "web_site")
        .addHeader("netType", "WIFI")
        .addHeader("language", "en_GB")
        .addHeader("User-Agent", "okhttp/3.12.1")

    private fun md5Hex(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun str(json: String, key: String) =
        Regex("\"$key\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1)

    private fun int(json: String, key: String) =
        Regex("\"$key\"\\s*:\\s*(\\d+)").find(json)?.groupValues?.get(1)?.toIntOrNull()

    private companion object {
        // Stable per-app client id; EZVIZ just needs a consistent 32-hex device code.
        const val FEATURE_CODE = "0a1b2c3d4e5f60718293a4b5c6d7e8f9"
    }
}
