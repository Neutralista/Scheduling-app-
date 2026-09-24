package com.waypoint.app.cycle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class CycleTimesTest {

    private fun at(day: Int, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(2026, 3, day, hour, minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private val hour = 3600_000L

    @Test
    fun nextAtTime_laterSameDay() {
        assertEquals(at(10, 23, 30), Cycle.nextAtTime(at(10, 7), 23, 30))
    }

    @Test
    fun nextAtTime_afterMidnightRollsToNextDay() {
        assertEquals(at(11, 1, 30), Cycle.nextAtTime(at(10, 7), 1, 30))
    }

    @Test
    fun nextAtTime_sameTimeAsAnchorRollsToNextDay() {
        assertEquals(at(11, 7), Cycle.nextAtTime(at(10, 7), 7, 0))
    }

    @Test
    fun timesError_validFullCycle() {
        assertNull(Cycle.timesError(at(10, 7), at(10, 23), at(11, 7), at(11, 8)))
    }

    @Test
    fun timesError_validOpenCycle() {
        assertNull(Cycle.timesError(at(10, 7), null, null, at(10, 12)))
    }

    @Test
    fun timesError_sleepBeforeWake() {
        assertNotNull(Cycle.timesError(at(10, 7), at(10, 1), null, at(10, 12)))
    }

    @Test
    fun timesError_nextWakeBeforeSleep() {
        assertNotNull(Cycle.timesError(at(10, 7), at(10, 23), at(10, 22), at(11, 8)))
    }

    @Test
    fun timesError_nextWakeWithoutSleepBeforeWake() {
        assertNotNull(Cycle.timesError(at(10, 7), null, at(10, 6), at(11, 8)))
    }

    @Test
    fun timesError_futureTimes() {
        val now = at(10, 12)
        assertNotNull(Cycle.timesError(now + hour, null, null, now))
        assertNotNull(Cycle.timesError(at(10, 7), now + hour, null, now))
        assertNotNull(Cycle.timesError(at(10, 7), at(10, 9), now + hour, now))
    }
}
