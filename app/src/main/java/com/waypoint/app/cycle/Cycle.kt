package com.waypoint.app.cycle

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Serializable
data class Cycle(
    val id: String,
    val wakeMillis: Long,
    val nextWakeMillis: Long? = null,
    val sleepStartMillis: Long? = null,
    val dateLabel: String  // "yyyy-MM-dd" of wake date — cycle ownership
) {
    val isOpen: Boolean get() = nextWakeMillis == null
    val endMillis: Long get() = nextWakeMillis ?: System.currentTimeMillis()
    val totalDurationMs: Long get() = endMillis - wakeMillis
    val awakeDurationMs: Long get() = (sleepStartMillis ?: endMillis) - wakeMillis
    val sleepDurationMs: Long? get() =
        if (sleepStartMillis != null && nextWakeMillis != null) nextWakeMillis - sleepStartMillis
        else null

    companion object {
        private val zone get() = ZoneId.systemDefault()
        private val dateFmt = DateTimeFormatter.ISO_LOCAL_DATE
        private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")

        fun dateLabel(millis: Long): String =
            Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().format(dateFmt)

        fun formatTime(millis: Long): String =
            Instant.ofEpochMilli(millis).atZone(zone).format(timeFmt)

        fun formatDate(millis: Long): String =
            Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().format(
                DateTimeFormatter.ofPattern("EEE, MMM d")
            )

        /** Rebuild millis from an existing value, replacing only the HH:mm, keeping the same calendar date. */
        fun withTime(originalMillis: Long, hour: Int, minute: Int): Long =
            Instant.ofEpochMilli(originalMillis).atZone(zone)
                .withHour(hour).withMinute(minute).withSecond(0).withNano(0)
                .toInstant().toEpochMilli()

        fun formatDuration(ms: Long): String {
            val totalMin = (ms / 60_000L).toInt()
            val h = totalMin / 60
            val m = totalMin % 60
            return if (h > 0) "${h}h ${m}m" else "${m}m"
        }
    }
}
