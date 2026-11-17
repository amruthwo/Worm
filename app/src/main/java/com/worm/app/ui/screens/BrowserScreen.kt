package com.worm.app.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.worm.app.download.DlState
import com.worm.app.download.Downloads
import com.worm.app.ui.WormViewModel
import com.worm.app.util.formatBytes
import org.json.JSONArray
import org.json.JSONObject

/** Starting points: free, legitimate libraries. You can add your own bookmarks. */
private val Starters = listOf(
    Triple("Standard Ebooks", "https://standardebooks.org/ebooks", "Beautifully made public-domain ebooks"),
    Triple("Project Gutenberg", "https://www.gutenberg.org", "70,000+ free classics"),
    Triple("LibriVox", "https://librivox.org", "Free public-domain audiobooks"),
    Triple("Internet Archive", "https://archive.org/details/texts", "Books, audio and more"),
    Triple("Open Library", "https://openlibrary.org", "Borrow and read"),
)

private fun bookmarks(c: android.content.Context): List<Pair<String, String>> = runCatching {
    val a = JSONArray(c.getSharedPreferences("worm_browser", 0).getString("bookmarks", "[]"))
    List(a.length()) { a.getJSONObject(it).let { o -> o.getString("t") to o.getString("u") } }
}.getOrDefault(emptyList())

private fun saveBookmarks(c: android.content.Context, list: List<Pair<String, String>>) =
    c.getSharedPreferences("worm_browser", 0).edit().putString("bookmarks", JSONArray(list.map { JSONObject().put("t", it.first).put("u", it.second) }).toString()).apply()

/** Address bar text → a URL: real addresses as-is, anything else becomes a search. */
private fun toUrl(input: String): String {
    val t = input.trim()
    return when {
        t.startsWith("http://") || t.startsWith("https://") -> t
        Regex("""^[\w-]+(\.[\w-]+)+(/.*)?$""").matches(t) -> "https://$t"
        else -> "https://duckduckgo.com/?q=" + Uri.encode(t)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(vm: WormViewModel, onBack: () -> Unit, onOpenDownloads: () -> Unit, startUrl: String? = null) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    var web by remember { mutableStateOf<WebView?>(null) }
    var url by remember { mutableStateOf<String?>(null) } // null = start page
    var address by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(1f) }
    var title by remember { mutableStateOf("") }
    var canBack by remember { mutableStateOf(false) }
    var canForward by remember { mutableStateOf(false) }
    var marks by remember { mutableStateOf(bookmarks(context)) }
    var pending by remember { mutableStateOf<PendingDownload?>(null) }
    val downloads by Downloads.items.collectAsStateWithLifecycle()
    val active = downloads.count { it.state == DlState.Running || it.state == DlState.Queued }
    remember { Downloads.load(context) }

    fun go(target: String) {
        val u = toUrl(target)
        url = u; address = u; editing = false; focus.clearFocus()
        web?.loadUrl(u)
    }

    BackHandler {
        when {
            editing -> { editing = false; focus.clearFocus() }
            url != null && web?.canGoBack() == true -> web?.goBack()
            url != null -> { url = null; address = "" }
            else -> onBack()
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        // ------------------------------------------------------- address bar
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.Close, "Close browser") }
            OutlinedTextField(
                value = if (editing) address else (title.takeIf { url != null && it.isNotBlank() } ?: address),
                onValueChange = { address = it },
                placeholder = { Text("Search or type a web address") },
                singleLine = true,
                shape = RoundedCornerShape(50),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (address.isNotBlank()) go(address) }),
                colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Color.Transparent, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh, focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                modifier = Modifier.weight(1f).onFocusChanged { f -> editing = f.isFocused; if (f.isFocused) address = url ?: "" },
            )
            IconButton(onClick = onOpenDownloads) {
                BadgedBox(badge = { if (active > 0) Badge { Text("$active") } }) { Icon(Icons.Rounded.Download, "Downloads") }
            }
        }
        if (progress < 1f && url != null) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(2.dp))

        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = android.view.ViewGroup.LayoutParams(-1, -1)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val u = request.url
                                return when (u.scheme) {
                                    "http", "https" -> false
                                    "magnet" -> { com.worm.app.torrent.TorrentEngine.addMagnet(ctx, u.toString()); vm.say("Fetching the torrent's details…"); true }
                                    else -> { runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, u)) }; true }
                                }
                            }
                            override fun onPageStarted(view: WebView, u: String, favicon: Bitmap?) { address = u; url = u }
                            override fun doUpdateVisitedHistory(view: WebView, u: String, isReload: Boolean) {
                                canBack = view.canGoBack(); canForward = view.canGoForward(); if (!editing) address = u
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView, p: Int) { progress = p / 100f }
                            override fun onReceivedTitle(view: WebView, t: String?) { title = t.orEmpty() }
                        }
                        // Catch every download: offer to save books/audio straight into the library.
                        setDownloadListener { dlUrl, userAgent, contentDisposition, mime, length ->
                            val name = Downloads.guessName(dlUrl, contentDisposition, mime).let { n ->
                                if ('.' in n) n else URLUtil.guessFileName(dlUrl, contentDisposition, mime)
                            }
                            pending = PendingDownload(dlUrl, name, userAgent, mime, length, this.url)
                        }
                        web = this
                        startUrl?.let { u -> url = u; address = u; loadUrl(u) }
                    }
                },
                onRelease = { it.destroy() },
                modifier = Modifier.fillMaxSize(),
            )
            if (url == null) StartPage(marks, onOpen = ::go, onRemove = { m -> marks = marks - m; saveBookmarks(context, marks) })
        }

        // --------------------------------------------------------- toolbar
        if (url != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            IconButton(onClick = { web?.goBack() }, enabled = canBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            IconButton(onClick = { web?.goForward() }, enabled = canForward) { Icon(Icons.AutoMirrored.Rounded.ArrowForward, "Forward") }
            IconButton(onClick = { web?.reload() }) { Icon(Icons.Rounded.Refresh, "Reload") }
            IconButton(onClick = {
                val u = url ?: return@IconButton
                if (marks.none { it.second == u }) { marks = marks + ((title.ifBlank { u }) to u); saveBookmarks(context, marks); vm.say("Bookmarked") }
            }) { Icon(Icons.Rounded.BookmarkAdd, "Bookmark this page") }
            IconButton(onClick = { url = null; address = "" }) { Icon(Icons.Rounded.Home, "Start page") }
        }
    }

    pending?.let { p ->
        val bookish = Downloads.isBookish(p.name, p.mime)
        AlertDialog(
            onDismissRequest = { pending = null },
            icon = { Icon(Icons.Rounded.Download, null) },
            title = { Text("Download “${p.name}”?") },
            text = {
                Column {
                    if (p.size > 0) Text(formatBytes(p.size), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(
                        if (bookish) "It'll go into your library (Worm Downloads). Zipped audiobooks are unpacked into their own folder."
                        else "This doesn't look like a book or audiobook, but Worm can still save it to Worm Downloads.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    Downloads.enqueue(context, p.url, p.name, p.referer, p.userAgent, p.mime, p.size)
                    vm.say("Downloading ${p.name}")
                    pending = null
                }) { Text("Download") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel") } },
        )
    }
}

private data class PendingDownload(val url: String, val name: String, val userAgent: String?, val mime: String?, val size: Long, val referer: String?)

@Composable
private fun StartPage(marks: List<Pair<String, String>>, onOpen: (String) -> Unit, onRemove: (Pair<String, String>) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text("Find something to read", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Books and audiobooks you download here go straight into your library.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Text("Free libraries", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Starters.forEach { (name, u, blurb) ->
                Card(
                    onClick = { onOpen(u) }, shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh, contentColor = MaterialTheme.colorScheme.onSurface),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(name, style = MaterialTheme.typography.titleMedium)
                        Text(blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Bookmarks, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Your bookmarks", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
            if (marks.isEmpty()) Text("Tap the bookmark button on any page to keep it here.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                marks.forEach { m ->
                    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.padding(top = 6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 14.dp)) {
                            Text(m.first, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.clickable { onOpen(m.second) }.padding(vertical = 8.dp))
                            IconButton(onClick = { onRemove(m) }, modifier = Modifier.size(32.dp)) { Icon(Icons.Rounded.Close, "Remove bookmark", Modifier.size(16.dp)) }
                        }
                    }
                }
            }
        }
    }
}
