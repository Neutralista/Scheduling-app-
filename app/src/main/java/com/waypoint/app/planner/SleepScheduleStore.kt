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
     * sleep duration. Cancels any pending late nudges, reschedules alarms, and updates the
     * planner registry for today. Returns the new (bedMs, wakeMs).
     */
    fun rescheduleBedToNow(
        logStore: SleepLogStore,
        registry: EventPlannerRegistry
    ): Pair<Long, Long> {
        val s = load()
        val now = System.currentTimeMillis()
        val newBedMs = now + 30 * 60_000L

        val today = LocalDate.now()

        var adjustedBedMs = newBedMs

        val plan = registry.planForDate(today)
        val calEvents = calendarEventsForDate(today) + calendarEventsForDate(today.plusDays(1))

        // Only bed slides — if an event overlaps the intended bed time, push bed later.
        for (se in plan.scheduled) {
            if (se.event.category == EventCategory.SLEEP) continue
            if (se.startMillis <= adjustedBedMs && se.endMillis > adjustedBedMs) {
                adjustedBedMs = se.endMillis + 15 * 60_000L
            }
        }
        for ((evStart, evEnd) in calEvents) {
            if (evStart <= adjustedBedMs && evEnd > adjustedBedMs) {
                adjustedBedMs = evEnd + 15 * 60_000L
            }
        }

        // Wake is computed from the final bed position to preserve target sleep duration.
        val adjustedWakeMs = adjustedBedMs + s.targetSleepMinutes * 60_000L

        SleepAlarmScheduler.cancelLateNudge(context)
        SleepAlarmScheduler.scheduleAlarms(context, adjustedBedMs, adjustedWakeMs)
        WakeAlarmScheduler.scheduleAlarms(context, adjustedWakeMs)
        logStore.updateScheduledTimes(adjustedBedMs, adjustedWakeMs)
        logStore.setRescheduledToday()
        SleepNotificationHelper.showAlarmStatus(context, adjustedBedMs, adjustedWakeMs)
        _scheduledTimes.value = adjustedBedMs to adjustedWakeMs

        val zone = ZoneId.systemDefault()
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

    fun computeEffectiveTimes(registry: EventPlannerRegistry): EffectiveSleepTimes {
        val today = LocalDate.now()
        val plan = registry.planForDate(today)
        val calEvents = calendarEventsForDate(today) + calendarEventsForDate(today.plusDays(1))
        return computeEffectiveTimesForDate(today, plan, load(), calEvents)
    }

    fun syncToRegistry(registry: EventPlannerRegistry) {
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

        for (dayOffset in -7..7) {
            val date = today.plusDays(dayOffset.toLong())

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
                        fixedEndMillis = logged.wakeMillis,
                        isLogged = true
                    ))
                    AppLogger.i("SleepSync", "syncToRegistry: logged sleep for $date, skipping plan")
                    continue
                }
            }

            val plan = registry.planForDate(date)
            val calEvents = calendarEventsForDate(date) + calendarEventsForDate(date.plusDays(1))
            val effective = computeEffectiveTimesForDate(date, plan, s, calEvents)

            if (dayOffset == 0) {
                val now = System.currentTimeMillis()
                if (logStore.isRescheduledToday() && logStore.getSleepModeState() == SleepModeState.IDLE) {
                    val storedBedMs = logStore.getScheduledBedMs()
                    val storedWakeMs = logStore.getScheduledWakeMs()
                    if (storedBedMs != null && storedWakeMs != null && storedWakeMs > now && storedWakeMs > storedBedMs) {
                        val zone = ZoneId.systemDefault()
                        val noonMs = today.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
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

            if (dayOffset == 0) {
                val now = System.currentTimeMillis()
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
                AppLogger.i("SleepSync", "syncToRegistry: computed wakeMs=$wakeMs bedMs=$bedMs")
                _scheduledTimes.value = bedMs to wakeMs
                WakeAlarmScheduler.scheduleAlarmsIfEarlier(context, wakeMs)
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
        s: SleepSchedule,
        calEvents: List<Pair<Long, Long>> = emptyList()
    ): EffectiveSleepTimes {
        // Use the user's directly-set preferred times as anchors.
        // "isPostMidnight" = bed hour falls after midnight (e.g. 01:00) meaning it's
        // already into the next calendar day, so both bed and wake are on date+1.
        var effectiveWake = s.preferredWakeTime
        var effectiveBed  = s.preferredBedTime

        val zone = ZoneId.systemDefault()
        val isPostMidnight = effectiveBed.totalMinutes < effectiveWake.totalMinutes
        var bedEpochMs = if (isPostMidnight)
            date.plusDays(1).atTime(effectiveBed.hour, effectiveBed.minute)
                .atZone(zone).toInstant().toEpochMilli()
        else
            date.atTime(effectiveBed.hour, effectiveBed.minute)
                .atZone(zone).toInstant().toEpochMilli()

        val wakeEpochMs = date.plusDays(1).atTime(effectiveWake.hour, effectiveWake.minute)
            .atZone(zone).toInstant().toEpochMilli()

        // Wake is concrete — only bed slides. Push bed later if an event overlaps it.
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

        effectiveBed = Calendar.getInstance().apply { timeInMillis = bedEpochMs }
            .let { ShiftTime(it.get(Calendar.HOUR_OF_DAY), it.get(Calendar.MINUTE)) }

        return EffectiveSleepTimes(effectiveWake, effectiveBed, isConstrained = false)
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
