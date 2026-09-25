package com.waypoint.app.planner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class PlanOverviewTest {

    @Test
    fun weekStartsOnMonday() {
        // 2026-09-24 is a Thursday.
        assertEquals(LocalDate.of(2026, 9, 21), weekStart(LocalDate.of(2026, 9, 24)))
        assertEquals(LocalDate.of(2026, 9, 21), weekStart(LocalDate.of(2026, 9, 21)))
        assertEquals(LocalDate.of(2026, 9, 21), weekStart(LocalDate.of(2026, 9, 27)))
        assertEquals(7, weekDays(LocalDate.of(2026, 9, 24)).size)
    }

    @Test
    fun monthWeeks_coverEveryDayOfTheMonth() {
        // September 2026 starts on a Tuesday and ends on a Wednesday: five weeks.
        val weeks = monthWeekStarts(YearMonth.of(2026, 9))
        assertEquals(LocalDate.of(2026, 8, 31), weeks.first())
        assertEquals(LocalDate.of(2026, 9, 28), weeks.last())
        assertEquals(5, weeks.size)
    }

    @Test
    fun zoomLevels_stepInAndOut() {
        assertEquals(PlanZoomLevel.WEEK, PlanZoomLevel.DAY.outer)
        assertEquals(PlanZoomLevel.MONTH, PlanZoomLevel.WEEK.outer)
        assertEquals(PlanZoomLevel.YEAR, PlanZoomLevel.MONTH.outer)
        assertNull(PlanZoomLevel.YEAR.outer)
        assertEquals(PlanZoomLevel.MONTH, PlanZoomLevel.YEAR.inner)
        assertNull(PlanZoomLevel.DAY.inner)
    }
}
