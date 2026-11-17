package com.worm.app.ui.screens

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.worm.app.data.Book
import com.worm.app.data.Bookmark
import com.worm.app.data.Chapter
import com.worm.app.player.PlayerHub
import com.worm.app.player.SleepState
import com.worm.app.ui.WormViewModel
import com.worm.app.ui.components.AuroraBackground
import com.worm.app.ui.components.BookCover
import com.worm.app.ui.components.MorphPlayButton
import com.worm.app.ui.components.SquigglySeekBar
import com.worm.app.ui.theme.LocalWormColors
import com.worm.app.util.formatDuration
import com.worm.app.util.formatHours
import kotlinx.coroutines.delay
import java.util.UUID

/** Live snapshot of the MediaController, refreshed by listener events plus a light 4 Hz tick. */
class PlayerUi {
    var playing by mutableStateOf(false)
    var index by mutableIntStateOf(0)
    var position by mutableLongStateOf(0L)
    var duration by mutableLongStateOf(0L)
    var speed by mutableFloatStateOf(1f)
    var hasMedia by mutableStateOf(false)
}

@Composable
fun rememberPlayerUi(controller: MediaController?): PlayerUi {
    val ui = remember { PlayerUi() }
    if (controller == null) return ui
    fun sync() {
        ui.playing = controller.isPlaying
        ui.index = controller.currentMediaItemIndex
        ui.position = controller.currentPosition.coerceAtLeast(0)
        ui.duration = controller.duration.takeIf { it != C.TIME_UNSET } ?: 0
        ui.speed = controller.playbackParameters.speed
        ui.hasMedia = controller.mediaItemCount > 0
    }
    DisposableEffect(controller) {
        val l = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = sync()
        }
        controller.addListener(l)
        sync()
        onDispose { controller.removeListener(l) }
    }
    LaunchedEffect(controller) {
        while (true) { sync(); delay(250) }
    }
    return ui
}

/** Position from the start of the whole book (multi-file books add up earlier tracks). */
private fun globalPosition(book: Book, ui: PlayerUi): Long =
    if (book.tracks.size > 1) book.tracks.take(ui.index).sumOf { it.durationMs } + ui.position else ui.position

private fun currentChapter(chapters: List<Chapter>, globalPos: Long): Int =
    chapters.indexOfLast { it.startMs <= globalPos + 250 }.coerceAtLeast(0)

@Composable
fun PlayerScreen(vm: WormViewModel, onBack: () -> Unit) {
    val controller by vm.controller.collectAsStateWithLifecycle()
    val bookId by PlayerHub.bookId.collectAsStateWithLifecycle()
    val chapters by PlayerHub.chapters.collectAsStateWithLifecycle()
    val sleep by PlayerHub.sleep.collectAsStateWithLifecycle()
    val books by vm.books.collectAsStateWithLifecycle()
    val bookmarks by vm.library.bookmarks.collectAsStateWithLifecycle()
    val book = books.firstOrNull { it.id == bookId }
    val ui = rememberPlayerUi(controller)
    val c = controller

    var sheet by remember { mutableStateOf<String?>(null) }

    Box(Modifier.fillMaxSize()) {
        AuroraBackground(emptyList())
        val darkTheme = LocalWormColors.current.isDark
        if (book?.coverPath != null && Build.VERSION.SDK_INT >= 31) {
            BookCover(
                book,
                Modifier
                    .fillMaxSize()
                    .blur(60.dp)
                    .graphicsLayer { alpha = if (darkTheme) 0.45f else 0.35f },
                corner = 0.dp, showKindBadge = false,
            )
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.background.copy(alpha = 0.85f)))))

        if (book == null || c == null) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Nothing playing", style = MaterialTheme.typography.headlineSmall)
                TextButton(onClick = onBack) { Text("Back to the library") }
            }
            return@Box
        }

        val global = globalPosition(book, ui)
        val chIndex = currentChapter(chapters, global)
        val ch = chapters.getOrNull(chIndex)
        val chStart = ch?.startMs ?: 0L
        val chLen = ((ch?.endMs ?: book.durationMs) - chStart).coerceAtLeast(1)
        val left = ((book.durationMs - global) / ui.speed.coerceAtLeast(0.1f)).toLong()

        // Swipe down anywhere (outside the seek bar) to tuck the player away.
        val pull = remember { androidx.compose.animation.core.Animatable(0f) }
        val pullScope = androidx.compose.runtime.rememberCoroutineScope()
        val dismissPx = with(androidx.compose.ui.platform.LocalDensity.current) { 140.dp.toPx() }
        val swipeDown = Modifier
            .graphicsLayer { translationY = pull.value; alpha = 1f - (pull.value / (dismissPx * 4)).coerceIn(0f, 0.5f) }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onVerticalDrag = { change, dy -> change.consume(); pullScope.launch { pull.snapTo((pull.value + dy).coerceAtLeast(0f)) } },
                    onDragEnd = { pullScope.launch { if (pull.value > dismissPx) onBack() else pull.animateTo(0f, spring(0.6f, 500f)) } },
                    onDragCancel = { pullScope.launch { pull.animateTo(0f) } },
                )
            }

        @Composable
        fun Cover(coverSize: Modifier) {
            // The cover "breathes" while playing.
            val breathe by animateFloatAsState(if (ui.playing) 1f else 0.9f, spring(0.5f, 120f), label = "breathe")
            BookCover(
                book,
                Modifier
                    .then(coverSize)
                    .aspectRatio(1f)
                    .graphicsLayer { scaleX = breathe; scaleY = breathe }
                    .shadow(24.dp, RoundedCornerShape(32.dp)),
                corner = 32.dp, showKindBadge = false,
            )
        }

        @Composable
        fun Controls() {
            Text(book.title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(book.author, book.narrator?.let { "read by $it" }).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(12.dp))
            Surface(
                onClick = { sheet = "chapters" },
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Rounded.List, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(ch?.title ?: "Chapter", style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (chapters.size > 1) Text("  ${chIndex + 1}/${chapters.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f))
                }
            }
            Spacer(Modifier.height(10.dp))

            // Seek bar covers the current chapter, which is what you actually care about mid-book.
            SquigglySeekBar(
                position = { globalPosition(book, ui) - chStart },
                duration = chLen,
                playing = ui.playing,
                onSeek = { rel -> seekGlobal(c, book, chStart + rel) },
                activeColor = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
                handleColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth()) {
                Text(formatDuration(global - chStart), style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.weight(1f))
                Text("${formatHours(left)} left", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.weight(1f))
                Text("-" + formatDuration(chLen - (global - chStart)), style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                IconButton(onClick = {
                    // Chapters are in whole-book time, so this works across files too.
                    val target = if (global - chStart > 3_000 || chIndex == 0) chStart else chapters.getOrNull(chIndex - 1)?.startMs ?: 0L
                    seekGlobal(c, book, target)
                }) { Icon(Icons.Rounded.SkipPrevious, "Previous chapter", Modifier.size(30.dp)) }
                SkipButton(vm.prefs.skipBackSec, back = true) { c.seekBack() }
                MorphPlayButton(ui.playing, onClick = { if (ui.playing) c.pause() else c.play() }, size = 84.dp)
                SkipButton(vm.prefs.skipForwardSec, back = false) { c.seekForward() }
                IconButton(enabled = chIndex < chapters.lastIndex, onClick = { chapters.getOrNull(chIndex + 1)?.let { seekGlobal(c, book, it.startMs) } }) { Icon(Icons.Rounded.SkipNext, "Next chapter", Modifier.size(30.dp)) }
            }
        }

        @Composable
        fun Tools() {
            HorizontalFloatingToolbar(
                expanded = true,
                colors = FloatingToolbarDefaults.standardFloatingToolbarColors(),
                modifier = Modifier.padding(bottom = 12.dp),
            ) {
                TextButton(onClick = { sheet = "speed" }) {
                    Icon(Icons.Rounded.Speed, null, Modifier.size(20.dp)); Spacer(Modifier.width(6.dp))
                    Text("${trimSpeed(ui.speed)}×")
                }
                IconButton(onClick = { sheet = "sleep" }) { Icon(Icons.Rounded.Bedtime, "Sleep timer") }
                IconButton(onClick = { sheet = "chapters" }) { Icon(Icons.AutoMirrored.Rounded.List, "Chapters") }
                IconButton(onClick = {
                    vm.library.addBookmark(
                        Bookmark(
                            UUID.randomUUID().toString(), book.id, "audio", ch?.title ?: "Bookmark", System.currentTimeMillis(),
                            positionMs = ui.position, trackIndex = ui.index,
                        ),
                    )
                    vm.say("Bookmarked at ${formatDuration(global)}")
                }) { Icon(Icons.Rounded.BookmarkAdd, "Add bookmark") }
                IconButton(onClick = { sheet = "bookmarks" }) { Icon(Icons.Rounded.Bookmarks, "Bookmarks") }
            }
        }

        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().then(swipeDown)) {
            if (maxWidth > maxHeight) {
                // Landscape: cover on the left, everything else on the right.
                Row(Modifier.fillMaxSize().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(0.42f).fillMaxSize(), contentAlignment = Alignment.Center) {
                        Cover(Modifier.fillMaxHeight(0.86f))
                        Box(Modifier.align(Alignment.TopStart)) { IconButton(onClick = onBack) { Icon(Icons.Rounded.KeyboardArrowDown, "Close player") } }
                    }
                    Spacer(Modifier.width(24.dp))
                    Column(
                        Modifier.weight(0.58f).fillMaxSize().androidVerticalScroll(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        AnimatedVisibility(sleep != SleepState.Off) {
                            AssistChip(onClick = { sheet = "sleep" }, label = { Text(sleepLabel(sleep)) }, leadingIcon = { Icon(Icons.Rounded.Bedtime, null, Modifier.size(18.dp)) })
                        }
                        Controls()
                        Spacer(Modifier.height(8.dp))
                        Tools()
                    }
                }
            } else {
                Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Rounded.KeyboardArrowDown, "Close player") }
                Spacer(Modifier.weight(1f))
                AnimatedVisibility(sleep != SleepState.Off) {
                    AssistChip(onClick = { sheet = "sleep" }, label = { Text(sleepLabel(sleep)) }, leadingIcon = { Icon(Icons.Rounded.Bedtime, null, Modifier.size(18.dp)) })
                }
            }
                    Spacer(Modifier.weight(0.4f))
                    Cover(Modifier.widthIn(max = 340.dp).fillMaxWidth(0.82f))
                    Spacer(Modifier.weight(0.4f))
                    Controls()
                    Spacer(Modifier.weight(0.5f))
                    Tools()
                }
            }
        }

        when (sheet) {
            "speed" -> SpeedSheet(ui.speed, onSpeed = { c.playbackParameters = PlaybackParameters(it) }, onDismiss = { sheet = null })
            "sleep" -> SleepSheet(sleep, vm.prefs.sleepMinutes, onSet = { PlayerHub.sleep.value = it; if (it is SleepState.Counting) vm.prefs.sleepMinutes = (it.totalMs / 60_000).toInt() }, onDismiss = { sheet = null })
            "chapters" -> ChapterSheet(chapters, chIndex, onPick = { seekGlobal(c, book, it.startMs); sheet = null }, onDismiss = { sheet = null })
            "bookmarks" -> BookmarkSheet(
                bookmarks.filter { it.bookId == book.id }.sortedWith(compareBy({ it.trackIndex }, { it.positionMs })),
                book,
                onPick = { c.seekTo(it.trackIndex, it.positionMs); sheet = null },
                onDelete = { vm.library.removeBookmark(it.id) },
                onDismiss = { sheet = null },
            )
        }
    }
}

private fun seekGlobal(c: MediaController, book: Book, globalMs: Long) {
    if (book.tracks.size <= 1) { c.seekTo(globalMs.coerceAtLeast(0)); return }
    var acc = 0L
    book.tracks.forEachIndexed { i, t ->
        if (globalMs < acc + t.durationMs || i == book.tracks.lastIndex) {
            c.seekTo(i, (globalMs - acc).coerceAtLeast(0)); return
        }
        acc += t.durationMs
    }
}

/** Skip button: an open circular arrow around the number of seconds. */
@Composable
private fun SkipButton(seconds: Int, back: Boolean, onClick: () -> Unit) {
    FilledTonalIconButton(onClick = onClick, shapes = IconButtonDefaults.shapes(), modifier = Modifier.size(60.dp)) {
        SkipGlyph(seconds, back, size = 44.dp)
    }
}

/**
 * ↺ / ↻ with the seconds inside. The ring is open at the top; the arrowhead sits on the open end
 * and points along the direction of travel (anticlockwise for back, clockwise for forward).
 */
@Composable
fun SkipGlyph(seconds: Int, back: Boolean, size: androidx.compose.ui.unit.Dp) {
    val tint = androidx.compose.material3.LocalContentColor.current
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size)) {
        androidx.compose.foundation.Canvas(Modifier.matchParentSize()) {
            val stroke = this.size.minDimension * 0.06f
            val head = this.size.minDimension * 0.16f
            val r = this.size.minDimension / 2 - head * 0.6f
            val c = center
            // Like Material's replay/forward icons: a 300° ring whose open end sits at the top,
            // with the head there pointing into the gap (left for back, right for forward).
            val headAngle = if (back) -95f else -85f
            drawArc(
                tint, headAngle, if (back) 300f else -300f, false,
                topLeft = androidx.compose.ui.geometry.Offset(c.x - r, c.y - r),
                size = androidx.compose.ui.geometry.Size(r * 2, r * 2),
                style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
            )
            val t = Math.toRadians(headAngle.toDouble())
            val p = androidx.compose.ui.geometry.Offset(c.x + (r * kotlin.math.cos(t)).toFloat(), c.y + (r * kotlin.math.sin(t)).toFloat())
            // Screen y points down, so increasing angle runs clockwise: tangent (-sin, cos). Back runs anticlockwise.
            val sx = kotlin.math.sin(t).toFloat(); val cx = kotlin.math.cos(t).toFloat()
            val dir = if (back) androidx.compose.ui.geometry.Offset(sx, -cx) else androidx.compose.ui.geometry.Offset(-sx, cx)
            val normal = androidx.compose.ui.geometry.Offset(-dir.y, dir.x)
            // A long, narrow head so its direction is unmistakable.
            val tip = p + dir * (head * 1.05f)
            val base = p - dir * (head * 0.15f)
            val half = head * 0.5f
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(tip.x, tip.y)
                lineTo(base.x + normal.x * half, base.y + normal.y * half)
                lineTo(base.x - normal.x * half, base.y - normal.y * half)
                close()
            }
            drawPath(path, tint)
        }
        Text("$seconds", fontSize = (size.value * 0.3f).sp, fontWeight = FontWeight.Bold, color = tint)
    }
}

private fun trimSpeed(s: Float) = if (s % 1f == 0f) "${s.toInt()}" else "%.2f".format(s).trimEnd('0').trimEnd('.')

private fun sleepLabel(s: SleepState): String = when (s) {
    SleepState.Off -> "Off"
    SleepState.EndOfChapter -> "End of chapter"
    is SleepState.Counting -> formatDuration((s.endsAt - System.currentTimeMillis()).coerceAtLeast(0))
}

@Composable
private fun SpeedSheet(speed: Float, onSpeed: (Float) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableFloatStateOf(speed) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
            Text("Playback speed", style = MaterialTheme.typography.titleLarge)
            Text("${trimSpeed(value)}×", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
            Slider(
                value = value,
                onValueChange = { value = (it * 20).toInt() / 20f },
                onValueChangeFinished = { onSpeed(value) },
                valueRange = 0.5f..3.5f,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0.8f, 1f, 1.25f, 1.5f, 2f, 2.5f).forEach { s ->
                    FilterChip(selected = value == s, onClick = { value = s; onSpeed(s) }, label = { Text("${trimSpeed(s)}×") })
                }
            }
        }
    }
}

@Composable
private fun SleepSheet(state: SleepState, lastMinutes: Int, onSet: (SleepState) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
            Text("Sleep timer", style = MaterialTheme.typography.titleLarge)
            Text(
                "Fades out gently at the end. Shake the phone while it's counting to add another ${lastMinutes} minutes.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            val options = listOf(5, 10, 15, 30, 45, 60, 90)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                options.take(4).forEach { m -> SleepChip("$m min") { onSet(SleepState.Counting(System.currentTimeMillis() + m * 60_000L, m * 60_000L)); onDismiss() } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                options.drop(4).forEach { m -> SleepChip(if (m >= 60) "${m / 60f}".removeSuffix(".0") + " h" else "$m min") { onSet(SleepState.Counting(System.currentTimeMillis() + m * 60_000L, m * 60_000L)); onDismiss() } }
                SleepChip("End of chapter") { onSet(SleepState.EndOfChapter); onDismiss() }
            }
            if (state != SleepState.Off) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { onSet(SleepState.Off); onDismiss() }) { Text("Turn off timer") }
            }
        }
    }
}

@Composable
private fun SleepChip(label: String, onClick: () -> Unit) {
    FilterChip(selected = false, onClick = onClick, label = { Text(label) })
}

@Composable
private fun ChapterSheet(chapters: List<Chapter>, current: Int, onPick: (Chapter) -> Unit, onDismiss: () -> Unit) {
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (current - 2).coerceAtLeast(0))
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text("Chapters", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
        LazyColumn(state = list, modifier = Modifier.navigationBarsPadding()) {
            itemsIndexed(chapters) { i, ch ->
                val now = i == current
                ListItem(
                    headlineContent = { Text(ch.title, fontWeight = if (now) FontWeight.Bold else null, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(formatDuration(ch.endMs - ch.startMs)) },
                    leadingContent = {
                        Box(
                            Modifier.size(32.dp).clip(CircleShape)
                                .background(if (now) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("${i + 1}", style = MaterialTheme.typography.labelMedium, color = if (now) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                        }
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = if (now) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else Color.Transparent,
                    ),
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { onPick(ch) },
                )
            }
        }
    }
}

@Composable
private fun BookmarkSheet(
    marks: List<Bookmark>,
    book: Book,
    onPick: (Bookmark) -> Unit,
    onDelete: (Bookmark) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text("Bookmarks", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
        if (marks.isEmpty()) {
            Text(
                "No bookmarks yet. Tap the bookmark button to save your spot.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
        }
        LazyColumn(Modifier.navigationBarsPadding()) {
            itemsIndexed(marks, key = { _, m -> m.id }) { _, m ->
                val global = book.tracks.take(m.trackIndex).sumOf { it.durationMs } + m.positionMs
                ListItem(
                    headlineContent = { Text(m.label) },
                    supportingContent = { Text(formatDuration(global)) },
                    trailingContent = { IconButton(onClick = { onDelete(m) }) { Icon(Icons.Rounded.Delete, "Delete bookmark") } },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.padding(horizontal = 8.dp).clip(RoundedCornerShape(16.dp)).clickable { onPick(m) },
                )
            }
        }
    }
}

@Composable
fun MiniPlayer(vm: WormViewModel, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val controller by vm.controller.collectAsStateWithLifecycle()
    val bookId by PlayerHub.bookId.collectAsStateWithLifecycle()
    val books by vm.books.collectAsStateWithLifecycle()
    val book = books.firstOrNull { it.id == bookId }
    val ui = rememberPlayerUi(controller)
    AnimatedVisibility(
        visible = book != null && ui.hasMedia,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = modifier,
    ) {
        if (book == null) return@AnimatedVisibility
        // Swipe sideways to dismiss: stops playback and clears the queue.
        val drag = remember { androidx.compose.animation.core.Animatable(0f) }
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        val widthPx = with(androidx.compose.ui.platform.LocalDensity.current) { androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp.toPx() }
        // Docked to the bottom edge, under the floating toolbar.
        Surface(
            onClick = onOpen,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shadowElevation = 12.dp,
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    translationX = drag.value
                    alpha = 1f - (kotlin.math.abs(drag.value) / widthPx).coerceIn(0f, 0.8f)
                }
                .pointerInput(book.id) {
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { change, delta -> change.consume(); scope.launch { drag.snapTo(drag.value + delta) } },
                        onDragEnd = {
                            scope.launch {
                                if (kotlin.math.abs(drag.value) > widthPx * 0.33f) {
                                    drag.animateTo(if (drag.value > 0) widthPx else -widthPx)
                                    controller?.run { stop(); clearMediaItems() }
                                    drag.snapTo(0f)
                                } else drag.animateTo(0f, androidx.compose.animation.core.spring(0.6f, 500f))
                            }
                        },
                        onDragCancel = { scope.launch { drag.animateTo(0f) } },
                    )
                },
        ) {
            Column(Modifier.navigationBarsPadding()) {
                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    BookCover(book, Modifier.size(48.dp), corner = 14.dp, showKindBadge = false)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(book.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(book.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { controller?.seekBack() }) { SkipGlyph(vm.prefs.skipBackSec, back = true, size = 34.dp) }
                    MorphPlayButton(ui.playing, onClick = { controller?.let { if (ui.playing) it.pause() else it.play() } }, size = 44.dp)
                    IconButton(onClick = { controller?.seekForward() }) { SkipGlyph(vm.prefs.skipForwardSec, back = false, size = 34.dp) }
                }
                val g = globalPosition(book, ui)
                Box(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 6.dp).height(3.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))) {
                    Box(Modifier.fillMaxWidth((g.toFloat() / book.durationMs.coerceAtLeast(1)).coerceIn(0f, 1f)).fillMaxSize().background(MaterialTheme.colorScheme.primary))
                }
            }
        }
    }
}

@Composable
private fun Modifier.androidVerticalScroll(): Modifier = this.verticalScroll(androidx.compose.foundation.rememberScrollState())
