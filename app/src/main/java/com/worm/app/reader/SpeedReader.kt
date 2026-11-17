package com.worm.app.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Words arriving from the book, one section (chapter file) at a time. */
class RsvpFeed {
    var section by mutableIntStateOf(-1)
    var words by mutableStateOf<List<String>>(emptyList())
    var index by mutableIntStateOf(0)
    var end by mutableStateOf(false)
    var loading by mutableStateOf(true)
    /** The section the reader was on when speed reading started (JS counts "ahead" from here). */
    var base = -1

    fun receive(section: Int, start: Int, words: List<String>, end: Boolean) {
        if (base < 0) base = section
        this.section = section; this.words = words; this.end = end
        index = start.coerceIn(0, (words.size - 1).coerceAtLeast(0))
        loading = false
    }
}

/** Where the pivot letter sits: a little left of centre, where the eye lands on a word. */
private fun pivotOf(word: String): Int = when (word.length) {
    0, 1 -> 0
    in 2..5 -> 1
    in 6..9 -> 2
    in 10..13 -> 3
    else -> 4
}

/** Longer pauses on sentence ends, commas and long words, so it reads like speech, not a metronome. */
private fun dwellMs(word: String, wpm: Int): Long {
    val base = 60_000.0 / wpm
    val f = when {
        word.endsWith('.') || word.endsWith('!') || word.endsWith('?') || word.endsWith('…') || word.endsWith(".”") || word.endsWith("?”") || word.endsWith("!”") -> 2.2
        word.endsWith(',') || word.endsWith(';') || word.endsWith(':') || word.endsWith('—') -> 1.5
        word.length > 9 -> 1.3
        else -> 1.0
    }
    return (base * f).toLong()
}

/**
 * Speed reading: one word at a time in the middle of the screen, pivot letter highlighted.
 * Play/pause, step, speed and size; leaving turns the page to where you stopped.
 */
@Composable
fun SpeedReader(
    feed: RsvpFeed,
    bg: Color,
    fg: Color,
    accent: Color,
    wpm: Int,
    textSize: Int,
    onWpm: (Int) -> Unit,
    onTextSize: (Int) -> Unit,
    onNeedSection: (ahead: Int) -> Unit,
    onExit: (section: Int, word: Int) -> Unit,
) {
    var playing by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(wpm.toFloat()) }
    var size by remember { mutableFloatStateOf(textSize.toFloat()) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    fun step(by: Int) {
        val n = feed.index + by
        when {
            n >= feed.words.size && !feed.end -> { feed.loading = true; onNeedSection(feed.section - feed.base + 1) }
            n < 0 && feed.section > 0 && feed.section > feed.base -> { feed.loading = true; onNeedSection(feed.section - feed.base - 1) }
            else -> feed.index = n.coerceIn(0, (feed.words.size - 1).coerceAtLeast(0))
        }
    }

    LaunchedEffect(playing, feed.section, feed.loading) {
        if (!playing || feed.loading) return@LaunchedEffect
        while (true) {
            val w = feed.words.getOrNull(feed.index) ?: break
            delay(dwellMs(w, speed.toInt()))
            if (feed.index >= feed.words.size - 1) {
                if (feed.end) { playing = false; break }
                feed.loading = true; onNeedSection(feed.section - feed.base + 1); break
            }
            feed.index++
        }
    }

    Box(
        Modifier.fillMaxSize().background(bg)
            .focusRequester(focus).focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionCenter, Key.Enter, Key.Spacebar, Key.ButtonA, Key.MediaPlayPause -> { playing = !playing; true }
                    Key.DirectionLeft, Key.ButtonL1 -> { playing = false; step(-1); true }
                    Key.DirectionRight, Key.ButtonR1 -> { playing = false; step(1); true }
                    Key.DirectionUp -> { speed = (speed + 25).coerceAtMost(1000f); onWpm(speed.toInt()); true }
                    Key.DirectionDown -> { speed = (speed - 25).coerceAtLeast(100f); onWpm(speed.toInt()); true }
                    Key.Back, Key.ButtonB, Key.Escape -> { onExit(feed.section, feed.index); true }
                    else -> false
                }
            }
            .pointerInput(Unit) { detectTapGestures { playing = !playing } },
    ) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onExit(feed.section, feed.index) }) { Icon(Icons.Rounded.Close, "Back to the page", tint = fg) }
                Text("Speed reading", color = fg, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text("${speed.toInt()} wpm", color = accent, style = MaterialTheme.typography.labelLarge)
            }
            if (feed.words.isNotEmpty()) LinearProgressIndicator(
                progress = { (feed.index + 1f) / feed.words.size }, color = accent, trackColor = fg.copy(alpha = 0.15f),
                drawStopIndicator = {}, modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val word = feed.words.getOrNull(feed.index)
                when {
                    feed.loading -> Text("…", color = fg.copy(alpha = 0.5f), fontSize = size.sp)
                    word == null -> Text("Nothing to read here", color = fg.copy(alpha = 0.6f))
                    else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        // A fixed notch marks where the pivot letter always lands.
                        Box(Modifier.width(2.dp).height(14.dp).background(accent.copy(alpha = 0.6f)))
                        val p = pivotOf(word.trimStart('“', '"', '‘', '(', '\'')) + (word.length - word.trimStart('“', '"', '‘', '(', '\'').length)
                        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(word.take(p), color = fg, fontSize = size.sp, textAlign = TextAlign.End, maxLines = 1, modifier = Modifier.weight(1f))
                            Text(word.getOrNull(p)?.toString().orEmpty(), color = accent, fontSize = size.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                            Text(word.drop(p + 1), color = fg, fontSize = size.sp, textAlign = TextAlign.Start, maxLines = 1, modifier = Modifier.weight(1f))
                        }
                        Box(Modifier.width(2.dp).height(14.dp).background(accent.copy(alpha = 0.6f)))
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { playing = false; step(-10) }) { Icon(Icons.Rounded.FastRewind, "Back 10 words", tint = fg) }
                Spacer(Modifier.width(12.dp))
                FilledIconButton(
                    onClick = { playing = !playing }, modifier = Modifier.size(64.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = accent, contentColor = bg),
                ) { Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play", Modifier.size(34.dp)) }
                Spacer(Modifier.width(12.dp))
                IconButton(onClick = { playing = false; step(10) }) { Icon(Icons.Rounded.FastForward, "Forward 10 words", tint = fg) }
            }
            val sliderColors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent, inactiveTrackColor = fg.copy(alpha = 0.2f))
            Text("Speed", color = fg.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
            Slider(speed, { speed = (it / 25f).roundToInt() * 25f }, valueRange = 100f..1000f, onValueChangeFinished = { onWpm(speed.toInt()) }, colors = sliderColors)
            Text("Word size", color = fg.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium)
            Slider(size, { size = it }, valueRange = 24f..72f, onValueChangeFinished = { onTextSize(size.toInt()) }, colors = sliderColors)
            TextButton(onClick = { onExit(feed.section, feed.index) }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("Back to the page here", color = accent)
            }
        }
    }
}
