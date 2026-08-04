package com.waypoint.app.signal

import kotlinx.serialization.Serializable

@Serializable
data class ShiftTime(val hour: Int, val minute: Int) {
    val displayString: String get() = "%02d:%02d".format(hour, minute)
    val totalMinutes: Int get() = hour * 60 + minute

    companion object {
        fun parse(text: String): ShiftTime? {
            val parts = text.trim().split(":").takeIf { it.size == 2 } ?: return null
            val h = parts[0].toIntOrNull() ?: return null
            val m = parts[1].toIntOrNull() ?: return null
            if (h !in 0..23 || m !in 0..59) return null
            return ShiftTime(h, m)
        }
    }
}
