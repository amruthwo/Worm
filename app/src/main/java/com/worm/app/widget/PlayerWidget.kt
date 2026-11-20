package com.worm.app.widget

import android.content.ComponentName
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.worm.app.R
import com.worm.app.data.Library
import com.worm.app.player.PlaybackService
import com.worm.app.player.PlayerHub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Now playing: from a 1×2 sliver (cover + play) to a 4×4 with chapter jumps and time left.
 * Works even when Worm isn't open: play resumes the last audiobook.
 */
class PlayerWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val prefs = Library.get(context).prefs
        provideContent {
            // Re-read everything whenever playback changes (see Widgets.playerTick).
            val tick by Widgets.playerTick.collectAsState()
            val item = remember(tick) { Widgets.nowPlaying(context) }
            val bmp = remember(item?.book?.id, item?.book?.coverPath) { item?.let { Widgets.cover(it.book, 320) } }
            val playing = remember(tick) { PlaybackService.playing }
            val chapter = remember(tick) { PlayerHub.chapterTitle.value }
            WormGlanceTheme { PlayerContent(item, bmp, playing, chapter, prefs.skipBackSec, prefs.skipForwardSec, prefs.widgetCorners) }
        }
    }
}

class PlayerWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = PlayerWidget() }

/**
 * Lays itself out from the widget's real size, so cover and controls stay visible at any size:
 * - tiny (1×1): cover with a play button on it
 * - one row tall: cover, title if there's room, as many buttons as fit
 * - narrow column: cover over the play button (skip buttons too when tall)
 * - wide: cover on the left; title, progress and buttons on the right
 * - square or tall: cover on top; title, progress and buttons underneath
 * Buttons appear by priority: play, then skip back/forward, then chapter jumps.
 */
@Composable
private fun PlayerContent(item: Widgets.Item?, bmp: android.graphics.Bitmap?, playing: Boolean, chapter: String?, back: Int, fwd: Int, corners: Int) {
    val size = LocalSize.current
    val context = LocalContext.current
    val c = GlanceTheme.colors
    val w = size.width; val h = size.height
    val pad = if (minOf(w, h) < 90.dp) 6.dp else 10.dp
    val root = GlanceModifier.fillMaxSize().background(c.surface).cornerRadius(corners.dp).padding(pad)
    // The cover opens the book itself (which resumes it), not an empty player screen.
    val openBook = actionStartActivity(item?.let { Widgets.openBookIntent(context, it.book) } ?: Widgets.openScreenIntent(context, "player"))

    @Composable fun CoverImg(maxW: androidx.compose.ui.unit.Dp, maxH: androidx.compose.ui.unit.Dp) {
        val r = (corners / 2).coerceAtLeast(6).dp
        val fit = Widgets.fit(Widgets.aspect(bmp, true), maxW.coerceAtLeast(16.dp), maxH.coerceAtLeast(16.dp))
        Box(GlanceModifier.width(maxW.coerceAtLeast(16.dp)).height(maxH.coerceAtLeast(16.dp)), contentAlignment = Alignment.Center) {
            if (bmp != null) Image(ImageProvider(bmp), item?.book?.title, GlanceModifier.size(fit.width, fit.height).cornerRadius(r).clickable(openBook), contentScale = ContentScale.Crop)
            else Box(GlanceModifier.size(fit.width, fit.height).cornerRadius(r).background(c.primaryContainer).clickable(openBook), contentAlignment = Alignment.Center) {
                Image(ImageProvider(R.drawable.worm_logo), null, GlanceModifier.size((fit.width * 0.6f).coerceAtMost(40.dp)))
            }
        }
    }

    @Composable fun Btn(icon: Int, label: String, action: androidx.glance.action.Action, s: androidx.compose.ui.unit.Dp, primary: Boolean = false) {
        Box(
            GlanceModifier.size(s).cornerRadius(s / 2).background(if (primary) c.primary else c.secondaryContainer).clickable(action),
            contentAlignment = Alignment.Center,
        ) {
            Image(ImageProvider(icon), label, GlanceModifier.size(s * 0.55f), colorFilter = ColorFilter.tint(if (primary) c.onPrimary else c.onSecondaryContainer))
        }
    }

    // Play/pause goes to the player as a media-button press, like headphones do, so it can start
    // playback in the background even when Worm is closed.
    val playPause = mediaKey(context, android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
    val playIcon = if (playing) R.drawable.ic_w_pause else R.drawable.ic_w_play
    val playLabel = if (playing) "Pause" else "Play"

    /** As many buttons as fit in [avail], most important first, play in the middle. */
    @Composable fun Controls(avail: androidx.compose.ui.unit.Dp, s: androidx.compose.ui.unit.Dp) {
        val gap = 6.dp
        val n = ((avail + gap) / (s + gap)).toInt()
        Row(verticalAlignment = Alignment.CenterVertically) {
            @Composable fun Gap() = Spacer(GlanceModifier.width(gap))
            if (n >= 5) { Btn(R.drawable.ic_w_prev, "Previous chapter", actionRunCallback<PrevChapterAction>(), s); Gap() }
            if (n >= 3) { Btn(skipIcon(back, false), "Back ${back}s", mediaKey(context, android.view.KeyEvent.KEYCODE_MEDIA_REWIND), s); Gap() }
            Btn(playIcon, playLabel, playPause, s, primary = true)
            if (n >= 3) { Gap(); Btn(skipIcon(fwd, true), "Forward ${fwd}s", mediaKey(context, android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD), s) }
            if (n >= 5) { Gap(); Btn(R.drawable.ic_w_next, "Next chapter", actionRunCallback<NextChapterAction>(), s) }
        }
    }
    fun controlsWidth(avail: androidx.compose.ui.unit.Dp, s: androidx.compose.ui.unit.Dp): androidx.compose.ui.unit.Dp {
        val n = ((avail + 6.dp) / (s + 6.dp)).toInt()
        val k = when { n >= 5 -> 5; n >= 3 -> 3; else -> 1 }
        return s * k + 6.dp * (k - 1)
    }

    @Composable fun Titles(lines: Int, center: Boolean = false) {
        val align = if (center) androidx.glance.text.TextAlign.Center else androidx.glance.text.TextAlign.Start
        Text(item?.book?.title ?: "Nothing playing", maxLines = lines, style = TextStyle(color = c.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold, textAlign = align), modifier = GlanceModifier.fillMaxWidth())
        Text(chapter ?: item?.book?.author ?: "Press play to resume", maxLines = 1, style = TextStyle(color = c.onSurfaceVariant, fontSize = 11.sp, textAlign = align), modifier = GlanceModifier.fillMaxWidth())
    }

    @Composable fun Bar() {
        LinearProgressIndicator(
            progress = item?.progress?.fraction ?: 0f,
            modifier = GlanceModifier.fillMaxWidth().height(4.dp).cornerRadius(2.dp),
            color = c.primary, backgroundColor = c.surfaceVariant,
        )
    }

    val iw = w - pad * 2; val ih = h - pad * 2
    when {
        // 1×1: the cover, with play on it.
        w < 110.dp && h < 120.dp -> Box(root, contentAlignment = Alignment.Center) {
            CoverImg(iw, ih)
            Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) { Btn(playIcon, playLabel, playPause, (minOf(iw, ih) * 0.45f).coerceIn(24.dp, 40.dp), primary = true) }
        }
        // One row tall: cover, buttons, and the title when there's room. Buttons beat the title.
        h < 120.dp -> {
            val s = ih.coerceIn(28.dp, 44.dp)
            val coverW = minOf(ih, iw - s - 16.dp).coerceAtLeast(24.dp)
            val rest = iw - coverW - 16.dp
            val three = s * 3 + 12.dp
            val (ctrl, showTitle) = when {
                rest - three >= 80.dp -> three to true
                rest >= three -> three to false
                rest - s >= 80.dp -> s to true
                else -> s to false
            }
            Row(root, verticalAlignment = Alignment.CenterVertically) {
                CoverImg(coverW, ih)
                Spacer(GlanceModifier.width(8.dp))
                if (showTitle) Column(GlanceModifier.defaultWeight()) { Titles(if (ih >= 50.dp) 2 else 1) } else Spacer(GlanceModifier.defaultWeight())
                Spacer(GlanceModifier.width(8.dp))
                Controls(ctrl, s)
            }
        }
        // Narrow column: cover over the buttons.
        w < 130.dp -> {
            val s = (iw * 0.5f).coerceIn(32.dp, 44.dp)
            val twoRows = ih >= 260.dp
            val ctrlH = if (twoRows) s * 2 + 6.dp else s
            val coverH = (ih - ctrlH - 8.dp).coerceAtMost(iw / Widgets.aspect(bmp, true))
            Column(root, horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically) {
                CoverImg(iw, coverH)
                Spacer(GlanceModifier.height(8.dp))
                Btn(playIcon, playLabel, playPause, s, primary = true)
                if (twoRows) Row {
                    Btn(skipIcon(back, false), "Back ${back}s", mediaKey(context, android.view.KeyEvent.KEYCODE_MEDIA_REWIND), (s * 0.8f))
                    Spacer(GlanceModifier.width(6.dp))
                    Btn(skipIcon(fwd, true), "Forward ${fwd}s", mediaKey(context, android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD), (s * 0.8f))
                }
            }
        }
        // Wide: cover left, everything else right.
        w >= h * 1.35f -> {
            val s = ((ih - 44.dp) * 0.8f).coerceIn(30.dp, if (ih < 180.dp) 40.dp else 44.dp)
            // Leave room for at least back / play / forward beside the cover.
            val coverW = minOf(ih, iw * 0.5f, iw - 10.dp - (s * 3 + 12.dp)).coerceAtLeast(48.dp)
            val right = iw - coverW - 10.dp
            Row(root, verticalAlignment = Alignment.CenterVertically) {
                CoverImg(coverW, ih)
                Spacer(GlanceModifier.width(10.dp))
                Column(GlanceModifier.defaultWeight()) {
                    Titles(if (ih >= 150.dp) 2 else 1)
                    Spacer(GlanceModifier.height(6.dp))
                    Bar()
                    Spacer(GlanceModifier.height(6.dp))
                    Controls(right, s)
                }
            }
        }
        // Small square: cover and buttons only, no room for words.
        ih < 200.dp -> {
            val s = (iw / 5).coerceIn(28.dp, 40.dp)
            Column(root, horizontalAlignment = Alignment.CenterHorizontally) {
                CoverImg(iw, ih - s - 8.dp)
                Spacer(GlanceModifier.height(8.dp))
                Controls(iw, s)
            }
        }
        // Square or tall: cover on top.
        else -> {
            val s = (iw / 6).coerceIn(32.dp, 48.dp)
            val below = 34.dp + 12.dp + s + 12.dp
            Column(root, horizontalAlignment = Alignment.CenterHorizontally) {
                CoverImg(iw, ih - below)
                Spacer(GlanceModifier.height(6.dp))
                Titles(1, center = true)
                Spacer(GlanceModifier.height(4.dp))
                Bar()
                Spacer(GlanceModifier.height(6.dp))
                Controls(iw, s)
            }
        }
    }
}

/** A media-button press straight to the player service: works when Worm is closed, like headphones. */
private fun mediaKey(context: Context, code: Int): androidx.glance.action.Action {
    val intent = android.content.Intent(android.content.Intent.ACTION_MEDIA_BUTTON)
        .setComponent(ComponentName(context, PlaybackService::class.java))
        .putExtra(android.content.Intent.EXTRA_KEY_EVENT, android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code))
        .setData(android.net.Uri.parse("worm://media/$code")) // keeps each button's intent distinct
    return androidx.glance.appwidget.action.actionStartService(intent, isForegroundService = true)
}

private fun skipIcon(sec: Int, forward: Boolean): Int = when (sec) {
    5 -> if (forward) R.drawable.ic_skip_fwd_5 else R.drawable.ic_skip_back_5
    10 -> if (forward) R.drawable.ic_skip_fwd_10 else R.drawable.ic_skip_back_10
    15 -> if (forward) R.drawable.ic_skip_fwd_15 else R.drawable.ic_skip_back_15
    20 -> if (forward) R.drawable.ic_skip_fwd_20 else R.drawable.ic_skip_back_20
    45 -> if (forward) R.drawable.ic_skip_fwd_45 else R.drawable.ic_skip_back_45
    60 -> if (forward) R.drawable.ic_skip_fwd_60 else R.drawable.ic_skip_back_60
    90 -> if (forward) R.drawable.ic_skip_fwd_90 else R.drawable.ic_skip_back_90
    else -> if (forward) R.drawable.ic_skip_fwd_30 else R.drawable.ic_skip_back_30
}

// --------------------------------------------------------------- buttons

/** Talks to the playback service the same way the car and earbuds do. */
private suspend fun withPlayer(context: Context, block: (MediaController) -> Unit) = withContext(Dispatchers.Main) {
    val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
    val future = MediaController.Builder(context, token).buildAsync()
    val controller = suspendCancellableCoroutine<MediaController?> { cont ->
        future.addListener({ cont.resume(runCatching { future.get() }.getOrNull()) }, { it.run() })
        cont.invokeOnCancellation { future.cancel(true) }
    } ?: return@withContext
    try {
        block(controller)
        kotlinx.coroutines.delay(400) // let the command land before letting go
    } finally { controller.release() }
}

class PlayPauseAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val next = Widgets.nowPlaying(context)
        withPlayer(context) { c ->
            when {
                c.isPlaying -> c.pause()
                else -> {
                    // Nothing loaded yet (Worm was closed): load the audiobook the widget shows, at your spot.
                    if (c.mediaItemCount == 0 && next != null) c.setMediaItem(PlayerHub.bookItem(context, next.book, next.progress))
                    if (c.playbackState == androidx.media3.common.Player.STATE_IDLE) c.prepare()
                    c.play()
                }
            }
        }
    }
}

class SkipBackAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) = withPlayer(context) { it.seekBack() }
}

class SkipForwardAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) = withPlayer(context) { it.seekForward() }
}

class NextChapterAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) =
        withPlayer(context) { it.sendCustomCommand(PlaybackService.CMD_NEXT_CHAPTER, android.os.Bundle.EMPTY) }
}

class PrevChapterAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) =
        withPlayer(context) { it.sendCustomCommand(PlaybackService.CMD_PREV_CHAPTER, android.os.Bundle.EMPTY) }
}
