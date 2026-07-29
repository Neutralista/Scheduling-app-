package com.waypoint.app.permission

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Surface
import com.waypoint.app.ui.theme.WaypointTheme

/**
 * Required by Health Connect: declares an activity with the
 * SHOW_PERMISSIONS_RATIONALE intent filter so HC knows this app can
 * explain why it needs health data before showing the consent screen.
 */
class PermissionRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WaypointTheme {
                Surface {
                    PermissionRationaleScreen(
                        title = "Health data access",
                        rationale = "Waypoint reads your daily step count so scripts can track activity goals and adapt your schedule automatically.",
                        permissionType = PermissionType.RUNTIME,
                        onGrant = { setResult(RESULT_OK); finish() },
                        onDismiss = { finish() }
                    )
                }
            }
        }
    }
}
