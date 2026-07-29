# Waypoint — Project Notes

## Architecture

- Scripts are registered in `WaypointApplication.kt` via `ScriptRegistry.register()`
- Built-in scripts: `WorkScheduleScript`, `SleepScheduleScript`
- User scripts: loaded from `ScriptStore` (`filesDir/scripts/<id>.js`) on launch
- Script state shape: `ScriptState(doneToday, values: Map<String,Double>, settings: Map<String,String>)`
- State persistence: `ScriptStateStore` (DataStore)
- Inter-script communication: `ScriptEnvironment.getScriptState(id)` / `setScriptState(id, state)`

## Branch

Active development branch: `claude/app-build-issues-mknbj6`
