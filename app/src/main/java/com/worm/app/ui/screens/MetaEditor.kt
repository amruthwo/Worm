package com.worm.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.ManageSearch
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Edit a book's details and cover by hand, or hand the job to magic match / online search. */
@Composable
fun EditDetailsSheet(vm: WormViewModel, bookId: String, onDismiss: () -> Unit) {
    val books by vm.books.collectAsStateWithLifecycle()
    val book = books.firstOrNull { it.id == bookId } ?: return
    val context = LocalContext.current
    var searching by remember { mutableStateOf(false) }

    var title by remember(bookId) { mutableStateOf(book.title) }
    var authors by remember(bookId) { mutableStateOf(book.authors.joinToString(", ")) }
    var narrators by remember(bookId) { mutableStateOf(book.narrators.joinToString(", ")) }
    var series by remember(bookId) { mutableStateOf(book.series.orEmpty()) }
    var index by remember(bookId) { mutableStateOf(book.seriesIndex?.toString()?.removeSuffix(".0").orEmpty()) }
    var description by remember(bookId) { mutableStateOf(book.description.orEmpty()) }
    // When a search result or magic match lands, refresh the fields from the updated book.
    LaunchedEffect(book.title, book.authors, book.narrators, book.series, book.seriesIndex, book.description) {
        title = book.title; authors = book.authors.joinToString(", "); narrators = book.narrators.joinToString(", ")
        series = book.series.orEmpty(); index = book.seriesIndex?.toString()?.removeSuffix(".0").orEmpty(); description = book.description.orEmpty()
    }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
        if (bytes != null) vm.setCoverFromBytes(book, bytes)
    }

    // Messages (magic match results etc.) show inside the sheet — the screen's own snackbar is hidden behind it.
    val sheetSnackbar = remember { androidx.compose.material3.SnackbarHostState() }
    LaunchedEffect(Unit) {
        vm.messages.collect {
            // Short, and at the top of the sheet, so it never sits on Save / Cancel / Reset.
            sheetSnackbar.currentSnackbarData?.dismiss()
            kotlinx.coroutines.withTimeoutOrNull(2_500) { sheetSnackbar.showSnackbar(it) }
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
      Box {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding().imePadding(),
        ) {
            Text("Edit details", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(110.dp).aspectRatio(if (book.isAudio) 1f else 0.68f).clip(RoundedCornerShape(16.dp)).clickable {
                    photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) { BookCover(book, Modifier.fillMaxWidth().aspectRatio(if (book.isAudio) 1f else 0.68f), corner = 16.dp, showKindBadge = false) }
                Spacer(Modifier.width(14.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = { vm.magicMatch(book) }) {
                        Icon(Icons.Rounded.AutoAwesome, null); Spacer(Modifier.width(8.dp)); Text("Magic match")
                    }
                    FilledTonalButton(onClick = { searching = true }) {
                        Icon(Icons.Rounded.ManageSearch, null); Spacer(Modifier.width(8.dp)); Text("Search online")
                    }
                    OutlinedButton(onClick = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                        Icon(Icons.Rounded.Image, null); Spacer(Modifier.width(8.dp)); Text("Cover from photos")
                    }
                }
            }
            Text(
                "Magic match fills in what's missing (cover, author, narrator, series, chapter names) from Audible, Google Books and Open Library. Your files are never changed.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 10.dp),
            )
            OutlinedTextField(title, { title = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(authors, { authors = it }, label = { Text("Author(s), comma separated") }, modifier = Modifier.fillMaxWidth())
            if (book.isAudio) OutlinedTextField(narrators, { narrators = it }, label = { Text("Narrator(s)") }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(series, { series = it }, label = { Text("Series") }, modifier = Modifier.weight(1f))
                OutlinedTextField(index, { index = it }, label = { Text("#") }, modifier = Modifier.width(80.dp), singleLine = true)
            }
            OutlinedTextField(description, { description = it }, label = { Text("Description") }, minLines = 3, maxLines = 8, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { vm.resetEdits(book) }) { Icon(Icons.Rounded.RestartAlt, null); Spacer(Modifier.width(6.dp)); Text("Reset") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Button(onClick = { vm.saveEdits(book, title, authors, narrators, series, index, description); onDismiss() }) { Text("Save") }
            }
            Spacer(Modifier.height(16.dp))
        }
        androidx.compose.material3.SnackbarHost(sheetSnackbar, Modifier.align(Alignment.TopCenter).padding(top = 8.dp))
      }
    }

    if (searching) MetaSearchSheet(vm, book, onDismiss = { searching = false })
}

/** Manual search: type a title/author, browse what the catalogues know, tap one to apply. */
@Composable
fun MetaSearchSheet(vm: WormViewModel, book: Book, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf(MetaSearch.cleanTitle(book.title)) }
    var author by remember { mutableStateOf(book.authors.firstOrNull().orEmpty()) }
    var results by remember { mutableStateOf<List<MetaHit>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<MetaHit?>(null) }

    fun run() {
        loading = true
        scope.launch {
            results = runCatching { MetaSearch.searchIO(title, author.ifBlank { null }, book.isAudio) }.getOrDefault(emptyList())
            loading = false
        }
    }
    LaunchedEffect(Unit) { run() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 16.dp).imePadding()) {
            Text("Find details & cover", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                author, { author = it }, label = { Text("Author (optional)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { run() }),
                trailingIcon = { androidx.compose.material3.IconButton(onClick = ::run) { Icon(Icons.Rounded.Search, "Search") } },
                modifier = Modifier.fillMaxWidth(),
            )
            if (loading) Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
            if (!loading && results?.isEmpty() == true) Text("Nothing found. Try fewer words.", modifier = Modifier.padding(16.dp))
        }
        LazyColumn(Modifier.navigationBarsPadding()) {
            items(results.orEmpty()) { hit ->
                Row(
                    Modifier.fillMaxWidth().clickable { preview = hit }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.size(64.dp)) {
                        if (hit.coverUrl != null) AsyncImage(hit.coverUrl, null, contentScale = ContentScale.Crop)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(hit.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(hit.authors.joinToString(", "), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(
                                hit.source, hit.year,
                                hit.narrators.takeIf { it.isNotEmpty() }?.let { "read by " + it.joinToString(", ") },
                                hit.series?.let { s -> s + (hit.seriesIndex?.let { " #${it.toString().removeSuffix(".0")}" } ?: "") },
                                hit.durationMs.takeIf { it > 0 }?.let(::formatHours),
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }

    preview?.let { hit ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text("Use this?") },
            text = {
                Column {
                    if (hit.coverUrl != null) AsyncImage(hit.coverUrl, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().height(200.dp))
                    Text(hit.title, style = MaterialTheme.typography.titleMedium)
                    Text(hit.authors.joinToString(", "))
                    hit.description?.let { Text(it, maxLines = 5, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    preview = null
                    scope.launch { vm.applyHit(book, hit, fillOnly = false); vm.say("Applied ✨"); onDismiss() }
                }) { Text("Everything") }
            },
            dismissButton = {
                Row {
                    if (hit.coverUrl != null) TextButton(onClick = {
                        preview = null
                        scope.launch {
                            val bytes = MetaSearch.download(hit.coverUrl)
                            if (bytes != null) withContext(Dispatchers.IO) { vm.library.setCustomCover(book.id, bytes) }
                            vm.say(if (bytes != null) "New cover ✨" else "Couldn't download that cover")
                            onDismiss()
                        }
                    }) { Text("Cover only") }
                    TextButton(onClick = { preview = null }) { Text("Back") }
                }
            },
        )
    }
}
