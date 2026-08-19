package com.waypoint.app.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.waypoint.app.AppLogger
import com.waypoint.app.MainActivity
import com.waypoint.app.R
import com.waypoint.app.planner.SleepLogStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SleepNotificationHelper {

    private const val TAG = "SleepNotificationHelper"

    const val CH_SLEEP_REMINDER = "waypoint_sleep_reminder"
    const val CH_SLEEP_NUDGE    = "waypoint_sleep_nudge"
    const val CH_WAKE_GENTLE    = "waypoint_wake_gentle"
    const val CH_WAKE_MEDIUM    = "waypoint_wake_medium"
    const val CH_WAKE_FULL      = "waypoint_wake_full"
    const val CH_ALARM_STATUS   = "waypoint_alarm_status"

    private const val NOTIF_PRE_SLEEP    = 100
    private const val NOTIF_BEDTIME      = 101
    private const val NOTIF_NUDGE        = 102
    private const val NOTIF_ALARM_STATUS = 120
    private const val NOTIF_USER_ALARM_STATUS = 121

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
        nm.createNotificationChannel(
            NotificationChannel(CH_ALARM_STATUS, "Sleep alarm status", NotificationManager.IMPORTANCE_LOW)
                .apply {
                    description = "Persistent indicator showing scheduled bed and wake times"
                    setSound(null, null)
                    enableVibration(false)
                }
        )
    }

    // ─── Sleep reminders ───────────────────────────────────────────────────────

    fun sendPreSleepReminder(context: Context, bedTimeText: String, minutesBefore: Int = 30) {
        val openPi = openAppPi(context, 200)
        val title = if (minutesBefore >= 60) "Bedtime in ${minutesBefore / 60}h"
                    else "Bedtime in ${minutesBefore} min"
        nm(context).notify(
            NOTIF_PRE_SLEEP,
            NotificationCompat.Builder(context, CH_SLEEP_REMINDER)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
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
     * Fires a silent nudge if the phone is active during the sleep mode window
     * and at least [NUDGE_COOLDOWN_MS] have passed since the last nudge.
     * Sleep mode being active is the trigger; we skip only if it's already past wake time.
     */
    fun maybeNudge(context: Context, logStore: SleepLogStore) {
        val now = System.currentTimeMillis()
        val bedMs  = logStore.getScheduledBedMs()
        val wakeMs = logStore.getScheduledWakeMs() ?: run {
            AppLogger.w(TAG, "maybeNudge: scheduledWakeMs null, skipping")
            return
        }

        AppLogger.i(TAG, "maybeNudge: now=$now bedMs=$bedMs wakeMs=$wakeMs")

        if (now > wakeMs) {
            AppLogger.i(TAG, "maybeNudge: past wake time, skipping")
            return
        }

        val lastNudge = logStore.getLastNudgeMillis() ?: 0L
        val sinceLastNudge = now - lastNudge
        if (sinceLastNudge < NUDGE_COOLDOWN_MS) {
            AppLogger.i(TAG, "maybeNudge: cooldown not expired (${sinceLastNudge / 1000}s < ${NUDGE_COOLDOWN_MS / 1000}s)")
            return
        }

        AppLogger.i(TAG, "maybeNudge: sending nudge")
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

    // ─── Alarm status (persistent, silent) ────────────────────────────────────

    fun showAlarmStatus(context: Context, bedMs: Long, wakeMs: Long) {
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val bedText  = fmt.format(Date(bedMs))
        val wakeText = fmt.format(Date(wakeMs))
        nm(context).notify(
            NOTIF_ALARM_STATUS,
            NotificationCompat.Builder(context, CH_ALARM_STATUS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Sleep alarms active")
                .setContentText("Bed $bedText · Wake $wakeText")
                .setContentIntent(openAppPi(context, 220))
                .setOngoing(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        )
    }

    fun clearAlarmStatus(context: Context) {
        nm(context).cancel(NOTIF_ALARM_STATUS)
    }

    // ─── User alarm status (persistent, silent) ───────────────────────────────

    fun showUserAlarmStatus(context: Context, label: String, fireMs: Long) {
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        nm(context).notify(
            NOTIF_USER_ALARM_STATUS,
            NotificationCompat.Builder(context, CH_ALARM_STATUS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Alarm set")
                .setContentText("$label · ${fmt.format(Date(fireMs))}")
                .setContentIntent(openAlarmsTabPi(context, 221))
                .setOngoing(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        )
    }

    fun clearUserAlarmStatus(context: Context) {
        nm(context).cancel(NOTIF_USER_ALARM_STATUS)
    }

    private fun openAlarmsTabPi(context: Context, reqCode: Int): PendingIntent =
        PendingIntent.getActivity(
            context, reqCode,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                putExtra("tab", 5)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

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
