package com.waypoint.app.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.waypoint.app.persistence.ScriptStateStore
import com.waypoint.app.script.AppScript
import com.waypoint.app.script.ScriptRegistry
import com.waypoint.app.script.ScriptState
import com.waypoint.app.script.ScriptStore
import com.waypoint.app.script.ScriptedModule
import com.waypoint.app.signal.RealScriptEnvironment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class HomeViewModel(
    private val stateStore: ScriptStateStore,
    private val scriptStore: ScriptStore,
    private val env: RealScriptEnvironment
) : ViewModel() {

    init {
        viewModelScope.launch(Dispatchers.IO) { refreshIntegrationCaches() }
    }

    private val _scriptList = MutableStateFlow(ScriptRegistry.all())
    val scripts: StateFlow<List<AppScript>> = _scriptList

    // Reset wizard step/substep for user scripts on the very first DataStore emission.
    // This prevents a stale mid-wizard step (e.g. from a previous crash) from
    // auto-launching the dialog the next time the app opens.
    private val launchResetDone = AtomicBoolean(false)
    private val userScriptIds: Set<String> by lazy {
        ScriptRegistry.all().filterIsInstance<ScriptedModule>().map { it.id }.toSet()
    }

    val statesById: StateFlow<Map<String, ScriptState>> = stateStore.allStates()
        .map { states ->
            if (launchResetDone.compareAndSet(false, true) && userScriptIds.isNotEmpty()) {
                states.mapValues { (id, state) ->
                    if (id in userScriptIds && (state.values["step"] ?: 0.0) > 0.0) {
                        state.copy(values = state.values - "step" - "substep")
                    } else state
                }
            } else states
        }
        .onEach { env.updateCache(it) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyMap()
        )

    fun onStateChange(scriptId: String, newState: ScriptState) {
        viewModelScope.launch { stateStore.save(scriptId, newState) }
    }

    /**
     * Evaluates [source] as a user script, registers it, and persists the JS.
     * Returns null on success or an error message on failure.
     */
    fun addUserScript(source: String): String? {
        return try {
            val module = ScriptedModule.fromSource(source)
            ScriptRegistry.register(module, env)
            scriptStore.save(module)
            _scriptList.update { ScriptRegistry.all() }
            null
        } catch (e: Exception) {
            e.message ?: "Unknown error"
        }
    }

    /**
     * Re-evaluates and replaces a user script's JS source in place.
     * The script id in the new source must match [id].
     */
    fun updateUserScript(id: String, newSource: String): String? {
        return try {
            val module = ScriptedModule.fromSource(newSource)
            if (module.id != id) return "Script id must stay \"$id\" but got \"${module.id}\""
            ScriptRegistry.unregister(id)
            ScriptRegistry.register(module, env)
            scriptStore.saveSource(id, newSource)
            _scriptList.update { ScriptRegistry.all() }
            null
        } catch (e: Exception) {
            e.message ?: "Unknown error"
        }
    }

    fun removeUserScript(id: String) {
        ScriptRegistry.unregister(id)
        scriptStore.delete(id)
        viewModelScope.launch { stateStore.clear(id) }
        _scriptList.update { ScriptRegistry.all() }
    }

    fun onPermissionGranted() {
        viewModelScope.launch(Dispatchers.IO) { refreshIntegrationCaches() }
    }

    private suspend fun refreshIntegrationCaches() {
        env.calendar.refreshCache()
        env.healthConnect.refreshCache()
    }

    fun resetBuiltInScript(id: String) {
        val script = ScriptRegistry.get(id) ?: return
        viewModelScope.launch { script.resetToDefaults() }
    }

    class Factory(
        private val stateStore: ScriptStateStore,
        private val scriptStore: ScriptStore,
        private val env: RealScriptEnvironment
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            HomeViewModel(stateStore, scriptStore, env) as T
    }
}
