package com.waypoint.app.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.waypoint.app.persistence.WidgetStateStore
import com.waypoint.app.widget.HabitWidget
import com.waypoint.app.widget.HabitWidgetRegistry
import com.waypoint.app.widget.WidgetState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(private val store: WidgetStateStore) : ViewModel() {

    /** Live list of registered widgets — set at startup, stable during a session. */
    val widgets: List<HabitWidget> get() = HabitWidgetRegistry.all()

    /** Persisted state for every widget, loaded from DataStore and kept live. */
    val statesById: StateFlow<Map<String, WidgetState>> = store.allStates()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyMap()
        )

    fun onStateChange(widgetId: String, newState: WidgetState) {
        viewModelScope.launch { store.save(widgetId, newState) }
    }

    class Factory(private val store: WidgetStateStore) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            HomeViewModel(store) as T
    }
}
