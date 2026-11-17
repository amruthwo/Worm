package com.worm.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.worm.app.data.Book
import com.worm.app.data.MetaHit
import com.worm.app.data.MetaSearch
import com.worm.app.ui.WormViewModel
import com.worm.app.ui.components.BookCover
import com.worm.app.util.formatHours
import kotlinx.coroutines.launch

/** What counts as "needs fixing". */
enum class Gap(val label: String, val missing: (Book) -> Boolean) {
    Cover("Cover", { it.coverPath == null }),
    Author("Author", { it.authors.isEmpty() }),
    Narrator("Narrator", { it.isAudio && it.narrators.isEmpty() }),
    Series("Series", { it.series == null }),
    Description("Description", { it.description.isNullOrBlank() }),
}

/**
 * Fix-up: walks through books with missing details one at a time. You can edit the search,
 * see every candidate with a confidence score, and pick (or skip). "Auto-apply sure matches"
 * handles the easy ones and stops on anything uncertain.
 */
@Composable
fun FixUpScreen(vm: WormViewModel, onBack: () -> Unit) {
    val all by vm.books.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var gaps by remember { mutableStateOf(setOf(Gap.Cover, Gap.Author, Gap.Narrator)) }
    var skipped by remember { mutableStateOf(setOf<String>()) }
    var done by remember { mutableStateOf(setOf<String>()) }
    var fixedCount by remember { mutableIntStateOf(0) }
    var auto by remember { mutableStateOf(false) }

    val queue = all.filter { b -> !b.hidden && b.id !in skipped && b.id !in done && gaps.any { it.missing(b) } }
    val current = queue.firstOrNull()

    var title by remember { mutableStateOf("") }
    var author by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Pair<MetaHit, Double>>?>(null) }
    var loading by remember { mutableStateOf(false) }

    fun search(b: Book, t: String?, a: String?) {
        loading = true; results = null
        scope.launch {
            results = runCatching { MetaSearch.candidatesIO(b, t, a) }.getOrDefault(emptyList())
            loading = false
        }
    }

    fun next(b: Book, applied: Boolean) {
        // Either way it leaves the queue: a match may legitimately leave a gap (no series exists).
        if (applied) { fixedCount++; done = done + b.id } else skipped = skipped + b.id
    }

    // New book at the front of the queue: prefill the search and run it.
    LaunchedEffect(current?.id) {
        val b = current ?: return@LaunchedEffect
        title = MetaSearch.queryVariants(b).firstOrNull()?.first ?: MetaSearch.cleanTitle(b.title)
        author = b.authors.firstOrNull().orEmpty()
        search(b, null, null)
    }
    // Auto mode: take confident matches without asking; stop and wait on anything unsure.
    LaunchedEffect(results, auto) {
        val b = current ?: return@LaunchedEffect
        val best = results?.firstOrNull() ?: return@LaunchedEffect
        val runnerUp = results!!.getOrNull(1)?.second ?: 0.0
        val sure = best.second >= 0.85 || (best.second >= MetaSearch.CONFIDENT && runnerUp < best.second - 0.05)
        if (auto && sure) {
            vm.applyHit(b, best.first, fillOnly = true)
            next(b, true)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Fix-up") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).imePadding(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text("Look for books missing:", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Gap.entries.forEach { g ->
                        FilterChip(g in gaps, { gaps = if (g in gaps) gaps - g else gaps + g }, label = { Text(g.label) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { auto = !auto }) {
                    Column(Modifier.weight(1f)) {
                        Text("Auto-apply sure matches", style = MaterialTheme.typography.bodyLarge)
                        Text("Stops and asks when it isn't sure.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(auto, { auto = it })
                }
                val total = queue.size + fixedCount
                Text("$fixedCount fixed · ${queue.size} to go · ${skipped.size} skipped", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                if (total > 0) LinearWavyProgressIndicator(progress = { fixedCount.toFloat() / total }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            }
            if (current == null) {
                item {
                    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("All tidy! 🪱✨", style = MaterialTheme.typography.headlineSmall)
                        if (skipped.isNotEmpty()) TextButton(onClick = { skipped = emptySet() }) { Text("Revisit ${skipped.size} skipped") }
                    }
                }
                return@LazyColumn
            }
            item(key = "current") {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.width(70.dp).aspectRatio(if (current.isAudio) 1f else 0.68f)) { BookCover(current, Modifier.fillMaxSize(), corner = 10.dp, showKindBadge = false) }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(current.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(current.author, style = MaterialTheme.typography.bodySmall)
                                Text(
                                    "Missing: " + Gap.entries.filter { it.missing(current) }.joinToString { it.label.lowercase() },
                                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                                )
                                Text(java.io.File(current.path).name, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        OutlinedTextField(title, { title = it }, label = { Text("Search title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(
                            author, { author = it }, label = { Text("Author (optional)") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { search(current, title, author) }),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { search(current, title, author) }) { Icon(Icons.Rounded.Search, null); Spacer(Modifier.width(6.dp)); Text("Search") }
                            OutlinedButton(onClick = { next(current, false) }) { Text("Skip") }
                        }
                    }
                }
            }
            if (loading) item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { LoadingIndicator() } }
            if (!loading && results?.isEmpty() == true) item { Text("Nothing found. Try a shorter title, or drop the author.", modifier = Modifier.padding(8.dp)) }
            items(results.orEmpty(), key = { it.first.source + it.first.title + it.first.asin + it.first.coverUrl }) { (hit, score) ->
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh, contentColor = MaterialTheme.colorScheme.onSurface),
                ) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.size(72.dp)) {
                            if (hit.coverUrl != null) AsyncImage(hit.coverUrl, null, contentScale = ContentScale.Crop)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ConfidenceBadge(score)
                                Spacer(Modifier.width(6.dp))
                                Text(hit.source, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(hit.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(hit.authors.joinToString(", "), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(
                                    hit.narrators.takeIf { it.isNotEmpty() }?.let { "read by " + it.joinToString(", ") },
                                    hit.series?.let { s -> s + (hit.seriesIndex?.let { " #${it.toString().removeSuffix(".0")}" } ?: "") },
                                    hit.durationMs.takeIf { it > 0 }?.let(::formatHours), hit.year,
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                            Row {
                                TextButton(onClick = { scope.launch { vm.applyHit(current, hit, fillOnly = true); next(current, true) } }) { Text("Fill gaps") }
                                TextButton(onClick = { scope.launch { vm.applyHit(current, hit, fillOnly = false); next(current, true) } }) { Text("Use all") }
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(40.dp)) }
        }
    }
}

@Composable
private fun ConfidenceBadge(score: Double) {
    val (label, color) = when {
        score >= MetaSearch.CONFIDENT -> "Likely" to Color(0xFF2E9E6A)
        score >= MetaSearch.MAYBE -> "Maybe" to Color(0xFFC08A1E)
        else -> "Long shot" to Color(0xFF8A7F90)
    }
    Surface(shape = RoundedCornerShape(50), color = color, contentColor = Color.White) {
        Text("$label ${(score * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}
