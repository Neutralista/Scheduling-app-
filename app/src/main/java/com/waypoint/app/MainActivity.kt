package com.waypoint.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.waypoint.app.home.HomeScreen
import com.waypoint.app.home.HomeViewModel
import com.waypoint.app.ui.theme.WaypointTheme

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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
            }
        }

        val app = application as WaypointApplication

        setContent {
            val statesById by viewModel.statesById.collectAsState()
            val scripts by viewModel.scripts.collectAsState()
            WaypointTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    HomeScreen(
                        workSchedule = app.env.workSchedule,
                        eventPlanner = app.env.eventPlanner,
                        scripts = scripts,
                        statesById = statesById,
                        onStateChange = viewModel::onStateChange,
                        onAddScript = viewModel::addUserScript,
                        onUpdateScript = viewModel::updateUserScript,
                        onRemoveScript = viewModel::removeUserScript,
                        onResetScript = viewModel::resetBuiltInScript,
                        onPermissionGranted = viewModel::onPermissionGranted
                    )
                }
            }
        }
    }
}
