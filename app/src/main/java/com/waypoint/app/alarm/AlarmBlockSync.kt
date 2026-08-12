package com.waypoint.app.alarm

import android.content.Context
import com.waypoint.app.planner.NamedBlockStore
import com.waypoint.app.planner.SleepScheduleStore
import java.time.LocalDate

object AlarmBlockSync {

    /**
     * Reads today's scheduled blocks and enables/disables any alarm (user alarm or
     * sleep-derived alarm) whose block-sync is on to match whether its linked block
     * is scheduled today.
     *
     * Safe to call from a BroadcastReceiver (uses synchronous store operations).
     * Call this before UserAlarmScheduler.scheduleAll so the scheduler picks up
     * the updated enabled states.
     */
    fun sync(context: Context) {
        val blockStore = NamedBlockStore(context)
        val scheduledBlockIds = blockStore.resolveForDate(LocalDate.now())
            .map { it.first.id }
            .toSet()
        syncUserAlarms(context, scheduledBlockIds)
        syncSleepAlarms(context, scheduledBlockIds)
    }

    private fun syncUserAlarms(context: Context, scheduledBlockIds: Set<String>) {
        val alarmStore = AlarmStore(context)
        alarmStore.loadAll().forEach { alarm ->
            if (!alarm.blockSyncEnabled || alarm.linkedBlockId == null) return@forEach
            val shouldBeEnabled = alarm.linkedBlockId in scheduledBlockIds
            if (alarm.enabled != shouldBeEnabled) {
                alarmStore.setEnabledSync(alarm.id, shouldBeEnabled)
            }
        }
    }

    private fun syncSleepAlarms(context: Context, scheduledBlockIds: Set<String>) {
        val sleepStore = SleepScheduleStore(context)
        val s = sleepStore.load()
        val checks = listOf(
            Triple("pre_sleep", s.preSleepSync, s.preSleepAlarmEnabled),
            Triple("bedtime", s.bedtimeSync, s.bedtimeAlarmEnabled),
            Triple("gentle_wake", s.gentleWakeSync, s.gentleWakeEnabled),
            Triple("medium_wake", s.mediumWakeSync, s.mediumWakeEnabled),
            Triple("wake_up", s.wakeUpSync, s.wakeAlarmEnabled)
        )
        for ((key, sync, currentlyEnabled) in checks) {
            if (!sync.blockSyncEnabled || sync.linkedBlockId == null) continue
            val shouldBeEnabled = sync.linkedBlockId in scheduledBlockIds
            if (currentlyEnabled != shouldBeEnabled) {
                sleepStore.setSleepAlarmEnabled(key, shouldBeEnabled)
            }
        }
    }
}
