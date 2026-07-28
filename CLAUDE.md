# Waypoint — Project Notes

## Dual-platform rule

Every feature or change must be implemented on **both platforms simultaneously**:

1. **Android app** — Kotlin + Jetpack Compose, in `app/src/main/java/com/waypoint/app/`
2. **Web artifact** — JavaScript, published at https://claude.ai/code/artifact/c1132c82-ce4a-4b9d-aae7-89241ce99389

No change ships to one without the other. When a platform genuinely can't support a feature (e.g. push notifications on web, Health Connect on web), note the gap explicitly rather than skipping silently.

## Platform capability map

| Feature | Android | Web artifact |
|---|---|---|
| Widget framework | Kotlin `HabitWidget` interface | JS `HabitWidget` class |
| State persistence | DataStore (Preferences) | `localStorage` |
| Calendar | `CalendarContract` ContentProvider | Manual event entry |
| Step count | Health Connect | Manual log input |
| Push notifications | WorkManager / AlarmManager | Not possible |
| Background tasks | WorkManager | Not possible |

## Architecture

- Widgets are registered in `WaypointApplication.kt` (Android) and at the bottom of the artifact `<script>` block (web)
- Both use the same conceptual registry + state store pattern
- Widget state shape: `{ doneToday: boolean, values: { [key]: number } }`

## Branch

Active development branch: `claude/app-build-issues-mknbj6`
