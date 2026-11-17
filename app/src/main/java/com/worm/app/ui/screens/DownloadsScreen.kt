package com.worm.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.worm.app.download.DlState
import com.worm.app.download.Downloads
import com.worm.app.ui.components.WigglingWorm
import com.worm.app.util.formatBytes

@Composable
fun DownloadsScreen(onBack: () -> Unit, onOpenBrowser: () -> Unit) {
    val context = LocalContext.current
    remember { Downloads.load(context) }
    val items by Downloads.items.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Downloads") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = {
                    if (items.any { it.state == DlState.Done }) TextButton(onClick = { Downloads.clearFinished(context) }) { Text("Clear finished") }
                    IconButton(onClick = onOpenBrowser) { Icon(Icons.Rounded.Language, "Browser") }
                },
            )
        },
    ) { padding ->
        val torrents by com.worm.app.torrent.TorrentEngine.items.collectAsStateWithLifecycle()
        val tStatus by com.worm.app.torrent.TorrentEngine.status.collectAsStateWithLifecycle()
        if (items.isEmpty() && torrents.isEmpty() && tStatus == null) {
            Column(Modifier.fillMaxSize().padding(padding).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                WigglingWorm(Modifier.size(180.dp, 100.dp))
                Text("Nothing downloading", style = MaterialTheme.typography.headlineSmall)
                Text("Find books in the browser; tap a download link and it lands here, then in your library.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onOpenBrowser) { Text("Open the browser") }
            }
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { TorrentRows() }
            items(items.sortedByDescending { it.addedAt }, key = { it.id }) { d ->
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh, contentColor = MaterialTheme.colorScheme.onSurface),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(d.fileName, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    when (d.state) {
                                        DlState.Queued -> "Waiting…"
                                        DlState.Running -> formatBytes(d.done) + (if (d.total > 0) " of ${formatBytes(d.total)}" else "")
                                        DlState.Paused -> "Paused · ${formatBytes(d.done)}" + (if (d.total > 0) " of ${formatBytes(d.total)}" else "")
                                        DlState.Done -> "In your library ✓" + (if (d.total > 0) " · ${formatBytes(d.total)}" else "")
                                        DlState.Failed -> "Failed: ${d.error.orEmpty()}"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (d.state == DlState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            when (d.state) {
                                DlState.Running, DlState.Queued -> IconButton(onClick = { Downloads.pause(context, d.id) }) { Icon(Icons.Rounded.Pause, "Pause") }
                                DlState.Paused -> IconButton(onClick = { Downloads.resume(context, d.id) }) { Icon(Icons.Rounded.PlayArrow, "Resume") }
                                DlState.Failed -> IconButton(onClick = { Downloads.resume(context, d.id) }) { Icon(Icons.Rounded.Refresh, "Retry") }
                                DlState.Done -> Unit
                            }
                            IconButton(onClick = { Downloads.remove(context, d.id) }) { Icon(Icons.Rounded.Close, if (d.state == DlState.Done) "Remove from list" else "Cancel") }
                        }
                        val f = if (d.total > 0) (d.done.toFloat() / d.total).coerceIn(0f, 1f) else null
                        when {
                            d.state == DlState.Running && f != null -> LinearWavyProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                            d.state == DlState.Running -> LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                            d.state == DlState.Paused && f != null -> LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp), drawStopIndicator = {})
                        }
                    }
                }
            }
            item { Spacer(Modifier.size(24.dp)) }
        }
    }
}
