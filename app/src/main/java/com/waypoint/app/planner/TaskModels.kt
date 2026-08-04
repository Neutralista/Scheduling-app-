package com.waypoint.app.planner

import kotlinx.serialization.Serializable

@Serializable
data class SubtaskDef(
    val id: String,
    val title: String,
    val defaultDurationMinutes: Int = 15
)

@Serializable
enum class TriggerEvent {
    TASK_STARTED,
    TASK_COMPLETED
}

@Serializable
data class TaskTrigger(
    val event: TriggerEvent,
    val chainTaskId: String,
    val deadlineMinutes: Int = 0
)

@Serializable
data class SubtaskExecution(
    val subtaskId: String,
    val startMillis: Long,
    val endMillis: Long? = null
)

@Serializable
data class TaskExecution(
    val taskId: String,
    val startMillis: Long,
    val endMillis: Long? = null,
    val subtaskExecutions: List<SubtaskExecution> = emptyList()
) {
    val measuredMinutes: Int?
        get() = endMillis?.let { ((it - startMillis) / 60_000L).toInt() }

    val isRunning: Boolean get() = endMillis == null
}

@Serializable
enum class AnchorType {
    BEFORE_SHIFT_START,
    AFTER_SHIFT_END,
    BEFORE_SLEEP,
    AFTER_WAKE
}

@Serializable
data class BufferRule(
    val id: String,
    val label: String,
    val anchorType: AnchorType,
    val offsetMinutes: Int = 0,
    val durationMinutes: Int = 15,
    val enabled: Boolean = true
)
