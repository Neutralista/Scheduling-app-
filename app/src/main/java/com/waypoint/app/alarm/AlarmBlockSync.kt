package com.waypoint.app.alarm

import android.content.Context
import com.waypoint.app.planner.NamedBlockStore
import java.time.LocalDate

object AlarmBlockSync {

    /**
     * Reads today's scheduled blocks and enables/disables any alarm whose
     * blockSyncEnabled == true to match whether its linked block is scheduled today.
     *
     * Safe to call from a BroadcastReceiver (uses synchronous store operations).
     * Call this before UserAlarmScheduler.scheduleAll so the scheduler picks up
     * the updated enabled states.
     */
    fun sync(context: Context) {
        val alarmStore = AlarmStore(context)
        val blockStore = NamedBlockStore(context)
        val scheduledBlockIds = blockStore.resolveForDate(LocalDate.now())
            .map { it.first.id }
            .toSet()

        alarmStore.loadAll().forEach { alarm ->
            if (!alarm.blockSyncEnabled || alarm.linkedBlockId == null) return@forEach
            val shouldBeEnabled = alarm.linkedBlockId in scheduledBlockIds
            if (alarm.enabled != shouldBeEnabled) {
                alarmStore.setEnabledSync(alarm.id, shouldBeEnabled)
            }
        }
    }
}
