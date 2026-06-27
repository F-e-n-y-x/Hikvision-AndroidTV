package com.hiktv.viewer.util

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Saves/loads the app's settings JSON to a stable file so the user doesn't re-enter the NVR,
 * cameras and PTZ details after a reinstall.
 *
 * On Android 10+ it uses the public **Downloads** folder via MediaStore (no permission needed,
 * survives uninstall). On older versions it falls back to the app's external files dir.
 */
object BackupManager {

    const val FILE_NAME = "HikTVViewer_backup.json"

    /** Writes [json] and returns a human-readable location, or throws on failure. */
    fun export(context: Context, json: String): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            deleteExisting(context)
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, FILE_NAME)
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Could not create backup file")
            resolver.openOutputStream(uri).use { it!!.write(json.toByteArray()) }
            return "Downloads/$FILE_NAME"
        }
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        File(dir, FILE_NAME).writeText(json)
        return "$dir/$FILE_NAME"
    }

    /** Reads the backup JSON, or null if none is found. */
    fun import(context: Context): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val projection = arrayOf(MediaStore.Downloads._ID)
            val selection = "${MediaStore.Downloads.DISPLAY_NAME} = ?"
            resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, selection,
                arrayOf(FILE_NAME), null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val id = c.getLong(c.getColumnIndexOrThrow(MediaStore.Downloads._ID))
                    val uri = android.content.ContentUris.withAppendedId(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
                    return resolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                }
            }
            return null
        }
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        val f = File(dir, FILE_NAME)
        return if (f.exists()) f.readText() else null
    }

    private fun deleteExisting(context: Context) {
        runCatching {
            context.contentResolver.delete(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                "${MediaStore.Downloads.DISPLAY_NAME} = ?", arrayOf(FILE_NAME)
            )
        }
    }
}
