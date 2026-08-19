package com.waypoint.app.notification

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.waypoint.app.R

/**
 * Bundles Waypoint's alarm/block/sleep notifications under one collapsible "Waypoint" entry in
 * the shade instead of scattering them individually — the same pattern apps like Uber use for
 * ride-status notifications. This is purely organizational: Android does not exempt group
 * summaries from the platform-wide dismissible-notification change introduced in Android 14
 * (see behavior-changes-all — FLAG_ONGOING_EVENT no longer blocks swipe for any notification,
 * summaries included; swiping a summary just dismisses the whole group in one gesture).
 */
object WaypointNotificationGroup {

    const val GROUP_KEY = "com.waypoint.app.ALARM_GROUP"
    private const val SUMMARY_NOTIF_ID = 130

    /**
     * Posts or updates the "Waypoint" summary card, or clears it once no grouped notification
     * remains. Call after every notify()/cancel() on a notification that sets this GROUP_KEY.
     */
    fun refresh(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val children = try {
            nm.activeNotifications.filter { it.notification.group == GROUP_KEY && it.id != SUMMARY_NOTIF_ID }
        } catch (e: Throwable) {
            return
        }
        if (children.isEmpty()) {
            nm.cancel(SUMMARY_NOTIF_ID)
            return
        }
        val summary = NotificationCompat.Builder(context, SleepNotificationHelper.CH_ALARM_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Waypoint")
            .setContentText(if (children.size == 1) "1 active notification" else "${children.size} active notifications")
            .setGroup(GROUP_KEY)
            .setGroupSummary(true)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        nm.notify(SUMMARY_NOTIF_ID, summary)
    }
}
