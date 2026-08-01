# Changelog

## Unreleased

### New Features

#### Sleep Scheduler

- **Late-sleep nudge notifications** — when bedtime passes and you are still awake, the app sends a notification every 30 minutes showing how far past bedtime you are ("14m past bedtime", "1h 2m past bedtime"), with a one-tap "Start Sleep Mode" action. Nudges stop automatically once sleep mode begins or the scheduled wake time passes.

- **Delay bedtime button** — a "Delay bedtime" button appears on the sleep card whenever you are awake past your scheduled bedtime. Tapping it pushes tonight's sleep window forward 30 minutes from now, adjusts the wake alarm to maintain your target sleep duration, and cancels any pending nudges. The new window is shown immediately on the card and in the timeline.

- **Automatic wake adjustment on sleep onset** — when the app detects you have fallen asleep (phone inactive for 60 minutes), it automatically recalculates your ideal wake time as `sleep start + target duration`. If you fell asleep earlier than expected the wake alarm moves earlier so you wake after exactly your target sleep. If you fell asleep later the alarm moves later, capped by your shift's morning buffer so it never make you late for work.

#### Reliability

- **Background alarm reliability** — added `USE_EXACT_ALARM` permission (auto-granted on Android 13+) so exact alarms fire without the user needing to grant them manually in Settings. A new "Background alarms" entry in Settings lets you exempt the app from battery optimisation so sleep and wake alarms fire reliably with the screen off.

- **Boot/update re-scheduling** — all sleep and user alarms are now properly rescheduled after device reboot or app update. The boot receiver now runs the calendar-query and alarm-scheduling work on a background thread so it completes correctly even in constrained boot environments.

#### Tasks & Shift Log

- **Tasks tab** — a dedicated Tasks tab tracks your per-day to-do list. A progress bar in the app header shows tasks done vs total, including your shift as an implicit task on work days.

- **Shift task** — on work days a shift row appears automatically in the Tasks tab, showing your scheduled shift times, live elapsed time while clocked in, and an on-time arrival indicator. The shift card resets itself once the shift ends and can be marked complete from the Tasks tab.

- **Shift log** — a "Log" button in the Plan tab opens a sheet showing all logged shift sessions. Each entry shows the actual start and end times. You can edit or delete any entry, and the associated calendar event is updated or removed automatically. Orphaned calendar events from sessions that were deleted outside the app are detected and offered for cleanup.

#### Navigation

- **Swipe navigation** — all tabs can be switched by swiping left and right in addition to tapping the tab row.

---

### Bug Fixes

- **Sleep window reset on plan refresh** — tapping the refresh button in the Plan tab called `syncToRegistry`, which recomputed the sleep window from config and overwrote any manually-rescheduled bed/wake times and alarms. Fixed by persisting a "rescheduled today" date flag in `SleepLogStore`. `syncToRegistry` now detects the flag and preserves the manual window (re-registering the timeline entry with stored times and skipping alarm rescheduling) until midnight, when the flag automatically expires.

- **Rescheduled state lost on tab navigation** — after tapping "Delay bedtime", switching to another tab and back caused the "· rescheduled" label and adjusted summary times to disappear from the sleep card. Fixed by initialising the card's rescheduled display state from the persisted flag on first composition, so the display is consistent regardless of navigation history.

- **Wake adjustment only moved alarm later, never earlier** — the sleep-onset wake recalculation had a guard that prevented moving the alarm earlier when the user fell asleep before their scheduled bedtime, resulting in less sleep than the target. The guard has been removed; the alarm now moves in either direction to hit the target duration, subject to a 30-minute minimum lead time and the shift's morning-buffer ceiling.

- **Shift log orphan calendar events** — deleting a shift session from the log did not always remove the associated calendar event, and events created outside the app were not visible or manageable. The log sheet now queries the device calendar for the matching event, shows its details, and offers deletion.

- **Missing imports causing build failure** — a build failure caused by missing imports in `HomeScreen.kt` after adding swipe navigation was resolved.

- **Smart cast failure on delegated session property** — a Kotlin smart cast error in `ShiftTaskRow` caused a compile failure when reading a delegated property of a nullable session type. Fixed by introducing a local variable to hold the non-null value.
