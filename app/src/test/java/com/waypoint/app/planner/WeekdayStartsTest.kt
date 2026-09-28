package com.waypoint.app.planner

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class WeekdayStartsTest {

    private val monday = LocalDate.of(2026, 9, 28)
    private val saturday = LocalDate.of(2026, 10, 3)

    private val work = NamedBlock(
        id = "work", name = "Work",
        defaultStartHour = 14, defaultStartMinute = 30,
        weekdayStarts = mapOf(6 to "16:00", 7 to "16:00")
    )

    @Test
    fun defaultOnWeekdays_ownStartAtWeekends() {
        assertEquals(14 to 30, work.startOn(monday))
        assertEquals(16 to 0, work.startOn(saturday))
    }

    @Test
    fun durationBlock_hasNoEnd() {
        assertEquals(-1 to 0, work.endOn(saturday))
    }

    @Test
    fun timeRange_keepsItsLength_evenPastMidnight() {
        val ranged = work.copy(defaultEndHour = 22, defaultEndMinute = 30)  // 8 h
        assertEquals(22 to 30, ranged.endOn(monday))
        assertEquals(0 to 0, ranged.endOn(saturday))
    }
}
