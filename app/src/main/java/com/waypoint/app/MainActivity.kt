package com.waypoint.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.waypoint.app.home.HomeScreen
import com.waypoint.app.home.HomeViewModel
import com.waypoint.app.persistence.WidgetStateStore

class MainActivity : ComponentActivity() {

    private val viewModel: HomeViewModel by viewModels {
        HomeViewModel.Factory(WidgetStateStore(applicationContext))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
            }
        }

        val signalSources = (application as WaypointApplication).signalSources

        setContent {
            val statesById by viewModel.statesById.collectAsState()
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    HomeScreen(
                        workSchedule = signalSources.workSchedule,
                        eventPlanner = signalSources.eventPlanner,
                        addedWidgets = viewModel.widgets,
                        statesById = statesById,
                        onStateChange = viewModel::onStateChange
                    )
                }
            }
        }
    }
}
