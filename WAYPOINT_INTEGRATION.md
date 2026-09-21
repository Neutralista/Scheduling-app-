# Training-app integration

Waypoint accepts completed workouts reported by Training-app (`com.neutralista.trainingapp`) and
logs them into its own block-session history via `BlockSessionLogStore`. One-way, fire-and-forget,
retroactive only — no live "in progress" state, and edits/deletes on either side afterward don't
sync back.

Received by `com.waypoint.app.integration.ExternalBlockSessionReceiver`. It writes a
`BlockSessionLog` directly — no matching `NamedBlock` is created, so these sessions appear in the
History tab but not on the live day-planner timeline.

## Contract

Explicit broadcast, action `com.waypoint.app.LOG_BLOCK_SESSION`, sent targeted at this app's
package. Gated by the `com.waypoint.app.permission.LOG_BLOCK_SESSION` permission this app declares
(`protectionLevel="normal"` — granted automatically to any app that requests it, no runtime
prompt).

| Extra | Type | Meaning |
|---|---|---|
| `blockId` | String | Grouping id for the History tab (Training-app always sends `"training-app-workout"`) |
| `blockName` | String | Display name for the session, e.g. `"Push Day"` |
| `startMs` | Long | Session start, epoch millis |
| `endMs` | Long | Session end, epoch millis (must be > `startMs` or the entry is dropped) |
| `tasksCompleted` | Int | Shown as the "X/Y" counter on the session row |
| `tasksTotal` | Int | Shown as the "X/Y" counter on the session row |

Any other app could in principle send this broadcast if it also declares
`<uses-permission android:name="com.waypoint.app.permission.LOG_BLOCK_SESSION" />` — the contract
isn't Training-app-specific, just documented from that side too since it's the current sender.
