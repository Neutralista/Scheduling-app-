package com.waypoint.app.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.mozilla.javascript.Context
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined

// ── Base class injected into every widget's JS scope ─────────────────────────

private val BASE_JS = """
var HabitWidget = (function() {
  function HabitWidget(opts) {
    this.id = opts.id;
    this.displayName = opts.displayName;
    this.size = opts.size || 'WIDE_ROW';
  }
  HabitWidget.prototype.render = function(state) {
    return { title: this.displayName, done: false };
  };
  HabitWidget.prototype.onAction = function(state) {
    return state;
  };
  return HabitWidget;
})();
""".trimIndent()

// ── View spec returned by JS render() ─────────────────────────────────────────

data class ScriptedView(
    val title: String = "",
    val subtitle: String? = null,
    val value: String? = null,
    val progress: Float? = null,
    val done: Boolean = false,
    val actionLabel: String = "Done"
)

// ── JS helper extensions ──────────────────────────────────────────────────────

private fun NativeObject.jsString(key: String): String? {
    val v = get(key, this)
    return if (v == null || v is Undefined || v == ScriptableObject.NOT_FOUND) null else v.toString()
}

private fun NativeObject.jsBool(key: String, default: Boolean = false): Boolean {
    val v = get(key, this)
    return when {
        v == null || v is Undefined || v == ScriptableObject.NOT_FOUND -> default
        v is Boolean -> v
        else -> v.toString().equals("true", ignoreCase = true)
    }
}

private fun NativeObject.jsFloat(key: String): Float? {
    val v = get(key, this)
    return (v as? Number)?.toFloat()
}

// ── ScriptedWidget ────────────────────────────────────────────────────────────

/**
 * A HabitWidget whose behaviour is defined by user-provided JavaScript.
 *
 * Widget format:
 *
 *   function MyHabit() {
 *     HabitWidget.call(this, { id: 'my_habit', displayName: 'My Habit' });
 *   }
 *   MyHabit.prototype = Object.create(HabitWidget.prototype);
 *
 *   MyHabit.prototype.render = function(state) {
 *     return {
 *       title: this.displayName,
 *       done: state.doneToday,
 *       actionLabel: state.doneToday ? 'Undo' : 'Mark done'
 *     };
 *   };
 *
 *   MyHabit.prototype.onAction = function(state) {
 *     return Object.assign({}, state, { doneToday: !state.doneToday });
 *   };
 *
 *   new MyHabit()    // last expression — must be the widget instance
 *
 * The render() return object fields:
 *   title       string   — required
 *   subtitle    string?  — small text below title
 *   value       string?  — prominent number/metric displayed large
 *   progress    number?  — 0.0–1.0 fills a linear progress bar
 *   done        boolean  — if true shows a ✓ instead of the action button
 *   actionLabel string?  — label on the action button (default "Done")
 */
class ScriptedWidget private constructor(
    val source: String,
    override val id: String,
    override val displayName: String,
    override val uiConfig: WidgetUiConfig
) : HabitWidget {

    companion object {
        /**
         * Evaluates [source], extracts the returned widget instance, and wraps it.
         * Throws IllegalArgumentException on syntax errors or missing required fields.
         */
        fun fromSource(source: String): ScriptedWidget {
            val cx = rhino()
            return try {
                val scope = cx.initSafeStandardObjects()
                cx.evaluateString(scope, BASE_JS, "<base>", 1, null)
                val result = cx.evaluateString(scope, source, "<widget>", 1, null)
                val obj = result as? NativeObject
                    ?: throw IllegalArgumentException(
                        "The last line must be an expression that produces a widget instance, e.g. new MyHabit()"
                    )
                val id = obj.jsString("id")
                    ?: throw IllegalArgumentException("Widget must have an id property")
                val displayName = obj.jsString("displayName") ?: id
                val size = when (obj.jsString("size")?.uppercase()) {
                    "SMALL_TILE" -> WidgetSize.SMALL_TILE
                    "FULL_CARD"  -> WidgetSize.FULL_CARD
                    else         -> WidgetSize.WIDE_ROW
                }
                ScriptedWidget(source, id, displayName, WidgetUiConfig(size))
            } finally {
                Context.exit()
            }
        }

        private fun rhino(): Context = Context.enter().also { it.optimizationLevel = -1 }
    }

    // ── Evaluate render() in a fresh scope ────────────────────────────────────

    fun renderView(state: WidgetState): ScriptedView {
        return try {
            val cx = rhino()
            try {
                val scope = cx.initSafeStandardObjects()
                cx.evaluateString(scope, BASE_JS, "<base>", 1, null)
                val widgetObj = cx.evaluateString(scope, source, "<widget>", 1, null) as? NativeObject
                    ?: return ScriptedView(title = displayName)
                val stateJs = state.toJS(cx, scope)
                val fn = ScriptableObject.getProperty(widgetObj, "render")
                    as? org.mozilla.javascript.Function
                    ?: return ScriptedView(title = displayName)
                val res = fn.call(cx, scope, widgetObj, arrayOf(stateJs)) as? NativeObject
                    ?: return ScriptedView(title = displayName)
                ScriptedView(
                    title       = res.jsString("title") ?: displayName,
                    subtitle    = res.jsString("subtitle"),
                    value       = res.jsString("value"),
                    progress    = res.jsFloat("progress"),
                    done        = res.jsBool("done"),
                    actionLabel = res.jsString("actionLabel") ?: "Done"
                )
            } finally {
                Context.exit()
            }
        } catch (e: Exception) {
            ScriptedView(title = displayName, subtitle = "⚠ ${e.message}")
        }
    }

    // ── Evaluate onAction() in a fresh scope ──────────────────────────────────

    fun applyAction(state: WidgetState): WidgetState {
        return try {
            val cx = rhino()
            try {
                val scope = cx.initSafeStandardObjects()
                cx.evaluateString(scope, BASE_JS, "<base>", 1, null)
                val widgetObj = cx.evaluateString(scope, source, "<widget>", 1, null) as? NativeObject
                    ?: return state.copy(doneToday = !state.doneToday)
                val stateJs = state.toJS(cx, scope)
                val fn = ScriptableObject.getProperty(widgetObj, "onAction")
                    as? org.mozilla.javascript.Function
                    ?: return state.copy(doneToday = !state.doneToday)
                val res = fn.call(cx, scope, widgetObj, arrayOf(stateJs)) as? NativeObject
                    ?: return state
                val newDone = res.jsBool("doneToday", state.doneToday)
                val valuesObj = res.get("values", res) as? NativeObject
                val newValues = if (valuesObj != null) {
                    valuesObj.ids.associate { k ->
                        val v = valuesObj.get(k.toString(), valuesObj)
                        k.toString() to (v as? Number)?.toDouble() ?: 0.0
                    }
                } else state.values
                WidgetState(doneToday = newDone, values = newValues)
            } finally {
                Context.exit()
            }
        } catch (e: Exception) {
            state
        }
    }

    // ── Compose UI ────────────────────────────────────────────────────────────

    @Composable
    override fun Content(state: WidgetState?, onStateChange: (WidgetState) -> Unit) {
        val current = state ?: WidgetState()
        val view = remember(current) { renderView(current) }
        ScriptedWidgetCard(
            view = view,
            onAction = { onStateChange(applyAction(current)) }
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun WidgetState.toJS(
        cx: Context,
        scope: org.mozilla.javascript.Scriptable
    ): NativeObject {
        val obj = cx.newObject(scope) as NativeObject
        ScriptableObject.putProperty(obj, "doneToday", doneToday)
        val vals = cx.newObject(scope) as NativeObject
        values.forEach { (k, v) -> ScriptableObject.putProperty(vals, k, v) }
        ScriptableObject.putProperty(obj, "values", vals)
        return obj
    }
}

// ── Generic card Compose renderer ─────────────────────────────────────────────

@Composable
fun ScriptedWidgetCard(view: ScriptedView, onAction: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = view.title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (view.subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = view.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                )
            }
            if (view.value != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = view.value,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (view.progress != null) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { view.progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.outline
                )
            }
        }

        if (view.done) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(40.dp)
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = "Done",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        } else {
            Button(onClick = onAction) {
                Text(view.actionLabel, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
