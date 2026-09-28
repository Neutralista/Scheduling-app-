package com.waypoint.app.notification

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import kotlin.math.sin
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
import androidx.compose.material3.Button
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

// Its own night palette, the same in light and dark theme: a sky, moonlight, starlight.
private val SkyTop = Color(0xFF0B1030)
private val SkyMid = Color(0xFF151B45)
private val SkyBottom = Color(0xFF2A1F4A)
private val Moonlight = Color(0xFFF6E7B8)
private val Starlight = Color(0xFFFFFBEF)
private val SoftText = Color(0xFFC9CBE8)

private data class Star(val x: Float, val y: Float, val radius: Float, val phase: Float, val speed: Float, val sparkle: Boolean)

/** A night sky: gradient, twinkling stars, and a glowing crescent moon. */
@Composable
private fun NightSky(modifier: Modifier = Modifier) {
    val stars = remember {
        val rnd = java.util.Random(7)
        List(90) {
            Star(
                x = rnd.nextFloat(),
                y = rnd.nextFloat() * 0.75f,
                radius = 0.6f + rnd.nextFloat() * 1.6f,
                phase = rnd.nextFloat() * 6.28f,
                speed = 0.6f + rnd.nextFloat() * 1.2f,
                sparkle = rnd.nextFloat() < 0.08f
            )
        }
    }
    val time by rememberInfiniteTransition(label = "sky").animateFloat(
        initialValue = 0f,
        targetValue = 6.2832f,
        animationSpec = infiniteRepeatable(tween(8000, easing = LinearEasing)),
        label = "twinkle"
    )
    Canvas(modifier.fillMaxSize()) {
        drawRect(Brush.verticalGradient(listOf(SkyTop, SkyMid, SkyBottom)))
        stars.forEach { st ->
            // Each star twinkles at its own pace.
            val a = 0.35f + 0.65f * ((sin(time * st.speed + st.phase) + 1f) / 2f)
            val c = Offset(st.x * size.width, st.y * size.height)
            val r = st.radius.dp.toPx()
            drawCircle(Starlight.copy(alpha = a), r, c)
            if (st.sparkle) {
                // A few brighter ones get a four-pointed glint.
                val len = r * 4.5f * a
                drawLine(Starlight.copy(alpha = a * 0.7f), Offset(c.x - len, c.y), Offset(c.x + len, c.y), 1.dp.toPx())
                drawLine(Starlight.copy(alpha = a * 0.7f), Offset(c.x, c.y - len), Offset(c.x, c.y + len), 1.dp.toPx())
            }
        }
    }
}

/** A crescent moon with a soft, slowly breathing glow. */
@Composable
private fun CrescentMoon(modifier: Modifier = Modifier) {
    val glow by rememberInfiniteTransition(label = "moon").animateFloat(
        initialValue = 0.55f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(tween(3500, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "glow"
    )
    Canvas(modifier.size(180.dp)) {
        val c = center
        val r = size.minDimension * 0.26f
        drawCircle(
            Brush.radialGradient(
                listOf(Moonlight.copy(alpha = 0.35f * glow), Moonlight.copy(alpha = 0.08f * glow), Color.Transparent),
                center = c,
                radius = size.minDimension / 2f
            ),
            radius = size.minDimension / 2f,
            center = c
        )
        val disc = Path().apply { addOval(Rect(c, r)) }
        val bite = Path().apply { addOval(Rect(Offset(c.x + r * 0.45f, c.y - r * 0.28f), r * 0.92f)) }
        val crescent = Path().apply { op(disc, bite, PathOperation.Difference) }
        drawPath(crescent, Brush.linearGradient(listOf(Moonlight, Color(0xFFE8C97A)), start = Offset(c.x - r, c.y - r), end = Offset(c.x + r, c.y + r)))
    }
}

@Composable
private fun BedtimeScreen(title: String, text: String, onSleep: () -> Unit, onSnooze: () -> Unit) {
    Box(Modifier.fillMaxSize().background(SkyTop)) {
        NightSky()
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Spacer(Modifier.height(48.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CrescentMoon()
                Spacer(Modifier.height(8.dp))
                Text(
                    title,
                    color = Starlight,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Light,
                    letterSpacing = 1.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(10.dp))
                Text(text, color = SoftText, fontSize = 16.sp, textAlign = TextAlign.Center)
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(bottom = 48.dp)
            ) {
                Button(
                    onClick = onSleep,
                    modifier = Modifier.fillMaxWidth().height(60.dp),
                    shape = RoundedCornerShape(30.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Moonlight, contentColor = SkyTop)
                ) {
                    Text("Go to sleep", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
                OutlinedButton(
                    onClick = onSnooze,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(26.dp),
                    border = BorderStroke(1.dp, SoftText.copy(alpha = 0.45f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = SoftText)
                ) {
                    Text("Snooze ${SleepActionReceiver.SNOOZE_MINUTES} min", fontSize = 15.sp)
                }
            }
        }
    }
}
