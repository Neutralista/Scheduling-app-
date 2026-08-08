package com.waypoint.app.home

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/** Confirmation dialog for task or event deletion. */
@Composable
fun TaskDeleteDialog(
    taskTitle: String,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete \"$taskTitle\"?") },
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
