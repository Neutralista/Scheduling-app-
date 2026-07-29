package com.waypoint.app.script

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.waypoint.app.AppLogger
import com.waypoint.app.notification.PlannerReminderScheduler
import com.waypoint.app.signal.DaySchedule
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
import org.mozilla.javascript.WrapFactory
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
  // onSecondaryAction(state, signals, scripts): called when the secondary widget button is pressed
  // (shown when widget() returns secondaryActionLabel). Also called by the test button in Scripts tab
  // when testActionLabel is set on the script object. Return new state or null to keep current.
  Script.prototype.onSecondaryAction = function(state, signals, scripts) { return null; };
  // onAnswer(state, answer, signals, scripts): called when the user responds to a dialog prompt.
  // answer is "yes"/"no" for yesno dialogs, the input string for text dialogs, or "cancel".
  // Return new state or null to keep current.
  Script.prototype.onAnswer   = function(state, answer, signals, scripts) { return null; };
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

/** Describes a modal prompt the wizard shows while onAnswer drives the flow. */
data class ScriptedDialog(
    val question: String,
    /** "yesno", "text", "time" (single clock picker), or "timerange" (start|end clock pickers). */
    val type: String = "yesno",
    val yesLabel: String = "Yes",
    val noLabel: String = "No",
    /** Default value / pre-fill for time/text inputs. */
    val placeholder: String = "",
    /** Second default for timerange (end time). */
    val placeholder2: String = ""
)

data class ScriptedView(
    val title: String = "",
    val subtitle: String? = null,
    val value: String? = null,
    val progress: Float? = null,
    val done: Boolean = false,
    val actionLabel: String = "Done",
    /** When non-null, a second button is rendered below the primary action button. */
    val secondaryActionLabel: String? = null,
    /** When non-null, a modal dialog is shown over the widget card for this step. */
    val dialog: ScriptedDialog? = null
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

@OptIn(DelicateCoroutinesApi::class)
private fun buildSignalsBridge(env: ScriptEnvironment, cx: Context, scope: Scriptable): NativeObject {
    val obj = cx.newObject(scope) as NativeObject

    // ── workSchedule ─────────────────────────────────────────────────────────
    try {
        val ws = cx.newObject(scope) as NativeObject
        val session = env.workSchedule.getTodaySession()
        val todaySchedule = env.workSchedule.getTodaySchedule()
        ScriptableObject.putProperty(ws, "shiftStart", todaySchedule.shiftStart?.displayString ?: "")
        ScriptableObject.putProperty(ws, "shiftEnd",   todaySchedule.shiftEnd?.displayString   ?: "")
        ScriptableObject.putProperty(ws, "isWorkDay",  todaySchedule.isWork)
        ScriptableObject.putProperty(ws, "isClockedIn",
            session.actualStartMillis != null && session.actualEndMillis == null)
        ScriptableObject.putProperty(ws, "clockedInAt",
            session.actualStartMillis?.let { java.util.Date(it).toString() } ?: "")
        ScriptableObject.putProperty(ws, "setShiftStart", object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
                val newTime = ShiftTime.parse(args.getOrNull(0)?.toString() ?: return null) ?: return null
                val key = env.workSchedule.dateKey(Calendar.getInstance())
                GlobalScope.launch(Dispatchers.IO) {
                    try { env.workSchedule.setDateOverride(key, todaySchedule.copy(shiftStart = newTime)) }
                    catch (e: Throwable) { AppLogger.e("WS", "setShiftStart failed", e) }
                }
                return null
            }
        })
        ScriptableObject.putProperty(ws, "setShiftEnd", object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
                val newTime = ShiftTime.parse(args.getOrNull(0)?.toString() ?: return null) ?: return null
                val key = env.workSchedule.dateKey(Calendar.getInstance())
                GlobalScope.launch(Dispatchers.IO) {
                    try { env.workSchedule.setDateOverride(key, todaySchedule.copy(shiftEnd = newTime)) }
                    catch (e: Throwable) { AppLogger.e("WS", "setShiftEnd failed", e) }
                }
                return null
            }
        })
        ScriptableObject.putProperty(ws, "getScheduleForDate", object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
                val cal = parseDateStr(args.getOrNull(0)?.toString() ?: return null) ?: return null
                val schedule = env.workSchedule.getSchedule(cal)
                val o = cx.newObject(scope) as NativeObject
                ScriptableObject.putProperty(o, "isWork", schedule.isWork)
                ScriptableObject.putProperty(o, "shiftStart", schedule.shiftStart?.displayString ?: "")
                ScriptableObject.putProperty(o, "shiftEnd",   schedule.shiftEnd?.displayString   ?: "")
                return o
            }
        })
        ScriptableObject.putProperty(ws, "setScheduleForDate", object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
                val dateArg = args.getOrNull(0)?.toString()
                AppLogger.i("WS", "setScheduleForDate called date=$dateArg")
                val cal  = parseDateStr(dateArg ?: return null) ?: run {
                    AppLogger.w("WS", "setScheduleForDate: bad date \"$dateArg\""); return null
                }
                val opts = args.getOrNull(1) as? NativeObject ?: run {
                    AppLogger.w("WS", "setScheduleForDate: opts not NativeObject"); return null
                }
                val key    = env.workSchedule.dateKey(cal)
                val isWork = (opts.get("isWork", opts) as? Boolean) ?: true
                val start  = opts.get("shiftStart", opts)?.toString()?.takeIf { it.isNotEmpty() }?.let { ShiftTime.parse(it) }
                val end    = opts.get("shiftEnd",   opts)?.toString()?.takeIf { it.isNotEmpty() }?.let { ShiftTime.parse(it) }
                AppLogger.i("WS", "setScheduleForDate: key=$key isWork=$isWork start=$start end=$end")
                GlobalScope.launch(Dispatchers.IO) {
                    try {
                        env.workSchedule.setDateOverride(key, DaySchedule(isWork, start, end))
                        AppLogger.i("WS", "setDateOverride done key=$key")
                    }
                    catch (e: Throwable) { AppLogger.e("WS", "setDateOverride failed", e) }
                }
                return null
            }
        })
        ScriptableObject.putProperty(ws, "setSchedulesForDates", object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
                val arg0 = args.getOrNull(0)
                AppLogger.i("WS", "setSchedulesForDates called arg0=${arg0?.javaClass?.simpleName}")
                val map = arg0 as? NativeObject ?: run {
                    AppLogger.w("WS", "setSchedulesForDates: arg0 not NativeObject, got ${arg0?.javaClass?.simpleName}")
                    return null
                }
                AppLogger.i("WS", "setSchedulesForDates: ${map.ids.size} keys")
                val overrides = mutableMapOf<String, DaySchedule>()
                for (rawKey in map.ids) {
                    val dateStr = rawKey.toString()
                    val cal = parseDateStr(dateStr)
                    if (cal == null) {
                        AppLogger.w("WS", "setSchedulesForDates: skipping bad date key \"$dateStr\"")
                        continue
                    }
                    val opts = map.get(dateStr, map) as? NativeObject
                    if (opts == null) {
                        AppLogger.w("WS", "setSchedulesForDates: opts for $dateStr not NativeObject")
                        continue
                    }
                    val key    = env.workSchedule.dateKey(cal)
                    val isWork = (opts.get("isWork", opts) as? Boolean) ?: true
                    val start  = opts.get("shiftStart", opts)?.toString()?.takeIf { it.isNotEmpty() }?.let { ShiftTime.parse(it) }
                    val end    = opts.get("shiftEnd",   opts)?.toString()?.takeIf { it.isNotEmpty() }?.let { ShiftTime.parse(it) }
                    overrides[key] = DaySchedule(isWork, start, end)
                }
                AppLogger.i("WS", "setSchedulesForDates: ${overrides.size} valid overrides, launching write")
                if (overrides.isNotEmpty()) {
                    val snapshot = overrides.toMap()
                    GlobalScope.launch(Dispatchers.IO) {
                        try {
                            env.workSchedule.setBulkDateOverrides(snapshot)
                            AppLogger.i("WS", "setBulkDateOverrides done: ${snapshot.size} dates")
                        }
                        catch (e: Throwable) { AppLogger.e("WS", "setBulkDateOverrides failed", e) }
                    }
                }
                return null
            }
        })
        ScriptableObject.putProperty(obj, "workSchedule", ws)
    } catch (e: Throwable) {
        AppLogger.e("Bridge", "workSchedule section failed: ${e.javaClass.name}: ${e.message}")
        ScriptableObject.putProperty(obj, "workSchedule", cx.newObject(scope))
    }

    // ── sleep ────────────────────────────────────────────────────────────────
    try {
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
    } catch (e: Throwable) {
        AppLogger.e("Bridge", "sleep section failed: ${e.javaClass.name}: ${e.message}")
        ScriptableObject.putProperty(obj, "sleep", cx.newObject(scope))
    }

    // ── calendar ─────────────────────────────────────────────────────────────
    try {
        val calObj = cx.newObject(scope) as NativeObject
        ScriptableObject.putProperty(calObj, "hasPermission", env.calendar.hasPermission())
        val calEvents = env.calendar.cachedEvents.map { evt ->
            val e = cx.newObject(scope) as NativeObject
            ScriptableObject.putProperty(e, "title",       evt.title)
            ScriptableObject.putProperty(e, "startMillis", evt.startMillis.toDouble())
            ScriptableObject.putProperty(e, "endMillis",   evt.endMillis.toDouble())
            ScriptableObject.putProperty(e, "allDay",      evt.allDay)
            e
        }
        ScriptableObject.putProperty(calObj, "events",
            cx.newArray(scope, calEvents.toTypedArray<Any?>()))
        ScriptableObject.putProperty(obj, "calendar", calObj)
    } catch (e: Throwable) {
        AppLogger.e("Bridge", "calendar section failed: ${e.javaClass.name}: ${e.message}")
        ScriptableObject.putProperty(obj, "calendar", cx.newObject(scope))
    }

    // ── health ───────────────────────────────────────────────────────────────
    try {
        val healthObj = cx.newObject(scope) as NativeObject
        ScriptableObject.putProperty(healthObj, "available",
            env.healthConnect.availability == HealthConnectAvailability.AVAILABLE)
        ScriptableObject.putProperty(healthObj, "steps", env.healthConnect.cachedSteps.toDouble())
        ScriptableObject.putProperty(obj, "health", healthObj)
    } catch (e: Throwable) {
        AppLogger.e("Bridge", "health section failed: ${e.javaClass.name}: ${e.message}")
        ScriptableObject.putProperty(obj, "health", cx.newObject(scope))
    }

    // ── notifications ─────────────────────────────────────────────────────────
    try {
        val androidCtx = env.context
        val notifObj = cx.newObject(scope) as NativeObject
        ScriptableObject.putProperty(notifObj, "scheduleWeekly", object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
                val day    = (args.getOrNull(0) as? Number)?.toInt() ?: return null
                val hour   = (args.getOrNull(1) as? Number)?.toInt() ?: return null
                val minute = (args.getOrNull(2) as? Number)?.toInt() ?: 0
                PlannerReminderScheduler.scheduleWeekly(androidCtx, day, hour, minute)
                return null
            }
        })
        ScriptableObject.putProperty(notifObj, "cancel", object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
                PlannerReminderScheduler.cancel(androidCtx)
                return null
            }
        })
        ScriptableObject.putProperty(notifObj, "sendNow", object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any? {
                PlannerReminderScheduler.sendNow(androidCtx)
                return null
            }
        })
        ScriptableObject.putProperty(obj, "notifications", notifObj)
    } catch (e: Throwable) {
        AppLogger.e("Bridge", "notifications section failed: ${e.javaClass.name}: ${e.message}")
        ScriptableObject.putProperty(obj, "notifications", cx.newObject(scope))
    }

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

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun parseDateStr(s: String): Calendar? {
    val parts = s.split("-")
    if (parts.size != 3) return null
    val y = parts[0].toIntOrNull() ?: return null
    val m = parts[1].toIntOrNull()?.minus(1) ?: return null
    val d = parts[2].toIntOrNull() ?: return null
    return Calendar.getInstance().apply { set(y, m, d, 0, 0, 0); set(Calendar.MILLISECOND, 0) }
}

// ── Settings spec ─────────────────────────────────────────────────────────────

/** A single declarative field returned by a script's settings() function. */
data class SettingSpec(
    val id: String,
    val label: String,
    /** "toggle", "time", or "text" */
    val type: String,
    val defaultValue: String,
    /** Optional section header to group fields visually. */
    val section: String? = null
)

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
    override val replacesId: String?,
    /** Label for a test button shown in the Scripts tab. Non-null when the JS object sets testActionLabel. */
    val testActionLabel: String?
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
                    val signalsJs = runCatching { buildSignalsBridge(env, cx, scope) }
                        .onFailure { AppLogger.e("JS[$id]", "buildSignalsBridge (onRegister) threw ${it.javaClass.name}", it) }
                        .getOrNull() ?: cx.newObject(scope) as NativeObject
                    val scriptsJs = runCatching { buildScriptsBridge(env, cx, scope) }.getOrNull()
                        ?: cx.newObject(scope) as NativeObject
                    fn.call(cx, scope, obj, arrayOf(signalsJs, scriptsJs))
                }
            } finally { Context.exit() }
        } catch (e: Throwable) { AppLogger.e("JS[$id]", "onRegister threw ${e.javaClass.name}", e) }
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

                val testActionLabel = obj.jsString("testActionLabel")

                ScriptedModule(source, id, displayName, WidgetUiConfig(size), hasWidget, replacesId, testActionLabel)
            } finally {
                Context.exit()
            }
        }

        private fun rhino(): Context = Context.enter().also { cx ->
            cx.optimizationLevel = -1
            cx.wrapFactory = object : WrapFactory() {
                // JavaMembers (used by NativeJavaObject) has a static initializer that references
                // javax.lang.model.SourceVersion, which does not exist on Android's runtime.
                // Once it fails to initialize it stays broken for the entire process lifetime,
                // breaking all subsequent JS→Java bridge calls with NoClassDefFoundError.
                // Returning null here prevents NativeJavaObject creation entirely; our bridge
                // exposes everything as NativeObject/BaseFunction so raw wrapping is never needed.
                override fun wrapAsJavaObject(
                    cx: Context?, scope: Scriptable?, javaObject: Any?, staticType: Class<*>?
                ): Scriptable? = null
            }
        }
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
                val signalsJs = runCatching { env?.let { buildSignalsBridge(it, cx, scope) } }
                    .onFailure { AppLogger.e("JS[$id]", "buildSignalsBridge (widget) threw ${it.javaClass.name}", it) }
                    .getOrNull() ?: cx.newObject(scope) as NativeObject
                val scriptsJs = runCatching { env?.let { buildScriptsBridge(it, cx, scope) } }.getOrNull()
                    ?: cx.newObject(scope) as NativeObject
                val res = fn.call(cx, scope, obj, arrayOf(stateJs, signalsJs, scriptsJs)) as? NativeObject
                    ?: return ScriptedView(title = displayName)
                val dialogObj = res.get("dialog", res)
                val dialog = if (dialogObj is NativeObject) ScriptedDialog(
                    question     = dialogObj.jsString("question")     ?: "",
                    type         = dialogObj.jsString("type")         ?: "yesno",
                    yesLabel     = dialogObj.jsString("yesLabel")     ?: "Yes",
                    noLabel      = dialogObj.jsString("noLabel")      ?: "No",
                    placeholder  = dialogObj.jsString("placeholder")  ?: "",
                    placeholder2 = dialogObj.jsString("placeholder2") ?: ""
                ) else null
                ScriptedView(
                    title                = res.jsString("title")               ?: displayName,
                    subtitle             = res.jsString("subtitle"),
                    value                = res.jsString("value"),
                    progress             = res.jsFloat("progress"),
                    done                 = res.jsBool("done"),
                    actionLabel          = res.jsString("actionLabel")          ?: "Done",
                    secondaryActionLabel = res.jsString("secondaryActionLabel"),
                    dialog               = dialog
                )
            } finally { Context.exit() }
        } catch (e: Throwable) {
            AppLogger.e("JS[$id]", "widget threw ${e.javaClass.name}", e)
            ScriptedView(title = displayName, subtitle = "⚠ ${e.message}")
        }
    }

    // ── Evaluate onAction() ───────────────────────────────────────────────────

    fun applyAction(state: ScriptState): ScriptState {
        AppLogger.i("JS[$id]", "onAction")
        return try {
            val cx = rhino()
            try {
                val (scope, obj) = buildScope(cx)
                val fn = ScriptableObject.getProperty(obj, "onAction") as? org.mozilla.javascript.Function
                    ?: return state.copy(doneToday = !state.doneToday)
                val stateJs  = state.toJS(cx, scope)
                val signalsJs = runCatching { env?.let { buildSignalsBridge(it, cx, scope) } }
                    .onFailure { AppLogger.e("JS[$id]", "buildSignalsBridge (onAction) threw ${it.javaClass.name}", it) }
                    .getOrNull() ?: cx.newObject(scope) as NativeObject
                val scriptsJs = runCatching { env?.let { buildScriptsBridge(it, cx, scope) } }.getOrNull()
                    ?: cx.newObject(scope) as NativeObject
                val res = fn.call(cx, scope, obj, arrayOf(stateJs, signalsJs, scriptsJs)) as? NativeObject
                    ?: return state
                res.toScriptState(state)
            } finally { Context.exit() }
        } catch (e: Throwable) {
            AppLogger.e("JS[$id]", "onAction threw ${e.javaClass.name}", e)
            state
        }
    }

    // ── Evaluate onSecondaryAction() ──────────────────────────────────────────

    fun applySecondaryAction(state: ScriptState): ScriptState {
        return try {
            val cx = rhino()
            try {
                val (scope, obj) = buildScope(cx)
                val fn = ScriptableObject.getProperty(obj, "onSecondaryAction") as? org.mozilla.javascript.Function
                    ?: return state
                val stateJs   = state.toJS(cx, scope)
                val signalsJs = runCatching { env?.let { buildSignalsBridge(it, cx, scope) } }
                    .onFailure { AppLogger.e("JS[$id]", "buildSignalsBridge (onSecondaryAction) threw ${it.javaClass.name}", it) }
                    .getOrNull() ?: cx.newObject(scope) as NativeObject
                val scriptsJs = runCatching { env?.let { buildScriptsBridge(it, cx, scope) } }.getOrNull()
                    ?: cx.newObject(scope) as NativeObject
                val res = fn.call(cx, scope, obj, arrayOf(stateJs, signalsJs, scriptsJs))
                (res as? NativeObject)?.toScriptState(state) ?: state
            } finally { Context.exit() }
        } catch (e: Throwable) {
            AppLogger.e("JS[$id]", "onSecondaryAction threw ${e.javaClass.name}", e)
            state
        }
    }

    // ── Evaluate onAnswer() ───────────────────────────────────────────────────

    fun applyAnswer(state: ScriptState, answer: String): ScriptState {
        val step    = state.values["step"]?.toInt() ?: 0
        val substep = state.values["substep"]?.toInt() ?: 0
        AppLogger.i("JS[$id]", "onAnswer step=$step substep=$substep answer=\"$answer\"")
        return try {
            AppLogger.i("JS[$id]", "onAnswer: init rhino")
            val cx = rhino()
            try {
                AppLogger.i("JS[$id]", "onAnswer: buildScope")
                val (scope, obj) = buildScope(cx)
                val fn = ScriptableObject.getProperty(obj, "onAnswer") as? org.mozilla.javascript.Function
                    ?: return state
                val stateJs   = state.toJS(cx, scope)
                AppLogger.i("JS[$id]", "onAnswer: buildSignals")
                val signalsJs = runCatching { env?.let { buildSignalsBridge(it, cx, scope) } }
                    .onFailure { AppLogger.e("JS[$id]", "buildSignalsBridge (onAnswer) threw ${it.javaClass.name}", it) }
                    .getOrNull() ?: cx.newObject(scope) as NativeObject
                val scriptsJs = runCatching { env?.let { buildScriptsBridge(it, cx, scope) } }.getOrNull()
                    ?: cx.newObject(scope) as NativeObject
                AppLogger.i("JS[$id]", "onAnswer: calling fn")
                val res = fn.call(cx, scope, obj, arrayOf(stateJs, answer, signalsJs, scriptsJs))
                AppLogger.i("JS[$id]", "onAnswer: fn returned, toScriptState")
                val newState = (res as? NativeObject)?.toScriptState(state) ?: state
                val newStep = newState.values["step"]?.toInt() ?: 0
                AppLogger.i("JS[$id]", "onAnswer → step=$newStep")
                newState
            } finally { Context.exit() }
        } catch (e: Throwable) {
            AppLogger.e("JS[$id]", "onAnswer threw ${e.javaClass.name}", e)
            state
        }
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
                val signalsJs = runCatching { env?.let { buildSignalsBridge(it, cx, scope) } }.getOrNull()
                    ?: cx.newObject(scope) as NativeObject
                val scriptsJs = runCatching { env?.let { buildScriptsBridge(it, cx, scope) } }.getOrNull()
                    ?: cx.newObject(scope) as NativeObject
                val res = fn.call(cx, scope, obj, arrayOf(stateJs, signalsJs, scriptsJs))
                (res as? NativeObject)?.toScriptState(state) ?: state
            } finally { Context.exit() }
        } catch (e: Throwable) {
            AppLogger.e("JS[$id]", "onTick threw ${e.javaClass.name}", e)
            state
        }
    }

    // ── Evaluate settings() ───────────────────────────────────────────────────

    fun getSettings(): List<SettingSpec> {
        return try {
            val cx = rhino()
            try {
                val (scope, obj) = buildScope(cx)
                val fn = ScriptableObject.getProperty(obj, "settings") as? org.mozilla.javascript.Function
                    ?: return emptyList()
                val arr = fn.call(cx, scope, obj, emptyArray()) as? org.mozilla.javascript.NativeArray
                    ?: return emptyList()
                (0 until arr.length.toInt()).mapNotNull { i ->
                    val item = arr.get(i, arr) as? NativeObject ?: return@mapNotNull null
                    SettingSpec(
                        id           = item.jsString("id")           ?: return@mapNotNull null,
                        label        = item.jsString("label")        ?: "",
                        type         = item.jsString("type")         ?: "text",
                        defaultValue = item.jsString("defaultValue") ?: "",
                        section      = item.jsString("section")
                    )
                }
            } finally { Context.exit() }
        } catch (_: Exception) { emptyList() }
    }

    // ── Compose UI ────────────────────────────────────────────────────────────

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun WidgetContent(state: ScriptState?, onStateChange: (ScriptState) -> Unit) {
        val current = state ?: ScriptState()
        val view = remember(current) { renderView(current) }
        ScriptedWidgetCard(
            view = view,
            onAction = { onStateChange(applyAction(current)) },
            onSecondaryAction = if (view.secondaryActionLabel != null) {
                { onStateChange(applySecondaryAction(current)) }
            } else null
        )

        val dialog = view.dialog
        if (dialog != null) {
            when (dialog.type) {
                "text" -> {
                    var input by remember(current) { mutableStateOf(dialog.placeholder) }
                    AlertDialog(
                        onDismissRequest = {},
                        title = { Text(view.title) },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(dialog.question, style = MaterialTheme.typography.bodyMedium)
                                OutlinedTextField(
                                    value = input,
                                    onValueChange = { input = it },
                                    singleLine = true,
                                    placeholder = { Text(dialog.placeholder) }
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { onStateChange(applyAnswer(current, input.ifBlank { dialog.placeholder })) }) {
                                Text("Confirm")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { onStateChange(applyAnswer(current, "cancel")) }) {
                                Text("Cancel")
                            }
                        }
                    )
                }
                "time" -> {
                    val initH = dialog.placeholder.split(":").getOrNull(0)?.toIntOrNull() ?: 9
                    val initM = dialog.placeholder.split(":").getOrNull(1)?.toIntOrNull() ?: 0
                    val tpState = rememberTimePickerState(initialHour = initH, initialMinute = initM, is24Hour = true)
                    BasicAlertDialog(onDismissRequest = {}) {
                        Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = dialog.question,
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp)
                                )
                                TimePicker(state = tpState)
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    TextButton(onClick = { onStateChange(applyAnswer(current, "cancel")) }) { Text("Cancel") }
                                    Spacer(Modifier.width(8.dp))
                                    TextButton(onClick = {
                                        onStateChange(applyAnswer(current, "%02d:%02d".format(tpState.hour, tpState.minute)))
                                    }) { Text("OK") }
                                }
                            }
                        }
                    }
                }
                "workday" -> {
                    var isWork by remember(current) { mutableStateOf(true) }
                    var pickingStart by remember(current) { mutableStateOf(true) }
                    val sh = remember(current) { dialog.placeholder.split(":").getOrNull(0)?.toIntOrNull() ?: 9 }
                    val sm = remember(current) { dialog.placeholder.split(":").getOrNull(1)?.toIntOrNull() ?: 0 }
                    val eh = remember(current) { dialog.placeholder2.split(":").getOrNull(0)?.toIntOrNull() ?: 17 }
                    val em = remember(current) { dialog.placeholder2.split(":").getOrNull(1)?.toIntOrNull() ?: 0 }
                    val startState = rememberTimePickerState(initialHour = sh, initialMinute = sm, is24Hour = true)
                    val endState   = rememberTimePickerState(initialHour = eh, initialMinute = em, is24Hour = true)
                    BasicAlertDialog(onDismissRequest = {}) {
                        Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = dialog.question,
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    FilterChip(
                                        selected = isWork,
                                        onClick = { isWork = true; pickingStart = true },
                                        label = { Text(dialog.yesLabel) },
                                        modifier = Modifier.weight(1f)
                                    )
                                    FilterChip(
                                        selected = !isWork,
                                        onClick = { isWork = false },
                                        label = { Text(dialog.noLabel) },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                if (isWork) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Spacer(Modifier.height(12.dp))
                                        Text(
                                            text = if (pickingStart) "Shift start" else "Shift end",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        Spacer(Modifier.height(8.dp))
                                        if (pickingStart) TimePicker(state = startState)
                                        else             TimePicker(state = endState)
                                    }
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    TextButton(onClick = { onStateChange(applyAnswer(current, "cancel")) }) {
                                        Text("Cancel")
                                    }
                                    TextButton(onClick = {
                                        when {
                                            !isWork -> onStateChange(applyAnswer(current, "off"))
                                            pickingStart -> pickingStart = false
                                            else -> {
                                                val ans = "work|%02d:%02d|%02d:%02d".format(
                                                    startState.hour, startState.minute,
                                                    endState.hour,   endState.minute
                                                )
                                                onStateChange(applyAnswer(current, ans))
                                            }
                                        }
                                    }) {
                                        Text(when {
                                            !isWork      -> "Confirm"
                                            pickingStart -> "Next"
                                            else         -> "Confirm"
                                        })
                                    }
                                }
                            }
                        }
                    }
                }
                "timerange" -> {
                    val sh = remember(current) { dialog.placeholder.split(":").getOrNull(0)?.toIntOrNull() ?: 9 }
                    val sm = remember(current) { dialog.placeholder.split(":").getOrNull(1)?.toIntOrNull() ?: 0 }
                    val eh = remember(current) { dialog.placeholder2.split(":").getOrNull(0)?.toIntOrNull() ?: 17 }
                    val em = remember(current) { dialog.placeholder2.split(":").getOrNull(1)?.toIntOrNull() ?: 0 }
                    val startState = rememberTimePickerState(initialHour = sh, initialMinute = sm, is24Hour = true)
                    val endState   = rememberTimePickerState(initialHour = eh, initialMinute = em, is24Hour = true)
                    var pickingStart by remember(current) { mutableStateOf(true) }
                    BasicAlertDialog(onDismissRequest = {}) {
                        Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = dialog.question,
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    FilterChip(
                                        selected = pickingStart,
                                        onClick = { pickingStart = true },
                                        label = { Text("%02d:%02d".format(startState.hour, startState.minute)) },
                                        leadingIcon = if (pickingStart) {{ Icon(Icons.Filled.Check, null, Modifier.size(16.dp)) }} else null,
                                        modifier = Modifier.weight(1f)
                                    )
                                    FilterChip(
                                        selected = !pickingStart,
                                        onClick = { pickingStart = false },
                                        label = { Text("%02d:%02d".format(endState.hour, endState.minute)) },
                                        leadingIcon = if (!pickingStart) {{ Icon(Icons.Filled.Check, null, Modifier.size(16.dp)) }} else null,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                                if (pickingStart) TimePicker(state = startState)
                                else             TimePicker(state = endState)
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    TextButton(onClick = { onStateChange(applyAnswer(current, "cancel")) }) {
                                        Text("Cancel")
                                    }
                                    TextButton(onClick = {
                                        val ans = "%02d:%02d|%02d:%02d".format(
                                            startState.hour, startState.minute,
                                            endState.hour,   endState.minute
                                        )
                                        onStateChange(applyAnswer(current, ans))
                                    }) { Text("OK") }
                                }
                            }
                        }
                    }
                }
                else -> {
                    AlertDialog(
                        onDismissRequest = {},
                        title = { Text(view.title) },
                        text = { Text(dialog.question, style = MaterialTheme.typography.bodyMedium) },
                        confirmButton = {
                            TextButton(onClick = { onStateChange(applyAnswer(current, "yes")) }) {
                                Text(dialog.yesLabel)
                            }
                        },
                        dismissButton = {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = { onStateChange(applyAnswer(current, "cancel")) }) {
                                    Text("Cancel", color = MaterialTheme.colorScheme.outline)
                                }
                                TextButton(onClick = { onStateChange(applyAnswer(current, "no")) }) {
                                    Text(dialog.noLabel)
                                }
                            }
                        }
                    )
                }
            }
        }
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
fun ScriptedWidgetCard(
    view: ScriptedView,
    onAction: () -> Unit,
    onSecondaryAction: (() -> Unit)? = null
) {
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
        } else if (view.dialog == null) {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Button(onClick = onAction) {
                    Text(view.actionLabel, style = MaterialTheme.typography.labelMedium)
                }
                if (onSecondaryAction != null && view.secondaryActionLabel != null) {
                    TextButton(onClick = onSecondaryAction) {
                        Text(
                            view.secondaryActionLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}
