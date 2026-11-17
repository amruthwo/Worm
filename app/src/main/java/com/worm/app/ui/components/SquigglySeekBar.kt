package com.worm.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/**
 * Material 3 Expressive-style wavy progress: the played part wiggles while audio plays and
 * flattens out when paused or while you drag. Tap or drag anywhere to seek.
 *
 * [position] is read only while drawing, so per-frame progress doesn't recompose anything.
 */
@Composable
fun SquigglySeekBar(
    position: () -> Long,
    duration: Long,
    playing: Boolean,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    onScrub: (Float?) -> Unit = {},
    activeColor: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = Color.White.copy(alpha = 0.28f),
    handleColor: Color = Color.White,
) {
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val currentDuration by rememberUpdatedState(duration)
    val currentOnSeek by rememberUpdatedState(onSeek)

    val amplitude by animateFloatAsState(
        targetValue = if (playing && dragFraction == null) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 300f),
        label = "amp",
    )
    val handleHeight by animateFloatAsState(if (dragFraction != null) 1.45f else 1f, spring(0.45f, 600f), label = "h")
    val phase by rememberInfiniteTransition(label = "wave").animateFloat(
        initialValue = 0f, targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
        label = "phase",
    )

    fun fractionAt(x: Float, width: Int) = (x / width).coerceIn(0f, 1f)

    Canvas(
        modifier
            .height(40.dp)
            .pointerInput(Unit) {
                detectTapGestures { off ->
                    if (currentDuration > 0) currentOnSeek((fractionAt(off.x, size.width) * currentDuration).toLong())
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { off -> dragFraction = fractionAt(off.x, size.width).also(onScrub) },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        dragFraction = fractionAt(change.position.x, size.width).also(onScrub)
                    },
                    onDragEnd = {
                        dragFraction?.let { if (currentDuration > 0) currentOnSeek((it * currentDuration).toLong()) }
                        dragFraction = null; onScrub(null)
                    },
                    onDragCancel = { dragFraction = null; onScrub(null) },
                )
            },
    ) {
        val frac = dragFraction
            ?: if (duration > 0) (position().toFloat() / duration).coerceIn(0f, 1f) else 0f
        val cy = size.height / 2
        val stroke = 4.dp.toPx()
        val gap = 6.dp.toPx()
        val x = frac * size.width

        // Remaining track (flat).
        if (x + gap < size.width) {
            drawLine(trackColor, Offset(x + gap, cy), Offset(size.width, cy), stroke, StrokeCap.Round)
        }
        // Played part (wavy).
        val end = (x - gap).coerceAtLeast(0f)
        if (end > 0f) {
            val wavelength = 26.dp.toPx()
            val amp = 3.5.dp.toPx() * amplitude
            val path = Path()
            var px = 0f
            path.moveTo(0f, cy + amp * sin(phase))
            while (px < end) {
                px = (px + 2f).coerceAtMost(end)
                // Taper the wave near the handle so it meets it smoothly.
                val taper = ((end - px) / wavelength).coerceIn(0f, 1f)
                path.lineTo(px, cy + amp * taper * sin(2f * PI.toFloat() * px / wavelength - phase))
            }
            drawPath(path, activeColor, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        // Handle: a vertical pill, like the M3 Expressive slider.
        val hw = 4.dp.toPx()
        val hh = 20.dp.toPx() * handleHeight
        drawRoundRect(
            color = handleColor,
            topLeft = Offset(x - hw / 2, cy - hh / 2),
            size = Size(hw, hh),
            cornerRadius = CornerRadius(hw / 2),
        )
    }
}
