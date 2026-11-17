package com.worm.app.reader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Footer options, in the order they appear in Page look. */
val FooterItems = listOf(
    "chapter" to "Chapter title",
    "chapterProgress" to "Chapter progress",
    "book" to "Book %",
    "bar" to "Progress bar",
    "clock" to "Clock",
    "battery" to "Battery",
)

/** Room the page leaves at the bottom so text never runs under the footer. */
fun footerSpace(items: Set<String>): Dp = when {
    items.isEmpty() -> 0.dp
    items == setOf("bar") -> 10.dp
    "bar" in items -> 34.dp
    else -> 26.dp
}

/**
 * The line under the page while reading. Left: chapter title. Right: chapter progress, book %,
 * clock, battery — whichever are switched on. Optional thin bar with a tick at every chapter.
 */
@Composable
fun ReaderFooter(loc: ReaderLocation, chapterStarts: List<Float>, items: Set<String>, fg: Color, modifier: Modifier = Modifier) {
    val dim = fg.copy(alpha = 0.5f)
    val clock = if ("clock" in items) rememberClock() else ""
    val battery = if ("battery" in items) rememberBattery() else -1
    Column(modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 4.dp)) {
        if ("bar" in items) {
            Canvas(Modifier.fillMaxWidth().height(8.dp)) {
                val y = size.height / 2
                val stroke = 2.dp.toPx()
                drawLine(fg.copy(alpha = 0.15f), Offset(0f, y), Offset(size.width, y), stroke, StrokeCap.Round)
                drawLine(fg.copy(alpha = 0.55f), Offset(0f, y), Offset(size.width * loc.fraction.coerceIn(0f, 1f), y), stroke, StrokeCap.Round)
                // Chapter markers.
                chapterStarts.drop(1).forEach { f ->
                    if (f in 0.001f..0.999f) drawLine(fg.copy(alpha = 0.4f), Offset(size.width * f, y - 3.dp.toPx()), Offset(size.width * f, y + 3.dp.toPx()), 1.dp.toPx())
                }
            }
        }
        val right = buildList {
            if ("chapterProgress" in items && loc.sectionPages > 1) {
                val left = (loc.sectionPages - loc.sectionPage - 1).coerceAtLeast(0)
                add(if (left == 0) "last page of chapter" else if (left == 1) "1 page left in chapter" else "$left pages left in chapter")
            }
            if ("book" in items) add("${(loc.fraction * 100).toInt()}%")
            if ("clock" in items) add(clock)
            if ("battery" in items && battery >= 0) add("$battery%🔋")
        }
        if ("chapter" in items || right.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if ("chapter" in items) loc.chapter else "",
                color = dim, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(right.joinToString("  ·  "), color = dim, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

@Composable
private fun rememberClock(): String {
    val context = LocalContext.current
    val format = remember { android.text.format.DateFormat.getTimeFormat(context) }
    var now by remember { mutableStateOf(format.format(java.util.Date())) }
    LaunchedEffect(Unit) {
        while (true) {
            now = format.format(java.util.Date())
            delay(60_000L - System.currentTimeMillis() % 60_000L + 50)
        }
    }
    return now
}

@Composable
private fun rememberBattery(): Int {
    val context = LocalContext.current
    var level by remember { mutableIntStateOf(-1) }
    DisposableEffect(Unit) {
        fun read(i: Intent?) {
            val l = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val s = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            if (l >= 0 && s > 0) level = l * 100 / s
        }
        val receiver = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) = read(i) }
        read(context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED)))
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    return level
}
