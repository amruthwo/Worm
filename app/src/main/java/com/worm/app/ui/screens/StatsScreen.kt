package com.worm.app.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.worm.app.data.Book
import com.worm.app.ui.WormViewModel
import com.worm.app.ui.components.AuroraBackground
import com.worm.app.ui.components.BookCover
import com.worm.app.ui.theme.LocalWormColors
import com.worm.app.ui.theme.Worm
import com.worm.app.util.formatBytes
import com.worm.app.util.formatHours
import com.worm.app.util.plural
import java.io.File

/** "Worm's-eye view": what you've got, how big it is, and how much you've read and listened. */
@Composable
fun StatsScreen(vm: WormViewModel, onBack: () -> Unit, onOpenBook: (Book) -> Unit) {
    val all by vm.books.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val unlocked by vm.unlocked.collectAsStateWithLifecycle()
    val books = all.filter { !it.hidden || unlocked }
    val ebooks = books.filter { !it.isAudio }
    val audio = books.filter { it.isAudio }
    val totalBytes = books.sumOf { it.sizeBytes }
    val ebookBytes = ebooks.sumOf { it.sizeBytes }
    val audioBytes = audio.sumOf { it.sizeBytes }
    val listenTotal = audio.sumOf { it.durationMs }
    val finished = books.count { progress[it.id]?.finished == true }
    val spentRead = ebooks.sumOf { progress[it.id]?.timeSpentSec ?: 0 }
    val spentListen = audio.sumOf { progress[it.id]?.timeSpentSec ?: 0 }
    val byFormat = books.groupBy { it.format.uppercase() }.mapValues { (_, v) -> v.sumOf { it.sizeBytes } }.entries.sortedByDescending { it.value }
    val biggest = books.sortedByDescending { it.sizeBytes }.take(8)
    val topAuthors = books.flatMap { b -> b.authors.map { it to b } }.groupBy({ it.first }, { it.second }).entries
        .sortedByDescending { it.value.size }.take(5)
    val freeBytes = remember { runCatching { android.os.Environment.getExternalStorageDirectory().usableSpace }.getOrDefault(0L) }

    val grow = remember { Animatable(0f) }
    LaunchedEffect(Unit) { grow.animateTo(1f, tween(1100, easing = FastOutSlowInEasing)) }

    val palette = listOf(Worm.Pink, Worm.Mint, Worm.Violet, Color(0xFFFFC857), Worm.Lilac, Worm.Blush, Color(0xFF6FA8FF))

    Box(Modifier.fillMaxSize()) {
        AuroraBackground(emptyList())
        Scaffold(
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onBackground,
            topBar = {
                TopAppBar(
                    title = { Text("Worm's-eye view") },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
            },
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                    .padding(horizontal = ((androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp - 760) / 2).coerceAtLeast(16).dp)
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Big donut: books vs audiobooks vs free space.
                StatCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val slices = listOf(ebookBytes to Worm.Pink, audioBytes to Worm.Mint, freeBytes to MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                        val sum = slices.sumOf { it.first }.coerceAtLeast(1)
                        Box(Modifier.size(140.dp), contentAlignment = Alignment.Center) {
                            Canvas(Modifier.fillMaxSize()) {
                                var start = -90f
                                val w = 20.dp.toPx()
                                slices.forEach { (bytes, color) ->
                                    val sweep = 360f * bytes / sum * grow.value
                                    if (sweep > 0.5f) drawArc(color, start, (sweep - 2f).coerceAtLeast(0.5f), false, Offset(w / 2, w / 2), Size(size.width - w, size.height - w), style = Stroke(w, cap = StrokeCap.Round))
                                    start += 360f * bytes / sum
                                }
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(formatBytes(totalBytes), style = MaterialTheme.typography.titleLarge)
                                Text("in Worm", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        Spacer(Modifier.width(18.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Legend(Worm.Pink, plural(ebooks.size, "book"), formatBytes(ebookBytes))
                            Legend(Worm.Mint, plural(audio.size, "audiobook"), formatBytes(audioBytes))
                            Legend(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f), "Free space", formatBytes(freeBytes))
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BigNumber(
                        "%,d h".format(listenTotal / 3_600_000),
                        if (listenTotal > 48 * 3_600_000L) "of listening ≈ ${listenTotal / 86_400_000} days nonstop" else "of listening on the shelf",
                        Modifier.weight(1f),
                    )
                    BigNumber("$finished", if (finished == 1) "book finished" else "books finished", Modifier.weight(1f))
                }
                RecentActivity(vm)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BigNumber(formatHours(spentRead * 1000), "spent reading", Modifier.weight(1f))
                    BigNumber(formatHours(spentListen * 1000), "spent listening", Modifier.weight(1f))
                }
                if (byFormat.isNotEmpty()) StatCard {
                    Text("By format", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(10.dp))
                    val max = byFormat.first().value.coerceAtLeast(1)
                    byFormat.forEachIndexed { i, (fmt, bytes) ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                            Text(fmt, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(54.dp))
                            Box(Modifier.weight(1f).height(14.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))) {
                                Box(Modifier.fillMaxWidth((bytes.toFloat() / max * grow.value).coerceIn(0.02f, 1f)).height(14.dp).clip(CircleShape).background(palette[i % palette.size]))
                            }
                            Text(formatBytes(bytes), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 8.dp).width(64.dp))
                        }
                    }
                }
                if (biggest.isNotEmpty()) StatCard {
                    Text("Biggest", style = MaterialTheme.typography.titleMedium)
                    biggest.forEach { b ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onOpenBook(b) }.padding(vertical = 6.dp),
                        ) {
                            Box(Modifier.width(40.dp).aspectRatio(if (b.isAudio) 1f else 0.68f)) { BookCover(b, Modifier.fillMaxSize(), corner = 8.dp, showKindBadge = false) }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(b.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(File(b.path).parentFile?.name.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                            Text(formatBytes(b.sizeBytes), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                if (topAuthors.isNotEmpty()) StatCard {
                    Text("Most-shelved authors", style = MaterialTheme.typography.titleMedium)
                    topAuthors.forEachIndexed { i, (name, list) ->
                        Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(28.dp).clip(CircleShape).background(palette[i % palette.size]), contentAlignment = Alignment.Center) {
                                Text("${i + 1}", style = MaterialTheme.typography.labelLarge, color = Worm.Ink)
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            Text(plural(list.size, "title"), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun StatCard(content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = if (LocalWormColors.current.isDark) 0.75f else 0.9f), contentColor = MaterialTheme.colorScheme.onSurface),
        modifier = Modifier.fillMaxWidth(),
    ) { Column(Modifier.padding(18.dp)) { content() } }
}

@Composable
private fun BigNumber(value: String, label: String, modifier: Modifier = Modifier) {
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f), contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
        modifier = modifier,
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(value, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f))
        }
    }
}

@Composable
private fun Legend(color: Color, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Column {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(value, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}


/** This week and this month, from the daily log (listening and reading kept apart). */
@Composable
private fun RecentActivity(vm: com.worm.app.ui.WormViewModel) {
    val days by vm.library.activity.collectAsStateWithLifecycle()
    val today = java.time.LocalDate.now()
    val weekStart = today.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
    val monthStart = today.withDayOfMonth(1)
    fun sum(from: java.time.LocalDate) = days.filterKeys { runCatching { !java.time.LocalDate.parse(it).isBefore(from) }.getOrDefault(false) }.values
    val week = sum(weekStart); val month = sum(monthStart)
    if (days.isEmpty()) return
    StatCard {
        Text("Lately", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        listOf("This week" to week, "This month" to month).forEach { (label, d) ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(96.dp))
                Text("🎧 " + formatHours(d.sumOf { it.listenSec } * 1000), modifier = Modifier.weight(1f))
                Text("📖 " + formatHours(d.sumOf { it.readSec } * 1000), modifier = Modifier.weight(1f))
                Text("✓ ${d.sumOf { it.finished }}", color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
