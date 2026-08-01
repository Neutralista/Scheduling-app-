package com.waypoint.app.planner

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import com.waypoint.app.AppLogger
import com.waypoint.app.notification.SleepAlarmScheduler
import com.waypoint.app.notification.SleepNotificationHelper
import com.waypoint.app.notification.WakeAlarmScheduler
import com.waypoint.app.signal.ShiftTime
import com.waypoint.app.signal.WorkScheduleSignals
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private val _scheduledTimes = MutableStateFlow<Pair<Long?, Long?>>(
        SleepLogStore(context).run { getScheduledBedMs() to getScheduledWakeMs() }
    )
    val scheduledTimesFlow: StateFlow<Pair<Long?, Long?>> = _scheduledTimes.asStateFlow()

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

    /**
     * Pushes tonight's sleep window forward so bed = now + 30 min, maintaining the target
     * sleep duration (capped by the shift's morning-buffer ceiling on work days).
     * Cancels any pending late nudges, reschedules alarms, and updates the planner registry
     * for today. Returns the new (bedMs, wakeMs).
     */
    fun rescheduleBedToNow(
        logStore: SleepLogStore,
        ws: WorkScheduleSignals,
        registry: EventPlannerRegistry
    ): Pair<Long, Long> {
        val s = load()
        val now = System.currentTimeMillis()
        val newBedMs = now + 30 * 60_000L

        val today = LocalDate.now()
        val cal = Calendar.getInstance().apply {
            set(Calendar.YEAR, today.year)
            set(Calendar.MONTH, today.monthValue - 1)
            set(Calendar.DAY_OF_MONTH, today.dayOfMonth)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val schedule = ws.getSchedule(cal)
        val zone = ZoneId.systemDefault()
        val targetWakeMs = newBedMs + s.targetSleepMinutes * 60_000L
        val wakeCeilMs = if (schedule.isWork && schedule.shiftStart != null) {
            val latestWakeMin = schedule.shiftStart.totalMinutes - s.minMorningBufferMinutes
            if (latestWakeMin > 0)
                today.plusDays(1).atTime(latestWakeMin / 60, latestWakeMin % 60)
                    .atZone(zone).toInstant().toEpochMilli()
            else Long.MAX_VALUE
        } else Long.MAX_VALUE

        var adjustedBedMs = newBedMs
        var adjustedWakeMs = minOf(targetWakeMs, wakeCeilMs)

        // Apply 15-min transition buffer around calendar and planner events (mirrors step 5 of computeEffectiveTimesForDate)
        val plan = registry.planForDate(today, ws)
        val calEvents = calendarEventsForDate(today) + calendarEventsForDate(today.plusDays(1))
        val minSleepMs = 60 * 60_000L

        // Bed push: event straddles bedtime
        val bedBeforePush = adjustedBedMs
        for (se in plan.scheduled) {
            if (se.event.category == EventCategory.SLEEP) continue
            if (se.startMillis <= adjustedBedMs && se.endMillis > adjustedBedMs) {
                adjustedBedMs = minOf(se.endMillis + 15 * 60_000L, adjustedWakeMs)
            }
        }
        for ((evStart, evEnd) in calEvents) {
            if (evStart <= adjustedBedMs && evEnd > adjustedBedMs) {
                adjustedBedMs = minOf(evEnd + 15 * 60_000L, adjustedWakeMs)
            }
        }
        // Forward slide: push wake to maintain target (capped at shift ceiling)
        if (adjustedBedMs > bedBeforePush) {
            val newTarget = adjustedBedMs + s.targetSleepMinutes * 60_000L
            if (newTarget > adjustedWakeMs) adjustedWakeMs = minOf(newTarget, wakeCeilMs)
        }
        // Wake pull: event starts inside the sleep window
        val wakeBeforePull = adjustedWakeMs
        for (se in plan.scheduled) {
            if (se.event.category == EventCategory.SLEEP) continue
            if (se.startMillis > adjustedBedMs && se.startMillis < adjustedWakeMs) {
                adjustedWakeMs = maxOf(se.startMillis - 15 * 60_000L, adjustedBedMs + minSleepMs)
            }
        }
        for ((evStart, _) in calEvents) {
            if (evStart > adjustedBedMs && evStart < adjustedWakeMs) {
                adjustedWakeMs = maxOf(evStart - 15 * 60_000L, adjustedBedMs + minSleepMs)
            }
        }
        // Backward slide: pull bed earlier to preserve target (floored at now+30 — user's chosen bedtime)
        if (adjustedWakeMs < wakeBeforePull) {
            val targetBed = adjustedWakeMs - s.targetSleepMinutes * 60_000L
            if (targetBed < adjustedBedMs) adjustedBedMs = maxOf(targetBed, newBedMs)
        }

        SleepAlarmScheduler.cancelLateNudge(context)
        SleepAlarmScheduler.scheduleAlarms(context, adjustedBedMs, adjustedWakeMs)
        WakeAlarmScheduler.scheduleAlarms(context, adjustedWakeMs)
        logStore.updateScheduledTimes(adjustedBedMs, adjustedWakeMs)
        logStore.setRescheduledToday()
        SleepNotificationHelper.showAlarmStatus(context, adjustedBedMs, adjustedWakeMs)
        _scheduledTimes.value = adjustedBedMs to adjustedWakeMs

        // A bed time before noon means we're still in last night's midnight-crossing window
        // (user was awake past midnight). Use yesterday's event ID so the correct night's
        // block moves — not tonight's future window.
        val noonMs = today.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val sleepNightDate = if (adjustedBedMs < noonMs) today.minusDays(1) else today
        registry.register(PlannerEvent(
            id = "sleep_$sleepNightDate",
            title = "Sleep",
            durationMinutes = ((adjustedWakeMs - adjustedBedMs) / 60_000L).toInt(),
            priority = PlannerPriority.SLEEP,
            category = EventCategory.SLEEP,
            fixedStartMillis = adjustedBedMs,
            fixedEndMillis = adjustedWakeMs
        ))

        return adjustedBedMs to adjustedWakeMs
    }

    /** Effective wake/bed for today — used by SleepScheduleCard to display adjusted times. */
    fun computeEffectiveTimes(ws: WorkScheduleSignals, registry: EventPlannerRegistry): EffectiveSleepTimes {
        val today = LocalDate.now()
        val plan = registry.planForDate(today, ws)
        val calEvents = calendarEventsForDate(today) + calendarEventsForDate(today.plusDays(1))
        return computeEffectiveTimesForDate(today, plan, ws, load(), calEvents)
    }

    /**
     * Registers fixed sleep events for a rolling window (yesterday through +7 days).
     * Each event spans bed-time on date D to wake-time on date D+1, and is split
     * naturally at midnight by the planner then stitched in the timeline view.
     */
    fun syncToRegistry(
        registry: EventPlannerRegistry,
        ws: WorkScheduleSignals
    ) {
        registry.clearSleepEvents()
        val s = load()
        if (!s.enabled) {
            AppLogger.i("SleepSync", "syncToRegistry: sleep disabled")
            SleepNotificationHelper.clearAlarmStatus(context)
            return
        }

        val today = LocalDate.now()
        val logStore = SleepLogStore(context)
        var prevNightDate: LocalDate? = null
        var prevNightEffective: EffectiveSleepTimes? = null

        for (dayOffset in -1..7) {
            val date = today.plusDays(dayOffset.toLong())

            // For past nights: if sleep was already logged, register actual times and skip planning
            if (dayOffset < 0) {
                val logged = logStore.loadForDate(date.plusDays(1).toString())
                if (logged != null && logged.wakeMillis > logged.bedMillis) {
                    registry.register(PlannerEvent(
                        id = "sleep_$date",
                        title = "Sleep",
                        durationMinutes = ((logged.wakeMillis - logged.bedMillis) / 60_000L).toInt(),
                        priority = PlannerPriority.SLEEP,
                        category = EventCategory.SLEEP,
                        fixedStartMillis = logged.bedMillis,
                        fixedEndMillis = logged.wakeMillis
                    ))
                    AppLogger.i("SleepSync", "syncToRegistry: logged sleep for $date, skipping plan")
                    continue
                }
            }

            val plan = registry.planForDate(date, ws)
            val calEvents = calendarEventsForDate(date) + calendarEventsForDate(date.plusDays(1))
            val effective = computeEffectiveTimesForDate(date, plan, ws, s, calEvents)

            // If the user manually delayed today's sleep window, preserve it on refresh
            if (dayOffset == 0) {
                val now = System.currentTimeMillis()
                if (logStore.isRescheduledToday() && logStore.getSleepModeState() == SleepModeState.IDLE) {
                    val storedBedMs = logStore.getScheduledBedMs()
                    val storedWakeMs = logStore.getScheduledWakeMs()
                    if (storedBedMs != null && storedWakeMs != null && storedWakeMs > now && storedWakeMs > storedBedMs) {
                        val zone = ZoneId.systemDefault()
                        val noonMs = today.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
                        // A bed before noon is a midnight-crossing window pushed into this morning:
                        // it belongs to yesterday's night event, not tonight's.
                        val eventDate = if (storedBedMs < noonMs) today.minusDays(1) else date
                        registry.register(PlannerEvent(
                            id = "sleep_$eventDate",
                            title = "Sleep",
                            durationMinutes = ((storedWakeMs - storedBedMs) / 60_000L).toInt(),
                            priority = PlannerPriority.SLEEP,
                            category = EventCategory.SLEEP,
                            fixedStartMillis = storedBedMs,
                            fixedEndMillis = storedWakeMs
                        ))
                        if (eventDate == today.minusDays(1)) {
                            // Last-night window: also register tonight's sleep normally
                            registerSleepEventForDate(registry, date, effective.wakeTime, effective.bedTime)
                        }
                        _scheduledTimes.value = storedBedMs to storedWakeMs
                        SleepAlarmScheduler.scheduleAlarms(context, storedBedMs, storedWakeMs)
                        WakeAlarmScheduler.scheduleAlarms(context, storedWakeMs)
                        if (logStore.getSleepModeState() == SleepModeState.IDLE) {
                            logStore.updateScheduledTimes(storedBedMs, storedWakeMs)
                        }
                        SleepNotificationHelper.showAlarmStatus(context, storedBedMs, storedWakeMs)
                        AppLogger.i("SleepSync", "syncToRegistry: preserving manual reschedule bedMs=$storedBedMs wakeMs=$storedWakeMs (eventDate=$eventDate)")
                        continue
                    }
                }
            }

            registerSleepEventForDate(registry, date, effective.wakeTime, effective.bedTime)

            if (dayOffset == -1) {
                prevNightDate = date
                prevNightEffective = effective
            }

            // Schedule alarms and cache scheduled times for today only
            if (dayOffset == 0) {
                val now = System.currentTimeMillis()

                // After midnight LocalDate.now() advances to the new day, but the previous
                // night's sleep window may still be active (we haven't woken up yet).
                // If yesterday's wakeMs is still in the future, schedule for that window
                // so the background worker doesn't overwrite this-morning's alarms with
                // tomorrow night's via FLAG_UPDATE_CURRENT.
                val (bedMs, wakeMs) = run {
                    val pd = prevNightDate
                    val pe = prevNightEffective
                    if (pd != null && pe != null) {
                        val (prevBedMs, prevWakeMs) = sleepMillis(pd, pe.bedTime, pe.wakeTime)
                        if (prevWakeMs > now) return@run prevBedMs to prevWakeMs
                    }
                    sleepMillis(date, effective.bedTime, effective.wakeTime)
                }

                SleepAlarmScheduler.scheduleAlarms(context, bedMs, wakeMs)
                // Schedule wake alarms without first cancelling already-armed ones so a
                // CalendarSyncWorker run that lands within seconds of a pending alarm can't
                // cancel it and fail to reschedule it (target slips into the past).
                AppLogger.i("SleepSync", "syncToRegistry: computed wakeMs=$wakeMs bedMs=$bedMs")
                _scheduledTimes.value = bedMs to wakeMs
                WakeAlarmScheduler.scheduleAlarmsIfEarlier(context, wakeMs)
                // Don't overwrite cached sleep window while sleep mode is active:
                // after midnight LocalDate.now() advances to the next day, so syncToRegistry
                // would cache tomorrow night's times, breaking maybeNudge for the remainder
                // of the current night.
                if (logStore.getSleepModeState() == SleepModeState.IDLE) {
                    logStore.updateScheduledTimes(bedMs, wakeMs)
                }
                SleepNotificationHelper.showAlarmStatus(context, bedMs, wakeMs)
            }
        }
    }

    private fun computeEffectiveTimesForDate(
        date: LocalDate,
        plan: DayPlan,
        ws: WorkScheduleSignals,
        s: SleepSchedule,
        calEvents: List<Pair<Long, Long>> = emptyList()
    ): EffectiveSleepTimes {
        val cal = Calendar.getInstance().apply {
            set(Calendar.YEAR, date.year)
            set(Calendar.MONTH, date.monthValue - 1)
            set(Calendar.DAY_OF_MONTH, date.dayOfMonth)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val schedule = ws.getSchedule(cal)

        var effectiveWakeMin: Int
        var effectiveBedMin: Int
        var effectiveWake: ShiftTime
        var effectiveBed: ShiftTime
        var isConstrained: Boolean
        // Floor for backward bed-slide on work days: epoch ms of shiftEnd + eveningBuffer.
        // Long.MIN_VALUE = no floor (day off — slide as far as needed to hit target duration).
        var shiftFloorBedEpochMs = Long.MIN_VALUE
        // Ceiling for forward wake-slide on work days: epoch ms of shiftStart − morningBuffer.
        // Long.MAX_VALUE = no ceiling (day off).
        var shiftCeilingWakeEpochMs = Long.MAX_VALUE

        if (!schedule.isWork || schedule.shiftStart == null || schedule.shiftEnd == null) {
            // Day off: anchor wake at preferred time, derive bed from target duration
            effectiveWakeMin = s.preferredWakeTime.totalMinutes
            effectiveBedMin = ((effectiveWakeMin - s.targetSleepMinutes) % 1440 + 1440) % 1440
            effectiveWake = s.preferredWakeTime
            effectiveBed = ShiftTime(effectiveBedMin / 60, effectiveBedMin % 60)
            isConstrained = false
            // Fall through to step 5 so calendar events are still respected
        } else {
            isConstrained = true

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
            val isFloorNextDay = shiftEndMinAbs + s.minEveningBufferMinutes >= 24 * 60
            val constraintZone = ZoneId.systemDefault()
            shiftFloorBedEpochMs = if (isFloorNextDay)
                date.plusDays(1).atTime(earliestBedMin / 60, earliestBedMin % 60)
                    .atZone(constraintZone).toInstant().toEpochMilli()
            else
                date.atTime(earliestBedMin / 60, earliestBedMin % 60)
                    .atZone(constraintZone).toInstant().toEpochMilli()

            if (latestWakeMin > 0) {
                shiftCeilingWakeEpochMs = date.plusDays(1)
                    .atTime(latestWakeMin / 60, latestWakeMin % 60)
                    .atZone(constraintZone).toInstant().toEpochMilli()
            }

            // Step 2: Anchor wake on preferred time, clamped by morning constraint
            effectiveWakeMin = s.preferredWakeTime.totalMinutes
            if (latestWakeMin > 0 && effectiveWakeMin > latestWakeMin) effectiveWakeMin = latestWakeMin
            effectiveWake = ShiftTime(effectiveWakeMin / 60, effectiveWakeMin % 60)

            // Step 3: Derive bed from target duration (wake − target)
            effectiveBedMin = ((effectiveWakeMin - s.targetSleepMinutes) % 1440 + 1440) % 1440
            effectiveBed = ShiftTime(effectiveBedMin / 60, effectiveBedMin % 60)

            // Step 4: If derived bed falls before the evening buffer, push the window forward.
            // derivedBedIsPostMidnight: bed wraps past midnight (e.g. 5h target → 02:00 AM bed).
            // In that case the real-time ordering is floor(PM) < bed(next-day AM), so "too early"
            // comparisons must account for the day boundary, not just clock minutes.
            val derivedBedIsPostMidnight = effectiveBedMin < effectiveWakeMin
            val bedTooEarly = if (schedule.crossesMidnight) {
                effectiveBedMin !in earliestBedMin..latestWakeMin
            } else if (derivedBedIsPostMidnight && !isFloorNextDay) {
                // next-morning bed is always after a same-evening floor in real time
                false
            } else if (!derivedBedIsPostMidnight && isFloorNextDay) {
                // same-evening bed is always before a next-morning floor in real time
                true
            } else {
                effectiveBedMin < earliestBedMin
            }

            if (bedTooEarly) {
                effectiveBedMin = earliestBedMin
                effectiveBed = ShiftTime(effectiveBedMin / 60, effectiveBedMin % 60)
                val newWakeMin = (effectiveBedMin + s.targetSleepMinutes) % 1440
                effectiveWakeMin = if (latestWakeMin > 0 && newWakeMin > latestWakeMin) latestWakeMin else newWakeMin
                effectiveWake = ShiftTime(effectiveWakeMin / 60, effectiveWakeMin % 60)
            }
        }

        // Cap sleep at preferred duration — shift buffers can over-expand the window on work days
        if (isConstrained) {
            val rawSleepMin = if (effectiveBed.totalMinutes > effectiveWake.totalMinutes)
                effectiveWake.totalMinutes + (1440 - effectiveBed.totalMinutes)
            else
                effectiveWake.totalMinutes - effectiveBed.totalMinutes
            if (rawSleepMin > s.targetSleepMinutes) {
                val excess = rawSleepMin - s.targetSleepMinutes
                effectiveBedMin = (effectiveBed.totalMinutes + excess) % 1440
                effectiveBed = ShiftTime(effectiveBedMin / 60, effectiveBedMin % 60)
            }
        }

        // Step 5: 15-min transition buffer around non-sleep events that crowd bedtime
        // Applies to both work days and days off — calendar events always shift the window
        val zone = ZoneId.systemDefault()
        val isPostMidnight = effectiveBed.totalMinutes < effectiveWake.totalMinutes
        var bedEpochMs = if (isPostMidnight)
            date.plusDays(1).atTime(effectiveBed.hour, effectiveBed.minute)
                .atZone(zone).toInstant().toEpochMilli()
        else
            date.atTime(effectiveBed.hour, effectiveBed.minute)
                .atZone(zone).toInstant().toEpochMilli()

        var wakeEpochMs = date.plusDays(1).atTime(effectiveWake.hour, effectiveWake.minute)
            .atZone(zone).toInstant().toEpochMilli()
        val minSleepMs = 60 * 60_000L // never compress sleep below 1h

        // Bed push: event straddles bedtime (starts before bed, ends after) → push bed later.
        // Events entirely inside the sleep window are handled by wake-pull below.
        val bedBeforePush = bedEpochMs
        for (se in plan.scheduled) {
            if (se.event.category == EventCategory.SLEEP) continue
            if (se.startMillis <= bedEpochMs && se.endMillis > bedEpochMs) {
                bedEpochMs = minOf(se.endMillis + 15 * 60_000L, wakeEpochMs)
            }
        }
        for ((evStart, evEnd) in calEvents) {
            if (evStart <= bedEpochMs && evEnd > bedEpochMs) {
                bedEpochMs = minOf(evEnd + 15 * 60_000L, wakeEpochMs)
            }
        }

        // Forward slide: if bed was pushed later, slide wake to maintain target duration.
        // On work days, wake can't go past the shift's morning-buffer ceiling.
        if (bedEpochMs > bedBeforePush) {
            val targetWakeMs = bedEpochMs + s.targetSleepMinutes * 60_000L
            if (targetWakeMs > wakeEpochMs) {
                wakeEpochMs = minOf(targetWakeMs, shiftCeilingWakeEpochMs)
            }
        }

        // Wake pull: event starts inside the sleep window → pull wake earlier
        val wakeBeforePull = wakeEpochMs
        for (se in plan.scheduled) {
            if (se.event.category == EventCategory.SLEEP) continue
            if (se.startMillis > bedEpochMs && se.startMillis < wakeEpochMs) {
                wakeEpochMs = maxOf(se.startMillis - 15 * 60_000L, bedEpochMs + minSleepMs)
            }
        }
        for ((evStart, _) in calEvents) {
            if (evStart > bedEpochMs && evStart < wakeEpochMs) {
                wakeEpochMs = maxOf(evStart - 15 * 60_000L, bedEpochMs + minSleepMs)
            }
        }

        // Slide bed back to preserve target duration when wake was pulled in.
        // On work days, bed can't go before the shift's evening-buffer floor.
        if (wakeEpochMs < wakeBeforePull) {
            val targetBedMs = wakeEpochMs - s.targetSleepMinutes * 60_000L
            if (targetBedMs < bedEpochMs) {
                bedEpochMs = maxOf(targetBedMs, shiftFloorBedEpochMs)
            }
        }

        effectiveBed = Calendar.getInstance().apply { timeInMillis = bedEpochMs }
            .let { ShiftTime(it.get(Calendar.HOUR_OF_DAY), it.get(Calendar.MINUTE)) }
        effectiveWake = Calendar.getInstance().apply { timeInMillis = wakeEpochMs }
            .let { ShiftTime(it.get(Calendar.HOUR_OF_DAY), it.get(Calendar.MINUTE)) }

        return EffectiveSleepTimes(effectiveWake, effectiveBed, isConstrained = isConstrained)
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

    /** Synchronous ContentResolver query for non-all-day calendar events on [date].
     *  Events titled "Sleep" are always excluded — they are Waypoint-created sleep windows
     *  and must not feed back as scheduling constraints (covers orphans too). */
    private fun calendarEventsForDate(date: LocalDate): List<Pair<Long, Long>> {
        if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) !=
            PackageManager.PERMISSION_GRANTED) return emptyList()
        val zone = ZoneId.systemDefault()
        val startMs = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMs = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(startMs.toString()).appendPath(endMs.toString()).build()
        val projection = arrayOf(
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.TITLE
        )
        val events = mutableListOf<Pair<Long, Long>>()
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            val beginIdx  = cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
            val endIdx    = cursor.getColumnIndexOrThrow(CalendarContract.Instances.END)
            val allDayIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.ALL_DAY)
            val titleIdx  = cursor.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
            while (cursor.moveToNext()) {
                if (cursor.getInt(allDayIdx) != 0) continue
                if (cursor.getString(titleIdx) == "Sleep") continue
                events += cursor.getLong(beginIdx) to cursor.getLong(endIdx)
            }
        }
        return events
    }
}
