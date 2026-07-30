package com.waypoint.app.home

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import com.waypoint.app.AppLogger
import com.waypoint.app.LogEntry
import com.waypoint.app.LogLevel
import com.waypoint.app.signal.HealthConnectAvailability
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsTab(onPermissionGranted: () -> Unit) {
    var showLogs by remember { mutableStateOf(false) }

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

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 12.dp),
            color = MaterialTheme.colorScheme.outlineVariant
        )

        Text(
            text = "Developer",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "App event log — script calls, schedule writes, and errors",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        Button(onClick = { showLogs = true }) { Text("View Logs") }

        Spacer(Modifier.height(32.dp))
    }

    if (showLogs) {
        LogViewerDialog(onDismiss = { showLogs = false })
    }
}

// ── Log viewer ────────────────────────────────────────────────────────────────

@Composable
private fun LogViewerDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val current by AppLogger.entries.collectAsState()
    val last    by AppLogger.lastSession.collectAsState()
    val listState = rememberLazyListState()

    val totalSize = last.size + current.size
    LaunchedEffect(totalSize) {
        if (totalSize > 0) listState.scrollToItem(totalSize - 1 + if (last.isNotEmpty()) 2 else 0)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                // ── Toolbar ───────────────────────────────────────────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(onClick = onDismiss) { Text("Close") }
                    Text(
                        text = "Logs",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                    )
                    TextButton(onClick = { AppLogger.clearAll() }) { Text("Clear") }
                    TextButton(onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("Waypoint Logs", AppLogger.copyText()))
                    }) { Text("Copy") }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // ── Log entries ───────────────────────────────────────────────
                if (last.isEmpty() && current.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "No log entries yet",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        if (last.isNotEmpty()) {
                            item {
                                SessionHeader(
                                    label = "Previous Session",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            items(last) { entry -> LogEntryRow(entry) }
                            item {
                                SessionHeader(
                                    label = "Current Session",
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        items(current) { entry -> LogEntryRow(entry) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionHeader(label: String, color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f), color = color.copy(alpha = 0.3f))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            letterSpacing = 0.5.sp
        )
        HorizontalDivider(modifier = Modifier.weight(1f), color = color.copy(alpha = 0.3f))
    }
}

@Composable
private fun LogEntryRow(entry: LogEntry) {
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val color = when (entry.level) {
        LogLevel.E -> Color(0xFFE53935)
        LogLevel.W -> Color(0xFFFF8F00)
        LogLevel.I -> MaterialTheme.colorScheme.onSurface
    }
    val levelTag = when (entry.level) {
        LogLevel.E -> "E"
        LogLevel.W -> "W"
        LogLevel.I -> "I"
    }
    Text(
        text = "[${fmt.format(Date(entry.millis))}] $levelTag/${entry.tag}: ${entry.message}",
        style = MaterialTheme.typography.bodySmall.copy(
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 16.sp
        ),
        color = color,
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)
    )
}

// ── Calendar ──────────────────────────────────────────────────────────────────

@Composable
private fun CalendarIntegration(onPermissionGranted: () -> Unit) {
    val context = LocalContext.current

    var readGranted by remember {
        mutableStateOf(context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED)
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        readGranted = perms[Manifest.permission.READ_CALENDAR] == true
        if (readGranted) onPermissionGranted()
    }

    IntegrationRow(
        title = "Calendar",
        description = "Events visible in Plan view · scripts can read events and create/delete events via signals.calendar",
        granted = readGranted,
        onConnect = {
            launcher.launch(arrayOf(
                Manifest.permission.READ_CALENDAR,
                Manifest.permission.WRITE_CALENDAR
            ))
        }
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
