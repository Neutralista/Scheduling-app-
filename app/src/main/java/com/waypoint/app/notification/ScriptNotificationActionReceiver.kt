package com.waypoint.app.notification

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.waypoint.app.MainActivity

class ScriptNotificationActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_SNOOZE   = "com.waypoint.app.SCRIPT_NOTIF_SNOOZE"
        const val ACTION_OPEN_TAB = "com.waypoint.app.SCRIPT_NOTIF_OPEN_TAB"
        const val ACTION_DISMISS  = "com.waypoint.app.SCRIPT_NOTIF_DISMISS"
        const val EXTRA_NOTIF_ID       = "notif_id"
        const val EXTRA_TAB            = "tab"
        const val EXTRA_SNOOZE_MINUTES = "snooze_minutes"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val notifId = intent.getStringExtra(EXTRA_NOTIF_ID) ?: return
        val slot = NotificationStore.slot(notifId)
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .cancel(NotificationStore.notifId(slot))

        when (intent.action) {
            ACTION_SNOOZE -> {
                val minutes = intent.getIntExtra(EXTRA_SNOOZE_MINUTES, 60)
                ScriptNotificationScheduler.snooze(context, notifId, minutes * 60_000L)
            }
            ACTION_OPEN_TAB -> {
                val tab = intent.getStringExtra(EXTRA_TAB) ?: "scripts"
                val tabIndex = when (tab) {
                    "plan"     -> 0
                    "scripts"  -> 1
                    "widgets"  -> 2
                    "settings" -> 3
                    else       -> 0
                }
                context.startActivity(
                    Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        putExtra("tab", tabIndex)
                    }
                )
            }
            // ACTION_DISMISS: notification already cancelled above
        }
    }
}
