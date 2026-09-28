package com.waypoint.app.notification

import android.annotation.SuppressLint
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
    // _v2 — these three previously had no setSound(null, null), so posting to them played the
    // system's default notification chime on top of the app's own MediaPlayer-driven alarm tone
    // (and, for CH_WAKE_FULL, its own channel vibration pattern on top of the app's manual
    // Vibrator calls too). Channels are immutable once created, so renaming forces every
    // existing install to pick up the silenced version instead of keeping the broken one forever.
    const val CH_WAKE_GENTLE    = "waypoint_wake_gentle_v2"
    const val CH_WAKE_MEDIUM    = "waypoint_wake_medium_v2"
    const val CH_WAKE_FULL      = "waypoint_wake_full_v2"
    const val CH_ALARM_STATUS   = "waypoint_alarm_status"

    private const val NOTIF_PRE_SLEEP    = 100
    private const val NOTIF_BEDTIME      = 101
    private const val NOTIF_NUDGE        = 102
    private const val NOTIF_ALARM_STATUS = 120
    private const val NOTIF_USER_ALARM_STATUS = 121

    private const val NUDGE_COOLDOWN_MS = 20 * 60_000L

    // IMPORTANCE_MAX (5) is a real NotificationManager constant one step above IMPORTANCE_HIGH,
    // just not part of the public @IntDef lint checks against — hence the suppress below.
    @SuppressLint("WrongConstant")
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
                .apply {
                    description = "Soft pre-wake alarm 15 minutes before wake time — sound and " +
                        "vibration are played by the app directly, not this channel"
                    setSound(null, null)
                    enableVibration(false)
                }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_WAKE_MEDIUM, "Wake alarm", NotificationManager.IMPORTANCE_HIGH)
                .apply {
                    description = "Wake alarm 10 minutes before scheduled wake time — sound and " +
                        "vibration are played by the app directly, not this channel"
                    setSound(null, null)
                    enableVibration(false)
                }
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val fullCh = NotificationChannel(CH_WAKE_FULL, "Full wake alarm", NotificationManager.IMPORTANCE_MAX)
                .apply {
                    description = "Full alarm at wake time — sound and vibration (including " +
                        "custom sound/volume/snooze settings) are played by the app directly, " +
                        "not this channel"
                    setSound(null, null)
                    enableVibration(false)
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
        val title = if (minutesBefore >= 60) "Bedtime in ${minutesBefore / 60}h"
                    else "Bedtime in ${minutesBefore} min"
        postBedtimeReminder(context, NOTIF_PRE_SLEEP, title, "Wind down and head to bed by $bedTimeText")
    }

    fun sendBedtimeNotification(context: Context) {
        postBedtimeReminder(context, NOTIF_BEDTIME, "Time to sleep", "Your sleep window starts now")
    }

    /**
     * A bedtime reminder, full screen: its notification carries a full-screen intent to
     * [BedtimeActivity] (over the lock screen) plus Go to sleep / Snooze, and with "Display over
     * other apps" allowed the screen also opens straight away over whatever's in use — a
     * full-screen intent alone only shows as a banner while the phone is being used.
     */
    private fun postBedtimeReminder(context: Context, id: Int, title: String, text: String, nudge: Boolean = false) {
        val screen = Intent(context, BedtimeActivity::class.java)
            .putExtra(BedtimeActivity.EXTRA_TITLE, title)
            .putExtra(BedtimeActivity.EXTRA_TEXT, text)
            .putExtra(BedtimeActivity.EXTRA_NUDGE, nudge)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
        val screenPi = PendingIntent.getActivity(
            context, 205, screen, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        fun action(action: String, code: Int) = PendingIntent.getBroadcast(
            context, code,
            Intent(action).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        nm(context).notify(
            id,
            NotificationCompat.Builder(context, CH_SLEEP_REMINDER)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(screenPi)
                .setFullScreenIntent(screenPi, true)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .addAction(0, "Go to sleep", action(SleepActionReceiver.ACTION_ENTER_SLEEP_MODE, 202))
                .addAction(
                    0, "Snooze ${SleepActionReceiver.SNOOZE_MINUTES} min",
                    if (nudge) action(SleepActionReceiver.ACTION_SNOOZE_NUDGE, 207) else action(SleepActionReceiver.ACTION_SNOOZE_BEDTIME, 206)
                )
                .setSilent(nudge)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setGroup(WaypointNotificationGroup.GROUP_KEY)
                .build()
        )
        WaypointNotificationGroup.refresh(context)
        if (FullScreenAlarmPermission.canDrawOverlays(context)) {
            runCatching { context.startActivity(screen) }
                .onFailure { AppLogger.e(TAG, "postBedtimeReminder: opening the bedtime screen failed", it) }
        }
    }

    /** Clears the bedtime reminders (gone to sleep, or snoozed). */
    fun clearBedtimeReminders(context: Context) {
        nm(context).cancel(NOTIF_NUDGE)
        nm(context).cancel(NOTIF_PRE_SLEEP)
        nm(context).cancel(NOTIF_BEDTIME)
        WaypointNotificationGroup.refresh(context)
    }

    /**
     * Fires a silent nudge if the phone is active during the sleep mode window
     * and at least [NUDGE_COOLDOWN_MS] have passed since the last nudge.
     * Sleep mode being active is the trigger; we skip only if it's already past wake time.
     */
    fun maybeNudge(context: Context, logStore: SleepLogStore, cooldownMs: Long = NUDGE_COOLDOWN_MS) {
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
        if (now < logStore.getNudgeQuietUntil()) {
            AppLogger.i(TAG, "maybeNudge: snoozed, skipping")
            return
        }

        val lastNudge = logStore.getLastNudgeMillis() ?: 0L
        val sinceLastNudge = now - lastNudge
        if (sinceLastNudge < cooldownMs) {
            AppLogger.i(TAG, "maybeNudge: cooldown not expired (${sinceLastNudge / 1000}s < ${cooldownMs / 1000}s)")
            return
        }

        // Still using the phone with Sleep Mode on: the bedtime screen again, gently (silent).
        AppLogger.i(TAG, "maybeNudge: showing the bedtime screen again")
        logStore.updateLastNudge(now)
        postBedtimeReminder(context, NOTIF_NUDGE, "Still up?", "Sleep Mode is on — time to put the phone down", nudge = true)
    }

    /** How soon after the last one an unlock (or the screen coming on) brings the bedtime screen back. */
    const val UNLOCK_NUDGE_COOLDOWN_MS = 60_000L

    /** While the phone stays in use in Sleep Mode, "Still up?" comes back this often. */
    const val STILL_UP_REPEAT_MS = 3 * 60_000L

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
                .setGroup(WaypointNotificationGroup.GROUP_KEY)
                .build()
        )
        WaypointNotificationGroup.refresh(context)
    }

    fun clearAlarmStatus(context: Context) {
        nm(context).cancel(NOTIF_ALARM_STATUS)
        WaypointNotificationGroup.refresh(context)
    }

    /** True while the sleep-alarm status card is currently showing in the shade. */
    fun isAlarmStatusShowing(context: Context): Boolean =
        try { nm(context).activeNotifications.any { it.id == NOTIF_ALARM_STATUS } } catch (e: Throwable) { true }

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
                .setGroup(WaypointNotificationGroup.GROUP_KEY)
                .build()
        )
        WaypointNotificationGroup.refresh(context)
    }

    fun clearUserAlarmStatus(context: Context) {
        nm(context).cancel(NOTIF_USER_ALARM_STATUS)
        WaypointNotificationGroup.refresh(context)
    }

    /** True while the user-alarm status card is currently showing in the shade. */
    fun isUserAlarmStatusShowing(context: Context): Boolean =
        try { nm(context).activeNotifications.any { it.id == NOTIF_USER_ALARM_STATUS } } catch (e: Throwable) { true }

    private fun openAlarmsTabPi(context: Context, reqCode: Int): PendingIntent =
        PendingIntent.getActivity(
            context, reqCode,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                putExtra("tab", 4)  // Alarms
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
