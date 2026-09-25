package com.waypoint.app.planner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class NTimesQuotaTest {

    private val anchor = LocalDate.of(2026, 6, 1)  // day 0 of a 7-day period

    private fun due(done: Set<LocalDate>, day: Int, count: Int = 3) =
        nTimesQuotaDue(count, 7, anchor.toString(), done.map { it.toString() }.toSet(), anchor.plusDays(day.toLong()))

    private fun d(day: Int) = anchor.plusDays(day.toLong())

    @Test
    fun keptUp_dueOnEvenlySpacedDays() {
        // Doing it each day it's due: due on days 0, 2 and 4, nothing after.
        val done = mutableSetOf<LocalDate>()
        val dueDays = (0 until 7).filter { day -> due(done, day).also { if (it) done += d(day) } }
        assertEquals(listOf(0, 2, 4), dueDays)
    }

    @Test
    fun missedDay_isCaughtUpNextDay() {
        assertTrue(due(emptySet(), 0))
        // Not done on day 0: still due on day 1.
        assertTrue(due(emptySet(), 1))
    }

    @Test
    fun doneEarly_pushesNextBack() {
        // Done on days 0 and 1: two of three already, so not due again until day 4.
        val done = setOf(d(0), d(1))
        assertFalse(due(done, 2))
        assertFalse(due(done, 3))
        assertTrue(due(done, 4))
    }

    @Test
    fun quotaMet_notDueForRestOfPeriod_thenResets() {
        val done = setOf(d(0), d(1), d(2))
        assertFalse(due(done, 5))
        // Day 7 starts the next period.
        assertTrue(due(done, 7))
    }

    @Test
    fun dayItWasDone_countsAsDue() {
        assertTrue(due(setOf(d(1), d(0)), 1))
    }

    @Test
    fun beforeAnchor_notDue() {
        assertFalse(nTimesQuotaDue(3, 7, anchor.toString(), emptySet(), anchor.minusDays(1)))
    }
}
