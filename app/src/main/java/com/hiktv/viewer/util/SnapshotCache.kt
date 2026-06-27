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

    fun save(context: Context, channel: Int, bytes: ByteArray) {
        runCatching { file(context, channel).writeBytes(bytes) }
    }

    /** Decoded cached snapshot, or null if none. Downsampled to keep memory low on the wall. */
    fun load(context: Context, channel: Int): Bitmap? {
        val f = file(context, channel)
        if (!f.exists()) return null
        return runCatching {
            val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
            BitmapFactory.decodeFile(f.absolutePath, opts)
        }.getOrNull()
    }

    fun decode(bytes: ByteArray): Bitmap? = runCatching {
        val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }.getOrNull()
}
