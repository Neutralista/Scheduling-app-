package com.waypoint.app.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.waypoint.app.planner.ActiveBlockSession

object BlockNotificationHelper {

    const val CHANNEL_ID = "waypoint_blocks"
    const val SESSION_CHANNEL_ID = "waypoint_session"
    private const val NOTIF_ID_BASE = 9000
    private const val SESSION_NOTIF_ID = 9500

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(
                CHANNEL_ID,
                "Time Blocks",
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "Notifications when a named time block starts" })
        }
        if (nm.getNotificationChannel(SESSION_CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(
                SESSION_CHANNEL_ID,
                "Active Block Session",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Persistent countdown while a time block is active" })
        }
    }

    fun postBlockStartNotification(
        context: Context,
        blockId: String,
        blockName: String,
        scheduledEndMs: Long
    ) {
        val nm = context.getSystemService(NotificationManager::class.java)

        val proceedIntent = Intent(context, BlockStartReceiver::class.java).apply {
            action = BlockStartReceiver.ACTION_PROCEED_BLOCK
            putExtra(BlockStartReceiver.EXTRA_BLOCK_ID, blockId)
            putExtra(BlockStartReceiver.EXTRA_BLOCK_NAME, blockName)
            putExtra(BlockStartReceiver.EXTRA_SCHEDULED_END_MS, scheduledEndMs)
        }
        val proceedPi = PendingIntent.getBroadcast(
            context,
            blockId.hashCode() + 1,
            proceedIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val dismissIntent = Intent(context, BlockStartReceiver::class.java).apply {
            action = BlockStartReceiver.ACTION_DISMISS_BLOCK_START
            putExtra(BlockStartReceiver.EXTRA_BLOCK_ID, blockId)
        }
        val dismissPi = PendingIntent.getBroadcast(
            context,
            blockId.hashCode() + 2,
            dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("$blockName starts now")
            .setContentText("Tap Proceed to enter the time block")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .addAction(0, "Proceed", proceedPi)
            .addAction(0, "Dismiss", dismissPi)
            .setContentIntent(proceedPi)
            .build()

        nm.notify(notifId(blockId), notif)
    }

    fun cancelBlockStartNotification(context: Context, blockId: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.cancel(notifId(blockId))
    }

    fun postSessionLiveNotification(context: Context, session: ActiveBlockSession) {
        val nm = context.getSystemService(NotificationManager::class.java)

        // setWhen() takes a wall-clock epoch-millis timestamp — the system derives the
        // chronometer's elapsedRealtime-based base from it internally (base = when +
        // (elapsedRealtime - currentTimeMillis)). Passing an elapsedRealtime-based value here
        // double-counts that offset and makes the displayed countdown wildly wrong.
        val notif = NotificationCompat.Builder(context, SESSION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(session.blockName)
            .setContentText("Block in progress")
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setWhen(session.scheduledEndMs)
            .setShowWhen(true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        nm.notify(SESSION_NOTIF_ID, notif)
    }

    fun cancelSessionLiveNotification(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.cancel(SESSION_NOTIF_ID)
    }

    private fun notifId(blockId: String) = NOTIF_ID_BASE + (blockId.hashCode() and 0x7FFFFFFF) % 1000
}
