package com.worm.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.worm.app.data.Library
import com.worm.app.data.Prefs
import com.worm.app.ui.theme.Worm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile
import kotlin.math.abs
import kotlin.math.atan2

/**
 * Bookworm: the secret game behind ten taps on "About". The worm eats the letters of a sentence
 * pulled from one of your own books (never from the Burrow) and the line fills in as you go.
 */
private data class Cell(val x: Int, val y: Int)
private enum class Dir(val dx: Int, val dy: Int) { Up(0, -1), Down(0, 1), Left(-1, 0), Right(1, 0) }

private data class Skin(val name: String, val bg: Color, val grid: Color, val body: Color, val head: Color, val food: Color, val text: Color)

private val Skins = listOf(
    // A nod to monochrome phone screens.
    Skin("Retro LCD", Color(0xFF9BBC0F), Color(0xFF8BAC0F), Color(0xFF306230), Color(0xFF0F380F), Color(0xFF0F380F), Color(0xFF0F380F)),
    Skin("Plum", Worm.Night, Color(0xFF1E0F2A), Worm.Bubblegum, Worm.Pink, Worm.Mint, Color(0xFFF4E2F4)),
    Skin("Paper", Color(0xFFFBF7EF), Color(0xFFF0E9DA), Color(0xFF8E1E82), Color(0xFF542177), Color(0xFFD552A3), Color(0xFF26201B)),
)

private const val COLS = 18
private const val ROWS = 24

data class Sentence(val text: String, val source: String?)

/** Pulls short, readable sentences out of the user's own EPUB/FB2 books (never Burrow books). */
object SentenceSource {
    private val fallback = listOf(
        "Every story begins with a single hungry letter.",
        "The best tunnels are dug one word at a time.",
        "Somewhere in the margins, a small worm is reading.",
        "Turn the page, the worm insists, there is more.",
        "Even the longest book is eaten one bite at a time.",
    ).map { Sentence(it, null) }

    suspend fun load(library: Library, want: Int = 30): List<Sentence> = withContext(Dispatchers.IO) {
        val books = library.books.value.filter { !it.hidden && !it.isAudio && it.format in setOf("epub", "fb2") && File(it.path).isFile }.shuffled()
        val out = mutableListOf<Sentence>()
        for (b in books.take(8)) {
            if (out.size >= want) break
            val text = runCatching { if (b.format == "epub") epubText(File(b.path)) else File(b.path).readText().take(400_000) }.getOrNull() ?: continue
            val sentences = clean(text).split(Regex("""(?<=[.!?])\s+"""))
                .map { it.trim() }
                .filter { s ->
                    s.length in 24..80 && s.first().isUpperCase() && s.last() in ".!?" &&
                        s.count { it.isLetter() } > s.length * 0.75 && s.none { it.isDigit() } && s.count { it == '"' || it == '“' } == 0
                }
                .shuffled().take(5)
            out += sentences.map { Sentence(it, b.title) }
        }
        out.shuffled().ifEmpty { fallback.shuffled() }
    }

    private fun epubText(f: File): String = ZipFile(f).use { z ->
        val docs = z.entries().asSequence()
            .filter { !it.isDirectory && Regex("""(?i)\.(x?html?)$""").containsMatchIn(it.name) && it.size > 4000 }
            .toList()
        // Skip the front and back matter (title pages, copyright, acknowledgements).
        val body = if (docs.size > 4) docs.sortedBy { it.name }.drop(docs.size / 6).dropLast(docs.size / 6) else docs
        val pick = body.shuffled().take(2)
        pick.joinToString(" ") { e -> z.getInputStream(e).bufferedReader().readText() }
    }

    private fun clean(html: String) = html
        .replace(Regex("""(?is)<(script|style|head)[^>]*>.*?</\1>"""), " ")
        .replace(Regex("""(?i)</(p|div|h\d|li|br)>|<br\s*/?>"""), ". ")
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&rsquo;", "’").replace("&lsquo;", "‘")
        .replace("&ldquo;", "“").replace("&rdquo;", "”").replace("&mdash;", "—").replace("&#8217;", "’")
        .replace(Regex("&#?\\w+;"), " ")
        .replace(Regex("\\s+"), " ")
}

@Composable
fun WormGameScreen(prefs: Prefs, onBack: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current
    val measurer = rememberTextMeasurer()
    var skinIndex by remember { mutableIntStateOf(prefs.gameSkin.coerceIn(0, Skins.lastIndex)) }
    val skin = Skins[skinIndex]
    var controls by remember { mutableStateOf(prefs.gameControls) }

    var sentences by remember { mutableStateOf<List<Sentence>>(emptyList()) }
    LaunchedEffect(Unit) { sentences = SentenceSource.load(Library.get(context)) }
    var sentenceIndex by remember { mutableIntStateOf(0) }
    val sentence = sentences.getOrNull(sentenceIndex % sentences.size.coerceAtLeast(1))
    /** How much of the sentence is revealed: everything before the next letter to eat. */
    var revealed by remember { mutableIntStateOf(0) }
    var finishedLine by remember { mutableStateOf<Sentence?>(null) }

    var worm by remember { mutableStateOf(listOf(Cell(6, 12), Cell(5, 12), Cell(4, 12))) }
    var dir by remember { mutableStateOf(Dir.Right) }
    var queued by remember { mutableStateOf<Dir?>(null) }
    var food by remember { mutableStateOf(Cell(12, 12)) }
    var score by remember { mutableIntStateOf(0) }
    var best by remember { mutableIntStateOf(prefs.gameBest) }
    var state by remember { mutableStateOf("ready") } // ready, playing, paused, over
    var game by remember { mutableIntStateOf(0) }

    /** Skip spaces and punctuation: those appear on their own. */
    fun nextLetterIndex(text: String, from: Int): Int {
        var i = from
        while (i < text.length && !text[i].isLetter()) i++
        return i
    }
    val target: Char = sentence?.let { s -> s.text.getOrNull(nextLetterIndex(s.text, revealed))?.uppercaseChar() } ?: 'W'

    fun newFood(body: List<Cell>) {
        val free = (0 until COLS).flatMap { x -> (0 until ROWS).map { y -> Cell(x, y) } }.filter { it !in body }
        food = free.random()
    }

    fun reset() {
        worm = listOf(Cell(6, 12), Cell(5, 12), Cell(4, 12))
        dir = Dir.Right; queued = null; score = 0
        revealed = 0; finishedLine = null; sentenceIndex = (0 until sentences.size.coerceAtLeast(1)).random()
        newFood(worm)
        state = "playing"
        game++
    }

    fun turn(d: Dir) {
        val current = queued ?: dir
        if (d.dx == -current.dx && d.dy == -current.dy) return // no reversing into yourself
        queued = d
    }

    LaunchedEffect(game, state) {
        if (state != "playing") return@LaunchedEffect
        while (true) {
            delay((170L - score * 3L).coerceAtLeast(75L))
            queued?.let { dir = it; queued = null }
            val head = worm.first()
            // Walls wrap around: leave one side, come back on the other. Only your own tail ends the game.
            val next = Cell((head.x + dir.dx).mod(COLS), (head.y + dir.dy).mod(ROWS))
            val grows = next == food
            val body = if (grows) worm else worm.dropLast(1)
            if (next in body) {
                state = "over"
                haptics.performHapticFeedback(HapticFeedbackType.Reject)
                if (score > best) { best = score; prefs.gameBest = score }
                break
            }
            worm = listOf(next) + body
            if (grows) {
                score++
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                val s = sentence
                if (s != null) {
                    val eatenAt = nextLetterIndex(s.text, revealed)
                    revealed = nextLetterIndex(s.text, eatenAt + 1).let { if (it >= s.text.length) s.text.length else it }
                    if (revealed >= s.text.length) {
                        // Whole sentence eaten: bonus, show where it came from, start another.
                        score += 5
                        finishedLine = s
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        sentenceIndex++
                        revealed = 0
                    }
                }
                newFood(worm)
            }
        }
    }

    // Gamepads and keyboards: arrows / d-pad steer; A, Enter, Space or Start begin and pause.
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    fun startOrPause() {
        state = when (state) { "playing" -> "paused"; "paused" -> "playing"; else -> { if (sentences.isNotEmpty()) reset(); state } }
    }

    @Composable fun Header() {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = skin.text) }
        Text("BOOKWORM", color = skin.text, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Spacer(Modifier.weight(1f))
        Text("%04d".format(score), color = skin.text, fontFamily = FontFamily.Monospace, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.padding(4.dp))
        Text("HI %04d".format(best), color = skin.text.copy(alpha = 0.6f), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
    }
    // The sentence being eaten: revealed text, then dots for letters still to come.
    Box(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 4.dp), contentAlignment = Alignment.CenterStart) {
        val s = sentence
        if (s != null) Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = skin.text)) { append(s.text.substring(0, revealed.coerceAtMost(s.text.length))) }
                withStyle(SpanStyle(color = skin.text.copy(alpha = 0.3f))) {
                    append(s.text.substring(revealed.coerceAtMost(s.text.length)).map { if (it.isLetter()) '·' else it }.joinToString(""))
                }
            },
            fontFamily = FontFamily.Monospace, fontSize = 14.sp, lineHeight = 19.sp,
        )
    }
    finishedLine?.source?.let {
        Text("— from “$it”", color = skin.text.copy(alpha = 0.7f), fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1)
    }
    }

    @Composable fun BoardArea(modifier: Modifier) {
    Box(
        modifier
            .aspectRatio(COLS / ROWS.toFloat())
            .clip(RoundedCornerShape(12.dp))
            .background(skin.grid)
            .pointerInput(controls) {
                if (controls != "swipe") return@pointerInput
                var acc = Offset.Zero
                detectDragGestures(
                    onDragStart = { acc = Offset.Zero },
                    onDrag = { change, amount ->
                        change.consume()
                        acc += amount
                        val threshold = 24.dp.toPx()
                        if (abs(acc.x) > threshold || abs(acc.y) > threshold) {
                            turn(if (abs(acc.x) > abs(acc.y)) (if (acc.x > 0) Dir.Right else Dir.Left) else (if (acc.y > 0) Dir.Down else Dir.Up))
                            acc = Offset.Zero
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                detectTapGestures {
                    state = when (state) { "playing" -> "paused"; "paused" -> "playing"; else -> state }
                }
            },
    ) {
        Board(worm, dir, food, target, skin, measurer)
        androidx.compose.animation.AnimatedVisibility(state != "playing", modifier = Modifier.align(Alignment.Center)) {
            Column(
                Modifier.clip(RoundedCornerShape(20.dp)).background(skin.bg.copy(alpha = 0.94f)).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    when (state) { "over" -> "THE END"; "paused" -> "PAUSED"; else -> "BOOKWORM" },
                    color = skin.text, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 26.sp,
                )
                Text(
                    when (state) {
                        "over" -> "You ate $score letters" + if (score > 0 && score >= best) "\nNew high score!" else ""
                        "paused" -> "Tap the page to keep wriggling"
                        else -> "Eat the letters in order to\nspell out a line from your books.\nWalls wrap around. Don't bite your own tail."
                    },
                    color = skin.text.copy(alpha = 0.8f), fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 10.dp),
                )
                if (state != "paused") Button(onClick = ::reset, enabled = sentences.isNotEmpty()) { Text(if (state == "over") "Again!" else "Wriggle!") }
            }
        }
    }
    }

    @Composable fun Controls() {
    when (controls) {
        "dpad" -> DPad(skin, ::turn)
        "joystick" -> Joystick(skin, ::turn)
    }
    }

    @Composable fun Options() {
    androidx.compose.foundation.layout.FlowRow(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("swipe" to "Swipe", "dpad" to "D-pad", "joystick" to "Joystick").forEach { (k, label) ->
            FilterChip(
                selected = controls == k, onClick = { controls = k; prefs.gameControls = k },
                label = { Text(label, color = skin.text) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = skin.body.copy(alpha = 0.35f)),
            )
        }
    }
    androidx.compose.foundation.layout.FlowRow(Modifier.padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Skins.forEachIndexed { i, s ->
            FilterChip(
                selected = i == skinIndex, onClick = { skinIndex = i; prefs.gameSkin = i },
                label = { Text(s.name, color = skin.text) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = skin.body.copy(alpha = 0.35f)),
            )
        }
    }
    }

    val landscape = androidx.compose.ui.platform.LocalConfiguration.current.let { it.screenWidthDp > it.screenHeightDp }
    Box(
        Modifier.fillMaxSize().background(skin.bg)
            .focusRequester(focus).focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionUp, Key.W -> { turn(Dir.Up); true }
                    Key.DirectionDown, Key.S -> { turn(Dir.Down); true }
                    Key.DirectionLeft, Key.A -> { turn(Dir.Left); true }
                    Key.DirectionRight, Key.D -> { turn(Dir.Right); true }
                    Key.ButtonA, Key.Enter, Key.Spacebar, Key.DirectionCenter, Key.ButtonStart, Key.NumPadEnter -> { startOrPause(); true }
                    Key.ButtonB, Key.Escape, Key.Back -> if (state == "playing") { state = "paused"; true } else { onBack(); true }
                    else -> false
                }
            },
    ) {
        if (landscape) {
            // Sideways: the line you're spelling on the left, the board in the middle, controls on the right.
            Row(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) { Header(); Options() }
                Spacer(Modifier.width(12.dp))
                BoardArea(Modifier.fillMaxHeight())
                Spacer(Modifier.width(12.dp))
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) { Controls() }
            }
        } else {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Header()
                BoardArea(Modifier.weight(1f))
                Controls()
                Options()
            }
        }
    }
}

@Composable
private fun DPad(skin: Skin, onTurn: (Dir) -> Unit) {
    @Composable
    fun Key(dir: Dir, icon: androidx.compose.ui.graphics.vector.ImageVector) {
        Surface(
            onClick = { onTurn(dir) }, shape = RoundedCornerShape(14.dp),
            color = skin.body.copy(alpha = 0.85f), contentColor = skin.bg, modifier = Modifier.size(58.dp),
        ) { Box(contentAlignment = Alignment.Center) { Icon(icon, dir.name, Modifier.size(34.dp)) } }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 8.dp)) {
        Key(Dir.Up, Icons.Rounded.KeyboardArrowUp)
        Row(horizontalArrangement = Arrangement.spacedBy(58.dp)) {
            Key(Dir.Left, Icons.Rounded.KeyboardArrowLeft)
            Key(Dir.Right, Icons.Rounded.KeyboardArrowRight)
        }
        Key(Dir.Down, Icons.Rounded.KeyboardArrowDown)
    }
}

@Composable
private fun Joystick(skin: Skin, onTurn: (Dir) -> Unit) {
    var knob by remember { mutableStateOf(Offset.Zero) }
    Box(
        Modifier
            .padding(top = 8.dp)
            .size(150.dp)
            .clip(CircleShape)
            .background(skin.grid)
            .pointerInput(Unit) {
                val radius = size.width / 2f
                fun steer(p: Offset) {
                    val v = p - Offset(radius, radius)
                    val len = v.getDistance()
                    knob = if (len > radius * 0.6f) v * (radius * 0.6f / len) else v
                    if (len > radius * 0.25f) {
                        val a = Math.toDegrees(atan2(v.y, v.x).toDouble())
                        onTurn(
                            when {
                                a > -45 && a <= 45 -> Dir.Right
                                a > 45 && a <= 135 -> Dir.Down
                                a > -135 && a <= -45 -> Dir.Up
                                else -> Dir.Left
                            },
                        )
                    }
                }
                detectDragGestures(
                    onDragStart = ::steer,
                    onDrag = { change, _ -> change.consume(); steer(change.position) },
                    onDragEnd = { knob = Offset.Zero },
                    onDragCancel = { knob = Offset.Zero },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(150.dp)) {
            drawCircle(skin.body.copy(alpha = 0.25f), radius = size.minDimension / 2 * 0.62f)
            drawCircle(skin.head, radius = size.minDimension * 0.17f, center = center + knob)
        }
    }
}

@Composable
private fun Board(worm: List<Cell>, dir: Dir, food: Cell, letter: Char, skin: Skin, measurer: TextMeasurer) {
    Canvas(Modifier.fillMaxSize()) {
        val c = size.width / COLS
        val pad = c * 0.08f
        val glyph = measurer.measure(
            letter.toString(),
            TextStyle(color = skin.food, fontSize = (c * 0.8f).toSp(), fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace),
        )
        drawText(glyph, topLeft = Offset(food.x * c + (c - glyph.size.width) / 2, food.y * c + (c - glyph.size.height) / 2))
        worm.drop(1).forEachIndexed { i, s ->
            drawRoundRect(skin.body, Offset(s.x * c + pad, s.y * c + pad), Size(c - 2 * pad, c - 2 * pad), CornerRadius(c * 0.35f))
            if (i % 2 == 1) drawRoundRect(
                skin.head.copy(alpha = 0.25f), Offset(s.x * c + c * 0.3f, s.y * c + c * 0.3f), Size(c * 0.4f, c * 0.4f), CornerRadius(c * 0.2f),
            )
        }
        val h = worm.first()
        drawRoundRect(skin.head, Offset(h.x * c + pad * 0.5f, h.y * c + pad * 0.5f), Size(c - pad, c - pad), CornerRadius(c * 0.45f))
        val cx = h.x * c + c / 2 + dir.dx * c * 0.15f
        val cy = h.y * c + c / 2 + dir.dy * c * 0.15f
        val ex = if (dir.dx == 0) c * 0.18f else 0f
        val ey = if (dir.dy == 0) c * 0.18f else 0f
        drawCircle(skin.bg, c * 0.11f, Offset(cx - ex, cy - ey))
        drawCircle(skin.bg, c * 0.11f, Offset(cx + ex, cy + ey))
    }
}

