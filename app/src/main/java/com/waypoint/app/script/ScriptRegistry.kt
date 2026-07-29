package com.waypoint.app.script

/**
 * Global registry of every AppScript the app knows about — built-in and
 * user-installed alike. Nothing in the app hard-codes specific scripts;
 * everything goes through this registry.
 */
object ScriptRegistry {
    private val scripts = linkedMapOf<String, AppScript>()

    fun register(script: AppScript, env: ScriptEnvironment) {
        script.onAttached(env)
        scripts[script.id] = script
    }

    /** All registered scripts, in registration order. */
    fun all(): List<AppScript> = scripts.values.toList()

    /** Scripts that define a visual widget. */
    fun withWidgets(): List<AppScript> = scripts.values.filter { it.hasWidget }

    fun get(id: String): AppScript? = scripts[id]

    fun unregister(id: String) { scripts.remove(id) }
}
