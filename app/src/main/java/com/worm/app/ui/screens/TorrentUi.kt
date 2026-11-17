package com.worm.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.worm.app.torrent.PendingTorrent
import com.worm.app.torrent.TorrentEngine
import com.worm.app.torrent.TorrentItem
import com.worm.app.util.formatBytes

/** Shown anywhere in the app once a torrent's details arrive: pick files, choose whether to keep sharing. */
@Composable
fun TorrentPicker() {
    val context = LocalContext.current
    val pending by TorrentEngine.pending.collectAsStateWithLifecycle()
    val p: PendingTorrent = pending ?: return
    var chosen by remember(p.hash) { mutableStateOf(p.files.filter { it.bookish }.map { it.index }.toSet().ifEmpty { p.files.map { it.index }.toSet() }) }
    var seed by remember(p.hash) { mutableStateOf(false) }
    val size = p.files.filter { it.index in chosen }.sumOf { it.size }
    AlertDialog(
        onDismissRequest = { TorrentEngine.cancelPending() },
        title = { Text(p.name) },
        text = {
            Column {
                Text("${chosen.size} of ${p.files.size} files · ${formatBytes(size)}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text("Books and audio are picked for you.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(Modifier.heightIn(max = 320.dp).padding(vertical = 6.dp)) {
                    items(p.files, key = { it.index }) { f ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable { chosen = if (f.index in chosen) chosen - f.index else chosen + f.index },
                        ) {
                            Checkbox(f.index in chosen, { chosen = if (it) chosen + f.index else chosen - f.index })
                            Column(Modifier.weight(1f)) {
                                Text(f.path.substringAfterLast('/'), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(formatBytes(f.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { seed = !seed }) {
                    Column(Modifier.weight(1f)) {
                        Text("Keep sharing after it finishes", style = MaterialTheme.typography.bodyMedium)
                        Text("Off: Worm stops as soon as your files are done.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(seed, { seed = it })
                }
            }
        },
        confirmButton = { Button(enabled = chosen.isNotEmpty(), onClick = { TorrentEngine.start(context, p, chosen, seed) }) { Text("Download") } },
        dismissButton = { TextButton(onClick = { TorrentEngine.cancelPending() }) { Text("Cancel") } },
    )
}

/** The torrents part of the Downloads screen. */
@Composable
fun TorrentRows() {
    val context = LocalContext.current
    val items by TorrentEngine.items.collectAsStateWithLifecycle()
    val status by TorrentEngine.status.collectAsStateWithLifecycle()
    var removing by remember { mutableStateOf<TorrentItem?>(null) }
    if (items.isEmpty() && status == null) return
    Text("Torrents", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
    status?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium)
        if (it.startsWith("Fetching")) LinearWavyProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 6.dp))
    }
    items.forEach { t ->
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh, contentColor = MaterialTheme.colorScheme.onSurface),
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        ) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            when (t.state) {
                                "metadata" -> "Getting details…"
                                "checking" -> "Checking files…"
                                "paused" -> "Paused · ${(t.progress * 100).toInt()}%"
                                "seeding" -> "In your library ✓ · sharing ↑ ${formatBytes(t.upBps.toLong())}/s · ${t.peers} peers"
                                "done" -> "In your library ✓"
                                else -> "${(t.progress * 100).toInt()}% · ↓ ${formatBytes(t.downBps.toLong())}/s · ${t.peers} peers · ${formatBytes(t.done)} of ${formatBytes(t.total)}"
                            },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    when (t.state) {
                        "downloading", "metadata", "checking" -> IconButton(onClick = { TorrentEngine.pause(t.hash) }) { Icon(Icons.Rounded.Pause, "Pause") }
                        "paused" -> IconButton(onClick = { TorrentEngine.resume(context, t.hash) }) { Icon(Icons.Rounded.PlayArrow, "Resume") }
                    }
                    IconButton(onClick = { removing = t }) { Icon(Icons.Rounded.Close, "Remove") }
                }
                when (t.state) {
                    "downloading" -> LinearWavyProgressIndicator(progress = { t.progress }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                    "paused" -> LinearProgressIndicator(progress = { t.progress }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp), drawStopIndicator = {})
                }
                if (t.state == "seeding" || t.state == "done") Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Keep sharing", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Switch(t.seed, { TorrentEngine.setSeed(context, t.hash, it) })
                }
            }
        }
    }
    removing?.let { t ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove “${t.name}”?") },
            text = { Text(if (t.state == "done" || t.state == "seeding") "Keep the books in your library, or delete them too?" else "Stop downloading. Keep what's been downloaded so far, or delete it?") },
            confirmButton = { TextButton(onClick = { TorrentEngine.remove(context, t.hash, deleteFiles = false); removing = null }) { Text("Remove, keep files") } },
            dismissButton = { TextButton(onClick = { TorrentEngine.remove(context, t.hash, deleteFiles = true); removing = null }) { Text("Delete files too", color = MaterialTheme.colorScheme.error) } },
        )
    }
}
