package com.waypoint.app.notification

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import com.waypoint.app.AppLogger
import com.waypoint.app.MainActivity
import com.waypoint.app.R
import com.waypoint.app.planner.Reminder
import com.waypoint.app.planner.ReminderOccurrence
import com.waypoint.app.planner.ReminderStatus
import com.waypoint.app.planner.ReminderStore
import com.waypoint.app.planner.nextOccurrenceAfter
import com.waypoint.app.planner.remindersForDay
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Reminders' notifications. Each reminder has one alarm, for its next time due; when it goes off
 * the reminder's notification is posted — pinned, with sound and vibration, Done and Skip — and
 * the next time is armed. Until Done or Skip it stays pinned: swiping it away brings it straight
 * back (silently). It rings once, at its time.
 */
object ReminderAlarms {

    private const val TAG = "Reminders"
    const val CHANNEL_ID = "waypoint_reminders"
    private const val TAG_PREFIX = "reminder:"
    private const val NOTIF_ID = 1
    private val VIBRATION = longArrayOf(0, 400, 200, 400, 200, 400)

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Medicine and other reminders, until they're done or skipped"
                enableVibration(true)
                vibrationPattern = VIBRATION
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
        )
    }

    // ── Alarms ────────────────────────────────────────────────────────────────

    private fun intent(context: Context, action: String, what: String, occ: ReminderOccurrence?, reminderId: String) =
        Intent(context, ReminderReceiver::class.java).apply {
            this.action = action
            // Unique per alarm / button, so PendingIntents never overwrite each other.
            data = Uri.parse("waypoint-reminder://$what/${Uri.encode(occ?.key ?: reminderId)}")
            putExtra(ReminderReceiver.EXTRA_ID, reminderId)
            occ?.let {
                putExtra(ReminderReceiver.EXTRA_DATE, it.date.toString())
                putExtra(ReminderReceiver.EXTRA_TIME, it.time)
            }
        }

    private fun broadcast(context: Context, intent: Intent) =
        PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun setAlarm(context: Context, atMs: Long, pi: PendingIntent) {
        val am = context.getSystemService(AlarmManager::class.java)
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        if (canExact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
    }

    /** Arms [reminder]'s next time due after [afterMs] (or cancels its alarm when there's none). */
    fun arm(context: Context, reminder: Reminder, afterMs: Long = System.currentTimeMillis()) {
        val store = ReminderStore(context)
        val am = context.getSystemService(AlarmManager::class.java)
        val next = reminder.nextOccurrenceAfter(afterMs, LocalDate.now()) { store.isSettled(it) }
        if (next == null) {
            am.cancel(broadcast(context, intent(context, ReminderReceiver.ACTION_FIRE, "fire", null, reminder.id)))
            return
        }
        // One fire alarm per reminder: its data is the reminder's id, whichever time is next.
        val fire = Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderReceiver.ACTION_FIRE
            data = Uri.parse("waypoint-reminder://fire/${Uri.encode(reminder.id)}")
            putExtra(ReminderReceiver.EXTRA_ID, reminder.id)
            putExtra(ReminderReceiver.EXTRA_DATE, next.date.toString())
            putExtra(ReminderReceiver.EXTRA_TIME, next.time)
        }
        setAlarm(context, next.atMs, broadcast(context, fire))
    }

    /** Cancels a ring-again alarm left from before reminders rang only once. */
    private fun cancelNag(context: Context, occ: ReminderOccurrence) {
        context.getSystemService(AlarmManager::class.java)
            .cancel(broadcast(context, intent(context, ReminderReceiver.ACTION_NAG, "nag", occ, occ.reminder.id)))
    }

    /**
     * Re-arms every reminder, and brings back any time due today (or a one-off from before) that
     * isn't done or skipped but has no notification — after a reboot, say. Call on launch, on
     * boot, and after reminders change.
     */
    fun rescheduleAll(context: Context) {
        val store = ReminderStore(context)
        runCatching { store.prune() }
        val reminders = store.loadAll()
        reminders.forEach { arm(context, it) }
        val showing = activeKeys(context)
        val now = System.currentTimeMillis()
        remindersForDay(reminders, LocalDate.now()) { store.isSettled(it) }
            .filter { it.atMs <= now && it.key !in showing }
            .forEach { occ -> post(context, occ, alert = true) }
    }

    // ── Done / Skip / Undo ───────────────────────────────────────────────────

    fun settle(context: Context, occ: ReminderOccurrence, status: ReminderStatus) {
        ReminderStore(context).setStatus(occ.key, status)
        context.getSystemService(NotificationManager::class.java).cancel(TAG_PREFIX + occ.key, NOTIF_ID)
        cancelNag(context, occ)
        arm(context, occ.reminder)
        AppLogger.i(TAG, "${status.name.lowercase()}: ${occ.key}")
    }

    /** Back to not done: its notification returns if it's already due. */
    fun undo(context: Context, occ: ReminderOccurrence) {
        ReminderStore(context).clearStatus(occ.key)
        if (occ.atMs <= System.currentTimeMillis()) post(context, occ, alert = false)
        arm(context, occ.reminder)
    }

    /** Clears [reminderId]'s notifications and alarms (deleted, switched off, or its times changed). */
    fun clear(context: Context, reminderId: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val store = ReminderStore(context)
        runCatching { nm.activeNotifications }.getOrDefault(emptyArray())
            .filter { it.tag?.startsWith(TAG_PREFIX) == true && it.tag.split("|").getOrNull(1) == reminderId }
            .forEach { sbn ->
                nm.cancel(sbn.tag, sbn.id)
                val parts = sbn.tag.removePrefix(TAG_PREFIX).split("|")
                val reminder = store.load(reminderId) ?: Reminder(reminderId, "")
                runCatching { cancelNag(context, ReminderOccurrence(reminder, LocalDate.parse(parts[0]), parts[2])) }
            }
        context.getSystemService(AlarmManager::class.java)
            .cancel(broadcast(context, intent(context, ReminderReceiver.ACTION_FIRE, "fire", null, reminderId)))
    }

    private fun activeKeys(context: Context): Set<String> =
        runCatching { context.getSystemService(NotificationManager::class.java).activeNotifications }
            .getOrDefault(emptyArray())
            .mapNotNull { it.tag?.takeIf { t -> t.startsWith(TAG_PREFIX) }?.removePrefix(TAG_PREFIX) }
            .toSet()

    // ── The notification ─────────────────────────────────────────────────────

    internal fun post(context: Context, occ: ReminderOccurrence, alert: Boolean) {
        val r = occ.reminder
        val today = LocalDate.now()
        val due = when (occ.date) {
            today -> "Due at ${occ.time}"
            today.minusDays(1) -> "Due yesterday at ${occ.time}"
            else -> "Due ${occ.date.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))} at ${occ.time}"
        }
        val text = if (r.note.isNotBlank()) "$due · ${r.note}" else due
        val open = PendingIntent.getActivity(
            context,
            (TAG_PREFIX + occ.key).hashCode(),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra("tab", 2)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(r.title.ifBlank { "Reminder" })
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setWhen(occ.atMs)
            .setShowWhen(true)
            .setContentIntent(open)
            .setDeleteIntent(broadcast(context, intent(context, ReminderReceiver.ACTION_DISMISSED, "dismissed", occ, r.id)))
            .addAction(0, "Done", broadcast(context, intent(context, ReminderReceiver.ACTION_DONE, "done", occ, r.id)))
            .addAction(0, "Skip", broadcast(context, intent(context, ReminderReceiver.ACTION_SKIP, "skip", occ, r.id)))
            .apply {
                if (alert) {
                    setVibrate(VIBRATION)
                    setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
                } else {
                    setSilent(true)
                }
            }
            .build()
        runCatching {
            context.getSystemService(NotificationManager::class.java).notify(TAG_PREFIX + occ.key, NOTIF_ID, notif)
        }.onFailure { AppLogger.e(TAG, "post failed", it) }
    }
}

class ReminderReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_FIRE = "com.waypoint.app.REMINDER_FIRE"
        const val ACTION_NAG = "com.waypoint.app.REMINDER_NAG"
        const val ACTION_DONE = "com.waypoint.app.REMINDER_DONE"
        const val ACTION_SKIP = "com.waypoint.app.REMINDER_SKIP"
        const val ACTION_DISMISSED = "com.waypoint.app.REMINDER_DISMISSED"
        const val EXTRA_ID = "reminder_id"
        const val EXTRA_DATE = "date"
        const val EXTRA_TIME = "time"
        private const val TAG = "ReminderReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val store = ReminderStore(context)
        val reminder = store.load(id)
        if (reminder == null) {
            // Deleted since: nothing to ring, and nothing to leave behind.
            ReminderAlarms.clear(context, id)
            return
        }
        val date = intent.getStringExtra(EXTRA_DATE)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return
        val time = intent.getStringExtra(EXTRA_TIME) ?: return
        val occ = ReminderOccurrence(reminder, date, time)
        val pending = reminder.enabled && !store.isSettled(occ.key)
        when (intent.action) {
            ACTION_FIRE -> {
                if (pending) ReminderAlarms.post(context, occ, alert = true)
                ReminderAlarms.arm(context, reminder, maxOf(System.currentTimeMillis(), occ.atMs))
            }
            // Reminders ring once now; an alarm left from when they rang again does nothing.
            ACTION_NAG -> Unit
            // Swiped away without Done or Skip: it comes back.
            ACTION_DISMISSED -> if (pending) ReminderAlarms.post(context, occ, alert = false)
            ACTION_DONE -> ReminderAlarms.settle(context, occ, ReminderStatus.DONE)
            ACTION_SKIP -> ReminderAlarms.settle(context, occ, ReminderStatus.SKIPPED)
        }
        AppLogger.i(TAG, "${intent.action?.substringAfterLast('.')} ${occ.key}")
    }
}
