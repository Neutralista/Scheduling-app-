package com.waypoint.app.planner

import android.content.Context
import com.waypoint.app.signal.ShiftTime
import com.waypoint.app.signal.WorkScheduleSignals
import java.time.LocalDate
import java.time.ZoneId
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
    fun resetToDefaults() = save(SleepSchedule())

    /** Effective wake/bed for today — used by SleepScheduleCard to display adjusted times. */
    fun computeEffectiveTimes(ws: WorkScheduleSignals): EffectiveSleepTimes =
        computeEffectiveTimesForDate(LocalDate.now(), ws, load())

    /**
     * Registers fixed sleep events for a rolling window (yesterday through +7 days).
     * Each event spans bed-time on date D to wake-time on date D+1, and is split
     * naturally at midnight by the planner then stitched in the timeline view.
     */
    fun syncToRegistry(registry: EventPlannerRegistry, ws: WorkScheduleSignals) {
        registry.clearSleepEvents()
        val s = load()
        if (!s.enabled) return

        val today = LocalDate.now()
        for (dayOffset in -1..7) {
            val date = today.plusDays(dayOffset.toLong())
            val effective = computeEffectiveTimesForDate(date, ws, s)
            registerSleepEventForDate(registry, date, effective.wakeTime, effective.bedTime)
        }
    }

    private fun computeEffectiveTimesForDate(
        date: LocalDate,
        ws: WorkScheduleSignals,
        s: SleepSchedule
    ): EffectiveSleepTimes {
        val cal = Calendar.getInstance().apply {
            set(Calendar.YEAR, date.year)
            set(Calendar.MONTH, date.monthValue - 1)
            set(Calendar.DAY_OF_MONTH, date.dayOfMonth)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val schedule = ws.getSchedule(cal)

        if (schedule.isWork && schedule.shiftStart != null && schedule.shiftEnd != null) {
            val latestWakeMin = schedule.shiftStart.totalMinutes - s.minMorningBufferMinutes
            val effectiveWake = if (latestWakeMin > 0)
                ShiftTime(latestWakeMin / 60, latestWakeMin % 60)
            else
                s.preferredWakeTime

            val shiftEndMinAbs: Int = if (date == LocalDate.now()) {
                val session = ws.getTodaySession()
                if (session.actualEndMillis != null) {
                    val endCal = Calendar.getInstance().apply { timeInMillis = session.actualEndMillis }
                    val actualEndMin = endCal.get(Calendar.HOUR_OF_DAY) * 60 + endCal.get(Calendar.MINUTE)
                    if (actualEndMin < schedule.shiftStart.totalMinutes) actualEndMin + 24 * 60 else actualEndMin
                } else if (schedule.crossesMidnight) {
                    schedule.shiftEnd.totalMinutes + 24 * 60
                } else {
                    schedule.shiftEnd.totalMinutes
                }
            } else if (schedule.crossesMidnight) {
                schedule.shiftEnd.totalMinutes + 24 * 60
            } else {
                schedule.shiftEnd.totalMinutes
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

    private fun registerSleepEventForDate(
        registry: EventPlannerRegistry,
        date: LocalDate,
        wake: ShiftTime,
        bed: ShiftTime
    ) {
        val zone = ZoneId.systemDefault()
        val bedMs  = date.atTime(bed.hour, bed.minute).atZone(zone).toInstant().toEpochMilli()
        val wakeMs = date.plusDays(1).atTime(wake.hour, wake.minute).atZone(zone).toInstant().toEpochMilli()
        if (wakeMs <= bedMs) return

        registry.register(PlannerEvent(
            id = "sleep_$date",
            title = "Sleep",
            durationMinutes = ((wakeMs - bedMs) / 60_000L).toInt(),
            priority = PlannerPriority.SLEEP,
            category = EventCategory.SLEEP,
            fixedStartMillis = bedMs,
            fixedEndMillis = wakeMs
        ))
    }
}
