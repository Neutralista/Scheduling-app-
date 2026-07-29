package com.waypoint.app.widget

import android.content.Context
import java.io.File

/**
 * Persists scripted widget JS source files to internal storage.
 * Each widget is stored as <filesDir>/scripted_widgets/<id>.js
 */
class ScriptedWidgetStore(private val context: Context) {

    private val dir: File
        get() = File(context.filesDir, "scripted_widgets").also { it.mkdirs() }

    fun save(widget: ScriptedWidget) {
        File(dir, "${widget.id}.js").writeText(widget.source)
    }

    fun delete(id: String) {
        File(dir, "$id.js").delete()
    }

    /** Load and evaluate all saved widget source files. Skips any that fail to parse. */
    fun loadAll(): List<ScriptedWidget> =
        dir.listFiles { f -> f.extension == "js" }
            ?.mapNotNull { file ->
                runCatching { ScriptedWidget.fromSource(file.readText()) }
                    .onFailure { file.delete() }  // remove corrupt/outdated scripts
                    .getOrNull()
            }
            ?: emptyList()

    fun ids(): Set<String> =
        dir.listFiles { f -> f.extension == "js" }
            ?.map { it.nameWithoutExtension }
            ?.toSet()
            ?: emptySet()
}
