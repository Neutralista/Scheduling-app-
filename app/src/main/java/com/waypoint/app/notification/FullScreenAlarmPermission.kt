package com.waypoint.app.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.waypoint.app.AppLogger
import com.waypoint.app.R
import com.waypoint.app.alarm.AlarmStore
import com.waypoint.app.planner.SleepScheduleStore

/**
 * The full-screen-intent permission is what lets a ringing alarm take over the lock screen. On
 * Android 14+ the system can turn it off when an app from outside the Play Store updates, and
 * nothing said so until an alarm failed to cover the lock screen — the only warning was a
 * banner on the Alarms tab. [check] runs after every update and reboot (BootReceiver) and on
 * every app resume: while alarms are set up and the permission is off it posts a notification
 * that opens the setting; once it's back on, that notification is cleared.
 */
object FullScreenAlarmPermission {

    private const val CHANNEL_ID = "waypoint_alarm_setup"
    private const val NOTIF_ID = 140
    private const val TAG = "FullScreenAlarmPerm"

    fun isGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    /** The system page for this permission, or the app's details page where it's missing. */
    fun settingsIntent(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${context.packageName}"))
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        }

    fun check(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (isGranted(context) || !hasAlarmsSetUp(context)) {
            nm.cancel(NOTIF_ID)
            return
        }
        AppLogger.w(TAG, "check: full-screen alarms are off while alarms are set — prompting")
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Alarm setup", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Warnings when something stops alarms from working properly" }
        )
        val intent = settingsIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(
            context, NOTIF_ID, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        nm.notify(
            NOTIF_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Alarms can't show on the lock screen")
                .setContentText("Android turned this off, often after an update. Tap to turn it back on.")
                .setStyle(NotificationCompat.BigTextStyle().bigText(
                    "Android turned off Waypoint's full-screen alarms, often after an update. Alarms " +
                        "will still sound, but won't open over the lock screen. Tap to turn it back on."
                ))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
        )
    }

    private fun hasAlarmsSetUp(context: Context): Boolean {
        if (AlarmStore(context).loadAll().any { it.enabled }) return true
        val sleep = SleepScheduleStore(context).load()
        return sleep.enabled && (sleep.wakeAlarmEnabled || sleep.mediumWakeEnabled || sleep.gentleWakeEnabled)
    }
}
