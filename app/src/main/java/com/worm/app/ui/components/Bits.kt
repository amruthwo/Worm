package com.worm.app.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.worm.app.data.Book
import com.worm.app.ui.theme.Worm
import java.io.File
import kotlin.math.abs

fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Two colours picked from the title, so books without art still get a distinct, on-brand cover. */
private fun generatedColors(seed: String): Pair<Color, Color> {
    val palette = listOf(Worm.Plum, Worm.Violet, Worm.Pink, Color(0xFF2E7D74), Color(0xFF6B2E8F), Color(0xFFB33C86), Color(0xFF3B1052))
    val h = abs(seed.hashCode())
    val a = palette[h % palette.size]
    val b = palette[(h / 7 + 3) % palette.size].let { if (it == a) Worm.Mint else it }
    return a to lerp(b, Color.Black, 0.25f)
}

/**
 * A book's cover: the real art when we have it, otherwise a generated "cloth binding" with the
 * title set on it. Audiobook covers are square-ish; ebook covers are 2:3 — callers pick the aspect.
 */
@Composable
fun BookCover(book: Book, modifier: Modifier = Modifier, corner: Dp = 14.dp, showKindBadge: Boolean = true) {
    val context = LocalContext.current
    Box(modifier.clip(RoundedCornerShape(corner))) {
        val (a, b) = remember(book.id) { generatedColors(book.title) }
        Box(Modifier.matchParentSize().background(Brush.linearGradient(listOf(a, b))))
        if (book.coverPath == null) {
            // Spine stripe + title, like a cloth hardback.
            Box(Modifier.matchParentSize().padding(start = 10.dp).background(Color.Black.copy(alpha = 0.0f))) {
                Canvas(Modifier.matchParentSize()) {
                    drawLine(Color.White.copy(alpha = 0.18f), Offset(0f, 0f), Offset(0f, size.height), 3.dp.toPx())
                }
            }
            Column(Modifier.align(Alignment.Center).padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(
                    book.title,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall.copy(lineHeight = 16.sp),
                    textAlign = TextAlign.Center,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (book.authors.isNotEmpty()) Text(
                    book.author,
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        } else {
            val request = remember(book.coverPath) {
                ImageRequest.Builder(context).data(File(book.coverPath)).memoryCacheKey("cover:${book.coverPath}").build()
            }
            AsyncImage(request, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        }
        if (showKindBadge) KindBadge(book.isAudio, Modifier.align(Alignment.TopEnd).padding(6.dp))
    }
}

@Composable
fun KindBadge(audio: Boolean, modifier: Modifier = Modifier) {
    Surface(
        shape = CircleShape,
        color = Color.Black.copy(alpha = 0.55f),
        contentColor = if (audio) Worm.Mint else Worm.Blush,
        modifier = modifier,
    ) {
        Icon(
            if (audio) Icons.Rounded.Headphones else Icons.Rounded.MenuBook,
            contentDescription = if (audio) "Audiobook" else "Book",
            modifier = Modifier.padding(4.dp).size(13.dp),
        )
    }
}

/** Thin progress line under a cover. Mint for audio, pink for books. */
@Composable
fun BoxScope.CoverProgress(fraction: Float, audio: Boolean) {
    if (fraction <= 0.001f) return
    Box(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .height(4.dp)
            .background(Color.Black.copy(alpha = 0.45f)),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxSize()
                .background(if (audio) Worm.Mint else Worm.Pink),
        )
    }
}

/** Circular progress ring with rounded caps. */
@Composable
fun ProgressRing(fraction: Float, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary, stroke: Dp = 4.dp) {
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    Canvas(modifier) {
        val w = stroke.toPx()
        drawArc(track, 0f, 360f, false, style = Stroke(w))
        drawArc(color, -90f, 360f * fraction.coerceIn(0f, 1f), false, style = Stroke(w, cap = StrokeCap.Round))
    }
}

/**
 * Play/pause that morphs shape: a circle while paused, a squircle while playing,
 * and squishes when pressed (M3 Expressive shape morphing).
 */
@Composable
fun MorphPlayButton(
    playing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
    container: Color = MaterialTheme.colorScheme.primary,
    content: Color = MaterialTheme.colorScheme.onPrimary,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val corner by animateFloatAsState(
        targetValue = when { pressed -> 22f; playing -> 30f; else -> 50f },
        animationSpec = spring(dampingRatio = 0.45f, stiffness = 500f), label = "corner",
    )
    val scale by animateFloatAsState(if (pressed) 0.88f else 1f, spring(0.4f, 700f), label = "scale")
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(corner.toInt()),
        color = container,
        contentColor = content,
        interactionSource = interaction,
        modifier = modifier
            .size(size)
            .graphicsLayer { scaleX = scale; scaleY = scale },
    ) {
        Box(contentAlignment = Alignment.Center) {
            AnimatedContent(
                targetState = playing,
                transitionSpec = { (scaleIn(spring(0.5f, 600f)) + fadeIn()) togetherWith (scaleOut() + fadeOut()) },
                label = "icon",
            ) { isPlaying ->
                Icon(
                    if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(size * 0.5f),
                )
            }
        }
    }
}
