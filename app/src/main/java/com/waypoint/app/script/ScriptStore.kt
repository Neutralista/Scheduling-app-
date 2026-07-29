package com.waypoint.app.script

import android.content.Context
import java.io.File

/**
 * Persists user-installed script JS source files to internal storage.
 * Each script is stored as <filesDir>/scripts/<id>.js
 */
class ScriptStore(private val context: Context) {

    private val dir: File
        get() = File(context.filesDir, "scripts").also { it.mkdirs() }

    fun save(module: ScriptedModule) = saveSource(module.id, module.source)

    fun saveSource(id: String, source: String) {
        File(dir, "$id.js").writeText(source)
    }

    fun delete(id: String) {
        File(dir, "$id.js").delete()
    }

    fun getSource(id: String): String? =
        File(dir, "$id.js").takeIf { it.exists() }?.readText()

    fun loadAll(): List<ScriptedModule> =
        dir.listFiles { f -> f.extension == "js" }
            ?.mapNotNull { file ->
                runCatching { ScriptedModule.fromSource(file.readText()) }
                    .onFailure { file.delete() }
                    .getOrNull()
            }
            ?: emptyList()
}
