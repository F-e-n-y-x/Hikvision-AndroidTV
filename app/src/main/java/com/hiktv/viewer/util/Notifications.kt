package com.hiktv.viewer.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.hiktv.viewer.R
import com.hiktv.viewer.ui.fullscreen.FullscreenActivity

/**
 * Posts camera-event notifications. On Android TV boxes that surface notifications this shows
 * a heads-up alert; the in-app banner is the primary signal on TVs without a shade.
 */
object Notifications {

    private const val CHANNEL_ID = "camera_events"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = context.getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID, "Camera events", NotificationManager.IMPORTANCE_HIGH
                ).apply { description = "Motion / area-detection alerts from your cameras" }
                mgr.createNotificationChannel(ch)
            }
        }
    }

    fun event(context: Context, channel: Int, cameraName: String, label: String) {
        ensureChannel(context)
        val open = Intent(context, FullscreenActivity::class.java)
            .putExtra(FullscreenActivity.EXTRA_CHANNEL, channel)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT or
            android.app.PendingIntent.FLAG_IMMUTABLE
        val pi = android.app.PendingIntent.getActivity(context, channel, open, flags)

        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_alert)
            .setContentTitle("$label — $cameraName")
            .setContentText("Tap to view live")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()

        runCatching {
            context.getSystemService(NotificationManager::class.java).notify(channel, n)
        }
    }
}
