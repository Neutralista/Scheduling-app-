package com.waypoint.app.planner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutineMigrationTest {

    private val routine = TaskRequest(
        id = "r1",
        title = "Morning routine",
        durationMinutes = 30,
        priority = 7,
        conditions = listOf(TaskConditionSpec("timeWindow", start = "06:00", end = "10:00")),
        isRoutine = true,
        subtasks = listOf(
            SubtaskDef("a", "Stretch", 10),
            SubtaskDef("b", "Shower", 15),
            SubtaskDef("c", "Breakfast", 20)
        ),
        bufferMinutes = 5,
        scheduleLate = true,
        colorArgb = 0x112233
    )

    @Test
    fun routine_becomesAutoPlacedBlock_withStepsInOrder() {
        val (block, tasks) = RoutineMigration.toBlock(routine)
        assertEquals("r1", block.id)
        assertEquals("Morning routine", block.name)
        assertTrue(block.isFloating)
        assertTrue(block.useTotalTaskDuration)
        assertEquals(45, block.estimatedMinutes)
        assertEquals(7, block.priority)
        assertEquals(5, block.bufferMinutes)
        assertEquals(PlannerZone.EVENING, block.zone)
        assertEquals(0x112233, block.colorArgb)
        assertEquals(routine.conditions, block.floatingConditions)
        assertEquals(listOf("Stretch", "Shower", "Breakfast"), tasks.map { it.title })
        assertEquals(listOf(0, 1, 2), tasks.map { it.sequence })
        assertTrue(tasks.all { it.blockId == "r1" && it.placement == BlockTaskPlacement.DURING && it.isAlways })
    }

    @Test
    fun steplessRoutine_isNotConverted() {
        assertFalse(RoutineMigration.isConvertible(routine.copy(subtasks = emptyList())))
        assertFalse(RoutineMigration.isConvertible(routine.copy(isRoutine = false)))
    }

    @Test
    fun afterRoutine_becomesAfterBlock_otherRefsKept() {
        val out = RoutineMigration.rewriteConditions(
            listOf(
                TaskConditionSpec("afterTask", referenceTaskIds = listOf("r1", "t2")),
                TaskConditionSpec("sameDayAs", referenceTaskIds = listOf("r1"))
            ),
            setOf("r1")
        )!!
        assertEquals(
            listOf(
                TaskConditionSpec("afterTask", referenceTaskIds = listOf("t2")),
                TaskConditionSpec("afterBlock", blockId = "r1")
            ),
            out
        )
    }

    @Test
    fun existingBlockCondition_isKept() {
        val out = RoutineMigration.rewriteConditions(
            listOf(
                TaskConditionSpec("beforeTask", referenceTaskIds = listOf("r1")),
                TaskConditionSpec("beforeBlock", blockId = "gym")
            ),
            setOf("r1")
        )!!
        assertEquals(listOf(TaskConditionSpec("beforeBlock", blockId = "gym")), out)
    }

    @Test
    fun noReferences_returnsNull() {
        assertNull(RoutineMigration.rewriteConditions(listOf(TaskConditionSpec("timeWindow", start = "09:00")), setOf("r1")))
    }
}
