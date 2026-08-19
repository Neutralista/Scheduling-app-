package com.waypoint.app.home

import com.waypoint.app.AppLogger
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.waypoint.app.alarm.AlarmBlockSync
import com.waypoint.app.alarm.AlarmEntry
import com.waypoint.app.alarm.AlarmSignals
import com.waypoint.app.alarm.UserAlarmScheduler
import com.waypoint.app.planner.NamedBlock
import com.waypoint.app.planner.NamedBlockStore
import com.waypoint.app.planner.SleepScheduleStore
import com.waypoint.app.ui.components.TimePickerDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import androidx.compose.ui.platform.LocalContext

@Composable
fun AlarmsTab(
    alarms: AlarmSignals,
    sleepTimesFlow: StateFlow<Pair<Long?, Long?>>
) {
    val context = LocalContext.current
    val sleepStore = remember { SleepScheduleStore(context) }
    val blockStore = remember { NamedBlockStore(context) }
    val allBlocks = remember { blockStore.loadAllBlocks() }
    val syncableBlocks = remember { allBlocks.filter { !it.isFloating } }
    val floatingBlockCount = remember { allBlocks.count { it.isFloating } }
    val alarmList by alarms.alarmsFlow.collectAsState()
    val sleepTimes by sleepTimesFlow.collectAsState()
    val scope = rememberCoroutineScope()

    val notificationManager = remember { context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager }
    fun fullScreenGrantedNow() =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || notificationManager.canUseFullScreenIntent()
    var fullScreenGranted by remember { mutableStateOf(fullScreenGrantedNow()) }
    val fullScreenLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { fullScreenGranted = fullScreenGrantedNow() }

    // The permission can also be granted from the Settings tab (or revoked by the OS) while
    // this tab stays mounted off-screen in the pager — re-check whenever the activity resumes,
    // not just when this tab's own launcher returns.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) fullScreenGranted = fullScreenGrantedNow()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var showDialog by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<AlarmEntry?>(null) }

    val initialConfig = remember { sleepStore.load() }
    var wakeAlarmCount by remember { mutableIntStateOf(initialConfig.wakeAlarmCount) }
    var wakeAlarmIntervalMinutes by remember { mutableIntStateOf(initialConfig.wakeAlarmIntervalMinutes) }
    var preSleepReminderMinutes by remember { mutableIntStateOf(initialConfig.preSleepReminderMinutes) }
    var preSleepAlarmEnabled by remember { mutableStateOf(initialConfig.preSleepAlarmEnabled) }
    var bedtimeAlarmEnabled by remember { mutableStateOf(initialConfig.bedtimeAlarmEnabled) }
    var gentleWakeEnabled by remember { mutableStateOf(initialConfig.gentleWakeEnabled) }
    var mediumWakeEnabled by remember { mutableStateOf(initialConfig.mediumWakeEnabled) }
    var wakeAlarmEnabled by remember { mutableStateOf(initialConfig.wakeAlarmEnabled) }
    var preSleepSync by remember { mutableStateOf(initialConfig.preSleepSync) }
    var bedtimeSync by remember { mutableStateOf(initialConfig.bedtimeSync) }
    var gentleWakeSync by remember { mutableStateOf(initialConfig.gentleWakeSync) }
    var mediumWakeSync by remember { mutableStateOf(initialConfig.mediumWakeSync) }
    var wakeUpSync by remember { mutableStateOf(initialConfig.wakeUpSync) }

    val (bedMs, wakeMs) = sleepTimes
    val hasSleepTimes = bedMs != null && wakeMs != null

    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) { delay(30_000); tick++ }
    }
    val nextAlarm = remember(
        alarmList, bedMs, wakeMs, preSleepAlarmEnabled, bedtimeAlarmEnabled,
        gentleWakeEnabled, mediumWakeEnabled, wakeAlarmEnabled,
        wakeAlarmCount, wakeAlarmIntervalMinutes, preSleepReminderMinutes, tick
    ) {
        val now = System.currentTimeMillis()
        val candidates = mutableListOf<Pair<String, Long>>()
        alarmList.filter { it.enabled }.forEach { a ->
            val fire = UserAlarmScheduler.nextFireTime(a)
            if (fire > now) candidates += (a.label.ifBlank { a.displayTime } to fire)
        }
        if (hasSleepTimes) {
            val intervalMs = wakeAlarmIntervalMinutes * 60_000L
            if (preSleepAlarmEnabled && preSleepReminderMinutes > 0) {
                val t = bedMs!! - preSleepReminderMinutes * 60_000L
                if (t > now) candidates += ("Pre-sleep reminder" to t)
            }
            if (bedtimeAlarmEnabled) {
                val t = bedMs!!
                if (t > now) candidates += ("Bedtime" to t)
            }
            if (wakeAlarmCount >= 3 && gentleWakeEnabled) {
                val t = wakeMs!! - 2 * intervalMs
                if (t > now) candidates += ("Gentle wake" to t)
            }
            if (wakeAlarmCount >= 2 && mediumWakeEnabled) {
                val t = wakeMs!! - intervalMs
                if (t > now) candidates += ("Medium wake" to t)
            }
            if (wakeAlarmEnabled) {
                val t = wakeMs!!
                if (t > now) candidates += ("Wake up" to t)
            }
        }
        candidates.minByOrNull { it.second }
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { editTarget = null; showDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add alarm")
            }
        }
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .windowInsetsPadding(WindowInsets.navigationBars)
        ) {
            if (!fullScreenGranted && (alarmList.isNotEmpty() || hasSleepTimes)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f))
                        .clickable {
                            try {
                                fullScreenLauncher.launch(
                                    Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                                        data = Uri.parse("package:${context.packageName}")
                                    }
                                )
                            } catch (e: ActivityNotFoundException) {
                                fullScreenLauncher.launch(
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                        data = Uri.parse("package:${context.packageName}")
                                    }
                                )
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Lock-screen alarms may not wake you",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            "Full-screen alarm permission is off, so alarms may ring as a quiet notification instead. Tap to fix in system settings.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f)
                        )
                    }
                }
            }

            if (nextAlarm != null) {
                val (label, fireMs) = nextAlarm
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            "Next: $label",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            fmtHHmm(fireMs),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        )
                    }
                    Text(
                        "in ${formatRelative(fireMs)}",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            if (!hasSleepTimes && alarmList.isEmpty()) {
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "No alarms",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Tap + to add one",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    if (hasSleepTimes) {
                        item {
                            Text(
                                "Sleep",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
                            )
                            val intervalMs = wakeAlarmIntervalMinutes * 60_000L

                            if (preSleepReminderMinutes > 0) {
                                SleepAlarmRow(
                                    label = "Pre-sleep reminder",
                                    epochMs = bedMs!! - preSleepReminderMinutes * 60_000L,
                                    enabled = preSleepAlarmEnabled,
                                    onToggle = { e ->
                                        preSleepAlarmEnabled = e
                                        scope.launch { sleepStore.setSleepAlarmEnabled("pre_sleep", e) }
                                    },
                                    syncableBlocks = syncableBlocks,
                                    sync = preSleepSync,
                                    onSyncChange = { newSync ->
                                        preSleepSync = newSync
                                        scope.launch {
                                            sleepStore.setSleepAlarmSync("pre_sleep", newSync)
                                            AlarmBlockSync.sync(context)
                                            preSleepAlarmEnabled = sleepStore.load().preSleepAlarmEnabled
                                        }
                                    }
                                )
                                Spacer(Modifier.height(8.dp))
                            }

                            SleepAlarmRow(
                                label = "Bedtime",
                                epochMs = bedMs!!,
                                enabled = bedtimeAlarmEnabled,
                                onToggle = { e ->
                                    bedtimeAlarmEnabled = e
                                    scope.launch { sleepStore.setSleepAlarmEnabled("bedtime", e) }
                                },
                                syncableBlocks = syncableBlocks,
                                sync = bedtimeSync,
                                onSyncChange = { newSync ->
                                    bedtimeSync = newSync
                                    scope.launch {
                                        sleepStore.setSleepAlarmSync("bedtime", newSync)
                                        AlarmBlockSync.sync(context)
                                        bedtimeAlarmEnabled = sleepStore.load().bedtimeAlarmEnabled
                                    }
                                }
                            )
                            Spacer(Modifier.height(8.dp))

                            if (wakeAlarmCount >= 3) {
                                SleepAlarmRow(
                                    label = "Gentle wake (35% volume)",
                                    epochMs = wakeMs!! - 2 * intervalMs,
                                    enabled = gentleWakeEnabled,
                                    onToggle = { e ->
                                        gentleWakeEnabled = e
                                        scope.launch { sleepStore.setSleepAlarmEnabled("gentle_wake", e) }
                                    },
                                    syncableBlocks = syncableBlocks,
                                    sync = gentleWakeSync,
                                    onSyncChange = { newSync ->
                                        gentleWakeSync = newSync
                                        scope.launch {
                                            sleepStore.setSleepAlarmSync("gentle_wake", newSync)
                                            AlarmBlockSync.sync(context)
                                            gentleWakeEnabled = sleepStore.load().gentleWakeEnabled
                                        }
                                    }
                                )
                                Spacer(Modifier.height(8.dp))
                            }

                            if (wakeAlarmCount >= 2) {
                                SleepAlarmRow(
                                    label = "Medium wake (70% volume)",
                                    epochMs = wakeMs!! - intervalMs,
                                    enabled = mediumWakeEnabled,
                                    onToggle = { e ->
                                        mediumWakeEnabled = e
                                        scope.launch { sleepStore.setSleepAlarmEnabled("medium_wake", e) }
                                    },
                                    syncableBlocks = syncableBlocks,
                                    sync = mediumWakeSync,
                                    onSyncChange = { newSync ->
                                        mediumWakeSync = newSync
                                        scope.launch {
                                            sleepStore.setSleepAlarmSync("medium_wake", newSync)
                                            AlarmBlockSync.sync(context)
                                            mediumWakeEnabled = sleepStore.load().mediumWakeEnabled
                                        }
                                    }
                                )
                                Spacer(Modifier.height(8.dp))
                            }

                            SleepAlarmRow(
                                label = "Wake up!",
                                epochMs = wakeMs!!,
                                enabled = wakeAlarmEnabled,
                                onToggle = { e ->
                                    wakeAlarmEnabled = e
                                    scope.launch { sleepStore.setSleepAlarmEnabled("wake_up", e) }
                                },
                                syncableBlocks = syncableBlocks,
                                sync = wakeUpSync,
                                onSyncChange = { newSync ->
                                    wakeUpSync = newSync
                                    scope.launch {
                                        sleepStore.setSleepAlarmSync("wake_up", newSync)
                                        AlarmBlockSync.sync(context)
                                        wakeAlarmEnabled = sleepStore.load().wakeAlarmEnabled
                                    }
                                }
                            )
                            Spacer(Modifier.height(16.dp))
                        }
                    }
                    if (alarmList.isNotEmpty()) {
                        item {
                            Text(
                                "Alarms",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                            )
                        }
                        items(alarmList, key = { it.id }) { alarm ->
                            AlarmRow(
                                alarm = alarm,
                                onToggle = { enabled ->
                                    scope.launch {
                                        try { alarms.setEnabled(alarm.id, enabled) }
                                        catch (e: Throwable) { AppLogger.e("AlarmsTab", "setEnabled threw ${e.javaClass.name}: ${e.message}", e) }
                                    }
                                },
                                onEdit = { editTarget = alarm; showDialog = true },
                                onDelete = {
                                    scope.launch {
                                        try { alarms.delete(alarm.id) }
                                        catch (e: Throwable) { AppLogger.e("AlarmsTab", "delete threw ${e.javaClass.name}: ${e.message}", e) }
                                    }
                                },
                                onSkipNext = {
                                    scope.launch { UserAlarmScheduler.skipNext(context, alarm) }
                                }
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }
        }
    }

    if (showDialog) {
        AlarmEditDialog(
            initial = editTarget,
            syncableBlocks = syncableBlocks,
            floatingBlockCount = floatingBlockCount,
            onDismiss = { showDialog = false },
            onSave = { entry ->
                scope.launch {
                    try {
                        if (entry.id.isBlank()) alarms.add(entry) else alarms.update(entry)
                        AlarmBlockSync.sync(context)
                    } catch (e: Throwable) {
                        AppLogger.e("AlarmsTab", "save threw ${e.javaClass.name}: ${e.message}", e)
                    }
                }
                showDialog = false
            }
        )
    }
}

private fun formatRelative(ms: Long): String {
    val diff = ms - System.currentTimeMillis()
    if (diff <= 0) return "now"
    val totalMin = diff / 60_000L
    val h = totalMin / 60
    val m = totalMin % 60
    return when {
        h > 0 && m > 0 -> "${h}h ${m}m"
        h > 0 -> "${h}h"
        else -> "${m}m"
    }
}

private fun fmtHHmm(ms: Long): String =
    java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ms))

private suspend fun playTestSound(context: android.content.Context, soundUri: String?, volume: Float) {
    withContext(Dispatchers.IO) {
        var player: MediaPlayer? = null
        try {
            val uri = soundUri?.let { Uri.parse(it) }
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(context, uri)
                setVolume(volume, volume)
                prepare()
                start()
            }
            delay(3000)
        } catch (e: Throwable) {
            AppLogger.e("AlarmsTab", "playTestSound threw ${e.javaClass.name}: ${e.message}", e)
        } finally {
            try { player?.stop() } catch (_: Throwable) {}
            player?.release()
        }
    }
}

@Composable
private fun AlarmRow(
    alarm: AlarmEntry,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onSkipNext: () -> Unit
) {
    val isSynced = alarm.blockSyncEnabled && alarm.linkedBlockId != null
    var showDeleteConfirm by remember { mutableStateOf(false) }
    if (showDeleteConfirm) {
        BlockDeleteDialog(
            itemLabel = alarm.label.ifBlank { alarm.displayTime },
            onDelete = onDelete,
            onDismiss = { showDeleteConfirm = false }
        )
    }
    Card(
        onClick = onEdit,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (alarm.enabled)
                MaterialTheme.colorScheme.surfaceVariant
            else
                MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = alarm.displayTime,
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                    color = if (alarm.enabled)
                        MaterialTheme.colorScheme.onSurface
                    else
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                )
                if (alarm.label.isNotEmpty()) {
                    Text(
                        text = alarm.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = alarm.repeatLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
                if (isSynced) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "⟳ block sync",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                    )
                }
                if (alarm.enabled && alarm.repeatDays.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "Skip next",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { onSkipNext() }
                    )
                }
            }
            IconButton(onClick = { showDeleteConfirm = true }, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Delete alarm",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.width(4.dp))
            Switch(
                checked = alarm.enabled,
                onCheckedChange = if (isSynced) null else onToggle
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlarmEditDialog(
    initial: AlarmEntry?,
    syncableBlocks: List<NamedBlock>,
    floatingBlockCount: Int,
    onDismiss: () -> Unit,
    onSave: (AlarmEntry) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var hour by remember { mutableIntStateOf(initial?.hour ?: 7) }
    var minute by remember { mutableIntStateOf(initial?.minute ?: 0) }
    var label by remember { mutableStateOf(initial?.label ?: "") }
    var repeatDays by remember { mutableStateOf(initial?.repeatDays ?: emptySet()) }
    var vibrate by remember { mutableStateOf(initial?.vibrate ?: true) }
    var showTimePicker by remember { mutableStateOf(false) }
    var blockSyncEnabled by remember { mutableStateOf(initial?.blockSyncEnabled ?: false) }
    var linkedBlockId by remember { mutableStateOf(initial?.linkedBlockId) }
    var blockDropdownExpanded by remember { mutableStateOf(false) }
    var soundUri by remember { mutableStateOf(initial?.soundUri) }
    var volume by remember { mutableFloatStateOf(initial?.volume ?: 1.0f) }
    var snoozeMinutes by remember { mutableIntStateOf(initial?.snoozeMinutes ?: 10) }
    var stagedWake by remember { mutableStateOf(initial?.stagedWake ?: false) }
    var maxVolumeOverride by remember { mutableStateOf(initial?.maxVolumeOverride ?: false) }
    var isTesting by remember { mutableStateOf(false) }

    val selectedBlockName = syncableBlocks.find { it.id == linkedBlockId }?.name ?: "None"
    val soundLabel = remember(soundUri) {
        soundUri?.let { raw ->
            try { RingtoneManager.getRingtone(context, Uri.parse(raw)).getTitle(context) ?: "Custom" }
            catch (e: Throwable) { "Custom" }
        } ?: "Default"
    }
    val soundPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        @Suppress("DEPRECATION")
        val uri = result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        soundUri = uri?.toString()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New Alarm" else "Edit Alarm") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { showTimePicker = true }
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(vertical = 14.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "%02d:%02d".format(hour, minute),
                        style = MaterialTheme.typography.displayMedium.copy(fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
                )

                Text(
                    "Repeat",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    AlarmEntry.DAY_LABELS.forEachIndexed { idx, dayLabel ->
                        val dayNum = idx + 1
                        FilterChip(
                            selected = dayNum in repeatDays,
                            onClick = {
                                repeatDays = if (dayNum in repeatDays)
                                    repeatDays - dayNum
                                else
                                    repeatDays + dayNum
                            },
                            label = { Text(dayLabel, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Vibrate", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = vibrate, onCheckedChange = { vibrate = it })
                }

                HorizontalDivider()

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            val existing = soundUri?.let { Uri.parse(it) }
                                ?: RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
                            val pickerIntent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                                putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                                putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, existing)
                            }
                            soundPickerLauncher.launch(pickerIntent)
                        },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Sound", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        soundLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Column {
                    Text("Volume", style = MaterialTheme.typography.bodyMedium)
                    Slider(value = volume, onValueChange = { volume = it }, valueRange = 0.1f..1.0f)
                }

                TextButton(
                    onClick = {
                        if (!isTesting) {
                            isTesting = true
                            scope.launch {
                                playTestSound(context, soundUri, volume)
                                isTesting = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (isTesting) "Playing…" else "Test sound") }

                Text(
                    "Snooze duration",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(5, 10, 15, 20).forEach { mins ->
                        FilterChip(
                            selected = snoozeMinutes == mins,
                            onClick = { snoozeMinutes = mins },
                            label = { Text("${mins}m", style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Gentle wake-up", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Two quiet reminder pulses ring 10 and 5 minutes before this alarm",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = stagedWake, onCheckedChange = { stagedWake = it })
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Force max volume", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Overrides the device's alarm volume when this alarm rings",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = maxVolumeOverride, onCheckedChange = { maxVolumeOverride = it })
                }

                if (syncableBlocks.isNotEmpty() || floatingBlockCount > 0) {
                    HorizontalDivider()
                    if (syncableBlocks.isNotEmpty()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Sync with block", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "Auto-enable when block is scheduled",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = blockSyncEnabled,
                                onCheckedChange = { blockSyncEnabled = it }
                            )
                        }

                        if (blockSyncEnabled) {
                            ExposedDropdownMenuBox(
                                expanded = blockDropdownExpanded,
                                onExpandedChange = { blockDropdownExpanded = it }
                            ) {
                                OutlinedTextField(
                                    value = selectedBlockName,
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text("Block") },
                                    trailingIcon = {
                                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = blockDropdownExpanded)
                                    },
                                    modifier = Modifier
                                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                                        .fillMaxWidth()
                                )
                                ExposedDropdownMenu(
                                    expanded = blockDropdownExpanded,
                                    onDismissRequest = { blockDropdownExpanded = false }
                                ) {
                                    syncableBlocks.forEach { block ->
                                        DropdownMenuItem(
                                            text = { Text(block.name) },
                                            onClick = {
                                                linkedBlockId = block.id
                                                blockDropdownExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (floatingBlockCount > 0) {
                        Text(
                            "$floatingBlockCount floating block${if (floatingBlockCount == 1) "" else "s"} " +
                                "can't be synced here — only fixed-time blocks can drive an alarm.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val syncOn = blockSyncEnabled && linkedBlockId != null
                onSave(
                    AlarmEntry(
                        id = initial?.id ?: "",
                        label = label.trim(),
                        hour = hour,
                        minute = minute,
                        enabled = initial?.enabled ?: true,
                        repeatDays = repeatDays,
                        vibrate = vibrate,
                        linkedBlockId = if (syncOn) linkedBlockId else null,
                        blockSyncEnabled = syncOn,
                        seq = initial?.seq ?: -1,
                        soundUri = soundUri,
                        volume = volume,
                        snoozeMinutes = snoozeMinutes,
                        stagedWake = stagedWake,
                        maxVolumeOverride = maxVolumeOverride
                    )
                )
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )

    if (showTimePicker) {
        TimePickerDialog(
            initialHour = hour,
            initialMinute = minute,
            onDismiss = { showTimePicker = false },
            onConfirm = { h, m ->
                hour = h
                minute = m
                showTimePicker = false
            }
        )
    }
}
