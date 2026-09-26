package com.waypoint.app.planner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class ReminderTest {

    private val today = LocalDate.of(2026, 9, 26)
    private fun ms(date: LocalDate, h: Int, m: Int = 0) =
        date.atTime(h, m).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private val pills = Reminder(id = "p", title = "Pills", times = listOf("20:00", "08:00"))

    @Test
    fun dailyReminder_timesInOrder() {
        assertEquals(listOf("08:00", "20:00"), pills.occurrencesOn(today).map { it.time })
    }

    @Test
    fun next_isTheNextUnsettledTime() {
        val next = pills.nextOccurrenceAfter(ms(today, 9), today) { false }
        assertEquals(today to "20:00", next!!.date to next.time)
        // Tonight's already done: tomorrow morning's.
        val after = pills.nextOccurrenceAfter(ms(today, 9), today) { it == occurrenceKey("p", today, "20:00") }
        assertEquals(today.plusDays(1) to "08:00", after!!.date to after.time)
    }

    @Test
    fun oneOff_ringsOnceThenNever() {
        val once = pills.copy(rule = RecurrenceRule.OneOff(today.toString()), times = listOf("10:00"))
        assertEquals(ms(today, 10), once.nextOccurrenceAfter(ms(today, 9), today) { false }!!.atMs)
        assertNull(once.nextOccurrenceAfter(ms(today, 11), today) { false })
        assertTrue(once.occurrencesOn(today.plusDays(1)).isEmpty())
    }

    @Test
    fun daysOfWeek_onlyThoseDays() {
        // 26 Sep 2026 is a Saturday.
        val weekdays = pills.copy(rule = RecurrenceRule.DaysOfWeek(listOf(1, 2, 3, 4, 5)))
        assertTrue(weekdays.occurrencesOn(today).isEmpty())
        val next = weekdays.nextOccurrenceAfter(ms(today, 9), today) { false }!!
        assertEquals(LocalDate.of(2026, 9, 28) to "08:00", next.date to next.time)
    }

    @Test
    fun disabled_neverDue() {
        val off = pills.copy(enabled = false)
        assertTrue(off.occurrencesOn(today).isEmpty())
        assertNull(off.nextOccurrenceAfter(ms(today, 0), today) { false })
    }

    @Test
    fun todoList_keepsAMissedOneOffUntilDone() {
        val yesterday = today.minusDays(1)
        val once = Reminder(id = "o", title = "Call", times = listOf("15:00"), rule = RecurrenceRule.OneOff(yesterday.toString()))
        val list = remindersForDay(listOf(once, pills), today) { false }
        assertEquals(listOf("o" to yesterday, "p" to today, "p" to today), list.map { it.reminder.id to it.date })
        val settled = remindersForDay(listOf(once), today) { it == occurrenceKey("o", yesterday, "15:00") }
        assertTrue(settled.isEmpty())
    }
}
