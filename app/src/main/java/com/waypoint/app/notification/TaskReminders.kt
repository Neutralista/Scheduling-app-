package com.waypoint.app.notification

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.waypoint.app.AppLogger
import com.waypoint.app.MainActivity
import com.waypoint.app.R
import com.waypoint.app.WaypointApplication
import com.waypoint.app.planner.ScheduledEvent
import com.waypoint.app.planner.TaskQueueStore
import com.waypoint.app.script.TaskManagerScript
import java.time.LocalDate

/**
 * "Remind me" on floating tasks: a notification when the plan says a task starts, with Done and
 * Skip. Armed from today's live plan every time it's worked out (the Plan tab's timeline), one
 * alarm per upcoming task, so it keeps working with the app closed. Tasks move as the plan
 * changes, so each sync re-arms them all at their current start. A task is reminded once a day.
 */
object TaskReminderScheduler {

    private const val TAG = "TaskReminders"
    const val CHANNEL_ID = "waypoint_task_reminders"
    private const val PREFS = "wp_task_reminders"
    private const val KEY_ARMED = "armed"
    private const val KEY_REMINDED = "reminded"  // "yyyy-MM-dd|taskId"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Task reminders", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "When a task you asked to be reminded of is due to start" }
            )
        }
    }

    /** Re-arms reminders from [scheduled], today's live plan. Only tasks still to do and still ahead. */
    fun sync(context: Context, scheduled: List<ScheduledEvent>, taskManager: TaskManagerScript) {
        val remindIds = TaskQueueStore(context).loadAll().filter { it.remindAtStart }.map { it.id }.toSet()
        val now = System.currentTimeMillis()
        val today = LocalDate.now().toString()
        val reminded = prefs(context).getStringSet(KEY_REMINDED, emptySet()).orEmpty()
        val due = scheduled.filter { se ->
            se.event.sourceWidgetId == TaskManagerScript.WIDGET_ID &&
                se.event.id in remindIds &&
                se.startMillis > now &&
                "$today|${se.event.id}" !in reminded &&
                !taskManager.isDone(se.event.id) &&
                !taskManager.isSkipped(se.event.id)
        }
        val am = context.getSystemService(AlarmManager::class.java)
        val armed = prefs(context).getStringSet(KEY_ARMED, emptySet()).orEmpty()
        (armed - due.map { it.event.id }.toSet()).forEach { am.cancel(remindIntent(context, it, "", 0L)) }
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        due.forEach { se ->
            val pi = remindIntent(context, se.event.id, se.event.title, se.startMillis)
            if (canExact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, se.startMillis, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, se.startMillis, pi)
        }
        // Only today's reminded marks are worth keeping.
        prefs(context).edit()
            .putStringSet(KEY_ARMED, due.map { it.event.id }.toSet())
            .putStringSet(KEY_REMINDED, reminded.filter { it.startsWith("$today|") }.toSet())
            .apply()
        if (due.isNotEmpty()) AppLogger.i(TAG, "sync: armed ${due.size} reminder(s)")
    }

    private fun remindIntent(context: Context, taskId: String, title: String, startMs: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            taskId.hashCode(),
            Intent(context, TaskReminderReceiver::class.java).apply {
                action = TaskReminderReceiver.ACTION_REMIND
                putExtra(TaskReminderReceiver.EXTRA_TASK_ID, taskId)
                putExtra(TaskReminderReceiver.EXTRA_TITLE, title)
                putExtra(TaskReminderReceiver.EXTRA_START_MS, startMs)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    internal fun markReminded(context: Context, taskId: String) {
        val p = prefs(context)
        val reminded = p.getStringSet(KEY_REMINDED, emptySet()).orEmpty() + "${LocalDate.now()}|$taskId"
        p.edit()
            .putStringSet(KEY_REMINDED, reminded)
            .putStringSet(KEY_ARMED, p.getStringSet(KEY_ARMED, emptySet()).orEmpty() - taskId)
            .apply()
    }

    internal fun notifId(taskId: String) = 12000 + (taskId.hashCode() and 0xFFF)
}

class TaskReminderReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_REMIND = "com.waypoint.app.TASK_REMIND"
        const val ACTION_DONE = "com.waypoint.app.TASK_REMIND_DONE"
        const val ACTION_SKIP = "com.waypoint.app.TASK_REMIND_SKIP"
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_START_MS = "start_ms"
        private const val TAG = "TaskReminderReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return
        val taskManager = runCatching { (context.applicationContext as WaypointApplication).env.taskManager }
            .onFailure { AppLogger.e(TAG, "onReceive: app not ready", it) }
            .getOrNull() ?: return
        val nm = context.getSystemService(NotificationManager::class.java)
        when (intent.action) {
            ACTION_REMIND -> {
                TaskReminderScheduler.markReminded(context, taskId)
                // It may have been done, skipped or deleted since this was armed.
                if (taskManager.isDone(taskId) || taskManager.isSkipped(taskId)) return
                if (taskManager.getAllTasks().none { it.id == taskId && it.remindAtStart }) return
                post(context, nm, taskId, intent.getStringExtra(EXTRA_TITLE).orEmpty())
            }
            ACTION_DONE -> {
                taskManager.markDone(taskId)
                nm.cancel(TaskReminderScheduler.notifId(taskId))
                AppLogger.i(TAG, "done from reminder: $taskId")
            }
            ACTION_SKIP -> {
                taskManager.skipTask(taskId)
                nm.cancel(TaskReminderScheduler.notifId(taskId))
                AppLogger.i(TAG, "skipped from reminder: $taskId")
            }
        }
    }

    private fun post(context: Context, nm: NotificationManager, taskId: String, title: String) {
        fun action(action: String, code: Int) = PendingIntent.getBroadcast(
            context,
            taskId.hashCode() + code,
            Intent(context, TaskReminderReceiver::class.java).apply {
                this.action = action
                putExtra(EXTRA_TASK_ID, taskId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            context,
            taskId.hashCode() + 3,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra("tab", 2)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(context, TaskReminderScheduler.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title.ifBlank { "Task" })
            .setContentText("Time to start")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(0, "Done", action(ACTION_DONE, 1))
            .addAction(0, "Skip today", action(ACTION_SKIP, 2))
            .setGroup(WaypointNotificationGroup.GROUP_KEY)
            .build()
        nm.notify(TaskReminderScheduler.notifId(taskId), notif)
        WaypointNotificationGroup.refresh(context)
    }
}
