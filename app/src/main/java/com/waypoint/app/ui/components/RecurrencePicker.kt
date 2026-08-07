package com.waypoint.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.waypoint.app.planner.RecurrenceRule
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class RecurrenceMode { ONCE, DAYS_OF_WEEK, EVERY_N_DAYS, EVERY_N_WEEKS, EVERY_N_MONTHS, N_TIMES_PER_PERIOD }

private val _DAY_LABELS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
private val _DATE_FMT   = DateTimeFormatter.ofPattern("MMM d, yyyy")

/**
 * Reusable recurrence picker.
 * [includeDaysOfWeek] shows the "Days of week" mode; set false when the caller
 * already handles day-of-week selection separately.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RecurrencePicker(
    value: RecurrenceRule?,
    onChange: (RecurrenceRule) -> Unit,
    includeDaysOfWeek: Boolean = true
) {
    val today = remember { LocalDate.now() }

    var mode by remember(value) {
        mutableStateOf(when (value) {
            is RecurrenceRule.OneOff         -> RecurrenceMode.ONCE
            is RecurrenceRule.DaysOfWeek     -> RecurrenceMode.DAYS_OF_WEEK
            is RecurrenceRule.EveryNDays     -> RecurrenceMode.EVERY_N_DAYS
            is RecurrenceRule.EveryNWeeks    -> RecurrenceMode.EVERY_N_WEEKS
            is RecurrenceRule.EveryNMonths   -> RecurrenceMode.EVERY_N_MONTHS
            is RecurrenceRule.NTimesPerPeriod -> RecurrenceMode.N_TIMES_PER_PERIOD
            null -> if (includeDaysOfWeek) RecurrenceMode.DAYS_OF_WEEK else RecurrenceMode.EVERY_N_DAYS
        })
    }

    var onceDate by remember(value) {
        mutableStateOf(
            (value as? RecurrenceRule.OneOff)
                ?.let { runCatching { LocalDate.parse(it.date) }.getOrNull() }
                ?: today
        )
    }
    var showOncePicker by remember { mutableStateOf(false) }

    val selectedDays = remember(value) {
        mutableStateListOf<Int>().also {
            it.addAll((value as? RecurrenceRule.DaysOfWeek)?.days ?: emptyList())
        }
    }

    var intervalN by remember(value) {
        mutableIntStateOf(when (value) {
            is RecurrenceRule.EveryNDays   -> value.n
            is RecurrenceRule.EveryNWeeks  -> value.n
            is RecurrenceRule.EveryNMonths -> value.n
            else -> 1
        })
    }
    var countN by remember(value) {
        mutableIntStateOf((value as? RecurrenceRule.NTimesPerPeriod)?.count ?: 2)
    }
    var periodDays by remember(value) {
        mutableIntStateOf((value as? RecurrenceRule.NTimesPerPeriod)?.periodDays ?: 30)
    }
    var anchorDate by remember(value) {
        mutableStateOf(when (value) {
            is RecurrenceRule.EveryNDays      -> runCatching { LocalDate.parse(value.anchorDate) }.getOrNull() ?: today
            is RecurrenceRule.EveryNWeeks     -> runCatching { LocalDate.parse(value.anchorDate) }.getOrNull() ?: today
            is RecurrenceRule.EveryNMonths    -> runCatching { LocalDate.parse(value.anchorDate) }.getOrNull() ?: today
            is RecurrenceRule.NTimesPerPeriod -> runCatching { LocalDate.parse(value.anchorDate) }.getOrNull() ?: today
            else -> today
        })
    }
    var showAnchorPicker by remember { mutableStateOf(false) }

    fun maxNForMode(m: RecurrenceMode) = when (m) {
        RecurrenceMode.EVERY_N_DAYS   -> 100
        RecurrenceMode.EVERY_N_WEEKS  -> 52
        RecurrenceMode.EVERY_N_MONTHS -> 24
        else -> 100
    }

    fun currentRule(): RecurrenceRule = when (mode) {
        RecurrenceMode.ONCE              -> RecurrenceRule.OneOff(onceDate.toString())
        RecurrenceMode.DAYS_OF_WEEK      -> RecurrenceRule.DaysOfWeek(selectedDays.sorted())
        RecurrenceMode.EVERY_N_DAYS      -> RecurrenceRule.EveryNDays(intervalN, anchorDate.toString())
        RecurrenceMode.EVERY_N_WEEKS     -> RecurrenceRule.EveryNWeeks(intervalN, anchorDate.toString())
        RecurrenceMode.EVERY_N_MONTHS    -> RecurrenceRule.EveryNMonths(intervalN, anchorDate.toString())
        RecurrenceMode.N_TIMES_PER_PERIOD -> RecurrenceRule.NTimesPerPeriod(countN, periodDays, anchorDate.toString())
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val modes = buildList<Pair<RecurrenceMode, String>> {
            if (includeDaysOfWeek) add(RecurrenceMode.DAYS_OF_WEEK to "Days of week")
            add(RecurrenceMode.EVERY_N_DAYS      to "Every N days")
            add(RecurrenceMode.EVERY_N_WEEKS     to "Every N weeks")
            add(RecurrenceMode.EVERY_N_MONTHS    to "Every N months")
            add(RecurrenceMode.N_TIMES_PER_PERIOD to "N times/period")
            add(RecurrenceMode.ONCE              to "Once")
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement   = Arrangement.spacedBy(4.dp)
        ) {
            modes.forEach { (m, label) ->
                FilterChip(
                    selected = mode == m,
                    onClick  = {
                        val maxN = maxNForMode(m)
                        if (intervalN > maxN) intervalN = maxN
                        mode = m
                        onChange(currentRule())
                    },
                    label    = { Text(label) }
                )
            }
        }

        when (mode) {
            RecurrenceMode.ONCE -> {
                TextButton(onClick = { showOncePicker = true }) {
                    Text("Date: ${onceDate.format(_DATE_FMT)}")
                }
            }
            RecurrenceMode.DAYS_OF_WEEK -> {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement   = Arrangement.spacedBy(4.dp)
                ) {
                    _DAY_LABELS.forEachIndexed { idx, lbl ->
                        val dow = idx + 1
                        FilterChip(
                            selected = dow in selectedDays,
                            onClick  = {
                                if (dow in selectedDays) selectedDays.remove(dow) else selectedDays.add(dow)
                                onChange(currentRule())
                            },
                            label    = { Text(lbl) }
                        )
                    }
                }
            }
            RecurrenceMode.N_TIMES_PER_PERIOD -> {
                val countLabel = if (countN == 1) "Once" else "$countN times"
                val periodLabel = "every $periodDays day${if (periodDays == 1) "" else "s"}"
                Row(
                    verticalAlignment     = Alignment.CenterVertically,
                    modifier              = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "$countLabel $periodLabel",
                        style    = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                }
                Text("Times per period (1–10)", style = MaterialTheme.typography.labelSmall)
                Slider(
                    value                = countN.toFloat(),
                    onValueChange        = { countN = it.toInt() },
                    onValueChangeFinished = { onChange(currentRule()) },
                    valueRange           = 1f..10f,
                    steps                = 8,
                    modifier             = Modifier.fillMaxWidth()
                )
                Text("Period length: $periodDays days (1–90)", style = MaterialTheme.typography.labelSmall)
                Slider(
                    value                = periodDays.toFloat(),
                    onValueChange        = { periodDays = it.toInt() },
                    onValueChangeFinished = { onChange(currentRule()) },
                    valueRange           = 1f..90f,
                    steps                = 88,
                    modifier             = Modifier.fillMaxWidth()
                )
                Row(
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Starting from:", style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { showAnchorPicker = true }) {
                        Text(anchorDate.format(_DATE_FMT))
                    }
                }
            }
            RecurrenceMode.EVERY_N_DAYS, RecurrenceMode.EVERY_N_WEEKS, RecurrenceMode.EVERY_N_MONTHS -> {
                val maxN = maxNForMode(mode)
                val unit = when (mode) {
                    RecurrenceMode.EVERY_N_DAYS   -> "day"
                    RecurrenceMode.EVERY_N_WEEKS  -> "week"
                    else                          -> "month"
                }
                val displayLabel = if (intervalN == 1) "Every $unit" else "Every $intervalN ${unit}s"

                Row(
                    verticalAlignment     = Alignment.CenterVertically,
                    modifier              = Modifier.fillMaxWidth()
                ) {
                    Text(
                        displayLabel,
                        style    = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.width(160.dp)
                    )
                }
                Slider(
                    value                = intervalN.toFloat(),
                    onValueChange        = { intervalN = it.toInt() },
                    onValueChangeFinished = { onChange(currentRule()) },
                    valueRange           = 1f..maxN.toFloat(),
                    steps                = maxN - 2,
                    modifier             = Modifier.fillMaxWidth()
                )
                Row(
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Starting from:", style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { showAnchorPicker = true }) {
                        Text(anchorDate.format(_DATE_FMT))
                    }
                }
            }
        }
    }

    if (showOncePicker) {
        val dpState = rememberDatePickerState(
            initialSelectedDateMillis = onceDate.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showOncePicker = false },
            confirmButton    = {
                TextButton(onClick = {
                    dpState.selectedDateMillis?.let { ms ->
                        onceDate = Instant.ofEpochMilli(ms).atZone(ZoneId.of("UTC")).toLocalDate()
                    }
                    showOncePicker = false
                    onChange(currentRule())
                }) { Text("OK") }
            },
            dismissButton    = { TextButton(onClick = { showOncePicker = false }) { Text("Cancel") } }
        ) { DatePicker(state = dpState) }
    }

    if (showAnchorPicker) {
        val dpState = rememberDatePickerState(
            initialSelectedDateMillis = anchorDate.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showAnchorPicker = false },
            confirmButton    = {
                TextButton(onClick = {
                    dpState.selectedDateMillis?.let { ms ->
                        anchorDate = Instant.ofEpochMilli(ms).atZone(ZoneId.of("UTC")).toLocalDate()
                    }
                    showAnchorPicker = false
                    onChange(currentRule())
                }) { Text("OK") }
            },
            dismissButton    = { TextButton(onClick = { showAnchorPicker = false }) { Text("Cancel") } }
        ) { DatePicker(state = dpState) }
    }
}
