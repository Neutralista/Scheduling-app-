package com.waypoint.app.planner

import org.junit.Assert.assertEquals
import org.junit.Test

class TaskOccurrencesTest {

    private val task = TaskRequest(id = "walk", title = "Walk the dog", durationMinutes = 20, priority = 5)

    @Test
    fun onceADay_isJustItsId() {
        assertEquals(listOf("walk" to null), task.occurrences())
        assertEquals(listOf("walk" to null), task.copy(timesOfDay = listOf("08:00")).occurrences())
    }

    @Test
    fun severalTimes_firstKeepsTheId() {
        val t = task.copy(timesOfDay = listOf("08:00", "13:00", "18:00"))
        assertEquals(listOf("walk" to "08:00", "walk~2" to "13:00", "walk~3" to "18:00"), t.occurrences())
        assertEquals("walk", taskBaseId("walk~3"))
        assertEquals("~3", occurrenceSuffix("walk~3"))
        assertEquals("", occurrenceSuffix("walk"))
    }

    @Test
    fun defaultTimes_spreadFrom8To20() {
        assertEquals(listOf("08:00", "20:00"), defaultTimesOfDay(2))
        assertEquals(listOf("08:00", "14:00", "20:00"), defaultTimesOfDay(3))
        assertEquals(listOf("08:00", "12:00", "16:00", "20:00"), defaultTimesOfDay(4))
    }

    @Test
    fun eachTime_isPlacedAroundIt() {
        val day = java.time.LocalDate.of(2026, 6, 10)
        fun ms(d: java.time.LocalDate, h: Int) = d.atTime(h, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val r = EventPlannerRegistry()
        // Asleep 23:00 → 07:00 around the day, as the app always has it.
        r.register(PlannerEvent("sleep_prev", "Sleep", 480, PlannerPriority.SLEEP, category = EventCategory.SLEEP,
            fixedStartMillis = ms(day.minusDays(1), 23), fixedEndMillis = ms(day, 7)))
        r.register(PlannerEvent("sleep_tonight", "Sleep", 480, PlannerPriority.SLEEP, category = EventCategory.SLEEP,
            fixedStartMillis = ms(day, 23), fixedEndMillis = ms(day.plusDays(1), 7)))
        r.register(PlannerEvent("a", "a", 30, 9, conditions = listOf(EventCondition.AroundTime(8, 0, 60))))
        r.register(PlannerEvent("a~2", "a", 30, 9, conditions = listOf(EventCondition.AroundTime(18, 0, 60))))
        val plan = r.planForDate(day)
        fun hourOf(id: String) = java.time.Instant.ofEpochMilli(plan.scheduled.first { it.event.id == id }.startMillis)
            .atZone(java.time.ZoneId.systemDefault()).hour
        assertEquals(8, hourOf("a"))
        assertEquals(18, hourOf("a~2"))
    }
}
