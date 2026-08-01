# Waypoint — Project Notes

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

### `scripts` object — inter-script state
```js
scripts.get(scriptId)          // returns {doneToday, values, settings} or null
scripts.set(scriptId, state)   // overwrites another script's state
// Built-in IDs: BUILTIN.WORK_SCHEDULE, BUILTIN.SLEEP_SCHEDULE
```

## Branch

Active development branch: `claude/app-build-issues-mknbj6`

## Releases

On each new release, update `CHANGELOG.md` at the repo root with:
- **New features** — what was added and how it works from the user's perspective
- **Bug fixes** — what broke, why, and what was changed to fix it
- **Breaking changes** — anything that changes existing behavior the user relied on

Group entries under a version heading (e.g. `## v1.2.0 — 2026-08-15`). Keep descriptions user-facing where possible; save implementation detail for bug fixes where the "why" matters.
