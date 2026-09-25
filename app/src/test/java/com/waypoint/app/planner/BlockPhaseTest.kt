package com.waypoint.app.planner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BlockPhaseTest {

    private val min = 60_000L
    private val warm = BlockPhase("warm", "Warmup", durationMinutes = 10)
    private val work = BlockPhase("work", "Workout", durationMinutes = 40)
    private val cool = BlockPhase("cool", "Cooldown", durationMinutes = 10)
    private val block = NamedBlock(id = "gym", name = "Gym", phases = listOf(warm, work, cool))
    private val start = 1_000_000L
    private val end = start + 60 * min

    @Test
    fun windows_runBackToBack_andStopAtTheEnd() {
        val w = phaseWindows(block, emptyList(), start, start + 55 * min)
        assertEquals(listOf(start, start + 10 * min, start + 50 * min), w.map { it.startMs })
        assertEquals(start + 55 * min, w.last().endMs)  // Cooldown cut off at the block's end
    }

    @Test
    fun phaseWithoutLength_isLeftOut() {
        val empty = block.copy(phases = listOf(BlockPhase("x", "Empty"), warm))
        assertEquals(listOf("warm"), phaseWindows(empty, emptyList(), start, end).map { it.phase.id })
    }

    @Test
    fun currentPhase_followsThePlan_untilNextPhaseIsUsed() {
        assertEquals(work, currentPhase(block, emptyList(), start, end, emptyMap(), start + 15 * min))
        // Next pressed early: Cooldown is on even though the plan says Workout.
        assertEquals(cool, currentPhase(block, emptyList(), start, end, mapOf("warm" to start, "cool" to start + 20 * min), start + 21 * min))
        // After the last planned phase, nothing is on.
        assertNull(currentPhase(block, emptyList(), start, start + 70 * min, emptyMap(), start + 65 * min))
    }

    @Test
    fun timings_fromNextPhase_endAtTheNextStart() {
        val t = sessionPhaseTimings(block, emptyList(), start, start + 50 * min,
            mapOf("warm" to start, "work" to start + 12 * min, "cool" to start + 45 * min))
        assertEquals(listOf(12, 33, 5), t.map { it.minutes })
    }

    @Test
    fun timings_withoutNextPhase_areThePlannedWindows() {
        val t = sessionPhaseTimings(block, emptyList(), start, start + 30 * min, emptyMap())
        assertEquals(listOf("Warmup" to 10, "Workout" to 20), t.map { it.name to it.minutes })
    }
}
