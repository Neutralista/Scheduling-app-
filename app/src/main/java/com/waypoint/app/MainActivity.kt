package com.waypoint.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.waypoint.app.home.HomeScreen
import com.waypoint.app.home.HomeViewModel
import com.waypoint.app.ui.theme.WaypointTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {

    private val viewModel: HomeViewModel by viewModels {
        val app = application as WaypointApplication
        HomeViewModel.Factory(
            stateStore = app.scriptStateStore,
            scriptStore = app.scriptStore,
            env = app.env
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as WaypointApplication

        // If startup crashed, show crash recovery UI — don't touch any lateinit properties.
        app.startupCrash?.let { crash ->
            setContent {
                WaypointTheme {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        CrashRecoveryScreen(crash = crash)
                    }
                }
            }
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
            }
        }

        // Sync sleep alarms from foreground Activity context — alarm scheduling APIs
        // can throw on some OEM builds when called from a background Application context.
        try {
            app.env.sleepStore.syncToRegistry(app.env.eventPlanner)
        } catch (e: Throwable) {
            AppLogger.e("MainActivity", "syncToRegistry failed", e)
        }

        val initialTab = intent?.getIntExtra("tab", 0) ?: 0
        val triggerScriptId = intent?.getStringExtra("triggerScriptAction").orEmpty()

        if (triggerScriptId.isNotEmpty()) {
            lifecycleScope.launch {
                // Wait until the launch-reset pass has finished so our trigger isn't undone.
                viewModel.initialLoadComplete.first { it }
                viewModel.triggerScriptAction(triggerScriptId)
            }
        }

        setContent {
            val statesById by viewModel.statesById.collectAsState()
            val scripts by viewModel.scripts.collectAsState()
            WaypointTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    HomeScreen(
                        eventPlanner = app.env.eventPlanner,
                        taskManager = app.env.taskManager,
                        calendarSignals = app.env.calendar,
                        alarms = app.env.alarms,
                        cycleTracker = app.cycleTracker,
                        sleepTimesFlow = app.env.sleepStore.scheduledTimesFlow,
                        scripts = scripts,
                        statesById = statesById,
                        onStateChange = viewModel::onStateChange,
                        onAddScript = viewModel::addUserScript,
                        onUpdateScript = viewModel::updateUserScript,
                        onRemoveScript = viewModel::removeUserScript,
                        onResetScript = viewModel::resetBuiltInScript,
                        onPermissionGranted = viewModel::onPermissionGranted,
                        initialTab = initialTab
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val app = application as WaypointApplication
        if (app.startupCrash != null) return
        app.cycleTracker.recordActive()
    }
}

@Composable
private fun CrashRecoveryScreen(crash: Throwable) {
    val context = LocalContext.current
    val logText = remember {
        runCatching {
            File(context.filesDir, "waypoint_last_session.log").readText()
        }.getOrElse { "Log file not found: ${it.message}" }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Spacer(Modifier.height(32.dp))
        Text("Startup Failed", style = MaterialTheme.typography.headlineSmall)
        Text(
            "${crash.javaClass.simpleName}: ${crash.message}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val logFile = File(context.filesDir, "waypoint_last_session.log")
                if (logFile.exists()) {
                    val uri = FileProvider.getUriForFile(
                        context, "${context.packageName}.fileprovider", logFile
                    )
                    context.startActivity(Intent.createChooser(
                        Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            putExtra(Intent.EXTRA_SUBJECT, "Waypoint crash log")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }, "Share crash log"
                    ))
                }
            }) { Text("Share Log") }
            OutlinedButton(onClick = {
                val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                clipboard.setPrimaryClip(
                    android.content.ClipData.newPlainText("crash log", logText)
                )
            }) { Text("Copy Log") }
        }
        Text(
            text = logText,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
