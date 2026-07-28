package com.waypoint.app.planner

import android.content.Context
import com.waypoint.app.signal.ShiftTime
import com.waypoint.app.signal.WorkScheduleSignals
import java.util.Calendar
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class SleepSchedule(
    val preferredWakeTime: ShiftTime = ShiftTime(7, 0),
    val preferredBedTime: ShiftTime = ShiftTime(23, 0),
    val minMorningBufferMinutes: Int = 120,
    val minEveningBufferMinutes: Int = 120,
    val enabled: Boolean = true
) {
    val totalSleepMinutes: Int
        get() = preferredWakeTime.totalMinutes + (24 * 60 - preferredBedTime.totalMinutes)
}

data class EffectiveSleepTimes(
    val wakeTime: ShiftTime,
    val bedTime: ShiftTime,
    val isConstrained: Boolean
) {
    val totalSleepMinutes: Int
        get() = if (bedTime.totalMinutes > wakeTime.totalMinutes)
            wakeTime.totalMinutes + (24 * 60 - bedTime.totalMinutes)
        else
            wakeTime.totalMinutes - bedTime.totalMinutes
}

class SleepScheduleStore(context: Context) {
    private val prefs = context.getSharedPreferences("waypoint_sleep", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): SleepSchedule {
        val raw = prefs.getString("config", null) ?: return SleepSchedule()
        return try { json.decodeFromString(raw) } catch (_: Exception) { SleepSchedule() }
    }

    private fun save(schedule: SleepSchedule) {
        prefs.edit().putString("config", json.encodeToString(schedule)).apply()
    }

    fun setPreferredWakeTime(time: ShiftTime) = save(load().copy(preferredWakeTime = time))
    fun setPreferredBedTime(time: ShiftTime) = save(load().copy(preferredBedTime = time))
    fun setEnabled(enabled: Boolean) = save(load().copy(enabled = enabled))

    /**
     * Computes effective wake/bed times from shift constraints:
     * - Work day: wake ≤ shiftStart − minMorningBufferMinutes
     *             bed  ≥ shiftEnd   + minEveningBufferMinutes
     * - Day off: use stored preferred times.
     */
    fun computeEffectiveTimes(ws: WorkScheduleSignals): EffectiveSleepTimes {
        val s = load()
        val today = ws.getTodaySchedule()

        if (today.isWork && today.shiftStart != null && today.shiftEnd != null) {
            val latestWakeMin = today.shiftStart.totalMinutes - s.minMorningBufferMinutes
            val effectiveWake = if (latestWakeMin > 0) ShiftTime(latestWakeMin / 60, latestWakeMin % 60)
                                else s.preferredWakeTime

            val session = ws.getTodaySession()
            val shiftEndMinAbs: Int = if (session.actualEndMillis != null) {
                val cal = Calendar.getInstance().apply { timeInMillis = session.actualEndMillis }
                val actualEndMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
                if (actualEndMin < today.shiftStart.totalMinutes) actualEndMin + 24 * 60 else actualEndMin
            } else if (today.crossesMidnight) {
                today.shiftEnd.totalMinutes + 24 * 60
            } else {
                today.shiftEnd.totalMinutes
            }
            val earliestBedMinAbs = shiftEndMinAbs + s.minEveningBufferMinutes
            val effectiveBed = if (earliestBedMinAbs < 24 * 60) {
                ShiftTime(earliestBedMinAbs / 60, earliestBedMinAbs % 60)
            } else {
                val bedMin = earliestBedMinAbs % (24 * 60)
                ShiftTime(bedMin / 60, bedMin % 60)
            }

            return EffectiveSleepTimes(effectiveWake, effectiveBed, isConstrained = true)
        }

        return EffectiveSleepTimes(s.preferredWakeTime, s.preferredBedTime, isConstrained = false)
    }

    /**
     * Registers sleep events into the EventPlannerRegistry as priority-10 time-window events,
     * replacing any previously registered ones.
     */
    fun syncToRegistry(registry: EventPlannerRegistry, ws: WorkScheduleSignals) {
        registry.clearSleepEvents()
        val s = load()
        if (!s.enabled) return

        val effective = computeEffectiveTimes(ws)
        registerSleepEvents(registry, effective.wakeTime, effective.bedTime)
    }

    private fun registerSleepEvents(registry: EventPlannerRegistry, wake: ShiftTime, bed: ShiftTime) {
        if (bed.totalMinutes < wake.totalMinutes) {
            // Bed falls after midnight (midnight-crossing shift) — single morning block
            val sleepMins = wake.totalMinutes - bed.totalMinutes
            if (sleepMins > 0) {
                registry.register(PlannerEvent(
                    id = "sleep_morning",
                    title = "Sleep",
                    durationMinutes = sleepMins,
                    priority = PlannerPriority.SLEEP,
                    conditions = listOf(EventCondition.TimeWindow(bed.hour, bed.minute, wake.hour, wake.minute)),
                    category = EventCategory.SLEEP
                ))
            }
        } else {
            val morningMins = wake.totalMinutes
            val eveningMins = 24 * 60 - bed.totalMinutes
            if (morningMins > 0) {
                registry.register(PlannerEvent(
                    id = "sleep_morning",
                    title = "Sleep",
                    durationMinutes = morningMins,
                    priority = PlannerPriority.SLEEP,
                    conditions = listOf(EventCondition.TimeWindow(0, 0, wake.hour, wake.minute)),
                    category = EventCategory.SLEEP
                ))
            }
            if (eveningMins > 0) {
                registry.register(PlannerEvent(
                    id = "sleep_evening",
                    title = "Sleep",
                    durationMinutes = eveningMins,
                    priority = PlannerPriority.SLEEP,
                    conditions = listOf(EventCondition.TimeWindow(bed.hour, bed.minute, 24, 0)),
                    category = EventCategory.SLEEP
                ))
            }
        }
    }
}
