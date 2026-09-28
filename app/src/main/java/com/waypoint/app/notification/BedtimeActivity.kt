package com.waypoint.app.notification

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.waypoint.app.ui.theme.BELAMOUR_THEME
import com.waypoint.app.ui.theme.ThemeStore
import com.waypoint.app.ui.theme.WaypointTheme
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The bedtime reminder, full screen — over the lock screen and over whatever's open — with the
 * two things to do about it: go to sleep (Sleep Mode on) or snooze it 15 minutes.
 */
class BedtimeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Time to sleep"
        val text = intent.getStringExtra(EXTRA_TEXT) ?: "Your sleep window starts now"
        val themeStore = runCatching { ThemeStore(this) }.getOrNull()

        setContent {
            val fallbackId = remember { MutableStateFlow(BELAMOUR_THEME.id) }
            val fallbackDark = remember { MutableStateFlow<Boolean?>(null) }
            val selectedThemeId by (themeStore?.selectedThemeIdFlow ?: fallbackId).collectAsState()
            val appTheme = remember(selectedThemeId) { themeStore?.resolveTheme(selectedThemeId) ?: BELAMOUR_THEME }
            val darkOverride by (themeStore?.darkModeOverrideFlow ?: fallbackDark).collectAsState()
            WaypointTheme(darkTheme = darkOverride ?: isSystemInDarkTheme(), appTheme = appTheme) {
                BedtimeScreen(
                    title = title,
                    text = text,
                    onSleep = { act(SleepActionReceiver.ACTION_ENTER_SLEEP_MODE) },
                    onSnooze = { act(SleepActionReceiver.ACTION_SNOOZE_BEDTIME) }
                )
            }
        }
    }

    // Already showing when the next reminder comes (e.g. "Time to sleep" after "Bedtime in 30
    // min"): show the new one.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate()
    }

    private fun act(action: String) {
        sendBroadcast(Intent(action).setPackage(packageName))
        finish()
    }

    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
    }
}

@Composable
private fun BedtimeScreen(title: String, text: String, onSleep: () -> Unit, onSnooze: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Spacer(Modifier.height(96.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.Bedtime,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(64.dp)
            )
            Spacer(Modifier.height(16.dp))
            Text(
                title,
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 34.sp,
                fontWeight = FontWeight.Light,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                fontSize = 16.sp,
                textAlign = TextAlign.Center
            )
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(bottom = 48.dp)
        ) {
            Button(
                onClick = onSleep,
                modifier = Modifier.fillMaxWidth().height(60.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text("Go to sleep", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
            OutlinedButton(
                onClick = onSnooze,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f)
                )
            ) {
                Text("Snooze ${SleepActionReceiver.SNOOZE_MINUTES} min", fontSize = 15.sp)
            }
        }
    }
}
