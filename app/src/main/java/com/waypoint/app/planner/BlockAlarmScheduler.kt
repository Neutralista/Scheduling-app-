package com.waypoint.app.planner

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.waypoint.app.AppLogger
import com.waypoint.app.notification.BlockStartReceiver

object BlockAlarmScheduler {

    private const val TAG = "BlockAlarmScheduler"

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
