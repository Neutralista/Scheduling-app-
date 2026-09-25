package com.waypoint.app.planner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

class EventPlannerRegistryTest {

    private val day = LocalDate.of(2026, 6, 10)

    // Zone read per call — the DST test switches the default zone mid-test.
    private fun ms(date: LocalDate, hour: Int, minute: Int = 0): Long =
        date.atTime(hour, minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

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
    fun doneTask_pinnedWhereItHappened_stillFloatsOnOtherDays() {
        val r = registry().apply {
            register(task("t", 60).copy(fixedStartMillis = ms(day, 9), fixedEndMillis = ms(day, 10), pinnedDayOnly = true))
        }
        // Pinned today even though "now" is past it, rather than floating after now.
        assertEquals(ms(day, 9), r.planForDate(day, nowMs = ms(day, 15)).startOf("t"))
        // Tomorrow it's planned like any other task, from that morning's wake.
        assertEquals(ms(day.plusDays(1), 7), r.planForDate(day.plusDays(1)).startOf("t"))
        // But at 00:30 in the same wake it's still done, not to do again.
        assertNull(r.planForDate(day.plusDays(1), nowMs = ms(day.plusDays(1), 0, 30)).startOf("t"))
    }

    @Test
    fun fixedEvent_withoutPinnedDayOnly_absentOnOtherDays() {
        val r = registry().apply {
            register(task("t", 60).copy(fixedStartMillis = ms(day, 9), fixedEndMillis = ms(day, 10)))
        }
        assertNull(r.planForDate(day.plusDays(1)).startOf("t"))
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
    fun lateTask_landsRightBeforeItsDeadline() {
        // Used to skip the only slot because its latest end passed the deadline, and get blocked.
        val r = registry().apply {
            register(PlannerEvent(
                id = "late", title = "late", durationMinutes = 60, priority = 9, scheduleLate = true,
                conditions = listOf(EventCondition.Deadline(ms(day, 14)))
            ))
        }
        assertEquals(ms(day, 13), r.planForDate(day).startOf("late"))
    }

    @Test
    fun taskDuringBlock_respectsDeadline() {
        val gym = NamedBlockInstance(NamedBlock(id = "gym", name = "Gym"), ms(day, 9), ms(day, 12))
        val r = registry().apply {
            register(PlannerEvent(
                id = "stretch", title = "stretch", durationMinutes = 60, priority = 9,
                zone = PlannerZone.EVENING,
                conditions = listOf(EventCondition.DuringBlock("gym"), EventCondition.Deadline(ms(day, 10, 30)))
            ))
        }
        assertEquals(ms(day, 9, 30), r.planForDate(day, namedBlockInstances = listOf(gym)).startOf("stretch"))
    }

    @Test
    fun deadline_isJudgedAgainstThePlannedDay() {
        val r = registry().apply {
            register(PlannerEvent(
                id = "due", title = "due", durationMinutes = 30, priority = 9,
                conditions = listOf(EventCondition.Deadline(ms(day, 12)))
            ))
        }
        assertEquals(ms(day, 7), r.planForDate(day).startOf("due"))
        val nextDay = r.planForDate(day.plusDays(1))
        assertTrue(nextDay.blocked.any { it.event.id == "due" && it.reason == "Past deadline" })
    }

    @Test
    fun urgentTask_isNotStarvedByItsLowPriorityPrerequisite() {
        // 20:00 → 23:00 free. Without inheritance "filler" (10) goes before "prep" (9), and
        // the urgent "main" — which must follow prep and end by 23:00 — no longer fits.
        val r = registry(busyUntilHour = 20).apply {
            register(PlannerEvent(id = "filler", title = "filler", durationMinutes = 60, priority = 10))
            register(PlannerEvent(id = "prep", title = "prep", durationMinutes = 60, priority = 9, bufferMinutes = 1))
            register(PlannerEvent(
                id = "main", title = "main", durationMinutes = 60, priority = PlannerPriority.URGENT,
                conditions = listOf(EventCondition.AfterTask(setOf("prep")), EventCondition.TimeWindow(0, 0, 23, 0))
            ))
        }
        val plan = r.planForDate(day)
        assertEquals(ms(day, 20), plan.startOf("prep"))
        assertEquals(ms(day, 21, 1), plan.startOf("main"))
    }

    @Test
    fun windowCrossingMidnight_isPlaceable() {
        val r = registry(busyUntilHour = 22).apply {
            register(task("late", 60, 9, EventCondition.TimeWindow(23, 30, 1, 0)))
        }
        assertEquals(ms(day, 23, 30), r.planForDate(day).startOf("late"))
    }

    @Test
    fun tasksInsideBlock_runBackToBack() {
        fun during(id: String) = BlockTask(id, "gym", id, 20, BlockTaskPlacement.DURING)
        val gym = NamedBlockInstance(
            NamedBlock(id = "gym", name = "Gym"), ms(day, 9), ms(day, 10),
            activeTasks = listOf(during("a"), during("b"), during("c"))
        )
        val plan = registry().planForDate(day, namedBlockInstances = listOf(gym))
        assertEquals(ms(day, 9), plan.startOf("a"))
        assertEquals(ms(day, 9, 20), plan.startOf("b"))
        assertEquals(ms(day, 9, 40), plan.startOf("c"))
    }

    @Test
    fun tasksDuringSameCalendarEvent_dontOverlap() {
        val r = registry().apply {
            register(task("notes", 30, 9, EventCondition.DuringCalEvent(42L)))
            register(task("reply", 30, 9, EventCondition.DuringCalEvent(42L)))
        }
        val plan = r.planForDate(day, calendarEventBlocks = mapOf(42L to (ms(day, 14) to ms(day, 15))))
        assertEquals(ms(day, 14), plan.startOf("notes"))
        assertEquals(ms(day, 14, 30), plan.startOf("reply"))
    }

    @Test
    fun lastHourOfDstFallBackDay_isPlannable() {
        val original = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))
        try {
            val fallBack = LocalDate.of(2026, 10, 25) // 25 hours long in Berlin
            val r = EventPlannerRegistry().apply {
                register(task("late", 30, 9, EventCondition.TimeWindow(23, 0, 23, 59)))
            }
            assertEquals(ms(fallBack, 23), r.planForDate(fallBack).startOf("late"))
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun automaticGap_isReclaimedWhenTheDayIsTight() {
        // 21:00 → 23:00 free; four 30-minute tasks only fit if the default 30-minute gaps give way.
        val r = registry(busyUntilHour = 21).apply {
            listOf("t1", "t2", "t3", "t4").forEach { register(task(it, 30)) }
        }
        val plan = r.planForDate(day)
        assertTrue(plan.blocked.none { it.event.id.startsWith("t") })
        assertEquals(ms(day, 21), plan.startOf("t1"))
        assertEquals(ms(day, 22), plan.startOf("t2"))
        assertEquals(ms(day, 21, 30), plan.startOf("t3"))
        assertEquals(ms(day, 22, 30), plan.startOf("t4"))
    }

    @Test
    fun taskDeliberatelyAfterBedtime_isNotCapped() {
        val r = registry(busyUntilHour = 22).apply {
            register(task("late", 20, 9, EventCondition.TimeWindow(23, 30, 23, 59)))
        }
        assertEquals(ms(day, 23, 30), r.planForDate(day).startOf("late"))
    }

    @Test
    fun blockBounds_areTheBlockAlone_notTheStretchedTile() {
        val gym = NamedBlockInstance(
            NamedBlock(id = "gym", name = "Gym"), ms(day, 18), ms(day, 19),
            activeTasks = listOf(BlockTask("pack", "gym", "pack", 30, BlockTaskPlacement.BEFORE))
        )
        val plan = registry().planForDate(day, namedBlockInstances = listOf(gym))
        assertTrue(plan.startOf("__block__gym")!! < ms(day, 18)) // tile wraps the BEFORE task
        assertEquals(ms(day, 18) to ms(day, 19), plan.blockBounds["gym"])
    }
    @Test
    fun phases_splitTheBlock_andHoldTheirOwnTasks() {
        fun during(id: String, minutes: Int, phase: String? = null) =
            BlockTask(id, "gym", id, minutes, BlockTaskPlacement.DURING, phaseId = phase)
        val block = NamedBlock(
            id = "gym", name = "Gym",
            phases = listOf(BlockPhase("warm", "Warmup", durationMinutes = 15), BlockPhase("work", "Workout"))
        )
        val gym = NamedBlockInstance(
            block, ms(day, 9), ms(day, 10, 30),
            // "u" is unphased, "ghost" belongs to a phase that no longer exists (so is unphased too).
            activeTasks = listOf(during("u", 20), during("w", 10, "warm"), during("x", 30, "work"), during("y", 20, "work"), during("ghost", 5, "gone"))
        )
        val plan = registry().planForDate(day, namedBlockInstances = listOf(gym))
        // Warmup 9:00–9:15, Workout 9:15–10:05, unphased time 10:05–10:30.
        assertEquals(ms(day, 9) to ms(day, 9, 15), plan.phaseBounds["gym"]!![0].let { it.startMs to it.endMs })
        assertEquals(ms(day, 9, 15) to ms(day, 10, 5), plan.phaseBounds["gym"]!![1].let { it.startMs to it.endMs })
        assertEquals(ms(day, 9), plan.startOf("w"))
        // x and y both inside Workout, not overlapping.
        val x = plan.scheduled.first { it.event.id == "x" }
        val y = plan.scheduled.first { it.event.id == "y" }
        listOf(x, y).forEach { assertTrue(it.startMillis >= ms(day, 9, 15) && it.endMillis <= ms(day, 10, 5)) }
        assertTrue(x.endMillis <= y.startMillis || y.endMillis <= x.startMillis)
        assertTrue(plan.startOf("u")!! >= ms(day, 10, 5))
        assertTrue(plan.startOf("ghost")!! >= ms(day, 10, 5))
    }

    @Test
    fun blockLength_fromUnphasedTasksPlusPhases() {
        val block = NamedBlock(
            id = "gym", name = "Gym", useTotalTaskDuration = true,
            phases = listOf(BlockPhase("warm", "Warmup", durationMinutes = 15), BlockPhase("work", "Workout"))
        )
        val tasks = listOf(
            BlockTask("w", "gym", "w", 10, BlockTaskPlacement.DURING, phaseId = "warm"),  // phase set longer: 15
            BlockTask("x", "gym", "x", 40, BlockTaskPlacement.DURING, phaseId = "work"),  // 40
            BlockTask("u", "gym", "u", 20, BlockTaskPlacement.DURING)                     // 20
        )
        assertEquals(75, effectiveDurationMinutes(block, tasks))
    }
    @Test
    fun doneTasks_stayWhereTheyActuallyHappened_othersAvoidThatTime() {
        fun during(id: String) = BlockTask(id, "gym", id, 20, BlockTaskPlacement.DURING)
        val gym = NamedBlockInstance(
            NamedBlock(id = "gym", name = "Gym"), ms(day, 9), ms(day, 10),
            activeTasks = listOf(during("a"), during("b")),
            // "b" was done 9:05–9:25 (e.g. logged by Might).
            taskActuals = mapOf("b" to (ms(day, 9, 5) to ms(day, 9, 25)))
        )
        val plan = registry().planForDate(day, namedBlockInstances = listOf(gym))
        val b = plan.scheduled.first { it.event.id == "b" }
        assertEquals(ms(day, 9, 5) to ms(day, 9, 25), b.startMillis to b.endMillis)
        val a = plan.scheduled.first { it.event.id == "a" }
        assertTrue(a.endMillis <= ms(day, 9, 5) || a.startMillis >= ms(day, 9, 25))
    }

    @Test
    fun floatingBlock_honoursEveryEventTag_notJustTheFirst() {
        val walk = NamedBlockInstance(
            NamedBlock(
                id = "walk", name = "Walk", estimatedMinutes = 60, isFloating = true,
                floatingConditions = listOf(
                    TaskConditionSpec("afterCalEvent", calendarEventId = 1L),
                    TaskConditionSpec("afterCalEvent", calendarEventId = 2L)
                )
            ),
            0L, 0L
        )
        val plan = registry().planForDate(
            day,
            calendarEventBlocks = mapOf(1L to (ms(day, 10) to ms(day, 11)), 2L to (ms(day, 14) to ms(day, 15))),
            floatingBlocks = listOf(walk)
        )
        assertEquals(ms(day, 15), plan.startOf("__block__walk"))
    }

    @Test
    fun task_afterTwoBlocks_startsAfterTheLaterOne() {
        val a = NamedBlockInstance(NamedBlock(id = "a", name = "A"), ms(day, 9), ms(day, 10))
        val b = NamedBlockInstance(NamedBlock(id = "b", name = "B"), ms(day, 12), ms(day, 13))
        val r = registry().apply {
            register(task("t", 30, 9, EventCondition.AfterBlock("a"), EventCondition.AfterBlock("b")))
        }
        val plan = r.planForDate(day, namedBlockInstances = listOf(a, b))
        assertEquals(ms(day, 13), plan.startOf("t"))
    }
}
