package com.worm.app.widget

import android.content.ComponentName
import android.content.Context
import androidx.compose.runtime.Composable
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
        val item = Widgets.nowPlaying(context)
        val bmp = item?.let { Widgets.cover(it.book, 360) }
        val lib = Library.get(context)
        val prefs = lib.prefs
        val chapter = PlayerHub.chapterTitle.value
        provideContent {
            WormGlanceTheme { PlayerContent(item, bmp, PlaybackService.playing, chapter, prefs.skipBackSec, prefs.skipForwardSec, prefs.widgetCorners) }
        }
    }
}

class PlayerWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = PlayerWidget() }

@Composable
private fun PlayerContent(item: Widgets.Item?, bmp: android.graphics.Bitmap?, playing: Boolean, chapter: String?, back: Int, fwd: Int, corners: Int) {
    val size = LocalSize.current
    val context = LocalContext.current
    val c = GlanceTheme.colors
    val root = GlanceModifier.fillMaxSize().background(c.surface).cornerRadius(corners.dp)
    val openPlayer = actionStartActivity(Widgets.openScreenIntent(context, "player"))

    @Composable fun CoverImg(maxW: androidx.compose.ui.unit.Dp, maxH: androidx.compose.ui.unit.Dp) {
        val r = (corners / 2).coerceAtLeast(6).dp
        val fit = Widgets.fit(Widgets.aspect(bmp, true), maxW, maxH)
        Box(GlanceModifier.width(maxW.coerceAtLeast(8.dp)).height(maxH.coerceAtLeast(8.dp)), contentAlignment = Alignment.Center) {
            if (bmp != null) Image(ImageProvider(bmp), item?.book?.title, GlanceModifier.size(fit.width, fit.height).cornerRadius(r).clickable(openPlayer), contentScale = ContentScale.Crop)
            else Box(GlanceModifier.size(fit.width, fit.height).cornerRadius(r).background(c.primaryContainer).clickable(openPlayer), contentAlignment = Alignment.Center) {
                Image(ImageProvider(R.drawable.worm_logo), null, GlanceModifier.size(32.dp))
            }
        }
    }

    @Composable fun Btn(icon: Int, label: String, action: androidx.glance.action.Action, big: Boolean = false) {
        val s = if (big) 52.dp else 40.dp
        Box(
            GlanceModifier.size(s).cornerRadius(s / 2).background(if (big) c.primary else c.secondaryContainer).clickable(action),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                ImageProvider(icon), label, GlanceModifier.size(if (big) 28.dp else 22.dp),
                colorFilter = ColorFilter.tint(if (big) c.onPrimary else c.onSecondaryContainer),
            )
        }
    }

    val playPause = actionRunCallback<PlayPauseAction>()
    val playIcon = if (playing) R.drawable.ic_w_pause else R.drawable.ic_w_play
    val playLabel = if (playing) "Pause" else "Play"
    val backIcon = skipIcon(back, false)
    val fwdIcon = skipIcon(fwd, true)

    @Composable fun Titles(maxTitle: Int) {
        Text(item?.book?.title ?: "Nothing playing", maxLines = maxTitle, style = TextStyle(color = c.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Bold))
        Text(chapter ?: item?.book?.author ?: "Tap play to pick up your last audiobook", maxLines = 1, style = TextStyle(color = c.onSurfaceVariant, fontSize = 11.sp))
    }

    @Composable fun Bar() {
        LinearProgressIndicator(
            progress = item?.progress?.fraction ?: 0f,
            modifier = GlanceModifier.fillMaxWidth().height(5.dp).cornerRadius(3.dp),
            color = c.primary, backgroundColor = c.surfaceVariant,
        )
    }

    when {
        // Big: cover on top, the lot underneath.
        size.height >= 250.dp -> Column(root.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            CoverImg(size.width - 24.dp, (size.height - 24.dp - 150.dp).coerceAtLeast(40.dp))
            Spacer(GlanceModifier.height(8.dp))
            Column(GlanceModifier.fillMaxWidth()) { Titles(1) }
            Spacer(GlanceModifier.height(6.dp))
            Bar()
            item?.let {
                val left = ((1f - (it.progress?.fraction ?: 0f)) * it.book.durationMs / 1000).toLong()
                Text("${Widgets.fmtHours(left)} left", style = TextStyle(color = c.primary, fontSize = 11.sp), modifier = GlanceModifier.padding(top = 2.dp))
            }
            Spacer(GlanceModifier.height(6.dp))
            Row(GlanceModifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically) {
                if (size.width >= 250.dp) { Btn(R.drawable.ic_w_prev, "Previous chapter", actionRunCallback<PrevChapterAction>()); Spacer(GlanceModifier.width(6.dp)) }
                Btn(backIcon, "Back ${back}s", actionRunCallback<SkipBackAction>()); Spacer(GlanceModifier.width(8.dp))
                Btn(playIcon, playLabel, playPause, big = true); Spacer(GlanceModifier.width(8.dp))
                Btn(fwdIcon, "Forward ${fwd}s", actionRunCallback<SkipForwardAction>())
                if (size.width >= 250.dp) { Spacer(GlanceModifier.width(6.dp)); Btn(R.drawable.ic_w_next, "Next chapter", actionRunCallback<NextChapterAction>()) }
            }
        }
        // Medium: cover left, titles + progress + controls right.
        size.height >= 120.dp && size.width >= 180.dp -> Row(root.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            CoverImg(size.width * 0.4f, size.height - 20.dp)
            Spacer(GlanceModifier.width(10.dp))
            Column(GlanceModifier.defaultWeight()) {
                Titles(2)
                Spacer(GlanceModifier.height(6.dp))
                Bar()
                Spacer(GlanceModifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (size.width >= 250.dp) { Btn(R.drawable.ic_w_prev, "Previous chapter", actionRunCallback<PrevChapterAction>()); Spacer(GlanceModifier.width(4.dp)) }
                    Btn(backIcon, "Back ${back}s", actionRunCallback<SkipBackAction>()); Spacer(GlanceModifier.width(6.dp))
                    Btn(playIcon, playLabel, playPause, big = true); Spacer(GlanceModifier.width(6.dp))
                    Btn(fwdIcon, "Forward ${fwd}s", actionRunCallback<SkipForwardAction>())
                    if (size.width >= 250.dp) { Spacer(GlanceModifier.width(4.dp)); Btn(R.drawable.ic_w_next, "Next chapter", actionRunCallback<NextChapterAction>()) }
                }
            }
        }
        // One row: cover, title, buttons.
        size.width >= 120.dp -> Row(root.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            CoverImg(size.width * 0.3f, size.height - 12.dp)
            Spacer(GlanceModifier.width(8.dp))
            if (size.width >= 180.dp) Column(GlanceModifier.defaultWeight()) { Titles(1) } else Spacer(GlanceModifier.defaultWeight())
            if (size.width >= 250.dp) { Btn(backIcon, "Back ${back}s", actionRunCallback<SkipBackAction>()); Spacer(GlanceModifier.width(4.dp)) }
            Btn(playIcon, playLabel, playPause)
            if (size.width >= 180.dp) { Spacer(GlanceModifier.width(4.dp)); Btn(fwdIcon, "Forward ${fwd}s", actionRunCallback<SkipForwardAction>()) }
        }
        // 1×2: cover with play underneath.
        else -> Column(root.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            CoverImg(size.width - 12.dp, size.height - 12.dp - 46.dp)
            Spacer(GlanceModifier.height(6.dp))
            Btn(playIcon, playLabel, playPause)
        }
    }
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
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) =
        withPlayer(context) { c -> if (c.isPlaying) c.pause() else { if (c.playbackState == androidx.media3.common.Player.STATE_IDLE) c.prepare(); c.play() } }
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
