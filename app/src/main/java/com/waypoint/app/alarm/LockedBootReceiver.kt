package com.waypoint.app.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.waypoint.app.AppLogger
import com.waypoint.app.notification.WakeAlarmScheduler

/**
 * Re-arms alarms right after a restart, before the phone is unlocked, from [BootAlarmMirror].
 * Direct-boot aware, so it runs at the PIN screen; BootReceiver does the full re-arm once the
 * phone is unlocked. Touches only device-protected storage.
 */
class LockedBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return
        val dp = context.createDeviceProtectedStorageContext()
        runCatching {
            val wake = BootAlarmMirror.wake(dp)
            if (wake != null && wake.wakeMs > System.currentTimeMillis()) {
                WakeAlarmScheduler.scheduleAlarms(
                    dp, wake.wakeMs, wake.count, wake.intervalMinutes,
                    wake.gentleEnabled, wake.mediumEnabled, wake.wakeEnabled
                )
            }
            AppLogger.i(TAG, "onReceive: re-armed wake alarm ${wake?.wakeMs}")
        }.onFailure { AppLogger.e(TAG, "onReceive: wake re-arm failed", it) }
        runCatching {
            val alarms = BootAlarmMirror.userAlarms(dp)
            UserAlarmScheduler.scheduleAll(dp, alarms)
            AppLogger.i(TAG, "onReceive: re-armed ${alarms.count { it.enabled }} user alarm(s)")
        }.onFailure { AppLogger.e(TAG, "onReceive: user alarm re-arm failed", it) }
    }

    private companion object {
        const val TAG = "LockedBootReceiver"
    }
}
