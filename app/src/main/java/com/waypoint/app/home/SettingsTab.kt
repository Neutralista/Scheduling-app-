package com.waypoint.app.home

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.waypoint.app.ui.components.HsvColorPicker
import com.waypoint.app.ui.theme.AppTheme
import com.waypoint.app.ui.theme.BUILT_IN_THEMES
import com.waypoint.app.ui.theme.ThemeStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@Composable
fun SettingsTab(
    onPermissionGranted: () -> Unit,
    themeStore: ThemeStore? = null
) {
    val context = LocalContext.current
    var showLogs by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        Spacer(Modifier.height(16.dp))

        if (themeStore != null) {
            ThemesSection(themeStore = themeStore)
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 12.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )
        }

        Text(
            text = "Integrations",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(12.dp))

        ExactAlarmIntegration()

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 12.dp),
            color = MaterialTheme.colorScheme.outlineVariant
        )

        BatteryOptimizationIntegration()

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 12.dp),
            color = MaterialTheme.colorScheme.outlineVariant
        )

        FullScreenIntentIntegration()

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 12.dp),
            color = MaterialTheme.colorScheme.outlineVariant
        )

        CalendarIntegration(onPermissionGranted)

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 12.dp),
            color = MaterialTheme.colorScheme.outlineVariant
        )

        LocationIntegration(onPermissionGranted)

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 12.dp),
            color = MaterialTheme.colorScheme.outlineVariant
        )

        HealthConnectIntegration(onPermissionGranted)

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 12.dp),
            color = MaterialTheme.colorScheme.outlineVariant
        )

        // Collapsed by default — these are developer diagnostics, not settings most
        // people need day to day, so they shouldn't sit in the default scroll path.
        var advancedExpanded by remember { mutableStateOf(false) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { advancedExpanded = !advancedExpanded }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Advanced",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = if (advancedExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (advancedExpanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        AnimatedVisibility(visible = advancedExpanded) {
            Column {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "App event log — script calls, schedule writes, and errors",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { showLogs = true }) { Text("View Logs") }
                    OutlinedButton(onClick = {
                        val logFile = java.io.File(context.filesDir, "waypoint_last_session.log")
                        if (logFile.exists()) {
                            val uri = androidx.core.content.FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                logFile
                            )
                            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(android.content.Intent.createChooser(intent, "Share crash log"))
                        }
                    }) { Text("Share Crash Log") }
                }
            }
        }

        Spacer(Modifier.height(32.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(16.dp))

        val versionName = remember {
            runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrDefault("–")
        }
        Text(
            text = "Waypoint  v$versionName",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
        )
        Spacer(Modifier.height(24.dp))
    }

    if (showLogs) {
        LogViewerDialog(onDismiss = { showLogs = false })
    }
}

// ── Themes ────────────────────────────────────────────────────────────────────

@Composable
private fun ThemesSection(themeStore: ThemeStore) {
    val selectedId by themeStore.selectedThemeIdFlow.collectAsState()
    val darkOverride by themeStore.darkModeOverrideFlow.collectAsState()
    var customThemes by remember { mutableStateOf(themeStore.loadCustomThemes()) }
    var showAddSheet by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<AppTheme?>(null) }

    Text(
        text = "Themes",
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.onBackground
    )
    Spacer(Modifier.height(4.dp))
    Text(
        text = "Choose a colour scheme for the entire app",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(12.dp))

    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        (BUILT_IN_THEMES + customThemes).forEach { theme ->
            ThemeCard(
                theme = theme,
                isSelected = selectedId == theme.id,
                onClick = { themeStore.selectTheme(theme.id) },
                onEdit = { editTarget = theme },
                onDelete = if (!theme.isBuiltIn) {
                    {
                        themeStore.deleteCustomTheme(theme.id)
                        customThemes = themeStore.loadCustomThemes()
                    }
                } else null
            )
        }
        // Add custom theme card
        Box(
            modifier = Modifier
                .size(width = 110.dp, height = 80.dp)
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                .clickable { showAddSheet = true },
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("+", style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Custom", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    Spacer(Modifier.height(14.dp))

    Text(
        text = "Appearance",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            "System" to null,
            "Light"  to false,
            "Dark"   to true,
        ).forEach { (label, value) ->
            FilterChip(
                selected = darkOverride == value,
                onClick = { themeStore.setDarkModeOverride(value) },
                label = { Text(label) }
            )
        }
    }

    if (showAddSheet) {
        CustomThemeSheet(
            onDismiss = { showAddSheet = false },
            onSave = { theme ->
                themeStore.saveCustomTheme(theme)
                themeStore.selectTheme(theme.id)
                customThemes = themeStore.loadCustomThemes()
                showAddSheet = false
            }
        )
    }

    if (editTarget != null) {
        CustomThemeSheet(
            initial = editTarget,
            onDismiss = { editTarget = null },
            onSave = { theme ->
                themeStore.saveCustomTheme(theme)
                themeStore.selectTheme(theme.id)
                customThemes = themeStore.loadCustomThemes()
                editTarget = null
            }
        )
    }
}

@Composable
private fun ThemeCard(
    theme: AppTheme,
    isSelected: Boolean,
    onClick: () -> Unit,
    onEdit: () -> Unit = {},
    onDelete: (() -> Unit)? = null
) {
    val scheme = MaterialTheme.colorScheme
    val isDark = isSystemInDarkTheme()
    val borderColor = if (isSelected) scheme.primary else scheme.outlineVariant
    val borderWidth = if (isSelected) 2.dp else 1.dp
    val swatches = if (isDark) {
        listOf(Color(theme.darkPrimaryArgb), Color(theme.darkBackgroundArgb), Color(theme.darkAccentArgb))
    } else {
        listOf(Color(theme.lightPrimaryArgb), Color(theme.lightBackgroundArgb), Color(theme.lightAccentArgb))
    }

    Box(
        modifier = Modifier
            .size(width = 110.dp, height = 80.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(borderWidth, borderColor, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                swatches.forEach { c ->
                    Box(
                        Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(c)
                            .border(0.5.dp, scheme.outline.copy(alpha = 0.25f), CircleShape)
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = theme.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                if (isSelected) {
                    Text("✓", style = MaterialTheme.typography.labelSmall, color = scheme.primary)
                }
            }
        }
        Row(
            modifier = Modifier.align(Alignment.TopEnd),
            horizontalArrangement = Arrangement.End
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onEdit),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = "Edit ${theme.name}",
                    tint = scheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.size(15.dp)
                )
            }
            if (onDelete != null) {
                var showDeleteConfirm by remember { mutableStateOf(false) }
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .clickable { showDeleteConfirm = true },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete ${theme.name}",
                        tint = scheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.size(15.dp)
                    )
                }
                if (showDeleteConfirm) {
                    BlockDeleteDialog(
                        itemLabel = theme.name,
                        onDelete = onDelete,
                        onDismiss = { showDeleteConfirm = false }
                    )
                }
            }
        }
    }
}

@Composable
private fun CustomThemeSheet(
    initial: AppTheme? = null,
    onDismiss: () -> Unit,
    onSave: (AppTheme) -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var darkPrimaryArgb by remember { mutableIntStateOf(initial?.darkPrimaryArgb ?: 0xFF4FC3F7.toInt()) }
    var darkBackgroundArgb by remember { mutableIntStateOf(initial?.darkBackgroundArgb ?: 0xFF0B1623.toInt()) }
    var darkAccentArgb by remember { mutableIntStateOf(initial?.darkAccentArgb ?: 0xFFEF5350.toInt()) }
    var lightPrimaryArgb by remember { mutableIntStateOf(initial?.lightPrimaryArgb ?: 0xFF1565C0.toInt()) }
    var lightBackgroundArgb by remember { mutableIntStateOf(initial?.lightBackgroundArgb ?: 0xFFEFF5FF.toInt()) }
    var lightAccentArgb by remember { mutableIntStateOf(initial?.lightAccentArgb ?: 0xFFD32F2F.toInt()) }
    var nameError by remember { mutableStateOf(false) }

    val isForking = initial?.isBuiltIn == true
    val title = when {
        initial == null -> "Custom Theme"
        isForking -> "Fork Theme"
        else -> "Edit Theme"
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f).padding(start = 4.dp)
                    )
                    TextButton(onClick = {
                        if (name.isBlank()) { nameError = true; return@TextButton }
                        val savedId = if (initial != null && !isForking) initial.id else UUID.randomUUID().toString()
                        onSave(
                            AppTheme(
                                id = savedId,
                                name = name.trim(),
                                isBuiltIn = false,
                                darkPrimaryArgb = darkPrimaryArgb,
                                darkBackgroundArgb = darkBackgroundArgb,
                                darkAccentArgb = darkAccentArgb,
                                lightPrimaryArgb = lightPrimaryArgb,
                                lightBackgroundArgb = lightBackgroundArgb,
                                lightAccentArgb = lightAccentArgb,
                            )
                        )
                    }) { Text("Save") }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp)
                ) {
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it; nameError = false },
                        label = { Text("Theme name") },
                        isError = nameError,
                        supportingText = if (nameError) {
                            { Text("Name required") }
                        } else null,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(20.dp))
                    ThemeColorSection(
                        title = "Dark mode",
                        primaryArgb = darkPrimaryArgb, onPrimaryChange = { darkPrimaryArgb = it },
                        backgroundArgb = darkBackgroundArgb, onBackgroundChange = { darkBackgroundArgb = it },
                        accentArgb = darkAccentArgb, onAccentChange = { darkAccentArgb = it }
                    )
                    Spacer(Modifier.height(20.dp))
                    ThemeColorSection(
                        title = "Light mode",
                        primaryArgb = lightPrimaryArgb, onPrimaryChange = { lightPrimaryArgb = it },
                        backgroundArgb = lightBackgroundArgb, onBackgroundChange = { lightBackgroundArgb = it },
                        accentArgb = lightAccentArgb, onAccentChange = { lightAccentArgb = it }
                    )
                    Spacer(Modifier.height(32.dp))
                }
            }
        }
    }
}

@Composable
private fun ThemeColorSection(
    title: String,
    primaryArgb: Int, onPrimaryChange: (Int) -> Unit,
    backgroundArgb: Int, onBackgroundChange: (Int) -> Unit,
    accentArgb: Int, onAccentChange: (Int) -> Unit,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.onBackground
    )
    Spacer(Modifier.height(10.dp))
    ThemeColorSliders("Primary", primaryArgb, onPrimaryChange)
    Spacer(Modifier.height(12.dp))
    ThemeColorSliders("Background", backgroundArgb, onBackgroundChange)
    Spacer(Modifier.height(12.dp))
    ThemeColorSliders("Accent", accentArgb, onAccentChange)
}

@Composable
private fun ThemeColorSliders(label: String, argb: Int, onChange: (Int) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.padding(bottom = 4.dp)
    ) {
        Box(
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(Color(argb))
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), CircleShape)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "#%06X".format(argb and 0xFFFFFF),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
    }
    HsvColorPicker(
        argb = argb,
        onColorChange = { onChange(0xFF000000.toInt() or (it and 0x00FFFFFF)) },
        showAlpha = false
    )
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
        LogLevel.E -> MaterialTheme.colorScheme.error
        LogLevel.W -> MaterialTheme.colorScheme.tertiary
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

// ── Exact alarms ──────────────────────────────────────────────────────────────

@Composable
private fun ExactAlarmIntegration() {
    val context = LocalContext.current

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        IntegrationRow(
            title = "Exact Alarms",
            description = "Alarms fire at the exact scheduled time",
            granted = true,
            onConnect = {}
        )
        return
    }

    val am = remember { context.getSystemService(Context.ALARM_SERVICE) as AlarmManager }
    var granted by remember { mutableStateOf(am.canScheduleExactAlarms()) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { granted = am.canScheduleExactAlarms() }

    IntegrationRow(
        title = "Exact Alarms",
        description = "Required for alarms and sleep notifications to fire at the exact scheduled time. Tap to open system settings.",
        granted = granted,
        connectLabel = "Open Settings",
        onConnect = {
            launcher.launch(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
            )
        }
    )
}

// ── Battery optimization ──────────────────────────────────────────────────────

@Composable
private fun BatteryOptimizationIntegration() {
    val context = LocalContext.current
    val pm = remember { context.getSystemService(Context.POWER_SERVICE) as PowerManager }
    var exempted by remember { mutableStateOf(pm.isIgnoringBatteryOptimizations(context.packageName)) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { exempted = pm.isIgnoringBatteryOptimizations(context.packageName) }

    IntegrationRow(
        title = "Background alarms",
        description = "Exempts the app from battery optimization so sleep and wake alarms fire reliably when the screen is off.",
        granted = exempted,
        connectLabel = "Disable restriction",
        onConnect = {
            launcher.launch(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
            )
        }
    )
}

// ── Full-screen intent (Android 14+) ─────────────────────────────────────────

@Composable
private fun FullScreenIntentIntegration() {
    val context = LocalContext.current

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        IntegrationRow(
            title = "Lock-screen alarms",
            description = "Alarms can show a full-screen activity on the lock screen",
            granted = true,
            onConnect = {}
        )
        return
    }

    val nm = remember { context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager }
    var granted by remember { mutableStateOf(nm.canUseFullScreenIntent()) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { granted = nm.canUseFullScreenIntent() }

    IntegrationRow(
        title = "Lock-screen alarms",
        description = "Required for wake and user alarms to show on the lock screen. Tap to open system settings.",
        granted = granted,
        connectLabel = "Open Settings",
        onConnect = {
            try {
                launcher.launch(
                    Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                )
            } catch (e: ActivityNotFoundException) {
                // Some OEM builds don't ship this exact screen — fall back to the app's own
                // settings page rather than leaving the tap looking like it did nothing.
                launcher.launch(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                )
            }
        }
    )
}

// ── Calendar ──────────────────────────────────────────────────────────────────

@Composable
private fun CalendarIntegration(onPermissionGranted: () -> Unit) {
    val context = LocalContext.current

    var readGranted by remember {
        mutableStateOf(context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED)
    }
    var writeGranted by remember {
        mutableStateOf(context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED)
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        readGranted = perms[Manifest.permission.READ_CALENDAR] == true
        writeGranted = perms[Manifest.permission.WRITE_CALENDAR] == true
        if (readGranted) onPermissionGranted()
    }

    IntegrationRow(
        title = "Calendar",
        description = "Events visible in Plan view · sleep logs saved as calendar events · scripts can read/write events via signals.calendar",
        granted = readGranted && writeGranted,
        onConnect = {
            launcher.launch(arrayOf(
                Manifest.permission.READ_CALENDAR,
                Manifest.permission.WRITE_CALENDAR
            ))
        }
    )
}

// ── Location ──────────────────────────────────────────────────────────────────

@Composable
private fun LocationIntegration(onPermissionGranted: () -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        granted = perms[Manifest.permission.ACCESS_COARSE_LOCATION] == true ||
                  perms[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (granted) onPermissionGranted()
    }
    IntegrationRow(
        title = "Location",
        description = "Scripts can read current coordinates and check proximity via signals.location",
        granted = granted,
        onConnect = {
            launcher.launch(arrayOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION
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
    connectLabel: String = "Connect",
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
                Button(onClick = onConnect) { Text(connectLabel) }
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
