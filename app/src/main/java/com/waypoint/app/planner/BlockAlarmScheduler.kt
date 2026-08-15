package com.waypoint.app.planner

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.waypoint.app.AppLogger
import com.waypoint.app.notification.BlockStartReceiver
import java.time.LocalDate
import java.time.ZoneId

object BlockAlarmScheduler {

    private const val TAG = "BlockAlarmScheduler"

    /**
     * Re-evaluates today's alarm for a single block after it's edited, rescheduled, or
     * deleted. schedule()/cancel() are otherwise only ever driven from a full startup/boot
     * sweep (WaypointApplication.scheduleBlockAlarms), so without this an edit made while the
     * app is running leaves the stale old-time alarm armed until the next restart or reboot.
     * Always cancels first — correct for a delete (loadBlock then returns null) as well as an
     * edit (the just-saved block is picked back up and re-armed if it still qualifies).
     */
    fun resync(context: Context, blockId: String) {
        cancel(context, blockId)
        val store = NamedBlockStore(context)
        val block = store.loadBlock(blockId) ?: return
        if (!block.notificationsEnabled) return
        val today = LocalDate.now()
        val (b, sched) = store.resolveForDate(today).find { it.first.id == blockId } ?: return
        val zone = ZoneId.systemDefault()
        val startMs = today.atTime(sched.startHour, sched.startMinute).atZone(zone).toInstant().toEpochMilli()
        val endMs = if (sched.endHour >= 0) {
            val e = today.atTime(sched.endHour, sched.endMinute).atZone(zone).toInstant().toEpochMilli()
            if (e > startMs) e else e + 24 * 3600_000L
        } else startMs + store.effectiveDurationMinutes(b, today) * 60_000L
        if (startMs > System.currentTimeMillis()) {
            schedule(context, b.id, b.name, b.colorArgb, startMs, endMs)
        }
    }

    fun schedule(
        context: Context,
        blockId: String,
        blockName: String,
        colorArgb: Int?,
        startMs: Long,
        scheduledEndMs: Long
    ) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, BlockStartReceiver::class.java).apply {
            action = BlockStartReceiver.ACTION_BLOCK_START
            putExtra(BlockStartReceiver.EXTRA_BLOCK_ID, blockId)
            putExtra(BlockStartReceiver.EXTRA_BLOCK_NAME, blockName)
            putExtra(BlockStartReceiver.EXTRA_COLOR_ARGB, colorArgb ?: 0)
            putExtra(BlockStartReceiver.EXTRA_HAS_COLOR, colorArgb != null)
            putExtra(BlockStartReceiver.EXTRA_SCHEDULED_END_MS, scheduledEndMs)
        }
        val pi = PendingIntent.getBroadcast(
            context,
            blockId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            am.canScheduleExactAlarms()
        } else true

        if (canExact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, startMs, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, startMs, pi)
            AppLogger.w(TAG, "Exact alarms not permitted; falling back to inexact for block $blockId")
        }
        AppLogger.i(TAG, "schedule: blockId=$blockId startMs=$startMs")
    }

    fun cancel(context: Context, blockId: String) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, BlockStartReceiver::class.java).apply {
            action = BlockStartReceiver.ACTION_BLOCK_START
        }
        val pi = PendingIntent.getBroadcast(
            context,
            blockId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.cancel(pi)
        AppLogger.i(TAG, "cancel: blockId=$blockId")
    }
}
