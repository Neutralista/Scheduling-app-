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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.waypoint.app.home.DebugLaunchScreen
import com.waypoint.app.home.HomeScreen
import com.waypoint.app.home.HomeViewModel
import com.waypoint.app.planner.BlockSessionStore
import com.waypoint.app.ui.theme.AppTheme
import com.waypoint.app.ui.theme.ThemeStore
import com.waypoint.app.ui.theme.WaypointTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val pendingTabFlow = MutableStateFlow<Int?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val tab = intent.getIntExtra("tab", -1)
        if (tab >= 0) pendingTabFlow.value = tab
    }

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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
            }
        }

        // Sync sleep alarms — only safe when env is initialized successfully
        if (app.startupCrash == null) {
            try {
                app.env.sleepStore.syncToRegistry(app.env.eventPlanner)
            } catch (e: Throwable) {
                AppLogger.e("MainActivity", "syncToRegistry failed", e)
            }
        }

        val initialTab = intent?.getIntExtra("tab", 0) ?: 0
        val triggerScriptId = intent?.getStringExtra("triggerScriptAction").orEmpty()

        if (app.startupCrash == null && triggerScriptId.isNotEmpty()) {
            lifecycleScope.launch {
                viewModel.initialLoadComplete.first { it }
                viewModel.triggerScriptAction(triggerScriptId)
            }
        }

        setContent {
            val activeSession by app.env.blockSessionStore.sessionFlow.collectAsState()
            val pendingTab by pendingTabFlow.collectAsState()
            val selectedThemeId by app.themeStore.selectedThemeIdFlow.collectAsState()
            val appTheme = remember(selectedThemeId) { app.themeStore.resolveTheme(selectedThemeId) }
            WaypointTheme(appTheme = appTheme, blockColorArgb = activeSession?.colorArgb) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    // Skip the debug screen entirely when init succeeded
                    var initComplete by remember { mutableStateOf(app.startupCrash == null) }

                    if (!initComplete) {
                        DebugLaunchScreen(
                            initSteps = app.initSteps,
                            startupCrash = app.startupCrash
                        )
                    } else {
                        val statesById by viewModel.statesById.collectAsState()
                        val scripts by viewModel.scripts.collectAsState()
                        HomeScreen(
                            eventPlanner = app.env.eventPlanner,
                            taskManager = app.env.taskManager,
                            calendarSignals = app.env.calendar,
                            alarms = app.env.alarms,
                            cycleTracker = app.cycleTracker,
                            sleepTimesFlow = app.env.sleepStore.scheduledTimesFlow,
                            blockSessionStore = app.env.blockSessionStore,
                            blockSessionLogStore = app.env.blockSessionLogStore,
                            themeStore = app.themeStore,
                            scripts = scripts,
                            statesById = statesById,
                            onStateChange = viewModel::onStateChange,
                            onAddScript = viewModel::addUserScript,
                            onUpdateScript = viewModel::updateUserScript,
                            onRemoveScript = viewModel::removeUserScript,
                            onResetScript = viewModel::resetBuiltInScript,
                            onPermissionGranted = viewModel::onPermissionGranted,
                            initialTab = initialTab,
                            externalTabRequest = pendingTab,
                            onTabNavigated = { pendingTabFlow.value = null }
                        )
                    }
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
