package com.waypoint.app.planner

import android.content.Context
import com.waypoint.app.notification.SleepAlarmScheduler
import com.waypoint.app.notification.SleepNotificationHelper
import com.waypoint.app.notification.WakeAlarmScheduler
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
    val enabled: Boolean = true,
    val targetSleepMinutes: Int = 480
)

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

class SleepScheduleStore(private val context: Context) {
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
    fun setTargetSleepMinutes(minutes: Int) = save(load().copy(targetSleepMinutes = minutes.coerceIn(240, 720)))
    fun resetToDefaults() = save(SleepSchedule())

    /** Effective wake/bed for today — used by SleepScheduleCard to display adjusted times. */
    fun computeEffectiveTimes(ws: WorkScheduleSignals, registry: EventPlannerRegistry): EffectiveSleepTimes {
        val today = LocalDate.now()
        val plan = registry.planForDate(today, ws)
        return computeEffectiveTimesForDate(today, plan, ws, load())
    }

    /**
     * Registers fixed sleep events for a rolling window (yesterday through +7 days).
     * Each event spans bed-time on date D to wake-time on date D+1, and is split
     * naturally at midnight by the planner then stitched in the timeline view.
     */
    fun syncToRegistry(registry: EventPlannerRegistry, ws: WorkScheduleSignals) {
        registry.clearSleepEvents()
        val s = load()
        if (!s.enabled) {
            SleepNotificationHelper.clearAlarmStatus(context)
            return
        }

        val today = LocalDate.now()
        for (dayOffset in -1..7) {
            val date = today.plusDays(dayOffset.toLong())
            val plan = registry.planForDate(date, ws)
            val effective = computeEffectiveTimesForDate(date, plan, ws, s)
            registerSleepEventForDate(registry, date, effective.wakeTime, effective.bedTime)

            // Schedule alarms and cache scheduled times for today only
            if (dayOffset == 0) {
                val (bedMs, wakeMs) = sleepMillis(date, effective.bedTime, effective.wakeTime)
                SleepAlarmScheduler.scheduleAlarms(context, bedMs, wakeMs)
                WakeAlarmScheduler.scheduleAlarms(context, wakeMs)
                SleepLogStore(context).updateScheduledTimes(bedMs, wakeMs)
                SleepNotificationHelper.showAlarmStatus(context, bedMs, wakeMs)
            }
        }
    }

    private fun computeEffectiveTimesForDate(
        date: LocalDate,
        plan: DayPlan,
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

        if (!schedule.isWork || schedule.shiftStart == null || schedule.shiftEnd == null) {
            // Day off: anchor wake at preferred time, derive bed from target duration
            val wakeMin = s.preferredWakeTime.totalMinutes
            val bedMin = ((wakeMin - s.targetSleepMinutes) % 1440 + 1440) % 1440
            return EffectiveSleepTimes(s.preferredWakeTime, ShiftTime(bedMin / 60, bedMin % 60), isConstrained = false)
        }

        // Step 1: Compute hard constraints from shift buffers
        val latestWakeMin = schedule.shiftStart.totalMinutes - s.minMorningBufferMinutes

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

        val earliestBedMin = (shiftEndMinAbs + s.minEveningBufferMinutes) % (24 * 60)

        // Step 2: Anchor wake on preferred time, clamped by morning constraint
        var effectiveWakeMin = s.preferredWakeTime.totalMinutes
        if (latestWakeMin > 0 && effectiveWakeMin > latestWakeMin) effectiveWakeMin = latestWakeMin
        var effectiveWake = ShiftTime(effectiveWakeMin / 60, effectiveWakeMin % 60)

        // Step 3: Derive bed from target duration (wake − target)
        var effectiveBedMin = ((effectiveWakeMin - s.targetSleepMinutes) % 1440 + 1440) % 1440
        var effectiveBed = ShiftTime(effectiveBedMin / 60, effectiveBedMin % 60)

        // Step 4: If derived bed falls before the evening buffer, push the window forward.
        // For midnight-crossing shifts the valid bed zone is [earliestBedMin, latestWakeMin]
        // (both small, post-midnight values). For normal shifts bed should be ≥ earliestBedMin.
        val bedTooEarly = if (schedule.crossesMidnight)
            effectiveBedMin !in earliestBedMin..latestWakeMin
        else
            effectiveBedMin < earliestBedMin

        if (bedTooEarly) {
            effectiveBedMin = earliestBedMin
            effectiveBed = ShiftTime(effectiveBedMin / 60, effectiveBedMin % 60)
            val newWakeMin = (effectiveBedMin + s.targetSleepMinutes) % 1440
            // Clamp if the full target doesn't fit before the morning constraint
            effectiveWakeMin = if (latestWakeMin > 0 && newWakeMin > latestWakeMin) latestWakeMin else newWakeMin
            effectiveWake = ShiftTime(effectiveWakeMin / 60, effectiveWakeMin % 60)
        }

        // Step 5: 15-min transition buffer around non-sleep events that crowd bedtime
        val zone = ZoneId.systemDefault()
        val isPostMidnight = effectiveBed.totalMinutes < effectiveWake.totalMinutes
        var bedEpochMs = if (isPostMidnight)
            date.plusDays(1).atTime(effectiveBed.hour, effectiveBed.minute)
                .atZone(zone).toInstant().toEpochMilli()
        else
            date.atTime(effectiveBed.hour, effectiveBed.minute)
                .atZone(zone).toInstant().toEpochMilli()

        for (se in plan.scheduled) {
            if (se.event.category == EventCategory.SLEEP) continue
            if (se.endMillis + 15 * 60_000L > bedEpochMs) {
                bedEpochMs = maxOf(bedEpochMs, se.endMillis + 15 * 60_000L)
            }
        }

        effectiveBed = Calendar.getInstance().apply { timeInMillis = bedEpochMs }
            .let { ShiftTime(it.get(Calendar.HOUR_OF_DAY), it.get(Calendar.MINUTE)) }

        return EffectiveSleepTimes(effectiveWake, effectiveBed, isConstrained = true)
    }

    private fun registerSleepEventForDate(
        registry: EventPlannerRegistry,
        date: LocalDate,
        wake: ShiftTime,
        bed: ShiftTime
    ) {
        val (bedMs, wakeMs) = sleepMillis(date, bed, wake)
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

    /**
     * Converts bed/wake ShiftTimes for a given date into epoch milliseconds.
     * When bed < wake in clock time (post-midnight shift), both endpoints land on date+1.
     * Otherwise bed is on date and wake is on date+1 (standard overnight span).
     */
    private fun sleepMillis(date: LocalDate, bed: ShiftTime, wake: ShiftTime): Pair<Long, Long> {
        val zone = ZoneId.systemDefault()
        return if (bed.totalMinutes < wake.totalMinutes) {
            date.plusDays(1).atTime(bed.hour, bed.minute).atZone(zone).toInstant().toEpochMilli() to
            date.plusDays(1).atTime(wake.hour, wake.minute).atZone(zone).toInstant().toEpochMilli()
        } else {
            date.atTime(bed.hour, bed.minute).atZone(zone).toInstant().toEpochMilli() to
            date.plusDays(1).atTime(wake.hour, wake.minute).atZone(zone).toInstant().toEpochMilli()
        }
    }
}
