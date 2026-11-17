package com.worm.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.worm.app.ui.theme.Worm
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

private class Pixel(
    val x: Float, val y: Float, val sizeDp: Float, val speed: Float,
    val wobble: Float, val twinkle: Float, val phase: Float, val color: Color,
)

/**
 * The little square "glitch pixels" from the Worm logo, drifting upward and twinkling.
 * Only the draw phase reads the frame clock, so this never recomposes.
 */
@Composable
fun PixelField(
    modifier: Modifier = Modifier,
    count: Int = 36,
    colors: List<Color> = Worm.Dust,
    speed: Float = 1f,
    intensity: Float = 1f,
    seed: Int = 7,
) {
    val pixels = remember(count, colors, seed) {
        val r = Random(seed)
        List(count) {
            Pixel(
                x = r.nextFloat(), y = r.nextFloat(),
                sizeDp = 2f + r.nextFloat() * r.nextFloat() * 8f,
                speed = 0.008f + r.nextFloat() * 0.03f,
                wobble = 0.3f + r.nextFloat(),
                twinkle = 0.6f + r.nextFloat() * 2.2f,
                phase = r.nextFloat() * 2f * PI.toFloat(),
                color = colors[r.nextInt(colors.size)],
            )
        }
    }
    var nanos by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { nanos = it - start }
    }
    Canvas(modifier) {
        val t = nanos / 1_000_000_000f
        val dp = 1.dp.toPx()
        for (p in pixels) {
            val y = (((p.y - p.speed * speed * t) % 1f) + 1f) % 1f
            val x = p.x + 0.012f * sin(t * p.wobble + p.phase)
            val tw = (sin(t * p.twinkle + p.phase) + 1f) / 2f
            val alpha = (0.15f + 0.85f * tw * tw) * intensity
            val s = p.sizeDp * dp
            drawRect(
                color = p.color.copy(alpha = alpha.coerceIn(0f, 1f)),
                topLeft = Offset(x * size.width, y * size.height),
                size = Size(s, s),
            )
        }
    }
}
