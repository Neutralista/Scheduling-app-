package com.waypoint.app.planner

import android.content.Context
import com.waypoint.app.signal.ShiftTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class SleepSchedule(
    val wakeTime: ShiftTime = ShiftTime(7, 0),
    val bedTime: ShiftTime = ShiftTime(23, 0),
    val enabled: Boolean = true
) {
    /** Total sleep minutes across the midnight boundary (bed→midnight + midnight→wake). */
    val totalSleepMinutes: Int
        get() = wakeTime.totalMinutes + (24 * 60 - bedTime.totalMinutes)
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

    fun setWakeTime(time: ShiftTime) = save(load().copy(wakeTime = time))
    fun setBedTime(time: ShiftTime) = save(load().copy(bedTime = time))
    fun setEnabled(enabled: Boolean) = save(load().copy(enabled = enabled))

    /**
     * Registers the current sleep schedule into the EventPlannerRegistry as two
     * priority-10 time-window events:
     *   sleep_morning : 00:00 → wakeTime  (tail of the previous night's sleep)
     *   sleep_evening : bedTime → 24:00   (start of tonight's sleep)
     * Calling this again replaces both events with fresh values.
     */
    fun syncToRegistry(registry: EventPlannerRegistry) {
        registry.clearSleepEvents()
        val s = load()
        if (!s.enabled) return

        val morningMins = s.wakeTime.totalMinutes
        val eveningMins = 24 * 60 - s.bedTime.totalMinutes

        if (morningMins > 0) {
            registry.register(PlannerEvent(
                id = "sleep_morning",
                title = "Sleep",
                durationMinutes = morningMins,
                priority = PlannerPriority.SLEEP,
                conditions = listOf(EventCondition.TimeWindow(0, 0, s.wakeTime.hour, s.wakeTime.minute)),
                category = EventCategory.SLEEP
            ))
        }
        if (eveningMins > 0) {
            registry.register(PlannerEvent(
                id = "sleep_evening",
                title = "Sleep",
                durationMinutes = eveningMins,
                priority = PlannerPriority.SLEEP,
                conditions = listOf(EventCondition.TimeWindow(s.bedTime.hour, s.bedTime.minute, 24, 0)),
                category = EventCategory.SLEEP
            ))
        }
    }
}
