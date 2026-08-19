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
data class SleepAlarmSync(
    /** Block id to sync with. Null = no block sync. */
    val linkedBlockId: String? = null,
    /** Whether block-sync is active for this sleep alarm. */
    val blockSyncEnabled: Boolean = false
)

@Serializable
data class SleepSchedule(
    val preferredWakeTime: ShiftTime = ShiftTime(7, 0),
    val preferredBedTime: ShiftTime = ShiftTime(23, 0),
    val enabled: Boolean = true,
    /** Number of escalating wake alarms (1–3). */
    val wakeAlarmCount: Int = 3,
    /** Minutes between consecutive wake alarms. */
    val wakeAlarmIntervalMinutes: Int = 5,
    /** Minutes before bedtime to send a reminder; 0 = disabled. */
    val preSleepReminderMinutes: Int = 30,
    val preSleepAlarmEnabled: Boolean = true,
    val bedtimeAlarmEnabled: Boolean = true,
    val gentleWakeEnabled: Boolean = true,
    val mediumWakeEnabled: Boolean = true,
    val wakeAlarmEnabled: Boolean = true,
    val preSleepSync: SleepAlarmSync = SleepAlarmSync(),
    val bedtimeSync: SleepAlarmSync = SleepAlarmSync(),
    val gentleWakeSync: SleepAlarmSync = SleepAlarmSync(),
    val mediumWakeSync: SleepAlarmSync = SleepAlarmSync(),
    val wakeUpSync: SleepAlarmSync = SleepAlarmSync(),
    /** Silently start inactivity-based sleep detection at the scheduled bedtime even if Sleep
     *  Mode was never manually started — bounded to the bedtime→wake window, see
     *  SleepScheduleStore.isWithinPassiveSleepWindow(). */
    val passiveSleepDetectionEnabled: Boolean = true
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
    fun setWakeAlarmCount(count: Int) {
        save(load().copy(wakeAlarmCount = count.coerceIn(1, 3)))
        rescheduleFromStored()
    }
    fun setWakeAlarmIntervalMinutes(minutes: Int) {
        save(load().copy(wakeAlarmIntervalMinutes = minutes.coerceAtLeast(1)))
        rescheduleFromStored()
    }
    fun setPassiveSleepDetectionEnabled(enabled: Boolean) =
        save(load().copy(passiveSleepDetectionEnabled = enabled))

    /**
     * True while we're between the scheduled bedtime and (wake time + a buffer), and passive
     * detection is turned on — the window where CycleTracker/WakeCheckReceiver may silently
     * start inactivity polling even though Sleep Mode was never manually armed.
     */
    fun isWithinPassiveSleepWindow(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!load().passiveSleepDetectionEnabled) return false
        val logStore = SleepLogStore(context)
        val bedMs = logStore.getScheduledBedMs() ?: return false
        val wakeMs = logStore.getScheduledWakeMs() ?: return false
        return nowMs in bedMs..(wakeMs + PASSIVE_WINDOW_BUFFER_MS)
    }

    fun setPreSleepReminderMinutes(minutes: Int) {
        save(load().copy(preSleepReminderMinutes = minutes.coerceAtLeast(0)))
        rescheduleFromStored()
    }
    fun setSleepAlarmEnabled(key: String, enabled: Boolean) {
        val s = load()
        val updated = when (key) {
            "pre_sleep"   -> s.copy(preSleepAlarmEnabled = enabled)
            "bedtime"     -> s.copy(bedtimeAlarmEnabled = enabled)
            "gentle_wake" -> s.copy(gentleWakeEnabled = enabled)
            "medium_wake" -> s.copy(mediumWakeEnabled = enabled)
            "wake_up"     -> s.copy(wakeAlarmEnabled = enabled)
            else          -> return
        }
        save(updated)
        rescheduleFromStored()
    }
    fun setSleepAlarmSync(key: String, sync: SleepAlarmSync) {
        val s = load()
        val updated = when (key) {
            "pre_sleep"   -> s.copy(preSleepSync = sync)
            "bedtime"     -> s.copy(bedtimeSync = sync)
            "gentle_wake" -> s.copy(gentleWakeSync = sync)
            "medium_wake" -> s.copy(mediumWakeSync = sync)
            "wake_up"     -> s.copy(wakeUpSync = sync)
            else          -> return
        }
        save(updated)
    }
    fun resetToDefaults() = save(SleepSchedule())

    /**
     * Re-posts the sleep-alarm status card if the user swiped it away but the schedule still
     * calls for one showing. Meant for a periodic background tick, not an instant reaction to
     * the dismiss — see UserAlarmScheduler.healStatusNotification for the same pattern.
     */
    fun healAlarmStatus() {
        if (SleepNotificationHelper.isAlarmStatusShowing(context)) return
        val (bedMs, wakeMs) = _scheduledTimes.value
        if (bedMs == null || wakeMs == null) return
        val s = load()
        val anyActive = s.preSleepAlarmEnabled || s.bedtimeAlarmEnabled ||
            s.gentleWakeEnabled || s.mediumWakeEnabled || s.wakeAlarmEnabled
        if (anyActive) SleepNotificationHelper.showAlarmStatus(context, bedMs, wakeMs)
    }

    private fun rescheduleFromStored() {
        val (bedMs, wakeMs) = _scheduledTimes.value
        if (bedMs == null || wakeMs == null) return
        val s = load()
        SleepAlarmScheduler.scheduleAlarms(
            context, bedMs, wakeMs,
            s.preSleepReminderMinutes, s.preSleepAlarmEnabled, s.bedtimeAlarmEnabled
        )
        WakeAlarmScheduler.scheduleAlarms(
            context, wakeMs, s.wakeAlarmCount, s.wakeAlarmIntervalMinutes,
            s.gentleWakeEnabled, s.mediumWakeEnabled, s.wakeAlarmEnabled
        )
        val anyActive = s.preSleepAlarmEnabled || s.bedtimeAlarmEnabled ||
            s.gentleWakeEnabled || s.mediumWakeEnabled || s.wakeAlarmEnabled
        if (anyActive) SleepNotificationHelper.showAlarmStatus(context, bedMs, wakeMs)
        else SleepNotificationHelper.clearAlarmStatus(context)
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

                SleepAlarmScheduler.scheduleAlarms(
                    context, bedMs, wakeMs,
                    s.preSleepReminderMinutes, s.preSleepAlarmEnabled, s.bedtimeAlarmEnabled
                )
                AppLogger.i("SleepSync", "syncToRegistry: computed wakeMs=$wakeMs bedMs=$bedMs")
                _scheduledTimes.value = bedMs to wakeMs
                WakeAlarmScheduler.scheduleAlarmsIfEarlier(
                    context, wakeMs, s.wakeAlarmCount, s.wakeAlarmIntervalMinutes,
                    s.gentleWakeEnabled, s.mediumWakeEnabled, s.wakeAlarmEnabled
                )
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

    companion object {
        // How long past the scheduled wake time the passive-detection window stays open, to
        // also catch oversleeping past the alarm rather than cutting off exactly at wake time.
        private const val PASSIVE_WINDOW_BUFFER_MS = 3 * 3600_000L  // 3 hours
    }
}
