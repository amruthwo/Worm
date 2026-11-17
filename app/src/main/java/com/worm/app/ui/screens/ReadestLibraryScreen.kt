package com.worm.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.worm.app.data.Book
import com.worm.app.sync.ProgressSync
import com.worm.app.sync.ReadestClient
import com.worm.app.ui.WormViewModel
import com.worm.app.ui.components.BookCover
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class Match { Exact, Linked, SameTitle, None }

private data class Row(val cloud: ReadestClient.CloudBook, val book: Book?, val match: Match)

private fun norm(s: String) = s.lowercase().replace(Regex("""\(.*?\)|\[.*?]"""), " ").replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

/** Your Readest cloud library, matched against what's in Worm. */
@Composable
fun ReadestLibraryScreen(vm: WormViewModel, onBack: () -> Unit, onOpenBook: (Book) -> Unit) {
    val context = LocalContext.current
    val books by vm.books.collectAsStateWithLifecycle()
    var cloud by remember { mutableStateOf<List<ReadestClient.CloudBook>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var linksVersion by remember { mutableIntStateOf(0) }
    var filter by remember { mutableIntStateOf(0) } // 0 all, 1 in Worm, 2 needs link, 3 not in Worm
    var files by remember { mutableStateOf<Map<String, ReadestClient.CloudFiles>>(emptyMap()) }
    var covers by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var downloading by remember { mutableStateOf(setOf<String>()) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    LaunchedEffect(Unit) {
        val r = ProgressSync.readest(context)
        if (r == null) { error = "Sign in to Readest in Settings › Sync first."; return@LaunchedEffect }
        runCatching { withContext(Dispatchers.IO) { r.library() } }
            .onSuccess { cloud = it }.onFailure { error = it.message }
        // Then the uploaded files: covers to show, books to download.
        runCatching {
            withContext(Dispatchers.IO) {
                val f = r.cloudFiles()
                files = f
                covers = r.downloadUrls(f.values.mapNotNull { it.cover }).let { urls -> f.mapNotNull { (h, cf) -> cf.cover?.let(urls::get)?.let { h to it } }.toMap() }
            }
        }.onFailure { android.util.Log.w("WormSync", "Readest files: ${it.message}") }
    }

    val rows = remember(cloud, books, linksVersion) {
        val links = ProgressSync.links(context)
        val linkedBack = links.entries.associate { (worm, readest) -> readest to worm }
        val ebooks = books.filter { !it.isAudio && !it.hidden }
        cloud.orEmpty().map { c ->
            val exact = ebooks.firstOrNull { it.id == c.hash }
            val linked = linkedBack[c.hash]?.let { id -> ebooks.firstOrNull { it.id == id } }
            val sameTitle = ebooks.firstOrNull { b ->
                norm(b.title) == norm(c.title) && (c.author.isBlank() || b.authors.isEmpty() ||
                    b.authors.any { a -> norm(c.author).contains(norm(a).substringAfterLast(' ')) })
            }
            when {
                exact != null -> Row(c, exact, Match.Exact)
                linked != null -> Row(c, linked, Match.Linked)
                sameTitle != null -> Row(c, sameTitle, Match.SameTitle)
                else -> Row(c, null, Match.None)
            }
        }
    }
    val shown = rows.filter {
        when (filter) { 1 -> it.match == Match.Exact || it.match == Match.Linked; 2 -> it.match == Match.SameTitle; 3 -> it.match == Match.None; else -> true }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Readest library") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        when {
            error != null -> Text(error!!, modifier = Modifier.padding(padding).padding(24.dp))
            cloud == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { LoadingIndicator() }
            else -> LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Text(
                        "${rows.size} books in your Readest cloud. Books sync when Worm has the very same file; for a different copy of the same book, tap Link.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
                        listOf(
                            "All" to rows.size,
                            "Syncing" to rows.count { it.match == Match.Exact || it.match == Match.Linked },
                            "Needs link" to rows.count { it.match == Match.SameTitle },
                            "Not in Worm" to rows.count { it.match == Match.None },
                        ).forEachIndexed { i, (label, n) -> FilterChip(filter == i, { filter = i }, label = { Text("$label $n") }) }
                    }
                }
                items(shown, key = { it.cloud.hash }) { row ->
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh, contentColor = MaterialTheme.colorScheme.onSurface),
                        onClick = { row.book?.let(onOpenBook) },
                    ) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.width(44.dp).aspectRatio(0.68f)) {
                                val cloudCover = covers[row.cloud.hash]
                                if (row.book != null) BookCover(row.book, Modifier.fillMaxSize(), corner = 8.dp, showKindBadge = false)
                                else if (cloudCover != null) coil3.compose.AsyncImage(cloudCover, null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)))
                                else Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.fillMaxSize()) {
                                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.CloudQueue, null) }
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(row.cloud.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    listOfNotNull(row.cloud.author.ifBlank { null }, row.cloud.format, files[row.cloud.hash]?.book?.let { "in your Readest cloud" }).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                val (label, color) = when (row.match) {
                                    Match.Exact -> "In Worm · syncing" to Color(0xFF2E9E6A)
                                    Match.Linked -> "Linked · syncing" to Color(0xFF2E9E6A)
                                    Match.SameTitle -> "Same book, different file" to Color(0xFFC08A1E)
                                    Match.None -> "Not in Worm" to Color(0xFF8A7F90)
                                }
                                Surface(shape = RoundedCornerShape(50), color = color, contentColor = Color.White, modifier = Modifier.padding(top = 4.dp)) {
                                    Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                                }
                            }
                            when (row.match) {
                                Match.SameTitle -> TextButton(onClick = { ProgressSync.link(context, row.book!!.id, row.cloud.hash); linksVersion++ }) { Text("Link") }
                                Match.Linked -> TextButton(onClick = { ProgressSync.link(context, row.book!!.id, null); linksVersion++ }) { Text("Unlink") }
                                Match.None -> {
                                    val key = files[row.cloud.hash]?.book
                                    if (row.cloud.hash in downloading) LoadingIndicator(Modifier.width(40.dp))
                                    else if (key != null) TextButton(onClick = {
                                        downloading = downloading + row.cloud.hash
                                        scope.launch {
                                            val dir = vm.sources.value.firstOrNull()?.path?.let { java.io.File(it) }
                                                ?: java.io.File(android.os.Environment.getExternalStorageDirectory(), "Books")
                                            runCatching {
                                                withContext(Dispatchers.IO) {
                                                    ProgressSync.readest(context)!!.downloadBook(key, dir, listOf(row.cloud.title, row.cloud.author).filter { it.isNotBlank() }.joinToString(" - "))
                                                }
                                            }.onSuccess { f ->
                                                vm.library.notifyMediaStore(f.absolutePath)
                                                if (vm.sources.value.none { f.absolutePath.startsWith(it.path) }) vm.addSource(dir.absolutePath) else vm.refresh()
                                                vm.say("Downloaded “${row.cloud.title}” 🪱")
                                            }.onFailure { vm.say(it.message ?: "Download failed") }
                                            downloading = downloading - row.cloud.hash
                                        }
                                    }) { Text("Download") }
                                }
                                else -> Unit
                            }
                        }
                    }
                }
            }
        }
    }
}
