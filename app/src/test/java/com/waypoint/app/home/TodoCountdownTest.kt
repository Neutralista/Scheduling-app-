package com.waypoint.app.home

import org.junit.Assert.assertEquals
import org.junit.Test

class TodoCountdownTest {

    private val min = 60_000L
    private val start = 1_000_000_000L
    private val end = start + 45 * min

    @Test
    fun beforeStart_countsDownToIt() {
        assertEquals(TodoCountdown("in 25m", CountdownState.UPCOMING), todoCountdown(start - 25 * min, start, end))
        assertEquals(TodoCountdown("in 1h 5m", CountdownState.UPCOMING), todoCountdown(start - 65 * min, start, end))
        assertEquals(TodoCountdown("in 2h", CountdownState.UPCOMING), todoCountdown(start - 120 * min, start, end))
        assertEquals(TodoCountdown("in <1m", CountdownState.UPCOMING), todoCountdown(start - 20_000L, start, end))
    }

    @Test
    fun duringItsSlot_showsTimeLeft() {
        assertEquals(TodoCountdown("now · 30m left", CountdownState.NOW), todoCountdown(start + 15 * min, start, end))
    }

    @Test
    fun afterItsSlot_isOverdue() {
        assertEquals(TodoCountdown("overdue 15m", CountdownState.OVERDUE), todoCountdown(end + 15 * min, start, end))
    }
}
