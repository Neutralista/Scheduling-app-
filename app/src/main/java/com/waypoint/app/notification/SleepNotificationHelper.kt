package com.waypoint.app.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.waypoint.app.MainActivity
import com.waypoint.app.R
import com.waypoint.app.planner.SleepLogStore

object SleepNotificationHelper {

    const val CH_SLEEP_REMINDER = "waypoint_sleep_reminder"
    const val CH_SLEEP_NUDGE    = "waypoint_sleep_nudge"
    const val CH_WAKE_GENTLE    = "waypoint_wake_gentle"
    const val CH_WAKE_MEDIUM    = "waypoint_wake_medium"
    const val CH_WAKE_FULL      = "waypoint_wake_full"

    private const val NOTIF_PRE_SLEEP  = 100
    private const val NOTIF_BEDTIME    = 101
    private const val NOTIF_NUDGE      = 102
    private const val NOTIF_WAKE_SOFT  = 110
    private const val NOTIF_WAKE_MED   = 111
    private const val NOTIF_WAKE_FULL  = 112

    private const val NUDGE_COOLDOWN_MS = 20 * 60_000L

    fun createChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CH_SLEEP_REMINDER, "Sleep reminders", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Bedtime approach and sleep window notifications" }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_SLEEP_NUDGE, "Sleep nudges", NotificationManager.IMPORTANCE_LOW)
                .apply {
                    description = "Silent reminders to put the phone down during sleep hours"
                    setSound(null, null)
                    enableVibration(false)
                }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_WAKE_GENTLE, "Gentle wake", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Soft pre-wake alarm 15 minutes before wake time" }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_WAKE_MEDIUM, "Wake alarm", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Wake alarm 10 minutes before scheduled wake time" }
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val fullCh = NotificationChannel(CH_WAKE_FULL, "Full wake alarm", NotificationManager.IMPORTANCE_MAX)
                .apply {
                    description = "Full alarm at wake time — maximum volume and vibration"
                    enableVibration(true)
                    vibrationPattern = longArrayOf(500, 1000, 500, 1000)
                }
            nm.createNotificationChannel(fullCh)
        }
    }

    // ─── Sleep reminders ───────────────────────────────────────────────────────

    fun sendPreSleepReminder(context: Context, bedTimeText: String) {
        val openPi = openAppPi(context, 200)
        nm(context).notify(
            NOTIF_PRE_SLEEP,
            NotificationCompat.Builder(context, CH_SLEEP_REMINDER)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Bedtime in 30 min")
                .setContentText("Wind down and head to bed by $bedTimeText")
                .setContentIntent(openPi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
        )
    }

    fun sendBedtimeNotification(context: Context) {
        val openPi = openAppPi(context, 201)
        val sleepModePi = PendingIntent.getBroadcast(
            context, 202,
            Intent(SleepActionReceiver.ACTION_ENTER_SLEEP_MODE).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        nm(context).notify(
            NOTIF_BEDTIME,
            NotificationCompat.Builder(context, CH_SLEEP_REMINDER)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Time to sleep")
                .setContentText("Your sleep window starts now")
                .setContentIntent(openPi)
                .addAction(0, "Start Sleep Mode", sleepModePi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
        )
    }

    /**
     * Fires a silent nudge if the phone is active during the scheduled sleep window
     * and at least [NUDGE_COOLDOWN_MS] have passed since the last nudge.
     */
    fun maybeNudge(context: Context, logStore: SleepLogStore) {
        val now = System.currentTimeMillis()
        val bedMs  = logStore.getScheduledBedMs()  ?: return
        val wakeMs = logStore.getScheduledWakeMs() ?: return
        if (now < bedMs || now > wakeMs) return

        val lastNudge = logStore.getLastNudgeMillis() ?: 0L
        if (now - lastNudge < NUDGE_COOLDOWN_MS) return

        logStore.updateLastNudge(now)
        val openPi = openAppPi(context, 203)
        nm(context).notify(
            NOTIF_NUDGE,
            NotificationCompat.Builder(context, CH_SLEEP_NUDGE)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Still awake?")
                .setContentText("Put down the phone and get some rest")
                .setContentIntent(openPi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setSilent(true)
                .build()
        )
    }

    // ─── Wake alarms ───────────────────────────────────────────────────────────

    fun sendWakeGentleNotification(context: Context) {
        nm(context).notify(
            NOTIF_WAKE_SOFT,
            NotificationCompat.Builder(context, CH_WAKE_GENTLE)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Wake up soon")
                .setContentText("Your alarm is in 15 minutes")
                .setContentIntent(openAppPi(context, 210))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
        )
    }

    fun sendWakeMediumNotification(context: Context) {
        nm(context).notify(
            NOTIF_WAKE_MED,
            NotificationCompat.Builder(context, CH_WAKE_MEDIUM)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Almost time to wake up")
                .setContentText("10 minutes until your alarm")
                .setContentIntent(openAppPi(context, 211))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
        )
    }

    fun sendWakeFullNotification(context: Context) {
        val dismissPi = PendingIntent.getBroadcast(
            context, 212,
            Intent(WakeAlarmReceiver.ACTION_DISMISS).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        nm(context).notify(
            NOTIF_WAKE_FULL,
            NotificationCompat.Builder(context, CH_WAKE_FULL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Wake up!")
                .setContentText("Time to start your day")
                .setContentIntent(openAppPi(context, 213))
                .addAction(0, "Dismiss", dismissPi)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVibrate(longArrayOf(500, 1000, 500, 1000))
                .setAutoCancel(true)
                .build()
        )
    }

    fun dismissWakeAlarm(context: Context) {
        nm(context).cancel(NOTIF_WAKE_FULL)
    }

    private fun openAppPi(context: Context, reqCode: Int): PendingIntent =
        PendingIntent.getActivity(
            context, reqCode,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun nm(context: Context) =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
}
