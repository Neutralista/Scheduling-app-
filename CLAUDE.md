# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

# Waypoint — Project Notes

## Build

```bash
# Assemble debug APK (builds without running)
bash gradlew :app:assembleDebug

# Compile Kotlin only (fastest error check)
bash gradlew :app:compileDebugKotlin
```

Note: `./gradlew` is broken in this repo's shell — always use `bash gradlew`.

## App structure

7 tabs in `HomeScreen.kt` (index order): **Plan · History · Tasks · Blocks · Modules · Alarms · Settings**

`MainActivity` → `WaypointApplication.env: RealScriptEnvironment` is the single shared dependency container. Everything that needs a store or signal should pull it from `env`, not create its own instance. `blockSessionStore` in particular must always come from `env` — creating a second instance loses the shared `sessionFlow`.

Entry point for init failures: `WaypointApplication.startupCrash` is non-null when `onCreate` threw. `MainActivity` shows a crash-recovery UI when this is set.

## Architecture

- Scripts are registered in `WaypointApplication.kt` via `ScriptRegistry.register()`
- Built-in scripts: `WorkScheduleScript`, `SleepScheduleScript`
- User scripts: loaded from `ScriptStore` (`filesDir/scripts/<id>.js`) on launch
- Script state shape: `ScriptState(doneToday, values: Map<String,Double>, settings: Map<String,String>)`
- State persistence: `ScriptStateStore` (DataStore)
- Inter-script communication: `ScriptEnvironment.getScriptState(id)` / `setScriptState(id, state)`

## Script Toolbelt

> **Keep this section current.** Whenever a new signal or method is added to `buildSignalsBridge()` in `ScriptedModule.kt`, add it here so it is available as context when writing new scripts.

All signals are passed as the `signals` argument to every lifecycle hook (`onTick`, `onAction`, `onAnswer`, `widget`, etc.).

### `signals.workSchedule` — work schedule & shift session
| Property / Method | Type | Description |
|---|---|---|
| `.shiftStart` | `"HH:MM"` | Planned start time for today's shift, or `""` |
| `.shiftEnd` | `"HH:MM"` | Planned end time for today's shift, or `""` |
| `.isWorkDay` | `Boolean` | Whether today is a scheduled work day |
| `.isClockedIn` | `Boolean` | True while a shift is active (started, not yet ended) |
| `.isClockedOut` | `Boolean` | True once a shift has been both started and ended today |
| `.clockedInAt` | `String` | `Date.toString()` of actual clock-in time, or `""` |
| `.clockedOutAt` | `String` | `Date.toString()` of actual clock-out time, or `""` |
| `.shiftDurationMinutes` | `Number` | Actual shift length in minutes (0 if not fully logged) |
| `.setShiftStart("HH:MM")` | `Function` | Override today's shift start (async) |
| `.setShiftEnd("HH:MM")` | `Function` | Override today's shift end (async) |
| `.getScheduleForDate("yyyy-MM-dd")` | `→ {isWork, shiftStart, shiftEnd}` | Read schedule for any date |
| `.setScheduleForDate("yyyy-MM-dd", opts)` | `Function` | Write a date override (async) |
| `.setSchedulesForDates({...})` | `Function` | Bulk-write date overrides (async) |

### `signals.sleep` — sleep schedule & log
| Property / Method | Type | Description |
|---|---|---|
| `.bedTime` | `"HH:MM"` | Preferred/scheduled bed time |
| `.wakeTime` | `"HH:MM"` | Preferred/scheduled wake time |
| `.enabled` | `Boolean` | Whether the sleep scheduler is active |
| `.isSleeping` | `Boolean` | True while sleep has been auto-detected (phone inactive ≥60 min) |
| `.isMonitoring` | `Boolean` | True while sleep mode is armed but sleep onset not yet detected |
| `.todayEntry` | `{bedTime, wakeTime, bedMillis, wakeMillis, durationMinutes}` or `null` | Today's logged sleep session; null if nothing logged yet |
| `.setBedTime("HH:MM")` | `Function` | Update preferred bed time |
| `.setWakeTime("HH:MM")` | `Function` | Update preferred wake time |

### `signals.alarms` — user alarm clock
| Property / Method | Type | Description |
|---|---|---|
| `.getAll()` | `Array<{id, label, time, hour, minute, enabled, repeatDays}>` | All user alarms; `repeatDays` is array of ISO day numbers (1=Mon…7=Sun), empty = one-shot |
| `.add({label, hour, minute, enabled?, repeatDays?, vibrate?})` | `→ String id` | Create and schedule an alarm; returns its id |
| `.delete(id)` | `Function` | Cancel and delete an alarm by id (async) |
| `.setEnabled(id, enabled)` | `Function` | Enable or disable an alarm without deleting it (async) |

### `signals.calendar` — device calendar
| Property / Method | Type | Description |
|---|---|---|
| `.hasPermission` | `Boolean` | READ_CALENDAR granted |
| `.hasWritePermission` | `Boolean` | WRITE_CALENDAR granted |
| `.events` | `Array<{title, startMillis, endMillis, allDay}>` | Cached today's calendar events |
| `.createEvent({title, startMillis, endMillis, description?, allDay?})` | `→ Number eventId` | Create an event; returns id or -1 on failure (synchronous IO) |
| `.deleteEvent(eventId)` | `Function` | Delete an event by id (async) |

### `signals.health` — Health Connect
| Property | Type | Description |
|---|---|---|
| `.available` | `Boolean` | Health Connect is installed and available |
| `.steps` | `Number` | Cached today step count |

### `signals.notifications` — in-app notifications
| Method | Description |
|---|---|
| `.schedule({id, title, body, actions[], weekly?})` | Schedule a recurring or one-off notification |
| `.sendNow(configOrString)` | Send a notification immediately |
| `.cancel(id)` | Cancel a scheduled notification |

Action `behavior` values: `"snooze"`, `"openTab"`, `"triggerScript"`, `"dismiss"`.

### `signals.time` — current time helpers
| Method | Returns | Description |
|---|---|---|
| `.now()` | `Number` | Current epoch milliseconds |
| `.today()` | `"yyyy-MM-dd"` | Today's date string |
| `.minutesUntil("HH:MM")` | `Number` | Minutes until the next occurrence of that time (wraps to tomorrow) |
| `.format(epochMs)` | `"HH:MM"` | Format epoch millis as a 24h time string |

### `signals.device` — battery & app usage
| Property / Method | Type | Description |
|---|---|---|
| `.batteryLevel` | `Number` | 0–100, or -1 if unknown |
| `.isCharging` | `Boolean` | |
| `.hasUsagePermission` | `Boolean` | PACKAGE_USAGE_STATS granted |
| `.appUsageMinutes(packageName)` | `Number` | Foreground minutes today for that package |

### `signals.planner` — day planner events
| Method | Description |
|---|---|
| `.register({id, title, durationMinutes, priority?, category?, conditions?})` | Register a planner event |
| `.unregister(id)` | Remove a planner event |
| `.getEvents()` | `Array<{id, title, durationMinutes, priority}>` |

Condition types: `timeWindow {start, end}`, `workDayOnly`, `dayOffOnly`, `notDuringShift`, `daysOfWeek {days: [1..7]}`.

### `signals.memory` — persistent key-value store (shared across all scripts)
| Method | Description |
|---|---|
| `.set(key, value)` | Store a string value |
| `.get(key)` | Returns a number (if parseable) or string |
| `.delete(key)` | |
| `.keys()` | `Array<String>` |

### `signals.streak` — daily streak counters
| Method | Description |
|---|---|
| `.get(key)` | Current count |
| `.increment(key)` | Mark today done; resets to 1 if yesterday was missed; returns new count |
| `.reset(key)` | Clear count and date |
| `.lastDate(key)` | `"yyyy-MM-dd"` of last increment, or `""` |

### `signals.countdown` — deadline timers
| Method | Description |
|---|---|
| `.set(key, endMs)` | Store a deadline (epoch ms) |
| `.remaining(key)` | Ms remaining; negative = overdue; `null` if not set |
| `.remainingMinutes(key)` | Minutes remaining; `null` if not set |
| `.endMs(key)` | Raw deadline epoch ms; `null` if not set |
| `.clear(key)` | |
| `.keys()` | `Array<String>` |

### `signals.location` — device location (refreshed each tick)
| Property / Method | Type | Description |
|---|---|---|
| `.hasPermission` | `Boolean` | ACCESS_COARSE_LOCATION granted |
| `.latitude` | `Number` | |
| `.longitude` | `Number` | |
| `.accuracy` | `Number` | Metres |
| `.isNear(lat, lon, radiusMetres?)` | `Boolean` | Default radius 200 m; uses haversine |

### `signals.log` — in-app developer log
```js
signals.log.info("message")   // appears in the developer log UI
signals.log.warn("message")
signals.log.error("message")
```

### `signals.tasks` — Task Manager (central scheduling gateway)
| Method | Description |
|---|---|
| `.submit(spec)` | Queue a floating task for the day planner |
| `.retract(id)` | Remove a task from the queue |
| `.getAll()` | `Array<{id, title, durationMinutes, priority}>` — all queued tasks |

`spec` shape: `{id, title, durationMinutes, priority?, conditions?}` — same condition types as `signals.planner`. Tasks are attributed to the calling script's id automatically. Built-in ID: `BUILTIN.TASK_MANAGER`.

### `scripts` object — inter-script state
```js
scripts.get(scriptId)          // returns {doneToday, values, settings} or null
scripts.set(scriptId, state)   // overwrites another script's state
// Built-in IDs: BUILTIN.WORK_SCHEDULE, BUILTIN.SLEEP_SCHEDULE, BUILTIN.TASK_MANAGER
```

## Named block / planner architecture

`NamedBlock` → stored in `NamedBlockStore` (`wp_named_blocks` SharedPreferences).

Two scheduling modes:
- **Fixed** (`isFloating=false`): recurring on `recurringDays` with a default start time; per-date overrides in `wp_block_schedules`.
- **Floating** (`isFloating=true`): placed by `EventPlannerRegistry` at runtime using `floatingConditions`.

`NamedBlockStore.resolveForDate(date)` → `List<Pair<NamedBlock, NamedBlockSchedule>>` (fixed blocks only — floating blocks are resolved by the planner).

`EventPlannerRegistry.planForDate(...)` accepts `namedBlockInstances` (fixed) and `floatingBlocks` separately. It:
1. Places floating blocks first (greedy first-fit, priority order) into `remaining` free slots.
2. Injects each block's `activeTasks` into `allSchedulable` as `PlannerEvent` entries with synthesised `EventCondition.BeforeBlock` / `DuringBlock` / `AfterBlock` conditions and `sourceWidgetId = "__block__${blockId}"`.
3. Runs the normal topological-sort greedy pass; block tasks are scheduled within/around the block window.
4. Returns a `DayPlan` where block sub-tasks appear in `plan.scheduled` alongside all other events.

Block events in `plan.scheduled` use `EventCategory.BLOCK` and `id = "__block__${blockId}"`. Sub-tasks use `sourceWidgetId = "__block__${blockId}"` to identify their parent.

**Block session lifecycle** (`BlockSessionStore`, `wp_block_session` SharedPreferences):
- `startSession()` writes prefs + emits on `sessionFlow: MutableStateFlow<ActiveBlockSession?>`.
- `endSession()` clears prefs + emits null + writes a log entry to `BlockSessionLogStore`.
- A `SharedPreferences.OnSharedPreferenceChangeListener` keeps the flow in sync when `BlockStartReceiver` writes from a BroadcastReceiver context.
- `loadCurrent()` auto-clears stale sessions (date mismatch).

**Notification / alarm flow for blocks:**
`BlockAlarmScheduler` → `AlarmManager.setExactAndAllowWhileIdle` → fires `BlockStartReceiver` (`ACTION_BLOCK_START`) → posts "starts now" notification via `BlockNotificationHelper` → user taps "Proceed" → `BlockStartReceiver` (`ACTION_PROCEED_BLOCK`) → `startSession()`.

## SharedPreferences stores

| Key | Store class | Contents |
|---|---|---|
| `wp_named_blocks` | `NamedBlockStore` | `NamedBlock` definitions |
| `wp_block_schedules` | `NamedBlockStore` | Per-date `NamedBlockSchedule` overrides |
| `wp_block_tasks` | `NamedBlockStore` | `BlockTask` definitions |
| `wp_block_activations` | `NamedBlockStore` | `BlockTaskActivation` (situational task toggles) |
| `wp_block_session` | `BlockSessionStore` | Active `ActiveBlockSession` (single key `"active"`) |
| `waypoint_timeline` | `DayTimelineView` | Zoom index preference |
| `wp_calendar_prefs` | `CalendarPrefsStore` | Which calendar events reserve time |
| `wp_sleep_schedule` | `SleepScheduleStore` | Bed/wake times |
| `wp_sleep_log` | `SleepLogStore` | Daily sleep log entries |

## Design Brainstorm — Container / Routine Unification

### Core observation
Named blocks and routine tasks are the same concept at different levels of scheduling rigidity. A named block is a routine anchored to a time + recurring days. A routine is a named block with no time anchor. The block system is already the richer model — routines should become blocks without a clock, not a parallel system.

### Unified container model
- Make the time anchor optional on `NamedBlock`. When set → current block behavior. When null → floating routine, placed by the planner as a single unit.
- Everything else (session model, task sheet, checklist UI, session card, placement/always/situational) already works and serves both without duplication.
- Planner needs to know how to place an unanchored block the same way it places a floating task.

### Nesting — one level deep, no deeper
Full inception-style nesting (blocks all the way down) is architecturally possible but the mental model collapses for the user beyond 2 levels, and scheduling recursive containers is hard.

**Decision: support one level of nesting with explicit phase semantics.**
- A block can contain sub-blocks (phases).
- A phase can contain tasks.
- Phases cannot contain phases.
- Example: Gym → Warmup phase / Workout phase / Cooldown phase, each with their own session entry, checklist, and timing. The outer block anchors the time; inner phases handle structure.

### Sequential vs flexible task ordering
- Current routine subtasks: purely sequential, dumb (just title + timer).
- Block tasks: flexible, independently scheduled.
- Goal: block tasks get an optional `sequence` field. When set, tasks execute in order within the phase/block window. When null, the planner schedules them flexibly by priority.

### What this replaces
The `isRoutine: Boolean` + `subtasks: List<SubtaskDef>` fields on `TaskRequest` are the legacy model. Long-term these get superseded by unanchored named blocks with ordered tasks.

## Branch

Active development branch: `claude/app-build-issues-mknbj6`

## Releases

On each new release, write the release notes directly on the GitHub Release (not in a file). Include:
- **New features** — what was added and how it works from the user's perspective
- **Bug fixes** — what broke and what was changed to fix it
- **Breaking changes** — anything that changes existing behavior the user relied on
