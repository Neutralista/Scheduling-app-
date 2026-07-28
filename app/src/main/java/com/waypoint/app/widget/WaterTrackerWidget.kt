package com.waypoint.app.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * A counter-style widget: tap + to log a glass, − to undo.
 * doneToday is computed (count >= goal) rather than set directly.
 * values["glasses"] carries the running count so the framework stores
 * one number, not just a boolean.
 *
 * Registration example:
 *   HabitWidgetRegistry.register(
 *       WaterTrackerWidget(id = "water", displayName = "Drink water"),
 *       signalSources
 *   )
 */
class WaterTrackerWidget(
    override val id: String,
    override val displayName: String = "Drink water",
    val goalGlasses: Int = 8,
    override val uiConfig: WidgetUiConfig = WidgetUiConfig(size = WidgetSize.FULL_CARD)
) : HabitWidget {

    private val glassesKey = "glasses"

    @Composable
    override fun Content(state: WidgetState?, onStateChange: (WidgetState) -> Unit) {
        val current = state ?: WidgetState()
        val count = (current.values[glassesKey] ?: 0.0).toInt()
        val done = count >= goalGlasses

        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(
                    if (done) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                )
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (done) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "$count / $goalGlasses",
                    style = MaterialTheme.typography.titleLarge,
                    color = if (done) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurface
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(goalGlasses) { i ->
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(
                                if (i < count) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                            )
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                OutlinedButton(
                    onClick = {
                        if (count > 0) {
                            val next = count - 1
                            onStateChange(current.copy(
                                doneToday = next >= goalGlasses,
                                values = current.values + (glassesKey to next.toDouble())
                            ))
                        }
                    },
                    enabled = count > 0
                ) { Text("−") }

                OutlinedButton(
                    onClick = {
                        val next = count + 1
                        onStateChange(current.copy(
                            doneToday = next >= goalGlasses,
                            values = current.values + (glassesKey to next.toDouble())
                        ))
                    }
                ) { Text("+") }
            }
        }
    }
}
