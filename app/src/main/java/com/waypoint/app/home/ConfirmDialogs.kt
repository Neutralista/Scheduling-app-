package com.waypoint.app.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Confirmation dialog for task deletion.
 * [onSkip] — when non-null, shows a "Skip today" button that hides the task for the current
 *             scheduling cycle without permanently deleting it.
 * [hasChainTriggers] — when true, adds a warning that chain targets won't fire today if skipped.
 */
@Composable
fun TaskDeleteDialog(
    taskTitle: String,
    hasChainTriggers: Boolean = false,
    onSkip: (() -> Unit)?,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove \"$taskTitle\"?") },
        text = {
            val body = if (onSkip != null) {
                "Skip today removes it from today's schedule only — it comes back tomorrow.\n\nDelete removes it permanently." +
                    if (hasChainTriggers) "\n\nNote: skipping will prevent any chained tasks from being triggered today." else ""
            } else {
                "This will permanently remove the task."
            }
            Text(body, style = MaterialTheme.typography.bodyMedium)
        },
        confirmButton = {
            TextButton(onClick = { onDelete(); onDismiss() }) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                if (onSkip != null) {
                    TextButton(onClick = { onSkip(); onDismiss() }) { Text("Skip today") }
                }
            }
        }
    )
}

/** Confirmation dialog for block or block-task deletion (no skip option). */
@Composable
fun BlockDeleteDialog(
    itemLabel: String,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete \"$itemLabel\"?") },
        text = { Text("This will permanently remove it.", style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(onClick = { onDelete(); onDismiss() }) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
