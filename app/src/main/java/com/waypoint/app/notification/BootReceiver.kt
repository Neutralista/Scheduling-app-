package com.waypoint.app.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.waypoint.app.AppLogger
import com.waypoint.app.WaypointApplication
import com.waypoint.app.alarm.AlarmBlockSync
import com.waypoint.app.alarm.UserAlarmScheduler
import com.waypoint.app.planner.SleepCheckReceiver
import com.waypoint.app.planner.SleepLogStore
import com.waypoint.app.planner.SleepModeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext as? WaypointApplication ?: return
        if (app.startupCrash != null) {
            AppLogger.e(TAG, "onReceive: skipping re-arm — app failed to initialize", app.startupCrash)
            return
        }
        // syncToRegistry does ContentResolver calendar queries — must not run on the main thread.
        // goAsync() holds the wake lock and extends the receiver's life until finish() is called.
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            // Each step runs independently of the others: one failing (e.g. a revoked calendar
            // permission inside syncToRegistry, which alone loops over two weeks of calendar
            // queries) must not silently cancel every re-arm step after it in the same coroutine
            // — previously an uncaught exception here meant NOTHING got rescheduled after a
            // reboot, not just the one step that actually failed.
            try {
                runCatching { app.env.sleepStore.syncToRegistry(app.env.eventPlanner) }
                    .onFailure { AppLogger.e(TAG, "onReceive: sleepStore.syncToRegistry failed", it) }
                runCatching { AlarmBlockSync.sync(context) }
                    .onFailure { AppLogger.e(TAG, "onReceive: AlarmBlockSync.sync failed", it) }
                runCatching { UserAlarmScheduler.scheduleAll(context, app.env.alarms.getAll()) }
                    .onFailure { AppLogger.e(TAG, "onReceive: UserAlarmScheduler.scheduleAll failed", it) }
                runCatching { app.scheduleBlockAlarms(context) }
                    .onFailure { AppLogger.e(TAG, "onReceive: scheduleBlockAlarms failed", it) }
                // Alarms don't survive a reboot — without this, sleep mode armed before the
                // restart would sit in MONITORING/SLEEPING with nothing checking it.
                runCatching {
                    if (SleepLogStore(context).getSleepModeState() != SleepModeState.IDLE) {
                        SleepCheckReceiver.scheduleNextCheck(context)
                    }
                }.onFailure { AppLogger.e(TAG, "onReceive: sleep check re-arm failed", it) }
                // Android can turn full-screen alarms off during an app update; say so now,
                // not when an alarm fails to cover the lock screen.
                runCatching { FullScreenAlarmPermission.check(context) }
                    .onFailure { AppLogger.e(TAG, "onReceive: full-screen permission check failed", it) }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
