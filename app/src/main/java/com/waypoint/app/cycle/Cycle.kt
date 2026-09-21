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
    val dateLabel: String,  // "yyyy-MM-dd" of wake date — cycle ownership
    // Set when the user dismisses a "does this cycle look right?" staleness prompt, so it
    // doesn't nag again immediately — only once the cycle has stayed open STALE_REPROMPT_MS
    // past that acknowledgment too.
    val staleAcknowledgedAtMs: Long? = null
) {
    val isOpen: Boolean get() = nextWakeMillis == null
    val endMillis: Long get() = nextWakeMillis ?: System.currentTimeMillis()
    val totalDurationMs: Long get() = endMillis - wakeMillis
    val awakeDurationMs: Long get() = (sleepStartMillis ?: endMillis) - wakeMillis
    val sleepDurationMs: Long? get() =
        if (sleepStartMillis != null && nextWakeMillis != null) nextWakeMillis - sleepStartMillis
        else null

    /**
     * True when this cycle has been open far longer than a normal waking day — most likely
     * because automatic sleep detection missed it. Used to prompt the user to confirm or
     * manually close it rather than letting it silently drift for days.
     */
    fun isStale(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!isOpen) return false
        if (nowMs - wakeMillis < STALE_OPEN_MS) return false
        val ackAt = staleAcknowledgedAtMs ?: return true
        return nowMs - ackAt >= STALE_REPROMPT_MS
    }

    companion object {
        private val zone get() = ZoneId.systemDefault()
        private val dateFmt = DateTimeFormatter.ISO_LOCAL_DATE
        private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")

        // A cycle open this long without closing is very unlikely to be a genuine single
        // waking day for anyone — flag it for the user to confirm rather than let it drift.
        const val STALE_OPEN_MS = 20 * 3600_000L      // 20 hours
        // After the user says "looks right", wait this long before asking again.
        const val STALE_REPROMPT_MS = 6 * 3600_000L   // 6 hours

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

        /**
         * Rebuild millis from an existing value, replacing only the calendar date.
         * [utcDateMs] is the UTC-midnight millis returned by Material3's DatePicker.
         */
        fun withDate(originalMillis: Long, utcDateMs: Long): Long {
            val selected = Instant.ofEpochMilli(utcDateMs)
                .atZone(java.time.ZoneOffset.UTC).toLocalDate()
            return Instant.ofEpochMilli(originalMillis).atZone(zone)
                .withYear(selected.year)
                .withMonth(selected.monthValue)
                .withDayOfMonth(selected.dayOfMonth)
                .toInstant().toEpochMilli()
        }

        fun formatDuration(ms: Long): String {
            val totalMin = (ms / 60_000L).toInt()
            val h = totalMin / 60
            val m = totalMin % 60
            return if (h > 0) "${h}h ${m}m" else "${m}m"
        }
    }
}
