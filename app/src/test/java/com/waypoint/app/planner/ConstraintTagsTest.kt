package com.waypoint.app.planner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ConstraintTagsTest {

    private val names = TagNames(
        task = { mapOf("t1" to "Emails")[it] },
        block = { mapOf("gym" to "Gym")[it] }
    )

    @Test
    fun specs_roundTrip_keepsEveryTag() {
        val tags = ConstraintTags(
            days = setOf(1, 3),
            recurrence = RecurrenceRule.NTimesPerPeriod(2, 7, "2026-09-01"),
            afterTaskIds = setOf(TASK_REF_SLEEP, "t1"),
            beforeEventIds = setOf(5L),
            afterBlockIds = setOf("gym", "work"),
            notWithIds = setOf("t1"),
            eventTitles = mapOf(5L to "Dentist")
        )
        assertEquals(tags, ConstraintTags.fromSpecs(tags.toSpecs()))
    }

    @Test
    fun nTimesPerPeriod_isSaved() {
        // Used to be dropped when a normal task was saved.
        val specs = ConstraintTags(recurrence = RecurrenceRule.NTimesPerPeriod(3, 7, "2026-09-01")).toSpecs()
        assertTrue(specs.any { it.type == "nTimesPerPeriod" && it.occurrenceCount == 3 && it.intervalN == 7 })
    }

    @Test
    fun labels_nameWhatTheyPointAt() {
        val specs = listOf(
            TaskConditionSpec("timeWindow", start = "09:00", end = "23:59"),
            TaskConditionSpec("daysOfWeek", days = listOf(1, 2, 3, 4, 5)),
            TaskConditionSpec("afterTask", referenceTaskIds = listOf(TASK_REF_SLEEP)),
            TaskConditionSpec("afterBlock", blockId = "gym"),
            TaskConditionSpec("beforeCalEvent", calendarEventId = 9L, label = "Dentist"),
            TaskConditionSpec("sameDayAs", referenceTaskIds = listOf("gone"))
        )
        val views = conditionTagViews(specs, names)
        assertEquals(
            listOf("After 09:00", "Weekdays", "After sleep", "After Gym", "Before Dentist", "Same day as deleted task"),
            views.map { it.label }
        )
        assertEquals(
            listOf(TagKind.TIME, TagKind.DAYS, TagKind.SLEEP, TagKind.BLOCK, TagKind.EVENT, TagKind.SAME_DAY),
            views.map { it.kind }
        )
    }

    @Test
    fun removingATag_dropsOnlyThatOne() {
        val tags = ConstraintTags(afterBlockIds = setOf("gym"), days = setOf(6, 7))
        val gym = tags.views(names).first { it.label == "After Gym" }
        assertEquals(ConstraintTags(days = setOf(6, 7)), gym.remove(tags))
    }

    @Test
    fun recurrenceLabels() {
        val today = LocalDate.of(2026, 9, 25)
        assertEquals("Once · today", recurrenceLabel(RecurrenceRule.OneOff("2026-09-25"), today))
        assertEquals("Once · tomorrow", recurrenceLabel(RecurrenceRule.OneOff("2026-09-26"), today))
        assertEquals("Every 3 days", recurrenceLabel(RecurrenceRule.EveryNDays(3, "2026-09-01"), today))
        assertEquals("2× a week", recurrenceLabel(RecurrenceRule.NTimesPerPeriod(2, 7, "2026-09-01"), today))
        assertEquals("Mon Wed", daysLabel(listOf(3, 1)))
        assertEquals("Weekends", daysLabel(listOf(6, 7)))
    }
}
