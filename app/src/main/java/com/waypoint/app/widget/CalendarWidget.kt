package com.waypoint.app.widget

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.waypoint.app.signal.CalendarEvent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class CalendarWidget(
    override val id: String,
    override val displayName: String = "Today",
    override val uiConfig: WidgetUiConfig = WidgetUiConfig(size = WidgetSize.FULL_CARD)
) : HabitWidget {

    private var signals: SignalSources? = null

    override fun onAttached(signals: SignalSources) {
        this.signals = signals
    }

    @Composable
    override fun Content(state: WidgetState?, onStateChange: (WidgetState) -> Unit) {
        val calendar = signals?.calendar
        var events by remember { mutableStateOf<List<CalendarEvent>>(emptyList()) }
        var hasPermission by remember { mutableStateOf(calendar?.hasPermission() == true) }

        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted -> hasPermission = granted }

        LaunchedEffect(hasPermission) {
            if (hasPermission) {
                events = calendar?.todayEvents() ?: emptyList()
            }
        }

        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = displayName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            when {
                !hasPermission -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Calendar access lets Waypoint show your day here.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                        Button(
                            onClick = { permissionLauncher.launch(Manifest.permission.READ_CALENDAR) }
                        ) { Text("Grant access") }
                    }
                }
                events.isEmpty() -> Text(
                    text = "Nothing scheduled today",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
                else -> {
                    events.take(8).forEach { EventRow(it) }
                    if (events.size > 8) {
                        Text(
                            text = "+${events.size - 8} more",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EventRow(event: CalendarEvent) {
    val fmt = DateTimeFormatter.ofPattern("h:mm a")
    val zone = ZoneId.systemDefault()

    val timeLabel = if (event.allDay) {
        "All day"
    } else {
        val start = Instant.ofEpochMilli(event.startMillis).atZone(zone).toLocalTime()
        val end   = Instant.ofEpochMilli(event.endMillis).atZone(zone).toLocalTime()
        "${start.format(fmt)} – ${end.format(fmt)}"
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        val dotColor = if (event.calendarColor != 0) Color(event.calendarColor)
                       else MaterialTheme.colorScheme.primary
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = event.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            Text(
                text = timeLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
}
