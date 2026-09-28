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
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.setValue
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
                    onSleep = ::goToSleep,
                    setBrightness = ::setBrightness,
                    onWindDownDone = ::windDownDone,
                    onSnooze = {
                        act(if (intent.getBooleanExtra(EXTRA_NUDGE, false)) SleepActionReceiver.ACTION_SNOOZE_NUDGE else SleepActionReceiver.ACTION_SNOOZE_BEDTIME)
                    }
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

    private val preview get() = intent.getBooleanExtra(EXTRA_PREVIEW, false)

    // Go to sleep: Sleep Mode starts right away; the screen then winds down gently (the
    // screen's own brightness and the sky fade out together) before it turns off.
    private fun goToSleep() {
        if (!preview) sendBroadcast(Intent(SleepActionReceiver.ACTION_ENTER_SLEEP_MODE).setPackage(packageName))
    }

    /** The brightness to fade from: this window's, else the phone's setting. */
    fun startBrightness(): Float {
        val own = window.attributes.screenBrightness
        if (own >= 0f) return own
        val system = runCatching { android.provider.Settings.System.getInt(contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS) }.getOrNull()
        return ((system ?: 128) / 255f).coerceIn(0.05f, 1f)
    }

    private fun setBrightness(level: Float) {
        window.attributes = window.attributes.apply { screenBrightness = level.coerceIn(0.01f, 1f) }
    }

    private fun windDownDone() {
        // Screen off like the power button, when turned on in Settings; else just close.
        if (!preview) ScreenOffService.screenOff()
        finish()
    }

    private fun act(action: String) {
        // A preview from Settings: its buttons just close it (no Sleep Mode, no snooze).
        if (!intent.getBooleanExtra(EXTRA_PREVIEW, false)) sendBroadcast(Intent(action).setPackage(packageName))
        finish()
    }

    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
        /** Shown again because the phone's in use with Sleep Mode on ("Still up?"). */
        const val EXTRA_NUDGE = "nudge"
        /** Opened from Settings to see how it looks. */
        const val EXTRA_PREVIEW = "preview"
    }
}

// The night's colours come from the app's theme (like the alarm screen), so it matches the rest
// of the app in light and dark: its background for the sky, its primary for the moon and the
// main button, its text colour for the stars.
private data class NightColors(val skyTop: Color, val skyBottom: Color, val moon: Color, val star: Color, val soft: Color, val onMoon: Color)

@Composable
private fun nightColors(): NightColors {
    val cs = MaterialTheme.colorScheme
    return NightColors(
        skyTop = cs.background,
        skyBottom = androidx.compose.ui.graphics.lerp(cs.background, cs.primary, 0.22f),
        moon = cs.primary,
        star = cs.onBackground,
        soft = cs.onBackground.copy(alpha = 0.7f),
        onMoon = cs.onPrimary
    )
}

private data class Star(val x: Float, val y: Float, val radius: Float, val phase: Float, val speed: Float, val sparkle: Boolean)

/** A night sky: gradient, twinkling stars, and a glowing crescent moon. */
@Composable
private fun NightSky(modifier: Modifier = Modifier) {
    val night = nightColors()
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
        drawRect(Brush.verticalGradient(listOf(night.skyTop, night.skyBottom)))
        stars.forEach { st ->
            // Each star twinkles at its own pace.
            val a = 0.35f + 0.65f * ((sin(time * st.speed + st.phase) + 1f) / 2f)
            val c = Offset(st.x * size.width, st.y * size.height)
            val r = st.radius.dp.toPx()
            drawCircle(night.star.copy(alpha = a * 0.9f), r, c)
            if (st.sparkle) {
                // A few brighter ones get a four-pointed glint.
                val len = r * 4.5f * a
                drawLine(night.star.copy(alpha = a * 0.6f), Offset(c.x - len, c.y), Offset(c.x + len, c.y), 1.dp.toPx())
                drawLine(night.star.copy(alpha = a * 0.6f), Offset(c.x, c.y - len), Offset(c.x, c.y + len), 1.dp.toPx())
            }
        }
    }
}

/** A crescent moon with a soft, slowly breathing glow. */
@Composable
private fun CrescentMoon(modifier: Modifier = Modifier) {
    val moon = nightColors().moon
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
                listOf(moon.copy(alpha = 0.35f * glow), moon.copy(alpha = 0.08f * glow), Color.Transparent),
                center = c,
                radius = size.minDimension / 2f
            ),
            radius = size.minDimension / 2f,
            center = c
        )
        val disc = Path().apply { addOval(Rect(c, r)) }
        val bite = Path().apply { addOval(Rect(Offset(c.x + r * 0.45f, c.y - r * 0.28f), r * 0.92f)) }
        val crescent = Path().apply { op(disc, bite, PathOperation.Difference) }
        drawPath(crescent, Brush.linearGradient(listOf(moon, moon.copy(alpha = 0.8f)), start = Offset(c.x - r, c.y - r), end = Offset(c.x + r, c.y + r)))
    }
}

@Composable
private fun BedtimeScreen(
    title: String,
    text: String,
    onSleep: () -> Unit,
    onSnooze: () -> Unit,
    setBrightness: (Float) -> Unit = {},
    onWindDownDone: () -> Unit = {}
) {
    val night = nightColors()
    val activity = androidx.compose.ui.platform.LocalContext.current as? BedtimeActivity
    var windingDown by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val dark = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(0f) }
    val goodNight = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(windingDown) {
        if (!windingDown) return@LaunchedEffect
        val from = activity?.startBrightness() ?: 0.5f
        kotlinx.coroutines.coroutineScope {
            launch { goodNight.animateTo(1f, tween(1200, easing = FastOutSlowInEasing)) }
            // About five seconds from here to dark, easing out like a sunset.
            launch {
                dark.animateTo(1f, tween(5000, easing = LinearEasing)) {
                    val t = value
                    setBrightness(from * (1f - t) * (1f - t) + 0.01f)
                }
            }
        }
        goodNight.animateTo(0f, tween(900))
        onWindDownDone()
    }
    Box(Modifier.fillMaxSize().background(night.skyTop)) {
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
                    color = night.star,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Light,
                    letterSpacing = 1.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(10.dp))
                Text(text, color = night.soft, fontSize = 16.sp, textAlign = TextAlign.Center)
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(bottom = 48.dp)
            ) {
                Button(
                    onClick = {
                        if (!windingDown) {
                            onSleep()
                            windingDown = true
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(60.dp),
                    shape = RoundedCornerShape(30.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = night.moon, contentColor = night.onMoon)
                ) {
                    Text("Go to sleep", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
                OutlinedButton(
                    onClick = onSnooze,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(26.dp),
                    border = BorderStroke(1.dp, night.soft.copy(alpha = 0.45f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = night.soft)
                ) {
                    Text("Snooze ${SleepActionReceiver.SNOOZE_MINUTES} min", fontSize = 15.sp)
                }
            }
        }
        if (windingDown) {
            // The night closing in: black over everything, "Good night" in the moon's colour.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = dark.value))
                    .pointerInput(Unit) { detectTapGestures { } },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Good night",
                    color = night.moon.copy(alpha = goodNight.value * (1f - dark.value * 0.6f)),
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Light,
                    letterSpacing = 2.sp
                )
            }
        }
    }
}
