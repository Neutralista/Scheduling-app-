package com.waypoint.app.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.waypoint.app.AppLogger
import com.waypoint.app.notification.AlarmRingService
import com.waypoint.app.notification.SleepNotificationHelper

class UserAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val id = intent.getStringExtra(EXTRA_ALARM_ID) ?: return
        val label = intent.getStringExtra(EXTRA_LABEL) ?: "Alarm"
        val stage = intent.getStringExtra(EXTRA_STAGE)
        val isSnoozeFire = intent.getBooleanExtra(EXTRA_SNOOZE_FIRE, false)
        AppLogger.i(TAG, "onReceive: id=$id label='$label' stage=$stage snoozeFire=$isSnoozeFire")

        val store = AlarmStore(context)
        val alarm = try {
            store.loadAll().firstOrNull { it.id == id }
        } catch (e: Throwable) {
            AppLogger.e(TAG, "onReceive: store read threw ${e.javaClass.name}: ${e.message}", e)
            null
        }

        // Only the final/full ring — not a staged pulse, not a snooze re-fire — advances the
        // alarm's own schedule (reschedule for the next repeat day, or disable a one-shot).
        if (stage == null && !isSnoozeFire) {
            try {
                if (alarm != null) {
                    if (alarm.repeatDays.isNotEmpty()) {
                        UserAlarmScheduler.schedule(context, alarm)
                    } else {
                        store.setEnabledSync(id, false)
                        UserAlarmScheduler.refreshStatusNotification(context, store.loadAll())
                    }
                }
            } catch (e: Throwable) {
                AppLogger.e(TAG, "onReceive: store/reschedule threw ${e.javaClass.name}: ${e.message}", e)
            }
        }

        val volume = when (stage) {
            STAGE_GENTLE -> 0.35f
            STAGE_MEDIUM -> 0.7f
            else -> alarm?.volume ?: 1.0f
        }
        val channel = when (stage) {
            STAGE_GENTLE -> SleepNotificationHelper.CH_WAKE_GENTLE
            STAGE_MEDIUM -> SleepNotificationHelper.CH_WAKE_MEDIUM
            else -> SleepNotificationHelper.CH_WAKE_FULL
        }
        val ringTitle = when (stage) {
            STAGE_GENTLE -> "$label soon"
            STAGE_MEDIUM -> "Almost time — $label"
            else -> label
        }
        val fullScreen = stage == null

        context.startForegroundService(
            Intent(context, AlarmRingService::class.java).apply {
                action = AlarmRingService.ACTION_RING
                putExtra(AlarmRingService.EXTRA_VOLUME, volume)
                putExtra(AlarmRingService.EXTRA_CHANNEL, channel)
                putExtra(AlarmRingService.EXTRA_TITLE, ringTitle)
                putExtra(AlarmRingService.EXTRA_FULL_SCREEN, fullScreen)
                putExtra(AlarmRingService.EXTRA_SOURCE, AlarmRingService.SOURCE_USER)
                putExtra(AlarmRingService.EXTRA_ALARM_ID, id)
                putExtra(AlarmRingService.EXTRA_SNOOZE_MINUTES, alarm?.snoozeMinutes ?: 10)
                putExtra(AlarmRingService.EXTRA_VIBRATE, alarm?.vibrate ?: true)
                alarm?.soundUri?.let { putExtra(AlarmRingService.EXTRA_SOUND_URI, it) }
                putExtra(AlarmRingService.EXTRA_MAX_VOLUME, fullScreen && (alarm?.maxVolumeOverride ?: false))
            }
        )
    }

    companion object {
        const val ACTION_FIRE       = "com.waypoint.app.USER_ALARM_FIRE"
        const val EXTRA_ALARM_ID    = "alarm_id"
        const val EXTRA_LABEL       = "label"
        const val EXTRA_STAGE       = "stage"
        const val EXTRA_SNOOZE_FIRE = "snooze_fire"
        const val STAGE_GENTLE      = "gentle"
        const val STAGE_MEDIUM      = "medium"
        private const val TAG       = "UserAlarmReceiver"
    }
}
