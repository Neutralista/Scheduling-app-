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
 * What lets a ringing alarm open over the lock screen. Either of two permissions does it:
 *
 * - Full-screen notifications (Android 14+). Android can turn this off when an app from outside
 *   the Play Store updates, which is why lock-screen alarms kept resetting after every update.
 * - Display over other apps. Android keeps this across updates, so with it granted the ring
 *   service opens the alarm screen itself when full-screen is off (AlarmRingService).
 *
 * [check] runs after every update and reboot (BootReceiver) and on every app resume: while
 * alarms are set up and neither permission is on, it posts a notification that opens the
 * "Display over other apps" setting, the one that lasts; once either is on, it clears it.
 */
object FullScreenAlarmPermission {

    private const val CHANNEL_ID = "waypoint_alarm_setup"
    private const val NOTIF_ID = 140
    private const val TAG = "FullScreenAlarmPerm"

    fun isGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    /** Whether a ringing alarm can open over the lock screen by either route. */
    fun canOpenOverLockScreen(context: Context): Boolean = isGranted(context) || canDrawOverlays(context)

    /** The "Display over other apps" page for Waypoint: the permission Android keeps across updates. */
    fun settingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))

    fun check(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (canOpenOverLockScreen(context) || !hasAlarmsSetUp(context)) {
            nm.cancel(NOTIF_ID)
            return
        }
        AppLogger.w(TAG, "check: alarms can't open over the lock screen while alarms are set — prompting")
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
                .setContentTitle("Alarms can't open on the lock screen")
                .setContentText("Tap and allow Display over other apps. It stays on through updates.")
                .setStyle(NotificationCompat.BigTextStyle().bigText(
                    "Alarms still sound, but won't open over the lock screen. Tap and allow " +
                        "\"Display over other apps\" for Waypoint. Unlike the full-screen setting, " +
                        "Android keeps it on through updates."
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
