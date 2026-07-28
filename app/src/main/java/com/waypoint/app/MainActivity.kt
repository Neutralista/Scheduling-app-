package com.waypoint.app

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
        setContent {
            val statesById by viewModel.statesById.collectAsState()
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    HomeScreen(
                        addedWidgets = viewModel.widgets,
                        statesById = statesById,
                        onStateChange = viewModel::onStateChange
                    )
                }
            }
        }
    }
}
