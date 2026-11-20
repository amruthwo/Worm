package com.worm.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
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
import com.worm.app.R
import com.worm.app.data.Library

/**
 * "Pick up where you left off" widgets: books, audiobooks, or both. They stretch from a single
 * cover (1×1) up to a shelf of several, showing more the bigger you make them.
 */
abstract class BaseResumeWidget(private val kind: String) : GlanceAppWidget() {
    // Exact: we lay covers out from the real size, so they keep their shape at every size.
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val items = Widgets.resumeItems(context, kind)
        val covers = items.associate { it.book.id to Widgets.cover(it.book) }
        val corners = Library.get(context).prefs.widgetCorners
        provideContent { WormGlanceTheme { ResumeContent(kind, items, covers, corners) } }
    }
}

class ReadWidget : BaseResumeWidget("read")
class ListenWidget : BaseResumeWidget("listen")
class ResumeWidget : BaseResumeWidget("both")

class ReadWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = ReadWidget() }
class ListenWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = ListenWidget() }
class ResumeWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = ResumeWidget() }

@Composable
private fun ResumeContent(kind: String, items: List<Widgets.Item>, covers: Map<String, android.graphics.Bitmap?>, corners: Int) {
    val size = LocalSize.current
    val context = LocalContext.current
    val c = GlanceTheme.colors
    val root = GlanceModifier.fillMaxSize().background(c.surface).cornerRadius(corners.dp)
    if (items.isEmpty()) {
        Box(root.padding(8.dp).clickable(actionStartActivity(Widgets.openScreenIntent(context, "home"))), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Image(ImageProvider(R.drawable.worm_logo), null, GlanceModifier.size(if (size.width < 100.dp) 28.dp else 40.dp))
                if (size.width >= 100.dp) Text(
                    when (kind) { "read" -> "Nothing being read"; "listen" -> "Nothing being listened to"; else -> "Nothing in progress" },
                    style = TextStyle(color = c.onSurfaceVariant, fontSize = 12.sp),
                )
            }
        }
        return
    }
    val wide = size.width >= 250.dp
    val tall = size.height >= 250.dp
    when {
        // A shelf of covers, each with its own progress line.
        wide -> Column(root.padding(10.dp)) {
            Header(kind)
            val perRow = 3
            // One row of bigger covers even when tall: two rows of six covers overflow what a widget can send.
            val rows = 1
            val tileW = (size.width - 20.dp - 16.dp) / perRow
            val tileH = (size.height - 20.dp - 26.dp) / rows - 26.dp
            items.take(perRow * rows).chunked(perRow).forEach { row ->
                Row(GlanceModifier.fillMaxWidth().defaultWeight()) {
                    row.forEachIndexed { i, it ->
                        if (i > 0) Spacer(GlanceModifier.width(8.dp))
                        CoverTile(it, covers[it.book.id], corners, GlanceModifier.defaultWeight().fillMaxHeight(), showTitle = true, maxW = tileW, maxH = tileH)
                    }
                    repeat(perRow - row.size) { Spacer(GlanceModifier.width(8.dp)); Box(GlanceModifier.defaultWeight()) {} }
                }
            }
        }
        // Tall and narrow: a list.
        tall -> Column(root.padding(10.dp)) {
            Header(kind)
            items.take(4).forEach { ListRow(it, covers[it.book.id], corners) }
        }
        // Square-ish: one book, big.
        // Small square: cover on top, progress and title under it.
        size.width >= 100.dp && size.height >= 100.dp && size.width < 200.dp -> {
            val it = items.first()
            Column(root.padding(8.dp).clickable(actionStartActivity(Widgets.openBookIntent(context, it.book))), horizontalAlignment = Alignment.CenterHorizontally) {
                Cover(it, covers[it.book.id], corners, size.width - 16.dp, size.height - 16.dp - 30.dp)
                Spacer(GlanceModifier.height(4.dp))
                Progress(it)
                Text(it.book.title, maxLines = 1, style = TextStyle(color = c.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Bold))
            }
        }
        size.width >= 100.dp && size.height >= 100.dp -> {
            val it = items.first()
            Column(root.padding(10.dp).clickable(actionStartActivity(Widgets.openBookIntent(context, it.book)))) {
                Row(GlanceModifier.defaultWeight().fillMaxWidth()) {
                    Cover(it, covers[it.book.id], corners, size.width * 0.5f, size.height - 34.dp)
                    Spacer(GlanceModifier.width(8.dp))
                    Column(GlanceModifier.defaultWeight()) {
                        Text(if (it.book.isAudio) "Keep listening" else "Keep reading", style = TextStyle(color = c.primary, fontSize = 11.sp, fontWeight = FontWeight.Medium))
                        Text(it.book.title, maxLines = 3, style = TextStyle(color = c.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Bold))
                        Text(it.book.author, maxLines = 1, style = TextStyle(color = c.onSurfaceVariant, fontSize = 11.sp))
                    }
                }
                Spacer(GlanceModifier.height(6.dp))
                Progress(it)
            }
        }
        // One row: cover and title.
        size.width >= 100.dp -> {
            val it = items.first()
            Row(root.padding(6.dp).clickable(actionStartActivity(Widgets.openBookIntent(context, it.book))), verticalAlignment = Alignment.CenterVertically) {
                Cover(it, covers[it.book.id], corners, size.width * 0.4f, size.height - 12.dp)
                Spacer(GlanceModifier.width(8.dp))
                Column(GlanceModifier.defaultWeight()) {
                    Text(it.book.title, maxLines = if (size.height < 90.dp) 1 else 2, style = TextStyle(color = c.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold))
                    Text("${((it.progress?.fraction ?: 0f) * 100).toInt()}%", style = TextStyle(color = c.primary, fontSize = 11.sp))
                }
            }
        }
        // 1×1 and 1×2: the cover is the widget.
        else -> {
            val it = items.first()
            Column(root.clickable(actionStartActivity(Widgets.openBookIntent(context, it.book))), horizontalAlignment = Alignment.CenterHorizontally) {
                Cover(it, covers[it.book.id], corners, size.width, size.height - if (size.height >= 120.dp) 26.dp else 0.dp)
                if (size.height >= 120.dp) Text(
                    "${((it.progress?.fraction ?: 0f) * 100).toInt()}%",
                    style = TextStyle(color = c.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Bold), modifier = GlanceModifier.padding(4.dp),
                )
            }
        }
    }
}

@Composable
private fun Header(kind: String) {
    val c = GlanceTheme.colors
    Row(GlanceModifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(ImageProvider(R.drawable.worm_logo), null, GlanceModifier.size(18.dp))
        Spacer(GlanceModifier.width(6.dp))
        Text(
            when (kind) { "read" -> "Keep reading"; "listen" -> "Keep listening"; else -> "Pick up where you left off" },
            style = TextStyle(color = c.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold),
        )
    }
}

@Composable
private fun Cover(it: Widgets.Item, bmp: android.graphics.Bitmap?, corners: Int, maxW: Dp, maxH: Dp) {
    val c = GlanceTheme.colors
    val r = (corners / 2).coerceAtLeast(4).dp
    val fit = Widgets.fit(Widgets.aspect(bmp, it.book.isAudio), maxW, maxH)
    // The space is reserved; the cover sits in the middle at its own shape.
    Box(GlanceModifier.width(maxW.coerceAtLeast(8.dp)).height(maxH.coerceAtLeast(8.dp)), contentAlignment = Alignment.Center) {
        if (bmp != null) Image(ImageProvider(bmp), it.book.title, GlanceModifier.size(fit.width, fit.height).cornerRadius(r), contentScale = ContentScale.Crop)
        else Box(GlanceModifier.size(fit.width, fit.height).cornerRadius(r).background(c.primaryContainer).padding(6.dp), contentAlignment = Alignment.Center) {
            Text(it.book.title, maxLines = 4, style = TextStyle(color = c.onPrimaryContainer, fontSize = 11.sp, fontWeight = FontWeight.Bold))
        }
    }
}

@Composable
private fun CoverTile(it: Widgets.Item, bmp: android.graphics.Bitmap?, corners: Int, modifier: GlanceModifier, showTitle: Boolean, maxW: Dp, maxH: Dp) {
    val context = LocalContext.current
    val c = GlanceTheme.colors
    Column(modifier.clickable(actionStartActivity(Widgets.openBookIntent(context, it.book)))) {
        Cover(it, bmp, corners, maxW, maxH)
        Spacer(GlanceModifier.height(4.dp))
        Progress(it)
        if (showTitle) Text(it.book.title, maxLines = 1, style = TextStyle(color = c.onSurface, fontSize = 11.sp))
    }
}

@Composable
private fun ListRow(it: Widgets.Item, bmp: android.graphics.Bitmap?, corners: Int) {
    val context = LocalContext.current
    val c = GlanceTheme.colors
    Row(
        GlanceModifier.fillMaxWidth().height(54.dp).padding(vertical = 3.dp).clickable(actionStartActivity(Widgets.openBookIntent(context, it.book))),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(it, bmp, corners, 48.dp, 48.dp)
        Spacer(GlanceModifier.width(8.dp))
        Column(GlanceModifier.defaultWeight()) {
            Text(it.book.title, maxLines = 1, style = TextStyle(color = c.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold))
            Text(
                (if (it.book.isAudio) "🎧 " else "📖 ") + "${((it.progress?.fraction ?: 0f) * 100).toInt()}% · ${it.book.author}",
                maxLines = 1, style = TextStyle(color = c.onSurfaceVariant, fontSize = 11.sp),
            )
            Progress(it)
        }
    }
}

@Composable
private fun Progress(it: Widgets.Item) {
    val c = GlanceTheme.colors
    LinearProgressIndicator(
        progress = it.progress?.fraction ?: 0f,
        modifier = GlanceModifier.fillMaxWidth().height(4.dp).cornerRadius(2.dp),
        color = if (it.book.isAudio) c.tertiary else c.primary,
        backgroundColor = c.surfaceVariant,
    )
}

/** Worm colours by default; the wallpaper's (Material You) if you pick that in Settings. */
@Composable
fun WormGlanceTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    if (Library.get(context).prefs.widgetColors == "wallpaper") GlanceTheme(content = content)
    else GlanceTheme(colors = Widgets.wormColors, content = content)
}
