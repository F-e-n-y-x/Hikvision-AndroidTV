package com.hiktv.viewer.data.isapi

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.Request
import kotlin.coroutines.coroutineContext

/** A camera event from the NVR (motion / line-crossing / intrusion / video-loss / tamper). */
data class CamEvent(
    val channel: Int,
    val type: String,
    val description: String
) {
    /** Human-friendly label for the event type. */
    val label: String
        get() = when (type.lowercase()) {
            "vmd" -> "Motion"
            "linedetection" -> "Line crossing"
            "fielddetection" -> "Intrusion"
            "regionentrance" -> "Region entry"
            "regionexiting" -> "Region exit"
            "videoloss" -> "Video loss"
            "tamperdetection" -> "Tamper"
            else -> "Event"
        }
}

/**
 * Subscribes to Hikvision's long-lived alert stream and emits [CamEvent]s. The stream is
 * a never-ending multipart body of <EventNotificationAlert> XML blocks. We parse blocks as
 * they arrive and only surface "active" events for interesting types so the grid can flash
 * the affected tile.
 */
class EventListener(private val isapi: IsapiClient) {

    fun events(): Flow<CamEvent> = flow {
        val req = Request.Builder()
            .url("${isapi.base()}/ISAPI/Event/notification/alertStream")
            .get().build()

        isapi.httpClient().newCall(req).execute().use { resp ->
            val source = resp.body?.source() ?: return@use
            val block = StringBuilder()
            while (!source.exhausted()) {
                coroutineContext.ensureActive()
                val line = source.readUtf8Line() ?: break
                block.append(line).append('\n')
                if (line.contains("</EventNotificationAlert>")) {
                    parse(block.toString())?.let { emit(it) }
                    block.setLength(0)
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun parse(xml: String): CamEvent? {
        val state = tag(xml, "eventState")
        if (state != null && !state.equals("active", true)) return null

        val type = tag(xml, "eventType") ?: return null
        if (type.lowercase() !in INTERESTING) return null

        val channel = (tag(xml, "dynChannelID") ?: tag(xml, "channelID"))?.toIntOrNull()
            ?: return null
        val desc = tag(xml, "eventDescription") ?: type
        return CamEvent(channel, type, desc)
    }

    private fun tag(xml: String, name: String): String? =
        Regex("<$name>(.*?)</$name>").find(xml)?.groupValues?.get(1)?.trim()

    companion object {
        // Hikvision event type strings worth showing.
        // lowercase for case-insensitive matching against the NVR's eventType strings
        private val INTERESTING = setOf(
            "vmd",            // motion detection
            "linedetection",
            "fielddetection", // intrusion / area
            "regionentrance",
            "regionexiting",
            "videoloss",
            "tamperdetection"
        )
    }
}
