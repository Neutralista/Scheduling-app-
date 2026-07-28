package com.waypoint.app.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * The simplest possible habit widget: tap to mark done today, tap again
 * to undo. This is the pattern to copy when adding a new habit type.
 */
class ChecklistHabitWidget(
    override val id: String,
    override val displayName: String,
    override val uiConfig: WidgetUiConfig = WidgetUiConfig(size = WidgetSize.WIDE_ROW)
) : HabitWidget {

    @Composable
    override fun Content(state: WidgetState?, onStateChange: (WidgetState) -> Unit) {
        val done = state?.doneToday ?: false
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(
                    if (done) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                )
                .clickable { onStateChange(WidgetState(doneToday = !done)) }
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(
                text = if (done) "✓" else "○",
                style = MaterialTheme.typography.titleMedium,
                color = if (done) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = displayName,
                style = MaterialTheme.typography.bodyLarge,
                color = if (done) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
