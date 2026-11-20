package com.worm.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
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
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
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
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import com.worm.app.R
import com.worm.app.data.DayActivity
import com.worm.app.data.Library
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle as JTextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** This week (Mon–Sun) or this month: time listened, time read, books finished, with little bars. */
abstract class BaseStatsWidget(private val monthly: Boolean) : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            // Re-read whenever the activity log changes (see Widgets.statsTick).
            val tick by Widgets.statsTick.collectAsState()
            val d = remember(tick) { compute(context) }
            WormGlanceTheme { StatsContent(d.title, d.total, d.bars, d.today, d.corners) }
        }
    }

    private class Data(val title: String, val total: DayActivity, val bars: List<Pair<String, DayActivity>>, val today: Int, val corners: Int)

    private fun compute(context: Context): Data {
        val lib = Library.get(context)
        val days = lib.activity.value
        val today = LocalDate.now()
        val start = if (monthly) today.withDayOfMonth(1) else today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val end = if (monthly) today.with(TemporalAdjusters.lastDayOfMonth()) else start.plusDays(6)
        // Bars: one per day for a week; one per week for a month.
        val bars: List<Pair<String, DayActivity>> = if (!monthly) {
            (0..6).map { i ->
                val d = start.plusDays(i.toLong())
                d.dayOfWeek.getDisplayName(JTextStyle.NARROW, Locale.getDefault()) to (days[d.toString()] ?: DayActivity())
            }
        } else {
            generateSequence(start) { it.plusWeeks(1) }.takeWhile { !it.isAfter(end) }.mapIndexed { i, w ->
                val sum = (0..6).map { w.plusDays(it.toLong()) }.filter { !it.isAfter(end) }.mapNotNull { days[it.toString()] }
                "W${i + 1}" to DayActivity(sum.sumOf { it.readSec }, sum.sumOf { it.listenSec }, sum.sumOf { it.finished })
            }.toList()
        }
        val inRange = days.filterKeys { k -> runCatching { LocalDate.parse(k) }.getOrNull()?.let { !it.isBefore(start) && !it.isAfter(end) } == true }.values
        val total = DayActivity(inRange.sumOf { it.readSec }, inRange.sumOf { it.listenSec }, inRange.sumOf { it.finished })
        val title = if (monthly) today.month.getDisplayName(JTextStyle.FULL, Locale.getDefault()) else "This week"
        val corners = lib.prefs.widgetCorners
        return Data(title, total, bars, todayIndex(monthly, today, start), corners)
    }

    private fun todayIndex(monthly: Boolean, today: LocalDate, start: LocalDate) =
        if (monthly) ((today.dayOfMonth - 1) / 7) else (today.toEpochDay() - start.toEpochDay()).toInt()
}

class WeekStatsWidget : BaseStatsWidget(monthly = false)
class MonthStatsWidget : BaseStatsWidget(monthly = true)
class WeekStatsWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = WeekStatsWidget() }
class MonthStatsWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = MonthStatsWidget() }

@Composable
private fun StatsContent(title: String, total: DayActivity, bars: List<Pair<String, DayActivity>>, today: Int, corners: Int) {
    val size = LocalSize.current
    val context = LocalContext.current
    val c = GlanceTheme.colors
    val w = size.width; val h = size.height
    val small = w < 110.dp || h < 90.dp
    val pad = if (small) 6.dp else 12.dp
    val root = GlanceModifier.fillMaxSize().background(c.surface).cornerRadius(corners.dp).padding(pad)
        .clickable(actionStartActivity(Widgets.openScreenIntent(context, "stats")))
    // Bigger widgets show more: one number → three numbers → header → bars.
    val showHeader = h >= 110.dp && w >= 110.dp
    val showAll = w >= 150.dp
    val barSpace = h - pad * 2 - (if (showHeader) 24.dp else 0.dp) - 48.dp - 18.dp
    val showBars = w >= 200.dp && barSpace >= 30.dp
    Column(root, verticalAlignment = if (showBars) Alignment.Top else Alignment.CenterVertically) {
        if (showHeader) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Image(ImageProvider(R.drawable.worm_logo), null, GlanceModifier.size(18.dp))
                Spacer(GlanceModifier.width(6.dp))
                Text(title, maxLines = 1, style = TextStyle(color = c.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold))
            }
            Spacer(GlanceModifier.height(6.dp))
        }
        if (showAll) Row(GlanceModifier.fillMaxWidth()) {
            Stat(Widgets.fmtHours(total.listenSec), "listened", GlanceModifier.defaultWeight(), small)
            Stat(Widgets.fmtHours(total.readSec), "read", GlanceModifier.defaultWeight(), small)
            Stat("${total.finished}", "finished", GlanceModifier.defaultWeight(), small)
        } else if (h >= 140.dp) Column(GlanceModifier.fillMaxWidth()) {
            // Narrow but tall (2×2): the three numbers stacked as compact lines.
            listOf(Widgets.fmtHours(total.listenSec) to "listened", Widgets.fmtHours(total.readSec) to "read", "${total.finished}" to "finished").forEach { (v, l) ->
                Row(GlanceModifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(v, maxLines = 1, style = TextStyle(color = c.primary, fontSize = 18.sp, fontWeight = FontWeight.Bold), modifier = GlanceModifier.width(64.dp))
                    Text(l, maxLines = 1, style = TextStyle(color = c.onSurfaceVariant, fontSize = 12.sp))
                }
            }
        } else Column(GlanceModifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            // Tiny: total time, with the period as the label.
            Stat(Widgets.fmtHours(total.listenSec + total.readSec), if (title == "This week") "this week" else "this month", GlanceModifier.fillMaxWidth(), small)
        }
        if (showBars) {
            Spacer(GlanceModifier.height(8.dp))
            Bars(bars, today, barSpace.coerceAtMost(120.dp))
        }
    }
}

@Composable
private fun Stat(value: String, label: String, modifier: GlanceModifier, small: Boolean = false) {
    val c = GlanceTheme.colors
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, maxLines = 1, style = TextStyle(color = c.primary, fontSize = if (small) 16.sp else 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center))
        Text(label, style = TextStyle(color = c.onSurfaceVariant, fontSize = 11.sp, textAlign = TextAlign.Center))
    }
}

/** Stacked bars (listening on reading), drawn with plain boxes since widgets can't draw. */
@Composable
private fun Bars(bars: List<Pair<String, DayActivity>>, today: Int, height: Dp) {
    val c = GlanceTheme.colors
    val max = bars.maxOfOrNull { it.second.readSec + it.second.listenSec }?.coerceAtLeast(60) ?: 60
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        bars.forEachIndexed { i, (label, d) ->
            Column(GlanceModifier.defaultWeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(GlanceModifier.height(height).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        val lh = height * (d.listenSec.toFloat() / max)
                        val rh = height * (d.readSec.toFloat() / max)
                        if (lh > 1.dp) Box(GlanceModifier.width(14.dp).height(lh).cornerRadius(4.dp).background(c.tertiary)) {}
                        if (rh > 1.dp) Box(GlanceModifier.width(14.dp).height(rh).cornerRadius(4.dp).background(c.primary)) {}
                        if (lh <= 1.dp && rh <= 1.dp) Box(GlanceModifier.width(14.dp).height(3.dp).cornerRadius(2.dp).background(c.surfaceVariant)) {}
                    }
                }
                Text(label, style = TextStyle(color = if (i == today) c.primary else c.onSurfaceVariant, fontSize = 10.sp, fontWeight = if (i == today) FontWeight.Bold else FontWeight.Normal))
            }
        }
    }
}

/** For players who found Bookworm: your best score and a way straight into a game. */
class GameWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Single

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val prefs = Library.get(context).prefs
        val best = prefs.gameBest
        val corners = prefs.widgetCorners
        provideContent {
            val c = androidx.glance.unit.ColorProvider(androidx.compose.ui.graphics.Color(0xFF98E8DE))
            Column(
                GlanceModifier.fillMaxSize().cornerRadius(corners.dp).background(androidx.compose.ui.graphics.Color(0xFF14091C)).padding(10.dp)
                    .clickable(actionStartActivity(Widgets.openScreenIntent(LocalContext.current, "game"))),
                horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(ImageProvider(R.drawable.ic_progress_worm), null, GlanceModifier.size(36.dp), colorFilter = androidx.glance.ColorFilter.tint(c))
                Text("BOOKWORM", style = TextStyle(color = c, fontSize = 14.sp, fontWeight = FontWeight.Bold))
                Text("HI %04d".format(best), style = TextStyle(color = androidx.glance.unit.ColorProvider(androidx.compose.ui.graphics.Color(0xFFD552A3)), fontSize = 13.sp))
                Spacer(GlanceModifier.height(6.dp))
                Box(GlanceModifier.cornerRadius(14.dp).background(androidx.compose.ui.graphics.Color(0xFF542177)).padding(horizontal = 14.dp, vertical = 5.dp)) {
                    Text("Wriggle!", style = TextStyle(color = c, fontSize = 12.sp, fontWeight = FontWeight.Bold))
                }
            }
        }
    }
}

class GameWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = GameWidget() }

