package com.waypoint.app.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.waypoint.app.persistence.WidgetStateStore
import com.waypoint.app.widget.HabitWidget
import com.waypoint.app.widget.HabitWidgetRegistry
import com.waypoint.app.widget.ScriptedWidget
import com.waypoint.app.widget.ScriptedWidgetStore
import com.waypoint.app.widget.SignalSources
import com.waypoint.app.widget.WidgetState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class HomeViewModel(
    private val store: WidgetStateStore,
    private val scriptedStore: ScriptedWidgetStore,
    private val signals: SignalSources
) : ViewModel() {

    /** Drives recomposition whenever the widget list changes. */
    private val _widgetList = MutableStateFlow(HabitWidgetRegistry.all())
    val widgets: StateFlow<List<HabitWidget>> = _widgetList

    val statesById: StateFlow<Map<String, WidgetState>> = store.allStates()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyMap()
        )

    fun onStateChange(widgetId: String, newState: WidgetState) {
        viewModelScope.launch { store.save(widgetId, newState) }
    }

    /**
     * Evaluates [source] as a scripted widget, registers it, and persists the code.
     * Returns null on success or an error message string on failure.
     */
    fun addScriptedWidget(source: String): String? {
        return try {
            val widget = ScriptedWidget.fromSource(source)
            HabitWidgetRegistry.register(widget, signals)
            scriptedStore.save(widget)
            _widgetList.update { HabitWidgetRegistry.all() }
            null
        } catch (e: Exception) {
            e.message ?: "Unknown error"
        }
    }

    fun removeScriptedWidget(id: String) {
        HabitWidgetRegistry.unregister(id)
        scriptedStore.delete(id)
        _widgetList.update { HabitWidgetRegistry.all() }
    }

    class Factory(
        private val store: WidgetStateStore,
        private val scriptedStore: ScriptedWidgetStore,
        private val signals: SignalSources
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            HomeViewModel(store, scriptedStore, signals) as T
    }
}
