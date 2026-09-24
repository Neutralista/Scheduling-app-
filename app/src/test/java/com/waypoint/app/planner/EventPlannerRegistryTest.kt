package com.waypoint.app.planner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class EventPlannerRegistryTest {

    private val day = LocalDate.of(2026, 6, 10)
    private val zone = ZoneId.systemDefault()

    private fun ms(date: LocalDate, hour: Int, minute: Int = 0): Long =
        date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun sleep(id: String, bed: Long, wake: Long) = PlannerEvent(
        id = id, title = "Sleep", durationMinutes = ((wake - bed) / 60_000L).toInt(),
        priority = PlannerPriority.SLEEP, category = EventCategory.SLEEP,
        fixedStartMillis = bed, fixedEndMillis = wake
    )

    // Priority 9+ is never jittered, so placements are exact.
    private fun task(id: String, minutes: Int, priority: Int = 9, vararg conditions: EventCondition) =
        PlannerEvent(id = id, title = id, durationMinutes = minutes, priority = priority, conditions = conditions.toList())

    /** Sleeps 23:00 → 07:00 around [day]; fills the day with a fixed event until [busyUntilHour]. */
    private fun registry(busyUntilHour: Int? = null) = EventPlannerRegistry().apply {
        register(sleep("sleep_prev", ms(day.minusDays(1), 23), ms(day, 7)))
        register(sleep("sleep_tonight", ms(day, 23), ms(day.plusDays(1), 7)))
        if (busyUntilHour != null) register(PlannerEvent(
            id = "busy", title = "busy", durationMinutes = 0,
            fixedStartMillis = ms(day, 7), fixedEndMillis = ms(day, busyUntilHour)
        ))
    }

    private fun DayPlan.startOf(id: String): Long? = scheduled.firstOrNull { it.event.id == id }?.startMillis

    @Test
    fun otherDay_startsAtThatMorningsWake_notTomorrow() {
        val r = registry().apply { register(task("t", 60)) }
        assertEquals(ms(day, 7), r.planForDate(day).startOf("t"))
    }

    @Test
    fun today_startsFromNow() {
        val r = registry().apply { register(task("t", 60)) }
        assertEquals(ms(day, 10), r.planForDate(day, nowMs = ms(day, 10)).startOf("t"))
    }

    @Test
    fun nonUrgentTask_neverPushedPastBedtime() {
        val r = registry(busyUntilHour = 22).apply {
            register(task("first", 60))
            register(task("second", 60))
        }
        val plan = r.planForDate(day)
        assertEquals(ms(day, 22), plan.startOf("first"))
        assertNull(plan.startOf("second"))
        assertTrue(plan.blocked.any { it.event.id == "second" })
    }

    @Test
    fun urgentTask_mayPushBedtime_andSleepSlides() {
        val r = registry(busyUntilHour = 23).apply { register(task("urgent", 120, PlannerPriority.URGENT)) }
        val plan = r.planForDate(day)
        assertEquals(ms(day, 23), plan.startOf("urgent"))
        assertEquals(ms(day.plusDays(1), 1), plan.startOf("sleep_tonight"))
    }

    @Test
    fun urgentTask_neverEatsIntoMinimumSleep() {
        // Only 23:00 → 03:00 (wake − 4h) is available; five hours can't fit.
        val r = registry(busyUntilHour = 23).apply { register(task("urgent", 300, PlannerPriority.URGENT)) }
        val plan = r.planForDate(day)
        assertNull(plan.startOf("urgent"))
    }

    @Test
    fun taskDeliberatelyAfterBedtime_isNotCapped() {
        val r = registry(busyUntilHour = 22).apply {
            register(task("late", 20, 9, EventCondition.TimeWindow(23, 30, 23, 59)))
        }
        assertEquals(ms(day, 23, 30), r.planForDate(day).startOf("late"))
    }
}
