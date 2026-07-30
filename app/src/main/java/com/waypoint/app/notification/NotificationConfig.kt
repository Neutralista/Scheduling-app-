package com.waypoint.app.notification

data class ActionConfig(
    val label: String,
    val behavior: String, // "snooze", "openTab", "dismiss"
    val snoozeMinutes: Int = 60,
    val tab: String = "scripts"
)

data class WeeklyTrigger(
    val day: Int,   // Calendar.SUNDAY=1, MONDAY=2, …
    val hour: Int,
    val minute: Int
)

data class NotificationConfig(
    val id: String,
    val title: String,
    val body: String,
    val actions: List<ActionConfig> = emptyList(),
    val weekly: WeeklyTrigger? = null
)
