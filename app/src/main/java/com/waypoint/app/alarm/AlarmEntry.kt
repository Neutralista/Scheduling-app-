package com.waypoint.app.alarm

import kotlinx.serialization.Serializable

@Serializable
data class AlarmEntry(
    val id: String,
    val label: String,
    val hour: Int,
    val minute: Int,
    val enabled: Boolean = true,
    /** ISO day numbers: 1=Mon … 7=Sun. Empty = one-shot (disables after firing). */
    val repeatDays: Set<Int> = emptySet(),
    val vibrate: Boolean = true,
    /** Block id to sync with. Null = no block sync. */
    val linkedBlockId: String? = null,
    /** Whether block-sync is active. When true, enabled is driven by resolveForDate each day. */
    val blockSyncEnabled: Boolean = false
) {
    val displayTime: String get() = "%02d:%02d".format(hour, minute)

    val repeatLabel: String get() = when {
        repeatDays.isEmpty() -> "Once"
        repeatDays.size == 7 -> "Every day"
        repeatDays == setOf(1, 2, 3, 4, 5) -> "Weekdays"
        repeatDays == setOf(6, 7) -> "Weekend"
        else -> repeatDays.sorted().joinToString(" ") { DAY_LABELS[it - 1] }
    }

    companion object {
        val DAY_LABELS = listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")
    }
}
