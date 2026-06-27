package com.hiktv.viewer.util

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Saves/loads the app's settings JSON to the shared **Downloads** folder so the user doesn't
 * re-enter the NVR / cameras / PTZ details after a reinstall.
 *
 * Reading must work for a *fresh* install (which no longer "owns" a MediaStore file created by
 * the previous install), so we read the public Downloads file directly. That needs
 * READ_EXTERNAL_STORAGE + requestLegacyExternalStorage (granted/effective up to Android 12).
 * MediaStore is used as a fallback for writing on newer versions.
 */
object BackupManager {

    const val FILE_NAME = "HikTVViewer_backup.json"

    private fun publicFile(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FILE_NAME)

    /** Writes [json] and returns a human-readable location, or throws on failure. */
    fun export(context: Context, json: String): String {
        // Direct write to public Downloads (works on API <= 29 with legacy storage).
        runCatching {
            val f = publicFile()
            f.parentFile?.mkdirs()
            f.writeText(json)
            return "Downloads/$FILE_NAME"
        }
        // Fallback: MediaStore (Android 10+ without legacy access).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            runCatching {
                resolver.delete(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    "${MediaStore.Downloads.DISPLAY_NAME} = ?", arrayOf(FILE_NAME)
                )
            }
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, FILE_NAME)
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Could not create backup file")
            resolver.openOutputStream(uri).use { it!!.write(json.toByteArray()) }
            return "Downloads/$FILE_NAME"
        }
        error("Could not write backup")
    }

    /** Reads the backup JSON, or null if none is found / not readable. */
    fun import(context: Context): String? {
        // Direct read first — this is what makes restore work after a reinstall.
        runCatching {
            val f = publicFile()
            if (f.exists() && f.canRead()) return f.readText()
        }
        // Fallback: a MediaStore file this install owns.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            runCatching {
                resolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Downloads._ID),
                    "${MediaStore.Downloads.DISPLAY_NAME} = ?", arrayOf(FILE_NAME), null
                )?.use { c ->
                    if (c.moveToFirst()) {
                        val id = c.getLong(c.getColumnIndexOrThrow(MediaStore.Downloads._ID))
                        val uri = android.content.ContentUris.withAppendedId(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
                        return resolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                    }
                }
            }
        }
        return null
    }
}
