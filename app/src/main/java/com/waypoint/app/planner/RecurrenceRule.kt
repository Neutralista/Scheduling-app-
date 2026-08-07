package com.waypoint.app.planner

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

@Serializable
sealed class RecurrenceRule {

    @Serializable @SerialName("one_off")
    data class OneOff(val date: String) : RecurrenceRule()

    @Serializable @SerialName("days_of_week")
    data class DaysOfWeek(val days: List<Int>) : RecurrenceRule()

    @Serializable @SerialName("every_n_days")
    data class EveryNDays(val n: Int, val anchorDate: String) : RecurrenceRule()

    @Serializable @SerialName("every_n_weeks")
    data class EveryNWeeks(val n: Int, val anchorDate: String) : RecurrenceRule()

    @Serializable @SerialName("every_n_months")
    data class EveryNMonths(val n: Int, val anchorDate: String) : RecurrenceRule()

    @Serializable @SerialName("n_times_per_period")
    data class NTimesPerPeriod(val count: Int, val periodDays: Int, val anchorDate: String) : RecurrenceRule()
}

fun RecurrenceRule.occursOn(date: LocalDate): Boolean {
    return when (this) {
        is RecurrenceRule.OneOff ->
            date.toString() == this.date

        is RecurrenceRule.DaysOfWeek ->
            date.dayOfWeek.value in this.days

        is RecurrenceRule.EveryNDays -> {
            val anchor = runCatching { LocalDate.parse(anchorDate) }.getOrNull() ?: return false
            val diff = ChronoUnit.DAYS.between(anchor, date)
            diff >= 0 && diff % n == 0L
        }

        is RecurrenceRule.EveryNWeeks -> {
            val anchor = runCatching { LocalDate.parse(anchorDate) }.getOrNull() ?: return false
            val diff = ChronoUnit.DAYS.between(anchor, date)
            diff >= 0 && diff % (n * 7L) == 0L
        }

        is RecurrenceRule.EveryNMonths -> {
            val anchor = runCatching { LocalDate.parse(anchorDate) }.getOrNull() ?: return false
            if (date.dayOfMonth != anchor.dayOfMonth) return false
            val months = ChronoUnit.MONTHS.between(anchor, date)
            months >= 0 && months % n == 0L
        }

        is RecurrenceRule.NTimesPerPeriod -> {
            val anchor = runCatching { LocalDate.parse(anchorDate) }.getOrNull() ?: return false
            val daysSince = ChronoUnit.DAYS.between(anchor, date)
            if (daysSince < 0) return false
            val dayInPeriod = (daysSince % periodDays).toInt()
            val spacing = periodDays.toDouble() / count
            (0 until count).any { k -> dayInPeriod == (k * spacing).roundToInt() }
        }
    }
}

fun RecurrenceRule.label(): String = when (this) {
    is RecurrenceRule.OneOff -> "Once"
    is RecurrenceRule.DaysOfWeek -> {
        val names = mapOf(1 to "Mon", 2 to "Tue", 3 to "Wed", 4 to "Thu",
            5 to "Fri", 6 to "Sat", 7 to "Sun")
        days.sorted().mapNotNull { names[it] }.joinToString(", ").ifEmpty { "Days of week" }
    }
    is RecurrenceRule.EveryNDays  -> if (n == 1) "Every day"   else "Every $n days"
    is RecurrenceRule.EveryNWeeks -> if (n == 1) "Every week"  else "Every $n weeks"
    is RecurrenceRule.EveryNMonths-> if (n == 1) "Every month" else "Every $n months"
    is RecurrenceRule.NTimesPerPeriod -> if (count == 1) "Once every $periodDays days" else "$count times every $periodDays days"
}
