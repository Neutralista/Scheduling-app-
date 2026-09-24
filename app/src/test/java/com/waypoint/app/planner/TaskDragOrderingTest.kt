package com.waypoint.app.planner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskDragOrderingTest {

    private fun task(id: String, after: List<String> = emptyList(), before: List<String> = emptyList()) = TaskRequest(
        id = id, title = id, durationMinutes = 30,
        conditions = listOfNotNull(
            after.takeIf { it.isNotEmpty() }?.let { TaskConditionSpec("afterTask", referenceTaskIds = it) },
            before.takeIf { it.isNotEmpty() }?.let { TaskConditionSpec("beforeTask", referenceTaskIds = it) }
        )
    )

    private fun TaskRequest.ids(type: String) = conditions.firstOrNull { it.type == type }?.referenceTaskIds.orEmpty()

    @Test
    fun draggingBack_removesTheOtherTasksContradictingRule() {
        // An earlier drag put b after a. Dragging a after b must drop b's "after a" rule.
        val updates = applyDragOrdering(listOf(task("a"), task("b", after = listOf("a"))), "a", setOf("b"), emptySet())
        val byId = updates.associateBy { it.id }
        assertEquals(listOf("b"), byId.getValue("a").ids("afterTask"))
        assertTrue(byId.getValue("b").ids("afterTask").isEmpty())
    }

    @Test
    fun draggingBefore_replacesTheOppositeRuleOnTheDraggedTask() {
        val updates = applyDragOrdering(listOf(task("a", after = listOf("b")), task("b")), "a", emptySet(), setOf("b"))
        val a = updates.single { it.id == "a" }
        assertTrue(a.ids("afterTask").isEmpty())
        assertEquals(listOf("b"), a.ids("beforeTask"))
    }

    @Test
    fun unrelatedTasksAndRules_areLeftAlone() {
        val updates = applyDragOrdering(
            listOf(task("a", before = listOf("c")), task("b"), task("c")), "a", setOf("b"), emptySet()
        )
        assertEquals(listOf("a"), updates.map { it.id })
        assertEquals(listOf("c"), updates.single().ids("beforeTask"))
    }

    @Test
    fun noCrossing_changesNothing() {
        assertTrue(applyDragOrdering(listOf(task("a"), task("b")), "a", emptySet(), emptySet()).isEmpty())
    }
}
