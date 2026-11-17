package com.worm.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.worm.app.convert.Metamorphosis
import com.worm.app.convert.SilkSpinner
import com.worm.app.data.Book
import com.worm.app.ui.WormViewModel
import com.worm.app.ui.components.BookCover
import com.worm.app.util.formatBytes
import com.worm.app.util.formatHours
import com.worm.app.util.plural
import java.io.File

/** Tools: Fix-up, Silk Spinner (→ .m4b) and Metamorphosis (→ EPUB). */
@Composable
fun ToolsScreen(vm: WormViewModel, onBack: () -> Unit, onOpenBook: (Book) -> Unit, onFixUp: () -> Unit) {
    val context = LocalContext.current
    val all by vm.books.collectAsStateWithLifecycle()
    val unlocked by vm.unlocked.collectAsStateWithLifecycle()
    val matching by vm.matching.collectAsStateWithLifecycle()
    val transforming by vm.transforming.collectAsStateWithLifecycle()
    val spinInfo by remember { WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(SilkSpinner.WORK) }
        .collectAsStateWithLifecycle(emptyList())
    val spinning = spinInfo.firstOrNull { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }

    val books = all.filter { !it.hidden || unlocked }
    val ebooks = books.filter { !it.isAudio && it.format != "epub" && File(it.path).extension.lowercase() in Metamorphosis.ALL }.sortedBy { it.sortTitle }
    val audio = books.filter { it.isAudio && it.format != "m4b" }.sortedByDescending { it.tracks.size }
    val missing = books.count { !it.hidden && (it.coverPath == null || it.authors.isEmpty()) }
    var tab by remember { mutableIntStateOf(if (audio.size >= ebooks.size) 0 else 1) }
    val list = if (tab == 0) audio else ebooks
    var selected by remember { mutableStateOf(setOf<String>()) }
    val chosen = list.filter { it.id in selected }
    var confirm by remember { mutableStateOf<String?>(null) } // "spin" | "transform" | "file"
    var pickedFile by remember { mutableStateOf<File?>(null) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val f = resolvePickedFile(context, uri, vm.sources.value.firstOrNull()?.path)
        if (f == null || !Metamorphosis.canConvert(f)) vm.say("Worm can't convert that kind of file yet")
        else { pickedFile = f; confirm = "file" }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tools") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            )
        },
        floatingActionButton = {
            AnimatedVisibility(chosen.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { confirm = if (tab == 0) "spin" else "transform" },
                    icon = { Icon(if (tab == 0) Icons.Rounded.Headphones else Icons.Rounded.AutoStories, null) },
                    text = { Text(if (tab == 0) "Spin ${chosen.size} into .m4b" else "Transform ${chosen.size} into EPUB") },
                    modifier = Modifier.navigationBarsPadding(),
                )
            }
        },
    ) { padding ->
        // Wide screens: keep the cards a readable width, centred.
        val side = ((androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp - 760) / 2).coerceAtLeast(16).dp
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = side, end = side, top = 8.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                ToolCard(
                    Icons.Rounded.AutoAwesome, "Magic match",
                    if (missing == 0) "Every book has a cover and an author."
                    else "${plural(missing, "book")} missing a cover or author. Fix-up walks through them with you; Auto-fill takes only confident matches.",
                ) {
                    val m = matching
                    if (m != null) {
                        val now by vm.matchingTitle.collectAsStateWithLifecycle()
                        Text("Matching ${m.first + 1} of ${m.second}: ${now.orEmpty()}", style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        LinearWavyProgressIndicator(progress = { (m.first + 1f) / m.second }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                    } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onFixUp) { Text("Fix-up, one by one") }
                        if (missing > 0) OutlinedButton(onClick = { vm.magicMatchMissing() }) { Text("Auto-fill all") }
                    }
                }
            }
            item {
                ToolCard(
                    Icons.Rounded.Headphones, "Silk Spinner · MP3 → M4B",
                    "Spins a folder of tracks (or one long file) into a single .m4b with chapters, cover and tags. Runs in the background; your place, bookmarks and shelves move to the new file.",
                ) {
                    spinning?.let { w ->
                        val p = w.progress
                        val title = p.getString("title")
                        if (title != null) {
                            Text("Spinning ${p.getInt("index", 1)} of ${p.getInt("total", 1)}: $title", style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            LinearWavyProgressIndicator(progress = { p.getFloat("fraction", 0f) }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                        } else Text("Getting ready…", style = MaterialTheme.typography.labelLarge)
                        TextButton(onClick = { SilkSpinner.cancel(context) }) { Text("Stop") }
                    }
                }
            }
            item {
                ToolCard(
                    Icons.Rounded.AutoStories, "Metamorphosis · → EPUB",
                    "MOBI, AZW3, FB2, CBZ, HTML, TXT, Markdown and Word files become EPUB, right here on your phone. The EPUB lands next to the original.",
                ) {
                    transforming?.let { t ->
                        Text("Transforming ${t.index} of ${t.total}: ${t.title}", style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        LinearWavyProgressIndicator(progress = { t.fraction }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                        Text("Keep Worm open until it finishes.", style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton(onClick = { filePicker.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Rounded.FileOpen, null); Spacer(Modifier.width(8.dp)); Text("Convert a file…")
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(tab == 0, { tab = 0; selected = emptySet() }, label = { Text("Audiobooks not M4B (${audio.size})") })
                    FilterChip(tab == 1, { tab = 1; selected = emptySet() }, label = { Text("Books not EPUB (${ebooks.size})") })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${plural(list.size, "item")} · ${formatBytes(list.sumOf { it.sizeBytes })}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = { selected = if (chosen.size == list.size) emptySet() else list.map { it.id }.toSet() }) {
                        Text(if (chosen.size == list.size && list.isNotEmpty()) "Select none" else "Select all")
                    }
                }
            }
            items(list, key = { it.id }) { b ->
                val on = b.id in selected
                Card(
                    onClick = { selected = if (on) selected - b.id else selected + b.id },
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                    ),
                ) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(on, { selected = if (on) selected - b.id else selected + b.id })
                        Box(Modifier.width(48.dp).aspectRatio(if (b.isAudio) 1f else 0.68f)) { BookCover(b, Modifier.fillMaxSize(), corner = 8.dp, showKindBadge = false) }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(b.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(
                                    b.format.uppercase(),
                                    b.tracks.size.takeIf { it > 1 }?.let { plural(it, "file") },
                                    formatBytes(b.sizeBytes),
                                    b.durationMs.takeIf { it > 0 }?.let(::formatHours),
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    when (confirm) {
        "spin" -> SpinOptions(chosen, onDismiss = { confirm = null }) { bitrate, delete ->
            confirm = null; vm.spin(chosen, bitrate, delete); selected = emptySet()
        }
        "transform" -> TransformOptions(plural(chosen.size, "book"), onDismiss = { confirm = null }) { delete ->
            confirm = null; vm.transform(chosen, deleteOriginals = delete); selected = emptySet()
        }
        "file" -> pickedFile?.let { f ->
            TransformOptions("“${f.name}”", onDismiss = { confirm = null; pickedFile = null }) { delete ->
                confirm = null; vm.transform(emptyList(), listOf(f), delete); pickedFile = null
            }
        }
    }
}

@Composable
private fun SpinOptions(books: List<Book>, onDismiss: () -> Unit, onGo: (Int?, Boolean) -> Unit) {
    var bitrate by remember { mutableStateOf<Int?>(null) }
    var delete by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Headphones, null) },
        title = { Text("Spin ${plural(books.size, "audiobook")}") },
        text = {
            Column {
                Text("Quality", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(null to "Match source", 64_000 to "64 kbps", 96_000 to "96 kbps", 128_000 to "128 kbps").forEach { (v, label) ->
                        FilterChip(bitrate == v, { bitrate = v }, label = { Text(label) })
                    }
                }
                Text("64 kbps is plenty for most narration and keeps files small.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                DeleteRow(delete) { delete = it }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Each book becomes one .m4b next to its folder. It can take a few minutes per book; you can leave Worm while it works.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { Button(onClick = { onGo(bitrate, delete) }) { Text("Spin") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun TransformOptions(what: String, onDismiss: () -> Unit, onGo: (Boolean) -> Unit) {
    var delete by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.AutoStories, null) },
        title = { Text("Transform $what into EPUB") },
        text = {
            Column {
                DeleteRow(delete) { delete = it }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Your reading percentage, edits and shelves move to the EPUB. Highlights and bookmarks from the old file can't be carried over.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { Button(onClick = { onGo(delete) }) { Text("Transform") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun DeleteRow(delete: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Remove the originals afterwards", style = MaterialTheme.typography.bodyLarge)
            Text("Only after the new file has been checked.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(delete, onChange)
    }
}

@Composable
private fun ToolCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String, actions: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null); Spacer(Modifier.width(10.dp)); Text(title, style = MaterialTheme.typography.titleMedium)
            }
            Text(body, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
            actions()
        }
    }
}

/**
 * A picked document as a real file. Files on shared storage map straight to their path (we have
 * All files access); anything else is copied into the first library folder so the EPUB has a home.
 */
private fun resolvePickedFile(context: android.content.Context, uri: android.net.Uri, fallbackDir: String?): File? {
    val docId = runCatching { android.provider.DocumentsContract.getDocumentId(uri) }.getOrNull()
    if (docId != null && ':' in docId) {
        val (volume, rel) = docId.split(':', limit = 2)
        val base = when {
            volume.equals("primary", true) -> android.os.Environment.getExternalStorageDirectory().absolutePath
            volume == "raw" -> return File(rel).takeIf { it.isFile }
            else -> "/storage/$volume"
        }
        File(base, rel).takeIf { it.isFile }?.let { return it }
    }
    val name = runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: return null
    val dir = File(fallbackDir ?: File(android.os.Environment.getExternalStorageDirectory(), "Books").path).apply { mkdirs() }
    val dest = File(dir, name)
    if (!dest.exists()) context.contentResolver.openInputStream(uri)?.use { input -> dest.outputStream().use { input.copyTo(it) } }
    return dest.takeIf { it.isFile }
}
