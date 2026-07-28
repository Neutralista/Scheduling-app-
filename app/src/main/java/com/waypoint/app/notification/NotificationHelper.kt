package com.waypoint.app.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.waypoint.app.MainActivity
import com.waypoint.app.R

object NotificationHelper {

    private const val CHANNEL_ID = "waypoint_reminders"
    private const val NOTIF_ID = 1
    private const val SHIFT_CHANNEL_ID = "waypoint_shift"
    private const val SHIFT_NOTIF_ID = 2

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Daily reminders",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Daily habit check-in reminders"
        }
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    fun createShiftChannel(context: Context) {
        val channel = NotificationChannel(
            SHIFT_CHANNEL_ID,
            "Shift reminders",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Clock-out reminder when your planned shift ends"
        }
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    fun sendShiftEndReminder(context: Context) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pi = PendingIntent.getActivity(
            context, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, SHIFT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Time to clock out")
            .setContentText("Your planned shift end has arrived — don't forget to end your shift")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(SHIFT_NOTIF_ID, notification)
    }

    fun sendDailyReminder(context: Context) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pi = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Waypoint")
            .setContentText("Time to check in on your habits")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, notification)
    }
}
