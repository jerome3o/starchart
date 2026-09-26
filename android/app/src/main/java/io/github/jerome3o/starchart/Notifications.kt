package io.github.jerome3o.starchart

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object Notifications {

    const val CHANNEL_ID = "reminders"
    const val SERVICE_CHANNEL_ID = "services"

    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.channel_reminders),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.channel_reminders_description)
        }
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    fun ensureServiceChannel(context: Context) {
        val channel = NotificationChannel(
            SERVICE_CHANNEL_ID,
            context.getString(R.string.channel_services),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.channel_services_description)
        }
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    fun serviceNotification(context: Context, title: String, text: String): android.app.Notification {
        ensureServiceChannel(context)
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, SERVICE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_star_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .build()
    }

    fun canNotify(context: Context): Boolean {
        return Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun show(context: Context, title: String, text: String) {
        if (!canNotify(context)) return
        ensureChannel(context)

        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_star_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()

        try {
            NotificationManagerCompat.from(context)
                .notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the notify call; nothing to do.
        }
    }
}
