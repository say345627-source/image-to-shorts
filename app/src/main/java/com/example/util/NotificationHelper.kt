package com.example.util

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.R

object NotificationHelper {

    private const val CHANNEL_ID = "channel_shorts_conversion"
    private const val NOTIFICATION_ID = 2001

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = context.getString(R.string.notification_channel_name)
            val descriptionText = context.getString(R.string.notification_channel_desc)
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                setShowBadge(false)
            }
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun canPostNotification(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun showProgressNotification(context: Context, current: Int, total: Int, fileName: String) {
        if (!canPostNotification(context)) return

        createNotificationChannel(context)

        val title = context.getString(R.string.notification_title)
        val progressText = context.getString(R.string.status_progress_count, current, total)
        val contentText = "$progressText • $fileName"

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title)
            .setContentText(contentText)
            .setProgress(total, current, false)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIFICATION_ID, builder.build())
    }

    fun showDoneNotification(context: Context, savedCount: Int) {
        if (!canPostNotification(context)) return

        createNotificationChannel(context)

        val title = context.getString(R.string.notification_done_title)
        val contentText = context.getString(R.string.notification_done_text, savedCount)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(title)
            .setContentText(contentText)
            .setProgress(0, 0, false)
            .setOngoing(false)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIFICATION_ID, builder.build())
    }

    fun clearNotification(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.cancel(NOTIFICATION_ID)
    }
}
