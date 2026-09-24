package com.waypoint.app.planner

/**
 * Turns a timeline drag of task [taskId] into ordering rules: it now runs after every task in
 * [nowAfter] and before every task in [nowBefore]. The opposite rules are removed on both
 * sides. The dragged task's own opposite rule goes, and so does any rule on the other task that
 * pointed the old way. Leaving that second rule in place used to give the two tasks
 * contradicting rules, and both were then blocked as a cyclic dependency.
 *
 * Returns only the requests that changed.
 */
internal fun applyDragOrdering(
    tasks: List<TaskRequest>,
    taskId: String,
    nowAfter: Set<String>,
    nowBefore: Set<String>
): List<TaskRequest> {
    if (nowAfter.isEmpty() && nowBefore.isEmpty()) return emptyList()
    return tasks.mapNotNull { req ->
        val updated = when (req.id) {
            taskId -> req.withOrdering(addAfter = nowAfter, addBefore = nowBefore, removeAfter = nowBefore, removeBefore = nowAfter)
            // Dragged after req, so req can no longer be "after" it; dragged before req, so req
            // can no longer be "before" it.
            in nowAfter  -> req.withOrdering(removeAfter = setOf(taskId))
            in nowBefore -> req.withOrdering(removeBefore = setOf(taskId))
            else -> req
        }
        updated.takeIf { it !== req }
    }
}

private fun TaskRequest.withOrdering(
    addAfter: Set<String> = emptySet(), addBefore: Set<String> = emptySet(),
    removeAfter: Set<String> = emptySet(), removeBefore: Set<String> = emptySet()
): TaskRequest {
    val after = (conditions.firstOrNull { it.type == "afterTask" }?.referenceTaskIds.orEmpty().toSet() - removeAfter) + addAfter
    val before = (conditions.firstOrNull { it.type == "beforeTask" }?.referenceTaskIds.orEmpty().toSet() - removeBefore) + addBefore
    val newConditions = conditions.filter { it.type != "afterTask" && it.type != "beforeTask" } +
        listOfNotNull(
            after.takeIf { it.isNotEmpty() }?.let { TaskConditionSpec("afterTask", referenceTaskIds = it.sorted()) },
            before.takeIf { it.isNotEmpty() }?.let { TaskConditionSpec("beforeTask", referenceTaskIds = it.sorted()) }
        )
    val oldOrdering = conditions.filter { it.type == "afterTask" || it.type == "beforeTask" }
        .associate { it.type to it.referenceTaskIds.orEmpty().toSet() }
    val newOrdering = mapOf("afterTask" to after, "beforeTask" to before).filterValues { it.isNotEmpty() }
    return if (oldOrdering == newOrdering) this else copy(conditions = newConditions)
}
