package com.worm.app.widget

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.glance.appwidget.updateAll
import androidx.compose.ui.unit.dp
import androidx.glance.material3.ColorProviders
import com.worm.app.MainActivity
import com.worm.app.data.Book
import com.worm.app.data.Library
import com.worm.app.data.ReadingProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Shared bits for Worm's home screen widgets: colours, data, covers, refreshing. */
object Widgets {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pending: Job? = null
    private var pendingStats: Job? = null

    /** Worm's own plum-and-candy colours (the default), or the wallpaper's (Material You). */
    val wormColors = ColorProviders(
        light = lightColorScheme(
            primary = Color(0xFF8E1EA2), onPrimary = Color.White,
            primaryContainer = Color(0xFFF6D8FF), onPrimaryContainer = Color(0xFF35003F),
            secondaryContainer = Color(0xFFFFD8EC), onSecondaryContainer = Color(0xFF3E0029),
            tertiary = Color(0xFF00695F), tertiaryContainer = Color(0xFFB7F3EA),
            surface = Color(0xFFFFF7FC), onSurface = Color(0xFF231A26),
            surfaceVariant = Color(0xFFEFDDF0), onSurfaceVariant = Color(0xFF5A4560),
            background = Color(0xFFFFF0FA), onBackground = Color(0xFF231A26),
        ),
        dark = darkColorScheme(
            primary = Color(0xFFD552A3), onPrimary = Color(0xFF2A0E3A),
            primaryContainer = Color(0xFF542177), onPrimaryContainer = Color(0xFFF8DCFF),
            secondaryContainer = Color(0xFF6A1E57), onSecondaryContainer = Color(0xFFFFD8EC),
            tertiary = Color(0xFF98E8DE), tertiaryContainer = Color(0xFF1F4F49),
            surface = Color(0xFF2A0E3A), onSurface = Color(0xFFF6E9FF),
            surfaceVariant = Color(0xFF3B1650), onSurfaceVariant = Color(0xFFD9C2E2),
            background = Color(0xFF2A0E3A), onBackground = Color(0xFFF6E9FF),
        ),
    )

    // ---------------------------------------------------------------- data

    data class Item(val book: Book, val progress: ReadingProgress?)

    /** Books you're partway through, newest first. kind: "read", "listen" or "both". */
    fun resumeItems(context: Context, kind: String, max: Int = 6): List<Item> {
        val lib = Library.get(context)
        val p = lib.progress.value
        val all = lib.books.value.filter { b ->
            !b.hidden && p[b.id]?.let { it.fraction > 0f && !it.finished && it.dismissedAt == 0L } == true
        }.sortedByDescending { p[it.id]?.updatedAt ?: 0L }
        val list = when (kind) {
            "read" -> all.filter { !it.isAudio }
            "listen" -> all.filter { it.isAudio }
            // One of each up front, so the newest book and the newest audiobook are both there.
            else -> listOfNotNull(all.firstOrNull { !it.isAudio }, all.firstOrNull { it.isAudio })
                .sortedByDescending { p[it.id]?.updatedAt ?: 0L }
                .let { firsts -> firsts + all.filter { it !in firsts } }
        }
        return list.take(max).map { Item(it, p[it.id]) }
    }

    /** The audiobook playing now, or the one you listened to last. */
    fun nowPlaying(context: Context): Item? {
        val lib = Library.get(context)
        val p = lib.progress.value
        val id = com.worm.app.player.PlayerHub.bookId.value
            ?: lib.prefs.lastBookId?.takeIf { lib.book(it)?.isAudio == true }
            ?: lib.books.value.filter { it.isAudio && !it.hidden && (p[it.id]?.fraction ?: 0f) > 0f }.maxByOrNull { p[it.id]?.updatedAt ?: 0L }?.id
        val book = id?.let(lib::book)?.takeIf { it.isAudio && !it.hidden } ?: return null
        return Item(book, p[book.id])
    }

    /** Small, fixed-size covers keep widgets under the launcher's memory limit. */
    fun cover(book: Book, px: Int = 280): Bitmap? = runCatching {
        val path = book.coverPath ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= px && bounds.outHeight / (sample * 2) >= px) sample *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    /** Width/height of a cover (from the image itself; a typical book or square audiobook if there's none). */
    fun aspect(bmp: Bitmap?, audio: Boolean): Float = bmp?.takeIf { it.height > 0 }?.let { it.width.toFloat() / it.height } ?: if (audio) 1f else 0.68f

    /** The biggest box with the cover's own shape that fits the space, so nothing gets cropped. */
    fun fit(aspect: Float, maxW: androidx.compose.ui.unit.Dp, maxH: androidx.compose.ui.unit.Dp): androidx.compose.ui.unit.DpSize {
        val h = maxH.value.coerceAtLeast(8f); val w = maxW.value.coerceAtLeast(8f)
        val fw = minOf(w, h * aspect)
        return androidx.compose.ui.unit.DpSize(fw.dp, (fw / aspect).dp)
    }

    fun openBookIntent(context: Context, book: Book) = Intent(context, MainActivity::class.java)
        .setAction(MainActivity.ACTION_OPEN_BOOK)
        .putExtra(MainActivity.EXTRA_BOOK_PATH, book.path)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    fun openScreenIntent(context: Context, screen: String) = Intent(context, MainActivity::class.java)
        .setAction(MainActivity.ACTION_OPEN_SCREEN)
        .putExtra(MainActivity.EXTRA_SCREEN, screen)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    fun fmtHours(sec: Long): String {
        val m = sec / 60
        return when { m < 60 -> "${m}m"; m < 600 -> "%.1fh".format(m / 60f).replace(".0h", "h"); else -> "${m / 60}h" }
    }

    // ------------------------------------------------------------- refresh

    /** Resume widgets: after a page turn, a listen, a finished book. Debounced. */
    fun refreshResume(context: Context) {
        val app = context.applicationContext
        pending?.cancel()
        pending = scope.launch {
            delay(1_500)
            runCatching { ReadWidget().updateAll(app); ListenWidget().updateAll(app); ResumeWidget().updateAll(app) }
        }
    }

    fun refreshPlayer(context: Context) {
        val app = context.applicationContext
        scope.launch { runCatching { PlayerWidget().updateAll(app) } }
    }

    fun refreshStats(context: Context) {
        val app = context.applicationContext
        pendingStats?.cancel()
        pendingStats = scope.launch {
            delay(2_000)
            runCatching { WeekStatsWidget().updateAll(app); MonthStatsWidget().updateAll(app) }
        }
    }

    fun refreshAll(context: Context) {
        refreshResume(context); refreshPlayer(context); refreshStats(context)
        val app = context.applicationContext
        scope.launch { runCatching { GameWidget().updateAll(app) } }
    }

    /** The Bookworm widget only shows up in the widget picker once you've found the game. */
    fun syncGameWidget(context: Context) {
        val on = Library.get(context).prefs.gameUnlocked
        context.packageManager.setComponentEnabledSetting(
            ComponentName(context, GameWidgetReceiver::class.java),
            if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
    }
}
