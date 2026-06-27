package com.hiktv.viewer.ui.playback

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.hiktv.viewer.data.isapi.IsapiClient
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * A horizontal 24-hour recording timeline. Recorded [segments] are drawn as lime blocks over
 * a dark track, with hour ticks/labels and a movable playhead. All times are "NVR clock"
 * (pseudo-epoch) millis so they line up with the NVR's labels and the playback URLs.
 */
class TimelineView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var windowStart = 0L
    private var windowEnd = 0L
    private var playhead = 0L
    private var segments: List<IsapiClient.Segment> = emptyList()

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1C222A") }
    private val rec = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#D0DA21") }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#3A434F") }
    private val head = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8A93A2"); textSize = dp(12f); textAlign = Paint.Align.CENTER
    }
    private val rectF = RectF()
    private val hm = SimpleDateFormat("h a", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    fun setWindow(startMs: Long, endMs: Long) { windowStart = startMs; windowEnd = endMs; invalidate() }
    fun setSegments(list: List<IsapiClient.Segment>) { segments = list; invalidate() }
    fun setPlayhead(ms: Long) { playhead = ms.coerceIn(windowStart, windowEnd); invalidate() }
    fun getPlayhead(): Long = playhead

    private fun xFor(ms: Long): Float {
        if (windowEnd <= windowStart) return 0f
        val f = (ms - windowStart).toFloat() / (windowEnd - windowStart)
        return paddingLeft + f * (width - paddingLeft - paddingRight)
    }

    override fun onDraw(canvas: Canvas) {
        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        val barTop = dp(10f)
        val barBottom = height - dp(22f)

        // track
        rectF.set(left, barTop, right, barBottom)
        canvas.drawRoundRect(rectF, dp(4f), dp(4f), track)

        // hour ticks + labels (every 3h)
        if (windowEnd > windowStart) {
            var t = windowStart
            while (t <= windowEnd) {
                val x = xFor(t)
                canvas.drawLine(x, barTop, x, barBottom, tick)
                canvas.drawText(hm.format(Date(t)), x, height - dp(6f), label)
                t += 3 * 3600_000L
            }
        }

        // recorded segments
        for (s in segments) {
            val x0 = xFor(s.startMs.coerceAtLeast(windowStart))
            val x1 = xFor(s.endMs.coerceAtMost(windowEnd))
            if (x1 <= x0) continue
            rectF.set(x0, barTop, x1, barBottom)
            canvas.drawRoundRect(rectF, dp(3f), dp(3f), rec)
        }

        // playhead
        val px = xFor(playhead)
        canvas.drawRect(px - dp(1.5f), barTop - dp(4f), px + dp(1.5f), barBottom + dp(4f), head)
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
}
