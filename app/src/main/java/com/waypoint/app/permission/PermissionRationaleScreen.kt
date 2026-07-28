package com.waypoint.app.permission

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Generic permission rationale screen used by any widget (or the framework
 * itself) that needs to explain a permission before asking for it.
 *
 * Two modes:
 *  - RUNTIME: shows a "Grant permission" button that triggers the caller's
 *    grant flow (Health Connect, camera, etc.). The caller is responsible
 *    for launching the actual permission dialog via onGrant().
 *  - SETTINGS_REDIRECT: shows an "Open Settings" button that deep-links to
 *    the specific settings page. Used for PACKAGE_USAGE_STATS, which cannot
 *    be granted via a runtime dialog.
 */
enum class PermissionType { RUNTIME, SETTINGS_REDIRECT }

@Composable
fun PermissionRationaleScreen(
    title: String,
    rationale: String,
    permissionType: PermissionType,
    settingsAction: String = Settings.ACTION_USAGE_ACCESS_SETTINGS,
    onGrant: () -> Unit = {},
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text = rationale,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(32.dp))

        Button(
            onClick = {
                when (permissionType) {
                    PermissionType.RUNTIME -> onGrant()
                    PermissionType.SETTINGS_REDIRECT -> {
                        context.startActivity(
                            Intent(settingsAction).apply {
                                data = Uri.fromParts("package", context.packageName, null)
                            }
                        )
                    }
                }
            }
        ) {
            Text(
                if (permissionType == PermissionType.RUNTIME) "Grant permission"
                else "Open Settings"
            )
        }

        Spacer(Modifier.height(8.dp))

        TextButton(onClick = onDismiss) {
            Text("Not now")
        }
    }
}
