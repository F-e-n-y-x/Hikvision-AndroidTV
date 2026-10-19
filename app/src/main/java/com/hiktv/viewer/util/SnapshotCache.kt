package com.hiktv.viewer.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * Caches the latest JPEG snapshot per channel on disk so the grid can show an instant preview
 * image on launch — before any RTSP stream connects, and even before the network responds.
 */
object SnapshotCache {

    private fun file(context: Context, channel: Int) =
        File(context.cacheDir, "snap_$channel.jpg")

    /** Milliseconds since the cached snapshot was written (Long.MAX_VALUE if none). */
    fun ageMs(context: Context, channel: Int): Long {
        val f = file(context, channel)
        return if (f.exists()) System.currentTimeMillis() - f.lastModified() else Long.MAX_VALUE
    }

    fun save(context: Context, channel: Int, bytes: ByteArray) {
        runCatching { file(context, channel).writeBytes(bytes) }
    }

    /** Decoded cached snapshot, or null if none. Downsampled to keep memory low on the wall. */
    fun load(context: Context, channel: Int): Bitmap? {
        val f = file(context, channel)
        if (!f.exists()) return null
        return runCatching {
            BitmapFactory.decodeFile(f.absolutePath, previewOpts())
        }.getOrNull()
    }

    fun decode(bytes: ByteArray): Bitmap? = runCatching {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, previewOpts())
    }.getOrNull()

    // RGB_565 halves memory vs ARGB_8888; a muted preview doesn't need alpha or 24-bit colour.
    private fun previewOpts() = BitmapFactory.Options().apply {
        inSampleSize = 2
        inPreferredConfig = Bitmap.Config.RGB_565
    }
}
