package com.worm.app.ui.screens

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CallMerge
import androidx.compose.material.icons.rounded.CallSplit
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.worm.app.data.Book
import com.worm.app.data.ReadingProgress
import com.worm.app.data.Shelf
import com.worm.app.ui.WormViewModel
import com.worm.app.ui.components.BookCover
import com.worm.app.util.formatBytes
import com.worm.app.util.formatHours
import com.worm.app.util.plural
import java.io.File

@Composable
fun BookSheet(
    vm: WormViewModel,
    book: Book,
    progress: ReadingProgress?,
    shelves: List<Shelf>,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onBurrow: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onOpenFurthest: () -> Unit,
) {
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(96.dp).aspectRatio(if (book.isAudio) 1f else 0.68f)) {
                    BookCover(book, Modifier.fillMaxWidth().aspectRatio(if (book.isAudio) 1f else 0.68f), corner = 16.dp)
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(book.title, style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text(book.author, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    book.narrator?.let { Text("Read by $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    book.series?.let { s ->
                        Text(s + (book.seriesIndex?.let { " · #${it.toString().removeSuffix(".0")}" } ?: ""), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                buildList {
                    add(book.format.uppercase())
                    add(formatBytes(book.sizeBytes))
                    if (book.isAudio && book.durationMs > 0) add(formatHours(book.durationMs))
                    if (book.tracks.size > 1) add(plural(book.tracks.size, "file"))
                    progress?.let { if (it.finished) add("Finished") else if (it.fraction > 0) add("${(it.fraction * 100).toInt()}% done") }
                    progress?.timeSpentSec?.takeIf { it > 60 }?.let { add("${formatHours(it * 1000)} spent") }
                }.joinToString(" · "),
                style = MaterialTheme.typography.labelLarge,
            )
            book.description?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 6, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = onOpen, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Icon(if (book.isAudio) Icons.Rounded.Headphones else Icons.Rounded.AutoStories, null)
                Spacer(Modifier.width(10.dp))
                Text(
                    when {
                        progress?.finished == true -> if (book.isAudio) "Listen again" else "Read again"
                        (progress?.fraction ?: 0f) > 0f -> if (book.isAudio) "Keep listening" else "Keep reading"
                        else -> if (book.isAudio) "Start listening" else "Start reading"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            if (shelves.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text("Shelves", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    shelves.forEach { s ->
                        FilterChip(
                            selected = book.id in s.bookIds,
                            onClick = { vm.toggleOnShelf(s.id, book.id) },
                            label = { Text(listOfNotNull(s.emoji, s.name).joinToString(" ")) },
                            leadingIcon = if (s.hidden) ({ Icon(Icons.Rounded.Lock, null, Modifier.size(16.dp)) }) else null,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Action(Icons.Rounded.AutoAwesome, if (book.coverPath == null || book.authors.isEmpty()) "Magic match (fill in cover & details)" else "Magic match") { vm.magicMatch(book) }
            Action(Icons.Rounded.Edit, "Edit details & cover") { onEdit() }
            if (!book.isAudio && !book.hidden && vm.syncAvailable()) {
                Action(Icons.Rounded.FastForward, "Sync: open at furthest read") { onOpenFurthest() }
                Action(Icons.Rounded.CloudDownload, "Sync: pull newest spot") { vm.pullSpot(book); onDismiss() }
                Action(Icons.Rounded.CloudUpload, "Sync: push my spot") { vm.pushSpot(book); onDismiss() }
            }
            if (!book.isAudio && !book.hidden && book.format.lowercase() in setOf("epub", "mobi", "azw3", "azw", "fb2", "fbz", "cbz", "pdf", "txt") &&
                com.worm.app.sync.ProgressSync.readest(androidx.compose.ui.platform.LocalContext.current) != null
            ) {
                Action(Icons.Rounded.CloudUpload, "Upload to Readest cloud") { vm.uploadToReadest(book); onDismiss() }
            }
            val finished = progress?.finished == true
            Action(Icons.Rounded.CheckCircle, if (finished) "Mark as not finished" else "Mark as finished") { vm.setFinished(book, !finished); onDismiss() }
            if (progress?.showInResume == true) Action(Icons.Rounded.RemoveCircleOutline, "Remove from Resume") { vm.dismissFromResume(book); onDismiss() }
            if (progress != null) Action(Icons.Rounded.RestartAlt, "Start over (forget progress)") { vm.resetProgress(book); onDismiss() }
            if (book.isAudio) {
                val lib = vm.library
                val folder = lib.bookFolder(book)
                val parent = folder.parentFile
                when {
                    folder.absolutePath in lib.prefs.splitFolders ->
                        Action(Icons.Rounded.CallMerge, "Undo split (“${folder.name}” is one book again)") { lib.setSplit(folder, false); onDismiss() }
                    folder.absolutePath in lib.prefs.mergeFolders ->
                        Action(Icons.Rounded.CallSplit, "Undo merge of “${folder.name}”") { lib.setMerge(folder, false); onDismiss() }
                    book.tracks.size > 1 ->
                        Action(Icons.Rounded.CallSplit, "Split: each file in “${folder.name}” is its own book") { lib.setSplit(folder, true); onDismiss() }
                }
                if (folder.absolutePath !in lib.prefs.mergeFolders && parent != null && lib.sources.value.none { java.io.File(it.path) == folder })
                    Action(Icons.Rounded.CallMerge, "Merge: everything in “${folder.name}” (and subfolders) is one book") { lib.setMerge(folder, true); onDismiss() }
            }
            if (book.isAudio && book.format != "m4b") Action(Icons.Rounded.Headphones, "Silk Spinner: spin into one .m4b") {
                vm.spin(listOf(book), null, deleteOriginals = false); onDismiss()
            }
            if (!book.isAudio && book.format != "epub") Action(Icons.Rounded.AutoStories, "Metamorphosis: transform into EPUB") {
                vm.transform(listOf(book), deleteOriginals = false); onDismiss()
            }
            if (book.hidden) Action(Icons.Rounded.LockOpen, "Let out of the Burrow") { onBurrow(false) }
            else Action(Icons.Rounded.Lock, "Hide in the Burrow") { onBurrow(true) }
            if (!book.hidden && book.file.isFile) Action(Icons.Rounded.Share, "Share file") {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", File(book.path))
                context.startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).setType("*/*").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                        "Share ${book.title}",
                    ),
                )
            }
            Action(Icons.Rounded.Delete, "Delete from device", tint = MaterialTheme.colorScheme.error) { confirmDelete = true }
            Text(book.path, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), modifier = Modifier.padding(vertical = 12.dp))
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete “${book.title}”?") },
            text = { Text("This removes ${if (book.tracks.size > 1) "the whole folder (${plural(book.tracks.size, "file")})" else "the file"} from your device. It can't be undone.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDismiss(); vm.delete(book) }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep it") } },
        )
    }
}

@Composable
private fun Action(icon: ImageVector, label: String, tint: Color = MaterialTheme.colorScheme.onSurface, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label, color = tint) },
        leadingContent = { Icon(icon, null, tint = tint) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick),
    )
}

private val ShelfEmoji = listOf(null, "📚", "🎧", "🪱", "✨", "🔥", "🌙", "🧪", "🗡️", "🐉", "🕵️", "💜", "🌿", "🚀")

@Composable
fun ShelfDialog(
    initial: Shelf?,
    onDismiss: () -> Unit,
    onSave: (name: String, emoji: String?, hidden: Boolean) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var emoji by remember { mutableStateOf(initial?.emoji) }
    var hidden by remember { mutableStateOf(initial?.hidden ?: false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New shelf" else "Edit shelf") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ShelfEmoji.forEach { e ->
                        FilterChip(selected = emoji == e, onClick = { emoji = e }, label = { Text(e ?: "none") })
                    }
                }
                Spacer(Modifier.height(8.dp))
                com.worm.app.reader.SwitchRow("Secret shelf (only shows while the Burrow is open)", hidden) { hidden = it }
                Text(
                    "Shelves can mix books and audiobooks. Long-press any book to put it on a shelf.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name.trim(), emoji, hidden) }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = {
            Row {
                onDelete?.let { TextButton(onClick = it) { Text("Delete shelf", color = MaterialTheme.colorScheme.error) } }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
