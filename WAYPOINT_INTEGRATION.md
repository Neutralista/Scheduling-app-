# Training-app integration

Two independent, one-way flows between Waypoint and Training-app (`com.neutralista.trainingapp`).
Both are fire-and-forget broadcasts gated by the same `com.waypoint.app.permission.LOG_BLOCK_SESSION`
permission this app declares (`protectionLevel="normal"` — granted automatically to any app that
requests it, no runtime prompt).

## 1. Completed workout → History log

Waypoint accepts completed workouts reported by Training-app and logs them into its own
block-session history via `BlockSessionLogStore`. Retroactive only — no live "in progress" state,
and edits/deletes on either side afterward don't sync back.

Received by `com.waypoint.app.integration.ExternalBlockSessionReceiver`. It writes a
`BlockSessionLog` directly — no matching `NamedBlock` is created, so these sessions appear in the
History tab but not on the live day-planner timeline.

Explicit broadcast, action `com.waypoint.app.LOG_BLOCK_SESSION`, sent targeted at this app's
package.

| Extra | Type | Meaning |
|---|---|---|
| `blockId` | String | Grouping id for the History tab (Training-app always sends `"training-app-workout"`) |
| `blockName` | String | Display name for the session, e.g. `"Push Day"` |
| `startMs` | Long | Session start, epoch millis |
| `endMs` | Long | Session end, epoch millis (must be > `startMs` or the entry is dropped) |
| `tasksCompleted` | Int | Shown as the "X/Y" counter on the session row |
| `tasksTotal` | Int | Shown as the "X/Y" counter on the session row |

## 2. Workout-block definition → live schedulable block

Training-app pushes a per-split-day exercise list, each with an estimated duration, and Waypoint
creates or updates a real floating `NamedBlock` for it — one `BlockTask` (placement `DURING`) per
exercise — so it actually shows up on Waypoint's day-planner timeline and gets scheduled like any
other block, not just logged after the fact. Sent once when a split day is first used (Training-app's
own initial time assumptions, before any workout has been logged) and again after every completed
workout (durations averaged from logged history) — both are the same idempotent upsert.

Received by `com.waypoint.app.integration.ExternalBlockDefinitionReceiver`, which calls
`NamedBlockStore.upsertExternalBlock()`: matches the block by `blockId`, replaces its task list
(matching each task by `"$blockId-$exerciseId"` so a re-sync updates existing tasks in place
instead of duplicating them), and deletes any task whose exercise is no longer present. An empty
exercise list deletes the whole block.

Explicit broadcast, action `com.waypoint.app.UPSERT_TRAINING_BLOCK`, sent targeted at this app's
package.

| Extra | Type | Meaning |
|---|---|---|
| `blockId` | String | Stable per-split-day id — Training-app sends `"training-app-<splitDayId>"` |
| `blockName` | String | The split day name, e.g. `"Push Day"` |
| `exerciseIds` | String[] | Training-app's own exercise ids, parallel to the two arrays below |
| `exerciseNames` | String[] | Exercise display names, in workout order |
| `exerciseMinutes` | int[] | Estimated (first sync) or logged-average (later syncs) duration per exercise |

The three array extras must be the same length, in the same order — a mismatch is logged and the
whole broadcast is ignored rather than guessing.

---

Any other app could in principle send either broadcast if it also declares
`<uses-permission android:name="com.waypoint.app.permission.LOG_BLOCK_SESSION" />` — the contract
isn't Training-app-specific, just documented from that side too since it's the current sender.

