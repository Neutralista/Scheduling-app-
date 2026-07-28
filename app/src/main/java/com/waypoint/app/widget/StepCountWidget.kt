package com.waypoint.app.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.waypoint.app.signal.HealthConnectAvailability
import java.time.LocalDate
import java.time.ZoneId

/**
 * Reads today's step count from Health Connect and marks the habit done
 * once the daily goal is reached. Uses values["steps"] for the count so
 * the framework stores one number, not just a boolean.
 *
 * Requires READ_STEPS in AndroidManifest.xml and granted at runtime via
 * the Health Connect permission flow. The widget gracefully handles the
 * not-available / not-permitted states rather than crashing.
 *
 * Registration example:
 *   HabitWidgetRegistry.register(
 *       StepCountWidget(id = "steps", displayName = "Step goal"),
 *       signalSources
 *   )
 */
class StepCountWidget(
    override val id: String,
    override val displayName: String = "Step goal",
    val dailyGoal: Int = 10_000,
    override val uiConfig: WidgetUiConfig = WidgetUiConfig(size = WidgetSize.FULL_CARD)
) : HabitWidget {

    private var signals: SignalSources? = null
    private val stepsKey = "steps"
    private val readStepsPermission = HealthPermission.getReadPermission(StepsRecord::class)

    override fun onAttached(signals: SignalSources) {
        this.signals = signals
    }

    @Composable
    override fun Content(state: WidgetState?, onStateChange: (WidgetState) -> Unit) {
        val steps = (state?.values?.get(stepsKey) ?: 0.0).toInt()
        val progress = (steps.toFloat() / dailyGoal).coerceIn(0f, 1f)
        val done = steps >= dailyGoal
        val hc = signals?.healthConnect

        LaunchedEffect(Unit) {
            hc ?: return@LaunchedEffect
            if (hc.availability != HealthConnectAvailability.AVAILABLE) return@LaunchedEffect
            if (!hc.hasPermission(readStepsPermission)) return@LaunchedEffect

            val zone = ZoneId.systemDefault()
            val today = LocalDate.now()
            val result = hc.readRecords(
                ReadRecordsRequest(
                    recordType = StepsRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(
                        today.atStartOfDay(zone).toInstant(),
                        today.plusDays(1).atStartOfDay(zone).toInstant()
                    )
                )
            )
            val total = result.records.sumOf { it.count }.toInt()
            onStateChange(WidgetState(
                doneToday = total >= dailyGoal,
                values = mapOf(stepsKey to total.toDouble())
            ))
        }

        val containerColor = if (done) MaterialTheme.colorScheme.primaryContainer
                             else MaterialTheme.colorScheme.surfaceVariant
        val contentColor = if (done) MaterialTheme.colorScheme.onPrimaryContainer
                           else MaterialTheme.colorScheme.onSurfaceVariant

        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(containerColor)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Column {
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.titleMedium,
                        color = contentColor
                    )
                    Text(
                        text = when {
                            hc?.availability != HealthConnectAvailability.AVAILABLE ->
                                "Health Connect unavailable"
                            else -> "via Health Connect"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = 0.6f)
                    )
                }
                Text(
                    text = "%,d".format(steps),
                    style = MaterialTheme.typography.displaySmall,
                    color = contentColor
                )
            }

            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
            )

            Text(
                text = if (done) "Goal reached · ${"%,d".format(dailyGoal)} steps"
                       else "${"%,d".format(dailyGoal - steps)} to go · Goal ${"%,d".format(dailyGoal)}",
                style = MaterialTheme.typography.bodySmall,
                color = contentColor.copy(alpha = 0.7f)
            )
        }
    }
}
