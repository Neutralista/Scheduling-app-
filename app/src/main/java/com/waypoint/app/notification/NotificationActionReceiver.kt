package com.waypoint.app.notification

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.waypoint.app.MainActivity

class NotificationActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_NOT_NOW = "com.waypoint.app.PLANNER_NOT_NOW"
        const val ACTION_SET_SCHEDULE = "com.waypoint.app.PLANNER_SET_SCHEDULE"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NotificationHelper.PLANNER_NOTIF_ID)

        if (intent.action == ACTION_SET_SCHEDULE) {
            context.startActivity(
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
            )
        }
    }
}
