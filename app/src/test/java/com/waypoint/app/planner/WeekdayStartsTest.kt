package com.waypoint.app.planner

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class WeekdayStartsTest {

    private val monday = LocalDate.of(2026, 9, 28)
    private val saturday = LocalDate.of(2026, 10, 3)

    private val block = NamedBlock(
        id = "b", name = "Block",
        defaultStartHour = 9, defaultStartMinute = 15,
        weekdayStarts = mapOf(6 to "11:00", 7 to "11:00")
    )

    @Test
    fun defaultOnWeekdays_ownStartAtWeekends() {
        assertEquals(9 to 15, block.startOn(monday))
        assertEquals(11 to 0, block.startOn(saturday))
    }

    @Test
    fun durationBlock_hasNoEnd() {
        assertEquals(-1 to 0, block.endOn(saturday))
    }

    @Test
    fun timeRange_keepsItsLength_evenPastMidnight() {
        val ranged = block.copy(defaultEndHour = 22, defaultEndMinute = 15, weekdayStarts = mapOf(6 to "20:00"))  // 13 h
        assertEquals(22 to 15, ranged.endOn(monday))
        assertEquals(9 to 0, ranged.endOn(saturday))
    }
}
