package com.waypoint.app.ui.components

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

private val HUE_SPECTRUM = listOf(
    Color(0xFFFF0000), Color(0xFFFFFF00), Color(0xFF00FF00),
    Color(0xFF00FFFF), Color(0xFF0000FF), Color(0xFFFF00FF), Color(0xFFFF0000)
)

/**
 * Hue / saturation / lightness sliders (backed by the platform's RGB<->HSV
 * conversion) plus an optional alpha slider. Replaces raw R/G/B[/A] channel
 * sliders wherever a custom color needs picking.
 */
@Composable
fun HsvColorPicker(
    argb: Int,
    onColorChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    showAlpha: Boolean = true
) {
    val alpha = (argb ushr 24) and 0xFF
    val hsv = FloatArray(3)
    AndroidColor.colorToHSV(argb or 0xFF000000.toInt(), hsv)
    val hue = hsv[0]
    val sat = hsv[1]
    val lightness = hsv[2]

    fun emit(h: Float = hue, s: Float = sat, v: Float = lightness, a: Int = alpha) {
        val rgb = AndroidColor.HSVToColor(floatArrayOf(h, s.coerceIn(0f, 1f), v.coerceIn(0f, 1f)))
        onColorChange((a shl 24) or (rgb and 0x00FFFFFF))
    }

    val fullHueColor = Color(AndroidColor.HSVToColor(floatArrayOf(hue, 1f, 1f)))
    val currentOpaque = Color(AndroidColor.HSVToColor(floatArrayOf(hue, sat, lightness)))

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ColorSliderRow(label = "H", valueLabel = "${hue.roundToInt()}°") {
            GradientSlider(
                value = hue / 360f,
                onValueChange = { emit(h = it * 360f) },
                trackBrush = Brush.horizontalGradient(HUE_SPECTRUM),
                thumbColor = fullHueColor
            )
        }
        ColorSliderRow(label = "S", valueLabel = "${(sat * 100).roundToInt()}%") {
            val satLow = Color(AndroidColor.HSVToColor(floatArrayOf(hue, 0f, lightness)))
            val satHigh = Color(AndroidColor.HSVToColor(floatArrayOf(hue, 1f, lightness)))
            GradientSlider(
                value = sat,
                onValueChange = { emit(s = it) },
                trackBrush = Brush.horizontalGradient(listOf(satLow, satHigh)),
                thumbColor = currentOpaque
            )
        }
        ColorSliderRow(label = "L", valueLabel = "${(lightness * 100).roundToInt()}%") {
            val liteHigh = Color(AndroidColor.HSVToColor(floatArrayOf(hue, sat, 1f)))
            GradientSlider(
                value = lightness,
                onValueChange = { emit(v = it) },
                trackBrush = Brush.horizontalGradient(listOf(Color.Black, liteHigh)),
                thumbColor = currentOpaque
            )
        }
        if (showAlpha) {
            ColorSliderRow(label = "A", valueLabel = "${(alpha / 255f * 100).roundToInt()}%") {
                GradientSlider(
                    value = alpha / 255f,
                    onValueChange = { emit(a = (it * 255f).roundToInt().coerceIn(0, 255)) },
                    trackBrush = Brush.horizontalGradient(
                        listOf(currentOpaque.copy(alpha = 0f), currentOpaque.copy(alpha = 1f))
                    ),
                    thumbColor = currentOpaque.copy(alpha = alpha / 255f),
                    checkerboard = true
                )
            }
        }
    }
}

/** Swatch that shows a checkerboard backdrop through partial transparency. */
@Composable
fun ColorPreviewSwatch(
    argb: Int,
    modifier: Modifier = Modifier,
    showCheckerboard: Boolean = true
) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier
            .clip(shape)
            .then(
                if (showCheckerboard) Modifier.drawBehind { drawCheckerboard(Offset.Zero, size) }
                else Modifier
            )
            .background(Color(argb))
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
    )
}

@Composable
private fun ColorSliderRow(label: String, valueLabel: String, content: @Composable () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(16.dp),
            textAlign = TextAlign.Center
        )
        Box(Modifier.weight(1f)) { content() }
        Text(
            valueLabel,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(42.dp),
            textAlign = TextAlign.End
        )
    }
}

@Composable
private fun GradientSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    trackBrush: Brush,
    thumbColor: Color,
    modifier: Modifier = Modifier,
    checkerboard: Boolean = false
) {
    val trackHeight = 14.dp
    val thumbRadius = 9.dp
    // pointerInput(Unit) launches its gesture-detection coroutine once and never
    // restarts it (the key never changes), so it would otherwise keep calling a
    // stale onValueChange captured from the first composition. rememberUpdatedState
    // keeps it reading the latest callback without needing to key on it (which
    // would tear down and restart the gesture loop on every value change).
    val latestOnValueChange = rememberUpdatedState(onValueChange)
    Canvas(
        modifier
            .fillMaxWidth()
            .height(28.dp)
            .pointerInput(Unit) {
                // A single unified press-then-drag loop: two separate pointerInput
                // blocks (one for tap, one for drag) race over the same touch events
                // and cause jumpy, inconsistent updates.
                awaitEachGesture {
                    val down = awaitFirstDown()
                    latestOnValueChange.value((down.position.x / size.width).coerceIn(0f, 1f))
                    drag(down.id) { change ->
                        latestOnValueChange.value((change.position.x / size.width).coerceIn(0f, 1f))
                        change.consume()
                    }
                }
            }
    ) {
        val cy = size.height / 2f
        val trackTopLeft = Offset(0f, cy - trackHeight.toPx() / 2f)
        val trackSize = Size(size.width, trackHeight.toPx())
        val corner = CornerRadius(trackHeight.toPx() / 2f)

        if (checkerboard) {
            drawRoundRect(
                color = Color.White,
                topLeft = trackTopLeft,
                size = trackSize,
                cornerRadius = corner
            )
            drawCheckerboard(trackTopLeft, trackSize)
        }
        drawRoundRect(brush = trackBrush, topLeft = trackTopLeft, size = trackSize, cornerRadius = corner)

        val thumbX = value.coerceIn(0f, 1f) * size.width
        drawCircle(Color.White, thumbRadius.toPx() + 2.dp.toPx(), Offset(thumbX, cy))
        drawCircle(thumbColor, thumbRadius.toPx(), Offset(thumbX, cy))
        drawCircle(
            Color.Black.copy(alpha = 0.18f),
            thumbRadius.toPx() + 2.dp.toPx(),
            Offset(thumbX, cy),
            style = Stroke(width = 1.dp.toPx())
        )
    }
}

private fun DrawScope.drawCheckerboard(topLeft: Offset, size: Size, cell: Float = 6f) {
    val light = Color(0xFFD8D8D8)
    val dark = Color(0xFFA8A8A8)
    var y = topLeft.y
    var row = 0
    while (y < topLeft.y + size.height) {
        var x = topLeft.x
        var col = row
        while (x < topLeft.x + size.width) {
            val w = minOf(cell, topLeft.x + size.width - x)
            val h = minOf(cell, topLeft.y + size.height - y)
            drawRect(if (col % 2 == 0) light else dark, topLeft = Offset(x, y), size = Size(w, h))
            x += cell
            col++
        }
        y += cell
        row++
    }
}
