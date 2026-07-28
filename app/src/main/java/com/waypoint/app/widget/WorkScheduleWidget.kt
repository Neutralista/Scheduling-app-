package com.waypoint.app.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.waypoint.app.signal.WorkScheduleConfig
import com.waypoint.app.signal.WorkScheduleSignals
import com.waypoint.app.signal.calendarDayToIso
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Calendar
import java.util.Locale

class WorkScheduleWidget(
    override val id: String,
    override val displayName: String = "Work schedule",
    override val uiConfig: WidgetUiConfig = WidgetUiConfig(size = WidgetSize.FULL_CARD)
) : HabitWidget {

    private var workSchedule: WorkScheduleSignals? = null

    override fun onAttached(signals: SignalSources) {
        workSchedule = signals.workSchedule
    }

    @Composable
    override fun Content(state: WidgetState?, onStateChange: (WidgetState) -> Unit) {
        val ws = workSchedule ?: return
        var config by remember { mutableStateOf(ws.getConfig()) }
        val scope = rememberCoroutineScope()

        val today = Calendar.getInstance()
        val todayIso = calendarDayToIso(today.get(Calendar.DAY_OF_WEEK))
        val isWorkToday = ws.isWorkDay()

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
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
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                StatusBadge(isWork = isWorkToday)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                DayOfWeek.values().forEach { dow ->
                    val isoDay = dow.value   // 1 = Mon … 7 = Sun
                    val isWork = config.weekdayDefaults[isoDay] ?: (isoDay in 1..5)
                    DayChip(
                        label = dow.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                        isWork = isWork,
                        isToday = isoDay == todayIso,
                        onClick = {
                            scope.launch {
                                ws.setWeekday(isoDay, !isWork)
                                config = ws.getConfig()
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(isWork: Boolean) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (isWork) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp
    ) {
        Text(
            text = if (isWork) "Work day" else "Day off",
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = if (isWork) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DayChip(label: String, isWork: Boolean, isToday: Boolean, onClick: () -> Unit) {
    val bg = if (isWork) MaterialTheme.colorScheme.primaryContainer
             else MaterialTheme.colorScheme.surface
    val fg = if (isWork) MaterialTheme.colorScheme.onPrimaryContainer
             else MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(bg)
            .then(
                if (isToday) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                else Modifier
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            color = fg
        )
    }
}
