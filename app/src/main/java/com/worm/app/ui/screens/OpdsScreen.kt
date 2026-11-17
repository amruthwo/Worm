package com.worm.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import com.worm.app.download.Downloads
import com.worm.app.opds.Catalog
import com.worm.app.opds.Entry
import com.worm.app.opds.Feed
import com.worm.app.opds.Opds
import com.worm.app.ui.WormViewModel
import com.worm.app.util.formatBytes
import kotlinx.coroutines.launch

/** OPDS catalogues: your own server (Calibre-Web, Kavita, Komga, Audiobookshelf…) or public ones. */
@Composable
fun OpdsScreen(vm: WormViewModel, onBack: () -> Unit, onOpenDownloads: () -> Unit) {
    val context = LocalContext.current
    var catalogs by remember { mutableStateOf(Opds.catalogs(context)) }
    var current by remember { mutableStateOf<Catalog?>(null) }
    var adding by remember { mutableStateOf(false) }

    val cat = current
    if (cat != null) {
        CatalogBrowser(vm, cat, onExit = { current = null }, onOpenDownloads = onOpenDownloads)
        return
    }
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Catalogs") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            )
        },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = { adding = true }, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("Add catalog") }) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text(
                    "OPDS catalogs let you browse and download from book servers: your own Calibre-Web, Kavita, Komga or Audiobookshelf, or public libraries.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (catalogs.isEmpty()) item { Text("No catalogs yet. Tap “Add catalog”.", modifier = Modifier.padding(vertical = 16.dp)) }
            items(catalogs, key = { it.id }) { c ->
                Card(
                    onClick = { current = c }, shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh, contentColor = MaterialTheme.colorScheme.onSurface),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(c.name, style = MaterialTheme.typography.titleMedium)
                            Text(c.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (c.user != null) Text("Signed in as ${c.user}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(onClick = { Opds.remove(context, c.id); catalogs = Opds.catalogs(context) }) { Icon(Icons.Rounded.Delete, "Remove catalog") }
                    }
                }
            }
        }
    }
    if (adding) AddCatalogDialog(onDismiss = { adding = false }) { name, url, user, pass ->
        Opds.add(context, name, url, user, pass); catalogs = Opds.catalogs(context); adding = false
    }
}

@Composable
private fun AddCatalogDialog(onDismiss: () -> Unit, onAdd: (String, String, String?, String?) -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a catalog") },
        text = {
            Column {
                Text("Public", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Opds.presets.forEach { (n, u) -> AssistChip(onClick = { name = n; url = u }, label = { Text(n) }) }
                }
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    url, { url = it.trim() }, label = { Text("Catalog address (OPDS)") }, singleLine = true,
                    placeholder = { Text("https://my-server/opds") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(),
                )
                Text("Private server? Add its login:", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                OutlinedTextField(user, { user = it }, label = { Text("Username (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(pass, { pass = it }, label = { Text("Password (optional)") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(enabled = url.startsWith("http"), onClick = { onAdd(name, url, user, pass) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun CatalogBrowser(vm: WormViewModel, cat: Catalog, onExit: () -> Unit, onOpenDownloads: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val stack = remember { mutableStateListOf(cat.url) }
    var feed by remember { mutableStateOf<Feed?>(null) }
    val more = remember { mutableStateListOf<Entry>() }
    var next by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<Entry?>(null) }

    LaunchedEffect(stack.size, stack.lastOrNull()) {
        loading = true; error = null; more.clear()
        runCatching { Opds.load(stack.last(), cat.auth) }
            .onSuccess { feed = it; next = it.next }
            .onFailure { error = it.message ?: "Couldn't load the catalog" }
        loading = false
    }
    BackHandler { if (stack.size > 1) stack.removeAt(stack.lastIndex) else onExit() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(feed?.title?.ifBlank { null } ?: cat.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = { if (stack.size > 1) stack.removeAt(stack.lastIndex) else onExit() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = { IconButton(onClick = onOpenDownloads) { Icon(Icons.Rounded.Download, "Downloads") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            feed?.search?.let { template ->
                OutlinedTextField(
                    query, { query = it }, placeholder = { Text("Search ${cat.name}") }, singleLine = true, shape = RoundedCornerShape(50),
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank()) stack.add(Opds.searchUrl(template, query)) }),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            when {
                loading && feed == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
                error != null -> Column(Modifier.padding(24.dp)) {
                    Text(error!!, style = MaterialTheme.typography.bodyLarge)
                    TextButton(onClick = { stack.add(stack.removeAt(stack.lastIndex)) }) { Text("Try again") }
                }
                else -> {
                    val entries = feed?.entries.orEmpty() + more
                    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (loading) item { LoadingIndicator(Modifier.size(32.dp)) }
                        if (entries.isEmpty()) item { Text("Nothing here.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        items(entries, key = { it.id + it.feed + it.title }) { e ->
                            EntryRow(e, cat.auth, onClick = { if (e.feed != null && e.acquisitions.isEmpty()) stack.add(e.feed) else picked = e })
                        }
                        next?.let { n ->
                            item {
                                OutlinedButton(onClick = {
                                    scope.launch {
                                        runCatching { Opds.load(n, cat.auth) }.onSuccess { more.addAll(it.entries); next = it.next }.onFailure { vm.say(it.message ?: "Couldn't load more") }
                                    }
                                }, modifier = Modifier.fillMaxWidth()) { Text("Load more") }
                            }
                        }
                    }
                }
            }
        }
    }

    picked?.let { e ->
        AlertDialog(
            onDismissRequest = { picked = null },
            title = { Text(e.title) },
            text = {
                Column {
                    if (e.authors.isNotEmpty()) Text(e.authors.joinToString(", "), style = MaterialTheme.typography.titleSmall)
                    e.series?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
                    e.summary?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 8, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp)) }
                    Spacer(Modifier.height(10.dp))
                    if (e.acquisitions.isEmpty()) Text("This entry has no download.")
                    Text("Download as:", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        e.acquisitions.forEach { a ->
                            AssistChip(
                                onClick = {
                                    val ext = when (a.label) { "Audio" -> "m4b"; else -> a.label.lowercase() }
                                    val name = listOfNotNull(e.title, e.authors.firstOrNull()).joinToString(" - ") + ".$ext"
                                    Downloads.enqueue(context, a.href, name, null, null, a.type, a.size ?: -1, auth = cat.auth)
                                    vm.say("Downloading “${e.title}”")
                                    picked = null
                                },
                                label = { Text(a.label + (a.size?.takeIf { it > 0 }?.let { " · ${formatBytes(it)}" } ?: "")) },
                                leadingIcon = { Icon(Icons.Rounded.Download, null, Modifier.size(18.dp)) },
                            )
                        }
                    }
                    if (e.feed != null) TextButton(onClick = { picked = null; stack.add(e.feed) }) { Text("Open details") }
                }
            },
            confirmButton = { TextButton(onClick = { picked = null }) { Text("Close") } },
        )
    }
}

@Composable
private fun EntryRow(e: Entry, auth: String?, onClick: () -> Unit) {
    val context = LocalContext.current
    Card(
        onClick = onClick, shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh, contentColor = MaterialTheme.colorScheme.onSurface),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (e.cover != null || e.acquisitions.isNotEmpty()) {
                Box(Modifier.width(52.dp).aspectRatio(0.68f).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                    if (e.cover != null) {
                        val req = remember(e.cover) {
                            ImageRequest.Builder(context).data(e.cover)
                                .apply { auth?.let { httpHeaders(NetworkHeaders.Builder().set("Authorization", it).build()) } }
                                .build()
                        }
                        AsyncImage(req, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                }
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(e.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (e.authors.isNotEmpty()) Text(e.authors.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (e.acquisitions.isNotEmpty()) Text(e.acquisitions.joinToString(" · ") { it.label }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            if (e.feed != null && e.acquisitions.isEmpty()) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null)
        }
    }
}
