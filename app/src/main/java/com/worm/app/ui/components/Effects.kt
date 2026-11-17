package com.worm.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.worm.app.ui.theme.LocalWormColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * Slowly drifting blobs of colour: the theme's glow colours, nudged toward whatever covers are
 * on screen. The backdrop for the library and player.
 */
@Composable
fun AuroraBackground(colors: List<Int>, modifier: Modifier = Modifier) {
    val wc = LocalWormColors.current
    val brand = listOf(wc.glowA, wc.glowB, wc.glowC, wc.glowA)
    val picked = (colors.map { Color(it) } + brand).take(4)
    val tones: List<State<Color>> = picked.mapIndexed { i, c ->
        val toned = if (wc.isDark) lerp(lerp(c, wc.glowA, 0.25f), Color.Black, 0.2f) else lerp(c, Color.White, 0.45f)
        animateColorAsState(toned, tween(1800), label = "aurora$i")
    }
    var nanos by remember { mutableLongStateOf(0L) }
    LaunchedEffect(wc.calm) {
        if (wc.calm) return@LaunchedEffect
        val start = withFrameNanos { it }
        while (true) withFrameNanos { nanos = it - start }
    }
    Canvas(modifier.fillMaxSize()) {
        drawRect(wc.backdrop)
        if (wc.calm) return@Canvas
        val t = nanos / 1_000_000_000f
        val big = max(size.width, size.height)
        val paths = listOf(
            Triple(0.21f, 0.13f, 0f), Triple(0.17f, 0.23f, 1.7f),
            Triple(0.13f, 0.19f, 3.1f), Triple(0.25f, 0.11f, 4.4f),
        )
        val alpha = if (wc.isDark) 0.55f else 0.6f
        tones.forEachIndexed { i, tone ->
            val (fx, fy, ph) = paths[i]
            val cx = size.width * (0.5f + 0.42f * sin(t * fx + ph))
            val cy = size.height * (0.28f + 0.26f * cos(t * fy + ph * 0.7f)) + i * size.height * 0.08f
            val r = big * (0.5f + 0.1f * sin(t * 0.3f + i))
            drawCircle(
                Brush.radialGradient(
                    listOf(tone.value.copy(alpha = alpha), tone.value.copy(alpha = alpha * 0.3f), Color.Transparent),
                    center = Offset(cx, cy), radius = r,
                ),
                radius = r, center = Offset(cx, cy),
            )
        }
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, wc.backdrop.copy(alpha = 0.6f), wc.backdrop.copy(alpha = 0.92f))))
    }
}

/**
 * A little worm that inches along a wavy path — used as the empty-state mascot and for
 * "working on it" moments. Purely decorative.
 */
@Composable
fun WigglingWorm(modifier: Modifier = Modifier, color: Color = com.worm.app.ui.theme.Worm.Bubblegum, speed: Float = 1f) {
    var nanos by remember { mutableLongStateOf(0L) }
    val calm = com.worm.app.ui.theme.LocalWormColors.current.calm
    LaunchedEffect(calm) {
        if (calm) return@LaunchedEffect
        val start = withFrameNanos { it }
        while (true) withFrameNanos { nanos = it - start }
    }
    val outline = com.worm.app.ui.theme.Worm.Ink
    Canvas(modifier) {
        val t = nanos / 1_000_000_000f * speed
        val w = size.width
        val h = size.height
        val thick = h * 0.28f
        val path = Path()
        val segments = 40
        for (i in 0..segments) {
            val u = i / segments.toFloat()
            val x = w * (0.08f + 0.72f * u)
            val y = h * 0.6f + sin(u * 2.2f * PI.toFloat() - t * 4f) * h * 0.14f * (0.4f + u * 0.6f)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, outline, style = Stroke(thick + 4.dp.toPx(), cap = StrokeCap.Round))
        drawPath(path, color, style = Stroke(thick, cap = StrokeCap.Round))
        // Head
        val hx = w * 0.82f
        val hy = h * 0.6f + sin(2.2f * PI.toFloat() - t * 4f) * h * 0.14f - thick * 0.15f
        val hr = thick * 0.78f
        drawCircle(outline, hr + 2.dp.toPx(), Offset(hx, hy))
        drawCircle(color, hr, Offset(hx, hy))
        val blink = if ((t % 3.2f) < 0.12f) 0.2f else 1f
        drawOval(outline, Offset(hx - hr * 0.42f, hy - hr * 0.35f), androidx.compose.ui.geometry.Size(hr * 0.22f, hr * 0.38f * blink))
        drawOval(outline, Offset(hx + hr * 0.18f, hy - hr * 0.35f), androidx.compose.ui.geometry.Size(hr * 0.22f, hr * 0.38f * blink))
        drawArc(outline, 20f, 140f, false, Offset(hx - hr * 0.45f, hy - hr * 0.2f), androidx.compose.ui.geometry.Size(hr * 0.9f, hr * 0.7f), style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round))
    }
}

/** Confetti made of tiny book pages, for finishing a book. */
@Composable
fun BoxScope.PageConfetti(trigger: Int, onDone: () -> Unit = {}) {
    if (trigger == 0) return
    val anim = remember(trigger) { Animatable(0f) }
    val dust = LocalWormColors.current.dust
    LaunchedEffect(trigger) {
        anim.animateTo(1f, tween(2200, easing = FastOutSlowInEasing))
        onDone()
    }
    val bits = remember(trigger) {
        val r = Random(trigger)
        List(70) { Pair(Offset(r.nextFloat(), -0.1f - r.nextFloat() * 0.3f), Triple(r.nextFloat() * 2 - 1, r.nextFloat() * 360f, dust[r.nextInt(dust.size)])) }
    }
    Canvas(Modifier.matchParentSize()) {
        val p = anim.value
        if (p >= 1f) return@Canvas
        val d = 1.dp.toPx()
        bits.forEach { (start, info) ->
            val (drift, spin, color) = info
            val x = (start.x + drift * 0.15f * p) * size.width + sin(p * 12f + spin) * 14f * d
            val y = (start.y + p * 1.3f) * size.height
            val rot = spin + p * 720f * drift
            val w = 7f * d; val h = 9f * d
            val c = cos(rot * PI.toFloat() / 180f)
            drawRect(
                color.copy(alpha = (1f - p).coerceIn(0f, 1f)),
                topLeft = Offset(x - w * c / 2, y - h / 2),
                size = androidx.compose.ui.geometry.Size(w * abs(c).coerceAtLeast(0.15f), h),
            )
        }
    }
}

private fun abs(f: Float) = kotlin.math.abs(f)
