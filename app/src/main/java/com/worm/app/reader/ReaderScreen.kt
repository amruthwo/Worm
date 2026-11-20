package com.worm.app.reader

import androidx.compose.foundation.verticalScroll
import androidx.lifecycle.repeatOnLifecycle
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FormatSize
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Toc
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.worm.app.data.Book
import com.worm.app.data.Bookmark
import com.worm.app.data.Highlight
import com.worm.app.data.Library
import com.worm.app.ui.components.PageConfetti
import com.worm.app.ui.components.findActivity
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

/** Lets MainActivity hand volume-key presses to the open reader. */
object ReaderKeys {
    @Volatile var handler: ((forward: Boolean) -> Boolean)? = null
    /** Game controllers, D-pads and keyboards (handhelds like the Retroid or Thor). */
    @Volatile var pad: ((keyCode: Int) -> Boolean)? = null
}

private val HighlightColors = listOf(
    "#FFE066" to "Sunny", "#FF9BD1" to "Bubblegum", "#98E8DE" to "Mint", "#C8A2FF" to "Lilac", "underline" to "Underline",
)

@Composable
fun ReaderScreen(book: Book, library: Library, onBack: () -> Unit, say: (String) -> Unit, startAtFurthest: Boolean = false) {
    val context = LocalContext.current
    val prefs = library.prefs
    val scope = rememberCoroutineScope()
    val saved = remember(book.id) { library.progress.value[book.id] }
    val highlights by library.highlights.collectAsStateWithLifecycle()
    val bookmarks by library.bookmarks.collectAsStateWithLifecycle()
    val myHighlights = highlights.filter { it.bookId == book.id }
    val myBookmarks = bookmarks.filter { it.bookId == book.id && it.locator != "audio" }

    // Look = this book's own settings if it has any, else your defaults (seeded from older prefs).
    val defaults = remember {
        ReaderSettings.decode(
            prefs.readerDefaults,
            ReaderSettings(
                ReaderTheme.of(prefs.readerTheme), (16 * prefs.fontScale).toInt().let { px -> FontSizes.minBy { kotlin.math.abs(it - px) } },
                prefs.lineHeight, prefs.margin, prefs.fontFamily, prefs.justify, prefs.hyphenate, prefs.scrolled,
            ),
        )
    }
    val calm = com.worm.app.ui.theme.LocalWormColors.current.calm
    var settings by remember { mutableStateOf(ReaderSettings.decode(prefs.readerFor(book.id), defaults).copy(instant = calm)) }
    var hasOwnLook by remember { mutableStateOf(prefs.readerFor(book.id) != null) }
    fun updateSettings(s: ReaderSettings) {
        settings = s
        prefs.setReaderFor(book.id, s.encode()); hasOwnLook = true
    }

    var footer by remember { mutableStateOf(prefs.readerFooter) }
    var webRef by remember { mutableStateOf<ReaderWebView?>(null) }

    // ------------------------------------------------------------- sync
    val syncScope = rememberCoroutineScope()
    var remoteOffer by remember { mutableStateOf<com.worm.app.sync.RemotePosition?>(null) }
    // Sync sheet: every remote spot, measured precisely in this book (fraction + resolved CFI).
    var syncOpen by remember { mutableStateOf(false) }
    var remotes by remember { mutableStateOf<List<com.worm.app.sync.RemotePosition>?>(null) }
    val measured = remember { androidx.compose.runtime.mutableStateMapOf<String, Pair<Float, String>>() }
    var wantFurthest by remember { mutableStateOf(startAtFurthest) }
    var pulled by remember { mutableStateOf(false) }
    var lastPush by remember { mutableStateOf(0L) }
    var latest by remember { mutableStateOf<ReaderLocation?>(null) }
    fun pushNow(l: ReaderLocation) {
        if (book.hidden || l.cfi.isEmpty()) return
        val ctx = context.applicationContext
        syncScope.launch(kotlinx.coroutines.NonCancellable) {
            com.worm.app.sync.ProgressSync.push(ctx, book.id, l.cfi, l.xpointer, l.fraction, l.page, l.pages)
        }
        // Remember what we sent, so it isn't offered back to us as "newer elsewhere".
        ctx.getSharedPreferences("worm_sync_sent", 0).edit().putString(book.id, l.cfi).putString(book.id + ":xp", l.xpointer).apply()
        lastPush = System.currentTimeMillis()
    }
    DisposableEffect(book.id) {
        onDispose { latest?.let { l -> if (prefs.syncPushWhileReading && com.worm.app.sync.ProgressSync.anyEnabled(context)) pushNow(l) } }
    }

    fun loadRemotes() {
        val ctx = context.applicationContext
        remotes = null; measured.clear()
        syncScope.launch {
            val list = com.worm.app.sync.ProgressSync.pull(ctx, book.id)
            remotes = list
            list.forEachIndexed { i, r -> (r.cfi ?: r.xpointer)?.let { webRef?.js("worm.measure('r$i', ${JSONObject.quote(it)})") } }
        }
    }

    /** Goes to whichever saved spot (here or elsewhere) is furthest into the book. */
    fun jumpToFurthest(): Boolean {
        val list = remotes ?: return false
        if (list.indices.any { "r$it" !in measured } && list.any { it.cfi != null || it.xpointer != null }) return false // still measuring
        val here = latest?.fraction ?: 0f
        val best = list.indices.mapNotNull { i -> measured["r$i"]?.takeIf { it.first >= 0 }?.let { i to it } }.maxByOrNull { it.second.first }
        if (best == null || best.second.first <= here + 0.0005f) {
            say(if (list.isEmpty()) "Nothing saved elsewhere yet" else "You're already at your furthest point (${(here * 100).toInt()}%)")
        } else {
            webRef?.js("worm.goTo(${JSONObject.quote(best.second.second)})")
            say("Jumped to ${list[best.first].source}'s spot (${(best.second.first * 100).toInt()}%)")
        }
        return true
    }

    // "Open at furthest read" from the library: once the remotes are measured, jump.
    LaunchedEffect(wantFurthest, remotes, measured.size) {
        if (wantFurthest && remotes != null && jumpToFurthest()) wantFurthest = false
    }

    /**
     * Looks for a newer spot elsewhere. Automatic checks only offer positions saved after your last
     * page turn here; "Sync now" offers any different position, and pushes yours when nothing's newer.
     */
    fun checkRemote(l: ReaderLocation, manual: Boolean) {
        val ctx = context.applicationContext
        if (book.hidden || !com.worm.app.sync.ProgressSync.anyEnabled(ctx)) { if (manual) say("Sign in to KOSync or Readest in Settings › Sync first"); return }
        syncScope.launch {
            val localAt = library.progress.value[book.id]?.movedAt?.takeIf { manual } ?: saved?.movedAt ?: 0L
            val best = com.worm.app.sync.ProgressSync.pull(ctx, book.id).maxByOrNull { it.updatedAt }
            android.util.Log.d("WormSync", "remote=${best?.source} at ${best?.updatedAt} spine=${best?.let(::spineOf)} · local moved $localAt spine=${spineOf(l.cfi)}")
            val sent = ctx.getSharedPreferences("worm_sync_sent", 0)
            val ours = best != null && ((best.cfi != null && best.cfi == sent.getString(book.id, null)) ||
                (best.xpointer != null && best.xpointer == sent.getString(book.id + ":xp", null)))
            // Close enough not to bother you: same chapter and within 2% of the book (a few pages).
            // Another chapter always counts, however close the percentages look.
            val different = best != null && !ours && best.cfi != l.cfi && best.xpointer != l.xpointer &&
                (spineOf(best) != spineOf(l.cfi) || kotlin.math.abs(best.fraction - l.fraction) > SYNC_CLOSE_ENOUGH)
            when {
                best != null && different && (manual || best.updatedAt > localAt - 5_000) -> remoteOffer = best
                manual -> { pushNow(l); say(if (best == null) "Sent your spot (nothing saved elsewhere yet)" else "You're up to date; sent your spot") }
            }
        }
    }

    // Brightness: right-edge swipe. -1 = follow the system.
    var brightness by remember { mutableFloatStateOf(prefs.readerBrightness) }
    var brightnessShown by remember { mutableStateOf(false) }

    var ready by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var chrome by remember { mutableStateOf(false) }
    var loc by remember { mutableStateOf(ReaderLocation(fraction = saved?.fraction ?: 0f)) }
    var toc by remember { mutableStateOf(emptyList<TocEntry>()) }
    var sectionFractions by remember { mutableStateOf(emptyList<Float>()) }
    var sheet by remember { mutableStateOf<String?>(null) }
    var rsvp by remember { mutableStateOf<RsvpFeed?>(null) }
    var defineWord by remember { mutableStateOf<String?>(null) }
    var noteFor by remember { mutableStateOf<Highlight?>(null) }
    var tappedHighlight by remember { mutableStateOf<Highlight?>(null) }
    var confetti by remember { mutableIntStateOf(0) }
    val searchHits = remember { mutableStateListOf<SearchHit>() }
    var searching by remember { mutableStateOf(false) }
    var searchProgress by remember { mutableFloatStateOf(0f) }
    var searchQuery by remember { mutableStateOf("") }

    val bg = Color(settings.theme.bg)
    val fg = Color(settings.theme.fg)
    val accent = Color(settings.theme.link)

    // Reading time, for stats: only while the reader is on screen, and not while the book sits
    // open and untouched (no page turn for 5 minutes stops the clock until the next one).
    val lastActivity = remember { java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis()) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(book.id) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            var pending = 0L
            try {
                while (true) {
                    kotlinx.coroutines.delay(15_000)
                    if (System.currentTimeMillis() - lastActivity.get() < 5 * 60_000) pending += 15
                    if (pending >= 60) { val add = pending; pending = 0; library.saveProgress(book.id) { it.copy(timeSpentSec = it.timeSpentSec + add) }; library.logActivity(readSec = add) }
                }
            } finally {
                if (pending > 0) { val add = pending; library.saveProgress(book.id) { it.copy(timeSpentSec = it.timeSpentSec + add) }; library.logActivity(readSec = add) }
                library.flushProgress()
            }
        }
    }

    // Immersive while reading; bars come back with the chrome.
    val view = LocalView.current
    LaunchedEffect(chrome) {
        val window = view.context.findActivity()?.window ?: return@LaunchedEffect
        val c = WindowCompat.getInsetsController(window, view)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.isAppearanceLightStatusBars = !settings.theme.dark
        if (chrome) c.show(WindowInsetsCompat.Type.systemBars()) else c.hide(WindowInsetsCompat.Type.systemBars())
    }
    LaunchedEffect(brightness) {
        val window = view.context.findActivity()?.window ?: return@LaunchedEffect
        window.attributes = window.attributes.apply { screenBrightness = brightness }
    }
    DisposableEffect(Unit) {
        val window = view.context.findActivity()?.window
        if (prefs.keepScreenOn) window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window?.let { it.attributes = it.attributes.apply { screenBrightness = -1f } }
            window?.let {
                val c = WindowCompat.getInsetsController(it, view)
                c.show(WindowInsetsCompat.Type.systemBars())
                c.isAppearanceLightStatusBars = false
            }
            ReaderKeys.handler = null
            ReaderKeys.pad = null
        }
    }

    BackHandler {
        when {
            sheet != null -> sheet = null
            chrome -> chrome = false
            else -> onBack()
        }
    }

    fun withSelection(block: (text: String, cfi: String) -> Unit) {
        webRef?.js("worm.selection()") { raw ->
            val json = raw?.let { runCatching { JSONObject(org.json.JSONTokener(it).nextValue() as String) }.getOrNull() } ?: return@js
            block(json.optString("text"), json.optString("cfi"))
        }
    }

    val events = remember {
        object : ReaderEvents {
            override fun onReady(title: String, toc0: List<TocEntry>, sectionFractions0: List<Float>) {
                toc = toc0; sectionFractions = sectionFractions0
            }
            override fun onRelocate(l: ReaderLocation) {
                lastActivity.set(System.currentTimeMillis())
                ready = true
                latest = l
                val ctx = view.context
                val firstShow = !pulled
                if (!pulled) {
                    pulled = true
                    // First position on screen: see if another device got further (or read more recently).
                    if (wantFurthest) loadRemotes() else if (prefs.syncPullOnOpen) checkRemote(l, manual = false)
                } else if (prefs.syncPushWhileReading && System.currentTimeMillis() - lastPush > 30_000 && com.worm.app.sync.ProgressSync.anyEnabled(ctx)) pushNow(l)
                val wasFinished = library.progress.value[book.id]?.finished == true
                loc = l
                library.saveProgress(book.id) {
                    // Only a real page turn counts as "moved" — not the first position shown when opening.
                    val moved = !firstShow && it.locator != l.cfi
                    it.copy(fraction = l.fraction, locator = l.cfi, xpointer = l.xpointer ?: it.xpointer, movedAt = if (moved) System.currentTimeMillis() else it.movedAt)
                }
                if (l.fraction >= 0.995f && !wasFinished) {
                    library.setFinished(book.id, true)
                    confetti++
                    say("You finished “${book.title}”! 🪱🎉")
                }
            }
            override fun onTap(zone: String) {
                if (zone == "menu") chrome = !chrome else if (chrome) chrome = false
            }
            override fun onSelection(active: Boolean) { if (active) chrome = false }
            override fun onAnnotationTap(cfi: String) {
                tappedHighlight = library.highlights.value.firstOrNull { it.bookId == book.id && it.cfi == cfi }
            }
            override fun onSearchResult(hit: SearchHit) { searchHits += hit }
            override fun onSearchProgress(fraction: Float) { searchProgress = fraction }
            override fun onSearchDone() { searching = false }
            override fun onLink(url: String) {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            }
            override fun onError(message: String) { error = message }
            override fun onMeasured(id: String, fraction: Float, cfi: String) { measured[id] = fraction to cfi }
            override fun onRsvp(section: Int, start: Int, words: List<String>, end: Boolean) { rsvp?.receive(section, start, words, end) }
            override fun onPinch(direction: Int) {
                val i = FontSizes.indexOfFirst { it >= settings.fontSize }.coerceAtLeast(0)
                val next = FontSizes[(i + direction).coerceIn(0, FontSizes.lastIndex)]
                if (next != settings.fontSize) {
                    val s = settings.copy(fontSize = next)
                    updateSettings(s); webRef?.applySettings(s)
                }
            }
            override fun onBrightness(delta: Float, end: Boolean) {
                if (end) { prefs.readerBrightness = brightness; brightnessShown = false; return }
                val current = if (brightness < 0) currentSystemBrightness(view.context) else brightness
                brightness = (current + delta * 1.2f).coerceIn(0.01f, 1f)
                brightnessShown = true
            }
        }
    }

    LaunchedEffect(webRef) {
        val w = webRef ?: return@LaunchedEffect
        ReaderKeys.handler = { forward ->
            if (prefs.volumeKeysTurnPages && sheet == null) { w.js(if (forward) "worm.next()" else "worm.prev()"); true } else false
        }
        // While reading (menus hidden): left/right, shoulder buttons and page keys turn pages;
        // A / centre / Enter / Menu opens the menus, which are then navigable with the d-pad.
        ReaderKeys.pad = pad@{ code ->
            if (sheet != null || rsvp != null) return@pad false
            if (chrome) {
                if (code == android.view.KeyEvent.KEYCODE_MENU || code == android.view.KeyEvent.KEYCODE_BUTTON_Y) { chrome = false; return@pad true }
                return@pad false
            }
            when (code) {
                android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_BUTTON_L1 -> { w.js("worm.left()"); true }
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT, android.view.KeyEvent.KEYCODE_BUTTON_R1, android.view.KeyEvent.KEYCODE_SPACE -> { w.js("worm.right()"); true }
                android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.KEYCODE_PAGE_UP, android.view.KeyEvent.KEYCODE_BUTTON_L2 -> { w.js("worm.prev()"); true }
                android.view.KeyEvent.KEYCODE_DPAD_DOWN, android.view.KeyEvent.KEYCODE_PAGE_DOWN, android.view.KeyEvent.KEYCODE_BUTTON_R2 -> { w.js("worm.next()"); true }
                android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER, android.view.KeyEvent.KEYCODE_BUTTON_A,
                android.view.KeyEvent.KEYCODE_MENU, android.view.KeyEvent.KEYCODE_BUTTON_Y -> { chrome = true; true }
                else -> false
            }
        }
        w.onSelectionAction = { action ->
            withSelection { text, cfi ->
                if (text.isBlank()) return@withSelection
                if (cfi.isBlank() && (action == "highlight" || action == "note")) { say("Couldn't mark that spot. Try selecting again"); return@withSelection }
                when (action) {
                    "highlight" -> {
                        val h = Highlight(UUID.randomUUID().toString(), book.id, cfi, text, HighlightColors[0].first, null, System.currentTimeMillis())
                        library.upsertHighlight(h)
                        w.js("worm.addHighlight(${w.quote(cfi)}, ${w.quote(h.color)}, null)")
                    }
                    "note" -> {
                        val h = Highlight(UUID.randomUUID().toString(), book.id, cfi, text, HighlightColors[1].first, "", System.currentTimeMillis())
                        library.upsertHighlight(h)
                        w.js("worm.addHighlight(${w.quote(cfi)}, ${w.quote(h.color)}, null)")
                        noteFor = h
                    }
                    "define" -> { defineWord = text; w.js("worm.clearSelection()") }
                    "search" -> {
                        searchQuery = text; searchHits.clear(); searching = true; sheet = "search"
                        w.js("worm.search(${w.quote(text)})")
                    }
                }
            }
        }
    }

    Box(Modifier.fillMaxSize().background(bg)) {
        AndroidView(
            factory = { ctx ->
                ReaderWebView(ctx, book, events).also { w ->
                    webRef = w
                    w.open(settings, saved?.locator, library.highlights.value.filter { it.bookId == book.id }.map { it.cfi to it.color }, saved?.fraction ?: 0f, saved?.xpointer.takeIf { saved?.locator == null })
                }
            },
            // Keep text out from under the camera hole punch / notch, and above the footer.
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.displayCutout)
                .padding(bottom = footerSpace(footer)),
            onRelease = { it.destroy() },
        )

        // Footer while reading: whatever you've chosen in Page look › Footer.
        AnimatedVisibility(!chrome && ready && footer.isNotEmpty(), enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            ReaderFooter(loc, sectionFractions, footer, fg, Modifier.windowInsetsPadding(WindowInsets.displayCutout))
        }

        if (!ready && error == null) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                LoadingIndicator(color = accent, modifier = Modifier.size(64.dp))
                Spacer(Modifier.height(12.dp))
                Text("Wriggling into “${book.title}”…", color = fg.copy(alpha = 0.7f))
            }
        }
        error?.let { msg ->
            Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("This book wouldn't open", style = MaterialTheme.typography.headlineSmall, color = fg)
                Text(msg, color = fg.copy(alpha = 0.7f))
                TextButton(onClick = onBack) { Text("Back to the library") }
            }
        }

        // ------------------------------------------------------------ chrome
        AnimatedVisibility(
            chrome, enter = slideInVertically { -it } + fadeIn(), exit = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Surface(color = bg.copy(alpha = 0.97f), contentColor = fg, shadowElevation = 4.dp, shape = RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp)) {
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                    Column(Modifier.weight(1f)) {
                        Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(loc.chapter.ifEmpty { book.author }, style = MaterialTheme.typography.bodySmall, color = fg.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (com.worm.app.sync.ProgressSync.anyEnabled(context)) IconButton(onClick = { syncOpen = true; loadRemotes() }) { Icon(Icons.Rounded.Sync, "Sync") }
                    IconButton(onClick = { sheet = "search" }) { Icon(Icons.Rounded.Search, "Search in book") }
                    val marked = myBookmarks.firstOrNull { it.locator == loc.cfi }
                    IconButton(onClick = {
                        if (marked != null) { library.removeBookmark(marked.id); say("Bookmark removed") }
                        else if (loc.cfi.isNotEmpty()) {
                            library.addBookmark(Bookmark(UUID.randomUUID().toString(), book.id, loc.cfi, loc.chapter.ifEmpty { "${(loc.fraction * 100).toInt()}%" }, System.currentTimeMillis()))
                            say("Bookmarked")
                        }
                    }) { Icon(if (marked != null) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder, "Bookmark this page", tint = if (marked != null) accent else fg) }
                }
            }
        }

        AnimatedVisibility(
            chrome, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Surface(color = bg.copy(alpha = 0.97f), contentColor = fg, shadowElevation = 4.dp, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    var scrub by remember { mutableStateOf<Float?>(null) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${((scrub ?: loc.fraction) * 100).toInt()}%", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(44.dp))
                        Slider(
                            value = scrub ?: loc.fraction,
                            onValueChange = { scrub = it },
                            onValueChangeFinished = { scrub?.let { f -> webRef?.js("worm.goToFraction($f)") }; scrub = null },
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            if (loc.pageLabel.isNotEmpty()) "p. ${loc.pageLabel}" else if (loc.pages > 0) "${loc.page + 1}/${loc.pages}" else "",
                            style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        ChromeButton(Icons.Rounded.Toc, "Contents") { sheet = "contents" }
                        ChromeButton(Icons.Rounded.FormatSize, "Look") { sheet = "look" }
                        ChromeButton(Icons.Rounded.Search, "Search") { sheet = "search" }
                        ChromeButton(Icons.AutoMirrored.Rounded.MenuBook, "Notes") { sheet = "notes" }
                        ChromeButton(Icons.Rounded.Speed, "Speed read") {
                            chrome = false
                            rsvp = RsvpFeed()
                            webRef?.js("worm.rsvpWords(0)")
                        }
                    }
                }
            }
        }

        // Brightness pill while sliding along the right edge.
        AnimatedVisibility(brightnessShown, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.CenterEnd).padding(end = 24.dp)) {
            Surface(shape = RoundedCornerShape(50), color = fg.copy(alpha = 0.85f), contentColor = bg) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.LightMode, null, Modifier.size(20.dp))
                    Text("${(brightness.coerceAtLeast(0f) * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        if (!com.worm.app.ui.theme.LocalWormColors.current.calm) PageConfetti(confetti)

        rsvp?.let { feed ->
            androidx.activity.compose.BackHandler { webRef?.js("worm.rsvpSeek(${feed.section}, ${feed.index})"); rsvp = null }
            SpeedReader(
                feed, bg, fg, accent,
                wpm = prefs.rsvpWpm, textSize = prefs.rsvpSize,
                onWpm = { prefs.rsvpWpm = it }, onTextSize = { prefs.rsvpSize = it },
                onNeedSection = { ahead -> webRef?.js("worm.rsvpWords($ahead)") },
                onExit = { section, word ->
                    if (section >= 0) webRef?.js("worm.rsvpSeek($section, $word)")
                    rsvp = null
                },
            )
        }
    }

    // -------------------------------------------------------------- sheets
    when (sheet) {
        "look" -> LookSheet(
            settings,
            hasOwnLook = hasOwnLook,
            footer = footer,
            onFooter = { footer = it; prefs.readerFooter = it },
            onChange = { updateSettings(it); webRef?.applySettings(it) },
            onMakeDefault = { prefs.readerDefaults = settings.encode(); say("Saved as the default look for all books") },
            onUseDefaults = {
                prefs.setReaderFor(book.id, null); hasOwnLook = false
                val d = ReaderSettings.decode(prefs.readerDefaults, defaults)
                settings = d; webRef?.applySettings(d)
            },
            onDismiss = { sheet = null },
        )
        "contents" -> ContentsSheet(toc, loc.chapterHref, onPick = { webRef?.js("worm.goTo(${JSONObject.quote(it.href)})"); sheet = null; chrome = false }, onDismiss = { sheet = null })
        "notes" -> NotesSheet(
            myBookmarks, myHighlights,
            onGo = { cfi -> webRef?.js("worm.goTo(${JSONObject.quote(cfi)})"); sheet = null; chrome = false },
            onDeleteBookmark = { library.removeBookmark(it.id) },
            onDeleteHighlight = { library.removeHighlight(it.id); webRef?.js("worm.removeHighlight(${JSONObject.quote(it.cfi)})") },
            onDismiss = { sheet = null },
        )
        "search" -> SearchSheet(
            query = searchQuery,
            hits = searchHits,
            searching = searching,
            progress = searchProgress,
            accent = accent,
            onSearch = { q ->
                searchQuery = q; searchHits.clear(); searching = true; searchProgress = 0f
                webRef?.js("worm.search(${JSONObject.quote(q)})")
            },
            onPick = { webRef?.js("worm.goTo(${JSONObject.quote(it.cfi)})"); sheet = null; chrome = false },
            onDismiss = { sheet = null; webRef?.js("worm.clearSearch()") },
        )
    }

    if (syncOpen) {
        ModalBottomSheet(onDismissRequest = { syncOpen = false }) {
            Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 16.dp).navigationBarsPadding()) {
                Text("Sync", style = MaterialTheme.typography.titleLarge)
                Text("Where each place has you in this book.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                SyncRow("This device", latest?.fraction ?: loc.fraction, null, current = true) {}
                val list = remotes
                if (list == null) LinearWavyProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 12.dp))
                else if (list.isEmpty()) Text("Nothing saved in KOSync or Readest for this book yet.", modifier = Modifier.padding(vertical = 8.dp))
                list?.forEachIndexed { i, r ->
                    val m = measured["r$i"]
                    SyncRow(
                        listOfNotNull(r.source, r.device).joinToString(" · "),
                        m?.first?.takeIf { it >= 0 } ?: r.fraction,
                        android.text.format.DateUtils.getRelativeTimeSpanString(r.updatedAt).toString(),
                        current = false,
                        measuring = m == null,
                    ) {
                        syncOpen = false
                        val target = m?.second?.ifEmpty { null } ?: r.cfi
                        if (target != null) webRef?.js("worm.goTo(${JSONObject.quote(target)})")
                        else r.xpointer?.let { webRef?.js("worm.goToXPointer(${JSONObject.quote(it)}, ${r.fraction})") }
                    }
                }
                Spacer(Modifier.height(12.dp))
                androidx.compose.material3.Button(onClick = { if (jumpToFurthest()) syncOpen = false }, enabled = remotes != null, modifier = Modifier.fillMaxWidth()) {
                    Text("Jump to furthest read")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    androidx.compose.material3.OutlinedButton(onClick = { syncOpen = false; latest?.let { checkRemote(it, manual = true) } }, modifier = Modifier.weight(1f)) { Text("Pull newest") }
                    androidx.compose.material3.OutlinedButton(onClick = { latest?.let(::pushNow); say("Sent your spot"); syncOpen = false }, modifier = Modifier.weight(1f)) { Text("Push my spot") }
                }
            }
        }
    }

    remoteOffer?.let { r ->
        AlertDialog(
            onDismissRequest = { remoteOffer = null },
            icon = { Icon(Icons.Rounded.Sync, null) },
            title = { Text("Pick up from ${r.device ?: r.source}?") },
            text = {
                Text(
                    "${r.source} has you at ${(r.fraction * 100).toInt()}%" +
                        " (${android.text.format.DateUtils.getRelativeTimeSpanString(r.updatedAt)}). You're at ${(loc.fraction * 100).toInt()}% here.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    remoteOffer = null
                    when {
                        r.cfi != null -> webRef?.js("worm.goTo(${JSONObject.quote(r.cfi)})")
                        r.xpointer != null -> webRef?.js("worm.goToXPointer(${JSONObject.quote(r.xpointer)}, ${r.fraction})")
                        else -> webRef?.js("worm.goToFraction(${r.fraction})")
                    }
                }) { Text("Jump there") }
            },
            dismissButton = { TextButton(onClick = { remoteOffer = null; latest?.let(::pushNow) }) { Text("Stay here") } },
        )
    }

    defineWord?.let { word -> DefineSheet(word, onDismiss = { defineWord = null }) }

    noteFor?.let { h ->
        NoteDialog(h.note.orEmpty(), onDismiss = { noteFor = null }, onSave = { text -> library.upsertHighlight(h.copy(note = text)); noteFor = null })
    }

    tappedHighlight?.let { h ->
        HighlightSheet(
            h,
            onColor = { color ->
                val updated = h.copy(color = color)
                library.upsertHighlight(updated)
                webRef?.js("worm.addHighlight(${JSONObject.quote(h.cfi)}, ${JSONObject.quote(color)}, null)")
                tappedHighlight = updated
            },
            onNote = { tappedHighlight = null; noteFor = h },
            onDefine = { tappedHighlight = null; defineWord = h.text },
            onShare = {
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, "“${h.text}”\n— ${book.title}, ${book.author}"), "Share quote"))
            },
            onDelete = {
                library.removeHighlight(h.id)
                webRef?.js("worm.removeHighlight(${JSONObject.quote(h.cfi)})")
                tappedHighlight = null
            },
            onDismiss = { tappedHighlight = null },
        )
    }
}

@Composable
private fun ChromeButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null)
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun LookSheet(
    s: ReaderSettings,
    hasOwnLook: Boolean,
    footer: Set<String>,
    onFooter: (Set<String>) -> Unit,
    onChange: (ReaderSettings) -> Unit,
    onMakeDefault: () -> Unit,
    onUseDefaults: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        // Scrolls: on short screens the footer options sit below the fold.
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
            Text("Page look", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ReaderTheme.entries.forEach { t ->
                    val selected = t == s.theme
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onChange(s.copy(theme = t)) }) {
                        Box(
                            Modifier.size(52.dp).clip(CircleShape).background(Color(t.bg))
                                .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { Text("Aa", color = Color(t.fg), fontWeight = FontWeight.Bold) }
                        Text(t.label, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            // Text size: standard sizes, A− / A+ like a word processor. (Pinch on the page works too.)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Text size", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                val i = FontSizes.indexOfFirst { it >= s.fontSize }.coerceAtLeast(0)
                androidx.compose.material3.FilledTonalIconButton(onClick = { onChange(s.copy(fontSize = FontSizes[(i - 1).coerceAtLeast(0)])) }) { Text("A−", fontWeight = FontWeight.Bold) }
                Text("${s.fontSize}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 12.dp))
                androidx.compose.material3.FilledTonalIconButton(onClick = { onChange(s.copy(fontSize = FontSizes[(i + 1).coerceAtMost(FontSizes.lastIndex)])) }) { Text("A+", fontWeight = FontWeight.Bold) }
            }
            LabeledSlider("Line spacing", "%.1f".format(s.lineHeight), s.lineHeight, 1.0f..2.4f) { onChange(s.copy(lineHeight = (it * 10).toInt() / 10f)) }
            LabeledSlider("Margins", "${s.margin}px", s.margin.toFloat(), 0f..80f) { onChange(s.copy(margin = it.toInt())) }
            Text("Font", style = MaterialTheme.typography.labelLarge)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReaderFont.entries.forEach { f ->
                    FilterChip(
                        selected = s.fontFamily == f.key, onClick = { onChange(s.copy(fontFamily = f.key)) },
                        label = { Text(f.label, fontFamily = previewFamily(f)) },
                    )
                }
            }
            SwitchRow("Scroll instead of turning pages", s.scrolled) { onChange(s.copy(scrolled = it)) }
            if (!s.scrolled) SwitchRow("Two pages side by side on wide screens", s.twoPage) { onChange(s.copy(twoPage = it)) }
            SwitchRow("Justify text", s.justify) { onChange(s.copy(justify = it)) }
            SwitchRow("Hyphenate", s.hyphenate) { onChange(s.copy(hyphenate = it)) }
            Text("Footer", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FooterItems.forEach { (key, label) ->
                    FilterChip(selected = key in footer, onClick = { onFooter(if (key in footer) footer - key else footer + key) }, label = { Text(label) })
                }
            }
            Text(
                if (hasOwnLook) "These settings are saved for this book." else "Using your default look.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
            )
            Row {
                TextButton(onClick = onMakeDefault) { Text("Use for all books") }
                if (hasOwnLook) TextButton(onClick = onUseDefaults) { Text("Reset to default") }
            }
        }
    }
}

@Composable
private fun LabeledSlider(label: String, value: String, v: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
    Slider(value = v, onValueChange = onChange, valueRange = range)
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked, onChange)
    }
}

@Composable
private fun ContentsSheet(toc: List<TocEntry>, currentHref: String, onPick: (TocEntry) -> Unit, onDismiss: () -> Unit) {
    val current = toc.indexOfFirst { it.href == currentHref }
    val state = rememberLazyListState((current - 3).coerceAtLeast(0))
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text("Contents", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
        if (toc.isEmpty()) Text("This book has no table of contents.", modifier = Modifier.padding(24.dp))
        LazyColumn(state = state, modifier = Modifier.navigationBarsPadding()) {
            itemsIndexed(toc) { i, e ->
                val now = i == current
                Text(
                    e.label.ifEmpty { "Untitled" },
                    style = if (e.depth == 0) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                    fontWeight = if (now) FontWeight.Bold else null,
                    color = if (now) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth().clickable { onPick(e) }.padding(start = 24.dp + (e.depth * 18).dp, end = 24.dp, top = 12.dp, bottom = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun NotesSheet(
    bookmarks: List<Bookmark>,
    highlights: List<Highlight>,
    onGo: (String) -> Unit,
    onDeleteBookmark: (Bookmark) -> Unit,
    onDeleteHighlight: (Highlight) -> Unit,
    onDismiss: () -> Unit,
) {
    var tab by remember { mutableIntStateOf(if (bookmarks.isEmpty() && highlights.isNotEmpty()) 1 else 0) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        PrimaryTabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
            Tab(tab == 0, { tab = 0 }, text = { Text("Bookmarks (${bookmarks.size})") })
            Tab(tab == 1, { tab = 1 }, text = { Text("Highlights (${highlights.size})") })
        }
        LazyColumn(Modifier.navigationBarsPadding()) {
            if (tab == 0) {
                if (bookmarks.isEmpty()) item { Text("Tap the ribbon at the top to bookmark a page.", modifier = Modifier.padding(24.dp)) }
                items(bookmarks.sortedByDescending { it.createdAt }, key = { it.id }) { b ->
                    ListItem(
                        headlineContent = { Text(b.label) },
                        leadingContent = { Icon(Icons.Rounded.Bookmark, null) },
                        trailingContent = { IconButton(onClick = { onDeleteBookmark(b) }) { Icon(Icons.Rounded.Delete, "Delete") } },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable { onGo(b.locator) },
                    )
                }
            } else {
                if (highlights.isEmpty()) item { Text("Select some text and tap Highlight.", modifier = Modifier.padding(24.dp)) }
                items(highlights.sortedByDescending { it.createdAt }, key = { it.id }) { h ->
                    ListItem(
                        headlineContent = { Text("“${h.text}”", maxLines = 4, overflow = TextOverflow.Ellipsis) },
                        supportingContent = h.note?.takeIf { it.isNotBlank() }?.let { n -> { Text(n) } },
                        leadingContent = { Box(Modifier.size(14.dp).clip(CircleShape).background(colorOf(h.color))) },
                        trailingContent = { IconButton(onClick = { onDeleteHighlight(h) }) { Icon(Icons.Rounded.Delete, "Delete") } },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable { onGo(h.cfi) },
                    )
                }
            }
        }
    }
}

private fun colorOf(c: String): Color = runCatching { Color(android.graphics.Color.parseColor(c)) }.getOrDefault(Color.Gray)

@Composable
private fun SearchSheet(
    query: String,
    hits: List<SearchHit>,
    searching: Boolean,
    progress: Float,
    accent: Color,
    onSearch: (String) -> Unit,
    onPick: (SearchHit) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(query) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                placeholder = { Text("Search this book") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch(text) }),
                shape = RoundedCornerShape(50),
                modifier = Modifier.fillMaxWidth(),
            )
            if (searching) LinearWavyProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
            else if (query.isNotEmpty()) Text("${hits.size} matches", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(8.dp))
        }
        LazyColumn(Modifier.navigationBarsPadding()) {
            items(hits) { h ->
                Column(Modifier.fillMaxWidth().clickable { onPick(h) }.padding(horizontal = 24.dp, vertical = 10.dp)) {
                    if (h.label.isNotEmpty()) Text(h.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(
                        buildAnnotatedString {
                            append("…" + h.pre.takeLast(60))
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold, background = accent.copy(alpha = 0.25f))) { append(h.match) }
                            append(h.post.take(60) + "…")
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                HorizontalDivider(Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            }
        }
    }
}

/**
 * Define: your offline dictionaries and Wiktionary in one tab, Wikipedia in another.
 * Names and multi-word selections open on Wikipedia, since that's usually what you want.
 */
@Composable
private fun DefineSheet(word: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clean = remember(word) { Dictionary.clean(word) }
    val looksLikeName = remember(clean) { ' ' in clean || (clean.firstOrNull()?.isUpperCase() == true && clean.drop(1).any { it.isLowerCase() }) }
    var tab by remember { mutableIntStateOf(if (' ' in clean) 1 else 0) }
    var defs by remember { mutableStateOf<Result<List<Lookup>>?>(null) }
    var wiki by remember { mutableStateOf<Result<Lookup?>?>(null) }
    LaunchedEffect(clean) {
        launch { defs = Dictionary.define(context, clean) }
        launch { wiki = Dictionary.wikipedia(clean) }
    }
    // A name with no dictionary entry: jump to Wikipedia once both answers are in.
    LaunchedEffect(defs, wiki) {
        if (looksLikeName && tab == 0 && defs?.getOrNull()?.isEmpty() == true && wiki?.getOrNull() != null) tab = 1
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 16.dp).navigationBarsPadding()) {
            Text(clean, style = MaterialTheme.typography.headlineMedium)
            androidx.compose.material3.SecondaryTabRow(selectedTabIndex = tab, modifier = Modifier.padding(vertical = 8.dp)) {
                androidx.compose.material3.Tab(tab == 0, { tab = 0 }, text = { Text("Dictionary") })
                androidx.compose.material3.Tab(tab == 1, { tab = 1 }, text = { Text("Wikipedia") })
            }
            if (tab == 0) when (val r = defs) {
                null -> LoadingIndicator(Modifier.size(40.dp))
                else -> r.onSuccess { list ->
                    if (list.isEmpty()) Text("No definition found. Try Wikipedia, or a dictionary app.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    list.forEach { l ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
                            Text(l.source, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
                            if (!l.headword.equals(clean, true)) Text("  ·  ${l.headword}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        l.definitions.take(6).forEach { d ->
                            if (d.partOfSpeech.isNotBlank()) Text(d.partOfSpeech, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
                            d.senses.forEachIndexed { i, s ->
                                Text(if (d.senses.size > 1 && d.partOfSpeech.isNotBlank()) "${i + 1}. $s" else s, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp))
                            }
                        }
                    }
                }.onFailure { Text("Offline: add a dictionary in Settings › Reading to look words up without a connection.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else when (val r = wiki) {
                null -> LoadingIndicator(Modifier.size(40.dp))
                else -> r.onSuccess { l ->
                    if (l == null) Text("Wikipedia has no page called “$clean”.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else {
                        Text(l.headword, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        Text(l.summary.orEmpty(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                    }
                }.onFailure { Text("Couldn't reach Wikipedia.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Spacer(Modifier.height(12.dp))
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { Dictionary.openInApp(context, clean) }) {
                    Icon(Icons.AutoMirrored.Rounded.MenuBook, null); Spacer(Modifier.width(6.dp)); Text("Dictionary app")
                }
                val link = if (tab == 1) wiki?.getOrNull()?.url ?: "https://en.wikipedia.org/wiki/Special:Search?search=${Uri.encode(clean)}"
                    else defs?.getOrNull()?.firstOrNull { it.url != null }?.url ?: "https://en.wiktionary.org/wiki/${Uri.encode(clean)}"
                TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) } }) {
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, null); Spacer(Modifier.width(6.dp)); Text(if (tab == 1) "Wikipedia" else "Wiktionary")
                }
            }
        }
    }
}

@Composable
private fun NoteDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Note") },
        text = { OutlinedTextField(text, { text = it }, minLines = 3, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { onSave(text.trim()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun HighlightSheet(
    h: Highlight,
    onColor: (String) -> Unit,
    onNote: () -> Unit,
    onDefine: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 16.dp).navigationBarsPadding()) {
            Text("“${h.text}”", style = MaterialTheme.typography.bodyLarge, maxLines = 6, overflow = TextOverflow.Ellipsis)
            h.note?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp)) }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HighlightColors.forEach { (c, _) ->
                    val sel = c == h.color
                    Box(
                        Modifier.size(36.dp).clip(CircleShape)
                            .background(if (c == "underline") MaterialTheme.colorScheme.surfaceContainerHighest else colorOf(c))
                            .border(if (sel) 3.dp else 0.dp, MaterialTheme.colorScheme.primary, CircleShape)
                            .clickable { onColor(c) },
                        contentAlignment = Alignment.Center,
                    ) { if (c == "underline") Text("U", fontWeight = FontWeight.Bold) }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row {
                TextButton(onClick = onNote) { Text(if (h.note.isNullOrBlank()) "Add note" else "Edit note") }
                TextButton(onClick = onDefine) { Text("Define") }
                TextButton(onClick = onShare) { Text("Share") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDelete) { Text("Remove") }
            }
        }
    }
}


/** Font previews in the chip row use the bundled files directly. */
@Composable
private fun previewFamily(f: ReaderFont): androidx.compose.ui.text.font.FontFamily? {
    val context = LocalContext.current
    return remember(f) {
        val file = when (f) {
            ReaderFont.Literata -> "fonts/Literata.ttf"
            ReaderFont.Lora -> "fonts/Lora.ttf"
            ReaderFont.Garamond -> "fonts/EBGaramond.ttf"
            ReaderFont.Atkinson -> "fonts/AtkinsonHyperlegible-Regular.ttf"
            ReaderFont.Serif -> return@remember androidx.compose.ui.text.font.FontFamily.Serif
            ReaderFont.Sans -> return@remember androidx.compose.ui.text.font.FontFamily.SansSerif
            ReaderFont.Mono -> return@remember androidx.compose.ui.text.font.FontFamily.Monospace
            ReaderFont.Publisher -> return@remember null
        }
        androidx.compose.ui.text.font.FontFamily(androidx.compose.ui.text.font.Font(file, context.assets))
    }
}

private fun currentSystemBrightness(context: android.content.Context): Float =
    runCatching { android.provider.Settings.System.getInt(context.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS) / 255f }
        .getOrDefault(0.5f)


/** Which spine item (chapter file) a CFI or KOReader XPointer points into, 0-based. */
/** Remote spots closer than this (fraction of the book, same chapter) don't trigger the "jump?" prompt. */
internal const val SYNC_CLOSE_ENOUGH = 0.02f

internal fun spineOf(cfi: String?): Int? = cfi?.let { Regex("""epubcfi\(/6/(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull()?.let { n -> n / 2 - 1 } }
internal fun spineOf(r: com.worm.app.sync.RemotePosition): Int? =
    spineOf(r.cfi) ?: r.xpointer?.let { Regex("""DocFragment(?:\[(\d+)\])?""").find(it)?.groupValues?.get(1)?.toIntOrNull()?.minus(1) ?: 0 }


@Composable
private fun SyncRow(label: String, fraction: Float, time: String?, current: Boolean, measuring: Boolean = false, onGo: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Text(
                listOfNotNull(if (measuring) "measuring…" else "${"%.1f".format(fraction * 100)}%", time).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            androidx.compose.material3.LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp), drawStopIndicator = {})
        }
        if (!current) TextButton(onClick = onGo) { Text("Go") } else Spacer(Modifier.width(56.dp))
    }
}
