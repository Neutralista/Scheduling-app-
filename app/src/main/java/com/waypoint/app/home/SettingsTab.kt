package com.waypoint.app.home

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import com.waypoint.app.signal.HealthConnectAvailability

@Composable
fun SettingsTab(onPermissionGranted: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Integrations",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(12.dp))

        CalendarIntegration(onPermissionGranted)

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 12.dp),
            color = MaterialTheme.colorScheme.outlineVariant
        )

        HealthConnectIntegration(onPermissionGranted)

        Spacer(Modifier.height(32.dp))
    }
}

// ── Calendar ──────────────────────────────────────────────────────────────────

@Composable
private fun CalendarIntegration(onPermissionGranted: () -> Unit) {
    val context = LocalContext.current

    var granted by remember {
        mutableStateOf(
            context.checkSelfPermission(Manifest.permission.READ_CALENDAR) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        granted = isGranted
        if (isGranted) onPermissionGranted()
    }

    IntegrationRow(
        title = "Calendar",
        description = "Read today's events in scripts via signals.calendar.events",
        granted = granted,
        onConnect = { launcher.launch(Manifest.permission.READ_CALENDAR) }
    )
}

// ── Health Connect ────────────────────────────────────────────────────────────

@Composable
private fun HealthConnectIntegration(onPermissionGranted: () -> Unit) {
    val context = LocalContext.current

    val availability = remember {
        when (HealthConnectClient.getSdkStatus(context)) {
            HealthConnectClient.SDK_AVAILABLE ->
                HealthConnectAvailability.AVAILABLE
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
                HealthConnectAvailability.NOT_INSTALLED
            else ->
                HealthConnectAvailability.NOT_SUPPORTED
        }
    }

    Column(Modifier.fillMaxWidth()) {
        Text(
            text = "Health Connect",
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = "Read step count in scripts via signals.health.steps",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))

        when (availability) {
            HealthConnectAvailability.NOT_SUPPORTED -> {
                Text(
                    text = "Not supported on this device",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            HealthConnectAvailability.NOT_INSTALLED -> {
                Text(
                    text = "Health Connect app is not installed",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = {
                    context.startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("market://details?id=com.google.android.apps.healthdata")
                        )
                    )
                }) {
                    Text("Install Health Connect")
                }
            }
            HealthConnectAvailability.AVAILABLE -> {
                // Extracted into its own composable so rememberLauncherForActivityResult
                // is called unconditionally with the HC client's own contract.
                HcConnectButton(onPermissionGranted)
            }
        }
    }
}

@Composable
private fun HcConnectButton(onPermissionGranted: () -> Unit) {
    val context = LocalContext.current
    val client = remember { HealthConnectClient.getOrCreate(context) }
    var granted by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        granted = client.permissionController
            .getGrantedPermissions()
            .contains("android.permission.health.READ_STEPS")
    }

    val launcher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { grantedPermissions ->
        val isGranted = "android.permission.health.READ_STEPS" in grantedPermissions
        granted = isGranted
        if (isGranted) onPermissionGranted()
    }

    if (granted) {
        StatusChip(connected = true)
    } else {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatusChip(connected = false)
            Button(onClick = {
                launcher.launch(setOf("android.permission.health.READ_STEPS"))
            }) {
                Text("Connect")
            }
        }
    }
}

// ── Shared row layout ─────────────────────────────────────────────────────────

@Composable
private fun IntegrationRow(
    title: String,
    description: String,
    granted: Boolean,
    onConnect: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        if (granted) {
            StatusChip(connected = true)
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatusChip(connected = false)
                Button(onClick = onConnect) { Text("Connect") }
            }
        }
    }
}

@Composable
private fun StatusChip(connected: Boolean) {
    Text(
        text = if (connected) "● Connected" else "○ Not connected",
        style = MaterialTheme.typography.labelMedium,
        color = if (connected)
            MaterialTheme.colorScheme.primary
        else
            MaterialTheme.colorScheme.onSurfaceVariant
    )
}
