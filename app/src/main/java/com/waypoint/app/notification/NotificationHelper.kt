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

    private const val CHANNEL_ID       = "waypoint_reminders"
    private const val NOTIF_ID         = 1
    const val SCRIPTS_CHANNEL_ID       = "waypoint_scripts"

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID, "Daily reminders", NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = "Daily habit check-in reminders" }
        nm(context).createNotificationChannel(channel)
    }

    fun createScriptsChannel(context: Context) {
        val channel = NotificationChannel(
            SCRIPTS_CHANNEL_ID, "Script notifications", NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = "Notifications triggered by scripts" }
        nm(context).createNotificationChannel(channel)
    }

    fun sendDailyReminder(context: Context) {
        val pi = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        nm(context).notify(
            NOTIF_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Waypoint")
                .setContentText("Time to check in on your habits")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
        )
    }

    fun sendScriptNotification(context: Context, config: NotificationConfig) {
        val slot = NotificationStore.slot(config.id)
        val builder = NotificationCompat.Builder(context, SCRIPTS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(config.title)
            .setContentText(config.body)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOngoing(true)
        config.actions.forEachIndexed { i, action ->
            val actionIntent = Intent(context, ScriptNotificationActionReceiver::class.java).apply {
                this.action = when (action.behavior) {
                    "snooze"        -> ScriptNotificationActionReceiver.ACTION_SNOOZE
                    "openTab"       -> ScriptNotificationActionReceiver.ACTION_OPEN_TAB
                    "triggerScript" -> ScriptNotificationActionReceiver.ACTION_TRIGGER_SCRIPT
                    else            -> ScriptNotificationActionReceiver.ACTION_DISMISS
                }
                putExtra(ScriptNotificationActionReceiver.EXTRA_NOTIF_ID, config.id)
                when (action.behavior) {
                    "snooze"        -> putExtra(ScriptNotificationActionReceiver.EXTRA_SNOOZE_MINUTES, action.snoozeMinutes)
                    "openTab"       -> putExtra(ScriptNotificationActionReceiver.EXTRA_TAB, action.tab)
                    "triggerScript" -> {
                        putExtra(ScriptNotificationActionReceiver.EXTRA_SCRIPT_ID, action.scriptId)
                        putExtra(ScriptNotificationActionReceiver.EXTRA_TAB, action.tab)
                    }
                }
            }
            val pi = PendingIntent.getBroadcast(
                context,
                NotificationStore.actionRequestCode(slot, i),
                actionIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, action.label, pi)
        }
        nm(context).notify(NotificationStore.notifId(slot), builder.build())
    }

    private fun nm(context: Context) =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
}
