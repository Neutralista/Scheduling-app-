package com.waypoint.app.script

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
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.waypoint.app.signal.HealthConnectAvailability
import com.waypoint.app.signal.ShiftTime
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Context
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import java.util.Calendar

// ── JS base injected into every user script's scope ──────────────────────────

private val BASE_JS = """
var Script = (function() {
  function Script(opts) {
    this.id          = opts.id;
    this.displayName = opts.displayName || opts.id;
    this.size        = opts.size || 'WIDE_ROW';
  }
  Script.prototype.settings   = function() { return []; };
  // widget(state, signals, scripts): return a view-spec object, or leave null for no widget.
  Script.prototype.widget     = null;
  // onRegister(signals, scripts): called once when the script is first loaded. Use for setup.
  Script.prototype.onRegister = function(signals, scripts) {};
  // onTick(state, signals, scripts): called when state changes. Return new state or null.
  Script.prototype.onTick     = function(state, signals, scripts) { return null; };
  // onAction(state, signals, scripts): called when the widget button is pressed.
  Script.prototype.onAction   = function(state, signals, scripts) {
    return Object.assign({}, state, { doneToday: !state.doneToday });
  };
  return Script;
})();

// Built-in script IDs — use these to read/write built-in state or to override a built-in
// entirely by creating a script with the same id.
var BUILTIN = {
  WORK_SCHEDULE:  'built_in.work_schedule',
  SLEEP_SCHEDULE: 'built_in.sleep_schedule'
};

// Kept for backward-compat: scripts written against the old HabitWidget API still work.
var HabitWidget = Script;
""".trimIndent()

// ── View spec produced by JS widget() ────────────────────────────────────────

data class ScriptedView(
    val title: String = "",
    val subtitle: String? = null,
    val value: String? = null,
    val progress: Float? = null,
    val done: Boolean = false,
    val actionLabel: String = "Done"
)

// ── NativeObject helpers ──────────────────────────────────────────────────────

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

// ── Signals bridge: built-in state exposed to JS ─────────────────────────────

private fun buildSignalsBridge(env: ScriptEnvironment, cx: Context, scope: Scriptable): NativeObject {
    val obj = cx.newObject(scope) as NativeObject

    // workSchedule
    val ws = cx.newObject(scope) as NativeObject
    val session = env.workSchedule.getTodaySession()
    val todaySchedule = env.workSchedule.getTodaySchedule()
    ScriptableObject.putProperty(ws, "shiftStart", todaySchedule.shiftStart?.displayString ?: "")
    ScriptableObject.putProperty(ws, "shiftEnd",   todaySchedule.shiftEnd?.displayString   ?: "")
    ScriptableObject.putProperty(ws, "isWorkDay",  todaySchedule.isWork)
    ScriptableObject.putProperty(ws, "isClockedIn", session.actualStartMillis != null && session.actualEndMillis == null)
    ScriptableObject.putProperty(ws, "clockedInAt",
        session.actualStartMillis?.let { java.util.Date(it).toString() } ?: "")
    // write: signals.workSchedule.setShiftStart('09:00')
    @OptIn(DelicateCoroutinesApi::class)
    ScriptableObject.putProperty(ws, "setShiftStart", object : BaseFunction() {
        override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val newTime = ShiftTime.parse(args.getOrNull(0)?.toString() ?: return null) ?: return null
            val key = env.workSchedule.dateKey(Calendar.getInstance())
            GlobalScope.launch(Dispatchers.IO) {
                env.workSchedule.setDateOverride(key, todaySchedule.copy(shiftStart = newTime))
            }
            return null
        }
    })
    @OptIn(DelicateCoroutinesApi::class)
    ScriptableObject.putProperty(ws, "setShiftEnd", object : BaseFunction() {
        override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val newTime = ShiftTime.parse(args.getOrNull(0)?.toString() ?: return null) ?: return null
            val key = env.workSchedule.dateKey(Calendar.getInstance())
            GlobalScope.launch(Dispatchers.IO) {
                env.workSchedule.setDateOverride(key, todaySchedule.copy(shiftEnd = newTime))
            }
            return null
        }
    })
    ScriptableObject.putProperty(obj, "workSchedule", ws)

    // sleep
    val sleepObj = cx.newObject(scope) as NativeObject
    val sleepConfig = env.sleepStore.load()
    ScriptableObject.putProperty(sleepObj, "bedTime",  sleepConfig.preferredBedTime.displayString)
    ScriptableObject.putProperty(sleepObj, "wakeTime", sleepConfig.preferredWakeTime.displayString)
    ScriptableObject.putProperty(sleepObj, "enabled",  sleepConfig.enabled)
    ScriptableObject.putProperty(sleepObj, "setBedTime", object : BaseFunction() {
        override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val t = ShiftTime.parse(args.getOrNull(0)?.toString() ?: return null) ?: return null
            env.sleepStore.setPreferredBedTime(t)
            return null
        }
    })
    ScriptableObject.putProperty(sleepObj, "setWakeTime", object : BaseFunction() {
        override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val t = ShiftTime.parse(args.getOrNull(0)?.toString() ?: return null) ?: return null
            env.sleepStore.setPreferredWakeTime(t)
            return null
        }
    })
    ScriptableObject.putProperty(obj, "sleep", sleepObj)

    // calendar
    val calObj = cx.newObject(scope) as NativeObject
    ScriptableObject.putProperty(calObj, "hasPermission", env.calendar.hasPermission())
    val calEvents = env.calendar.cachedEvents.map { evt ->
        val e = cx.newObject(scope) as NativeObject
        ScriptableObject.putProperty(e, "title", evt.title)
        ScriptableObject.putProperty(e, "startMillis", evt.startMillis)
        ScriptableObject.putProperty(e, "endMillis", evt.endMillis)
        ScriptableObject.putProperty(e, "allDay", evt.allDay)
        e
    }
    ScriptableObject.putProperty(calObj, "events", cx.newArray(scope, calEvents.toTypedArray()))
    ScriptableObject.putProperty(obj, "calendar", calObj)

    // health
    val healthObj = cx.newObject(scope) as NativeObject
    ScriptableObject.putProperty(healthObj, "available",
        env.healthConnect.availability == HealthConnectAvailability.AVAILABLE)
    ScriptableObject.putProperty(healthObj, "steps", env.healthConnect.cachedSteps.toDouble())
    ScriptableObject.putProperty(obj, "health", healthObj)

    return obj
}

// ── Scripts bridge: peer script state exposed to JS ──────────────────────────

private fun buildScriptsBridge(env: ScriptEnvironment, cx: Context, scope: Scriptable): NativeObject {
    val obj = cx.newObject(scope) as NativeObject

    ScriptableObject.putProperty(obj, "get", object : BaseFunction() {
        override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val id = args.getOrNull(0)?.toString() ?: return null
            val state = env.getScriptState(id) ?: return null
            return state.toJS(cx, scope)
        }
    })
    ScriptableObject.putProperty(obj, "set", object : BaseFunction() {
        override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
            val id    = args.getOrNull(0)?.toString() ?: return null
            val jsState = args.getOrNull(1) as? NativeObject ?: return null
            env.setScriptState(id, jsState.toScriptState(env.getScriptState(id) ?: ScriptState()))
            return null
        }
    })

    return obj
}

// ── ScriptedModule ────────────────────────────────────────────────────────────

/**
 * An [AppScript] whose behaviour is entirely defined by user-provided JavaScript.
 *
 * The JS module must end with `new YourScript()`. Minimal form:
 *
 *   function StepGoal() {
 *     Script.call(this, { id: 'step_goal', displayName: 'Step Goal' });
 *   }
 *   StepGoal.prototype = Object.create(Script.prototype);
 *
 *   // Declare a widget (optional):
 *   StepGoal.prototype.widget = function(state, signals, scripts) {
 *     return {
 *       title:       'Steps today',
 *       value:       state.values.steps || 0,
 *       progress:    (state.values.steps || 0) / 10000,
 *       done:        (state.values.steps || 0) >= 10000,
 *       actionLabel: '+1000'
 *     };
 *   };
 *
 *   // React to state changes (optional):
 *   StepGoal.prototype.onTick = function(state, signals, scripts) {
 *     // e.g. read signals.workSchedule.shiftEnd
 *     return null; // return new state or null to keep current
 *   };
 *
 *   new StepGoal()
 */
class ScriptedModule private constructor(
    val source: String,
    override val id: String,
    override val displayName: String,
    val uiConfig: WidgetUiConfig,
    override val hasWidget: Boolean,
    override val replacesId: String?
) : AppScript {

    override val isUserScript: Boolean get() = true

    private var env: ScriptEnvironment? = null

    override fun onAttached(env: ScriptEnvironment) {
        this.env = env
        try {
            val cx = rhino()
            try {
                val (scope, obj) = buildScope(cx)
                val fn = ScriptableObject.getProperty(obj, "onRegister") as? org.mozilla.javascript.Function
                if (fn != null) {
                    val signalsJs = buildSignalsBridge(env, cx, scope)
                    val scriptsJs = buildScriptsBridge(env, cx, scope)
                    fn.call(cx, scope, obj, arrayOf(signalsJs, scriptsJs))
                }
            } finally { Context.exit() }
        } catch (_: Exception) {}
    }

    companion object {
        fun fromSource(source: String): ScriptedModule {
            val cx = rhino()
            return try {
                val scope = cx.initSafeStandardObjects()
                cx.evaluateString(scope, BASE_JS, "<base>", 1, null)
                val result = cx.evaluateString(scope, source, "<script>", 1, null)
                val obj = result as? NativeObject
                    ?: throw IllegalArgumentException(
                        "The last expression must produce a script instance — e.g. new MyScript()"
                    )
                val id = obj.jsString("id")
                    ?: throw IllegalArgumentException("Script must set an id property")
                val displayName = obj.jsString("displayName") ?: id
                val size = when (obj.jsString("size")?.uppercase()) {
                    "SMALL_TILE" -> WidgetSize.SMALL_TILE
                    "FULL_CARD"  -> WidgetSize.FULL_CARD
                    else         -> WidgetSize.WIDE_ROW
                }
                // hasWidget: true if the prototype defines a non-null widget function
                val widgetProp = ScriptableObject.getProperty(obj, "widget")
                val hasWidget = widgetProp != null
                    && widgetProp !is Undefined
                    && widgetProp != ScriptableObject.NOT_FOUND
                    && widgetProp !is Boolean

                // replacesId: any script using a built_in.* id is overriding that built-in
                val replacesId = if (id.startsWith("built_in.")) id else null

                ScriptedModule(source, id, displayName, WidgetUiConfig(size), hasWidget, replacesId)
            } finally {
                Context.exit()
            }
        }

        private fun rhino(): Context = Context.enter().also { it.optimizationLevel = -1 }
    }

    // ── Evaluate widget() ─────────────────────────────────────────────────────

    fun renderView(state: ScriptState): ScriptedView {
        return try {
            val cx = rhino()
            try {
                val (scope, obj) = buildScope(cx)
                val fn = ScriptableObject.getProperty(obj, "widget") as? org.mozilla.javascript.Function
                    ?: return ScriptedView(title = displayName)
                val stateJs   = state.toJS(cx, scope)
                val signalsJs = env?.let { buildSignalsBridge(it, cx, scope) } ?: cx.newObject(scope) as NativeObject
                val scriptsJs = env?.let { buildScriptsBridge(it, cx, scope) } ?: cx.newObject(scope) as NativeObject
                val res = fn.call(cx, scope, obj, arrayOf(stateJs, signalsJs, scriptsJs)) as? NativeObject
                    ?: return ScriptedView(title = displayName)
                ScriptedView(
                    title       = res.jsString("title")       ?: displayName,
                    subtitle    = res.jsString("subtitle"),
                    value       = res.jsString("value"),
                    progress    = res.jsFloat("progress"),
                    done        = res.jsBool("done"),
                    actionLabel = res.jsString("actionLabel") ?: "Done"
                )
            } finally { Context.exit() }
        } catch (e: Exception) {
            ScriptedView(title = displayName, subtitle = "⚠ ${e.message}")
        }
    }

    // ── Evaluate onAction() ───────────────────────────────────────────────────

    fun applyAction(state: ScriptState): ScriptState {
        return try {
            val cx = rhino()
            try {
                val (scope, obj) = buildScope(cx)
                val fn = ScriptableObject.getProperty(obj, "onAction") as? org.mozilla.javascript.Function
                    ?: return state.copy(doneToday = !state.doneToday)
                val stateJs  = state.toJS(cx, scope)
                val signalsJs = env?.let { buildSignalsBridge(it, cx, scope) } ?: cx.newObject(scope) as NativeObject
                val scriptsJs = env?.let { buildScriptsBridge(it, cx, scope) } ?: cx.newObject(scope) as NativeObject
                val res = fn.call(cx, scope, obj, arrayOf(stateJs, signalsJs, scriptsJs)) as? NativeObject
                    ?: return state
                res.toScriptState(state)
            } finally { Context.exit() }
        } catch (e: Exception) { state }
    }

    // ── Evaluate onTick() ─────────────────────────────────────────────────────

    fun tick(state: ScriptState): ScriptState {
        return try {
            val cx = rhino()
            try {
                val (scope, obj) = buildScope(cx)
                val fn = ScriptableObject.getProperty(obj, "onTick") as? org.mozilla.javascript.Function
                    ?: return state
                val stateJs   = state.toJS(cx, scope)
                val signalsJs = env?.let { buildSignalsBridge(it, cx, scope) } ?: cx.newObject(scope) as NativeObject
                val scriptsJs = env?.let { buildScriptsBridge(it, cx, scope) } ?: cx.newObject(scope) as NativeObject
                val res = fn.call(cx, scope, obj, arrayOf(stateJs, signalsJs, scriptsJs))
                (res as? NativeObject)?.toScriptState(state) ?: state
            } finally { Context.exit() }
        } catch (e: Exception) { state }
    }

    // ── Compose UI ────────────────────────────────────────────────────────────

    @Composable
    override fun WidgetContent(state: ScriptState?, onStateChange: (ScriptState) -> Unit) {
        val current = state ?: ScriptState()
        val view = remember(current) { renderView(current) }
        ScriptedWidgetCard(view = view, onAction = { onStateChange(applyAction(current)) })
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildScope(cx: Context): Pair<Scriptable, NativeObject> {
        val scope = cx.initSafeStandardObjects()
        cx.evaluateString(scope, BASE_JS, "<base>", 1, null)
        val obj = cx.evaluateString(scope, source, "<script>", 1, null) as? NativeObject
            ?: throw IllegalStateException("Script did not return an object")
        return scope to obj
    }
}

// ── JS ↔ ScriptState conversion ───────────────────────────────────────────────

internal fun ScriptState.toJS(cx: Context, scope: Scriptable): NativeObject {
    val obj = cx.newObject(scope) as NativeObject
    ScriptableObject.putProperty(obj, "doneToday", doneToday)
    val vals = cx.newObject(scope) as NativeObject
    values.forEach { (k, v) -> ScriptableObject.putProperty(vals, k, v) }
    ScriptableObject.putProperty(obj, "values", vals)
    val setts = cx.newObject(scope) as NativeObject
    settings.forEach { (k, v) -> ScriptableObject.putProperty(setts, k, v) }
    ScriptableObject.putProperty(obj, "settings", setts)
    return obj
}

internal fun NativeObject.toScriptState(base: ScriptState): ScriptState {
    val newDone = jsBool("doneToday", base.doneToday)
    val valuesObj = get("values", this) as? NativeObject
    val newValues = valuesObj?.ids?.associate { k ->
        k.toString() to ((valuesObj.get(k.toString(), valuesObj) as? Number)?.toDouble() ?: 0.0)
    } ?: base.values
    val settingsObj = get("settings", this) as? NativeObject
    val newSettings = settingsObj?.ids?.associate { k ->
        k.toString() to settingsObj.get(k.toString(), settingsObj).toString()
    } ?: base.settings
    return ScriptState(doneToday = newDone, values = newValues, settings = newSettings)
}

// ── Shared widget card renderer ───────────────────────────────────────────────

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
                        Icons.Default.Check, contentDescription = "Done",
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
