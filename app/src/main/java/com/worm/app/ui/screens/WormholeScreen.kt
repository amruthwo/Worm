package com.worm.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CallMade
import androidx.compose.material.icons.rounded.CallReceived
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.worm.app.data.Book
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material3.FilterChip
import com.worm.app.ui.WormViewModel
import com.worm.app.ui.components.BookCover
import com.worm.app.ui.theme.Worm
import com.worm.app.util.formatBytes
import com.worm.app.util.plural
import com.worm.app.wormhole.Nearby
import com.worm.app.wormhole.QrCode
import com.worm.app.wormhole.QrScanner
import com.worm.app.wormhole.RemoteBook
import com.worm.app.wormhole.TransferState
import com.worm.app.wormhole.WormholeStage
import com.worm.app.wormhole.WormholeViewModel
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

/** Wormhole: beam books, audiobooks and reading spots between Worm devices (or to a browser). */
@Composable
fun WormholeScreen(vm: WormViewModel, onBack: () -> Unit) {
    val wh: WormholeViewModel = viewModel()
    val stage by wh.stage.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { wh.messages.collect { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(it) } }
    fun exit() { wh.leave(); onBack() }
    BackHandler { if (stage == WormholeStage.Idle) exit() else wh.leave() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Wormhole") },
                navigationIcon = { IconButton(onClick = { if (stage == WormholeStage.Idle) exit() else wh.leave() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = {
                    val share by wh.shareSpots.collectAsStateWithLifecycle()
                    if (stage == WormholeStage.Connected && share) TextButton(onClick = wh::swapSpots) {
                        Icon(Icons.Rounded.SwapHoriz, null); Spacer(Modifier.width(6.dp)); Text("Swap spots")
                    }
                },
            )
        },
    ) { padding ->
        val offers by wh.offers.collectAsStateWithLifecycle()
        offers.firstOrNull()?.let { o -> OfferDialog(o, onDecide = { accept, trust -> wh.decide(o, accept, trust) }) }
        Box(Modifier.fillMaxSize().padding(padding)) {
            AnimatedContent(stage, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "stage") { s ->
                when (s) {
                    WormholeStage.Idle -> IdlePane(wh)
                    WormholeStage.Starting, WormholeStage.Connecting -> Busy(if (s == WormholeStage.Starting) "Opening the Wormhole…" else "Tunnelling through…")
                    WormholeStage.Hosting -> HostPane(wh, vm)
                    WormholeStage.Joining -> JoinPane(wh)
                    WormholeStage.Connected -> ConnectedPane(wh, vm)
                }
            }
        }
    }
}

/** A swirling tunnel of rings in the Worm colours. */
@Composable
private fun WormholeSwirl(modifier: Modifier = Modifier) {
    var t by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { val start = withFrameNanos { it }; while (true) withFrameNanos { t = it - start } }
    val colors = listOf(Worm.Pink, Worm.Violet, Worm.Mint, Worm.Bubblegum, Worm.Lilac)
    Canvas(modifier) {
        val time = t / 1_000_000_000f
        val c = center
        val maxR = size.minDimension / 2
        for (i in 0 until 14) {
            // Rings fall inward forever; each spins a little and wobbles off-centre.
            val p = ((i / 14f) + time * 0.12f) % 1f
            val r = maxR * (1f - p)
            val wobble = Offset(cos(time + i) * maxR * 0.05f * (1 - p), sin(time * 1.3f + i) * maxR * 0.05f * (1 - p))
            rotate(time * 40f * (if (i % 2 == 0) 1 else -1)) {
                drawArc(
                    colors[i % colors.size].copy(alpha = (0.15f + 0.85f * p).coerceIn(0f, 1f)),
                    startAngle = i * 37f, sweepAngle = 260f + 60f * sin(time + i), useCenter = false,
                    topLeft = c + wobble - Offset(r, r), size = androidx.compose.ui.geometry.Size(r * 2, r * 2),
                    style = Stroke(width = (2f + 6f * (1 - p)) * density, cap = androidx.compose.ui.graphics.StrokeCap.Round),
                )
            }
        }
        drawCircle(Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent), c, maxR * 0.35f), maxR * 0.35f, c)
    }
}

@Composable
private fun Busy(text: String) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(contentAlignment = Alignment.Center) {
            WormholeSwirl(Modifier.size(220.dp))
            LoadingIndicator(Modifier.size(56.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun IdlePane(wh: WormholeViewModel) {
    val context = LocalContext.current
    var renaming by remember { mutableStateOf(false) }
    var hotspot by remember { mutableStateOf(false) }
    val hotspotPermission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
    val askHotspot = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) wh.host(true) }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { wh.startJoining() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        WormholeSwirl(Modifier.size(200.dp))
        Text("Beam books, audiobooks and your reading spot to another Worm, or to any browser on the same Wi-Fi.", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
            Text("You're “${wh.myName}”", style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = { renaming = true }) { Icon(Icons.Rounded.Edit, "Rename this device") }
        }
        Card(
            onClick = {
                if (!hotspot) wh.host(false)
                else if (ContextCompat.checkSelfPermission(context, hotspotPermission) == PackageManager.PERMISSION_GRANTED) wh.host(true)
                else askHotspot.launch(hotspotPermission)
            },
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (hotspot) Icons.Rounded.WifiTethering else Icons.Rounded.Wifi, null)
                    Spacer(Modifier.width(10.dp))
                    Text("Open a Wormhole", style = MaterialTheme.typography.titleLarge)
                }
                Text("Shows a QR code for the other device, and an address for a browser.", style = MaterialTheme.typography.bodyMedium)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Text("No shared Wi-Fi? Use a private hotspot", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(hotspot, { hotspot = it })
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Card(
            onClick = {
                if (!hasCameraHardware(context) || ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) wh.startJoining()
                else askCamera.launch(Manifest.permission.CAMERA)
            },
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.QrCodeScanner, null); Spacer(Modifier.width(10.dp))
                    Text("Jump through", style = MaterialTheme.typography.titleLarge)
                }
                Text(
                    if (hasCameraHardware(context)) "Scan the other Worm's code, pick it from the nearby list, or type its address."
                    else "Pick the other Worm from the nearby list, or type its address.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
    if (renaming) {
        var name by remember { mutableStateOf(wh.myName) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("This device's Wormhole name") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { wh.setMyName(name); renaming = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun HostPane(wh: WormholeViewModel, vm: WormViewModel) {
    val host by wh.host.collectAsStateWithLifecycle()
    val peers by wh.peers.collectAsStateWithLifecycle()
    val unlocked by vm.unlocked.collectAsStateWithLifecycle()
    var shareBurrow by remember { mutableStateOf(false) }
    val h = host ?: return
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("On the other Worm: Wormhole › Jump through › scan", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Surface(shape = RoundedCornerShape(28.dp), color = Color.White, modifier = Modifier.size(260.dp)) {
            QrCode(h.invite.toUri(), Modifier.padding(18.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text("Code", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(h.code.chunked(3).joinToString(" "), fontSize = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp, color = MaterialTheme.colorScheme.primary)
        h.hotspot?.let { Text("Hotspot “${it.ssid}” · password ${it.pass}", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center) }
        Spacer(Modifier.height(8.dp))
        Text("From a computer, or a Worm with no camera (Jump through › Type an address):", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        h.urls.take(2).forEach { Text(it, style = MaterialTheme.typography.titleMedium) }
        if (unlocked) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Text("Share Burrow books too", modifier = Modifier.weight(1f))
            Switch(shareBurrow, { shareBurrow = it; wh.setShareBurrow(it) })
        }
        Spacer(Modifier.height(12.dp))
        if (peers.isEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
            LoadingIndicator(Modifier.size(28.dp)); Spacer(Modifier.width(8.dp)); Text("Waiting for someone to come through…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        peers.forEach { Text("✨ ${it.name} is connected", style = MaterialTheme.typography.bodyLarge) }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = wh::leave) { Text("Close the Wormhole") }
    }
}

@Composable
private fun JoinPane(wh: WormholeViewModel) {
    val context = LocalContext.current
    val nearby by wh.nearby.collectAsStateWithLifecycle()
    var codeFor by remember { mutableStateOf<Nearby?>(null) }
    val hardware = hasCameraHardware(context)
    val hasCamera = hardware && ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    var scanned by remember { mutableStateOf(false) }
    var manual by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (hasCamera) Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(28.dp))) {
            QrScanner(onResult = { if (!scanned) { scanned = true; wh.join(it) } }, modifier = Modifier.fillMaxSize())
        } else if (hardware) Text("Allow the camera to scan a Wormhole code, or pick a nearby Worm below.", textAlign = TextAlign.Center)
        else Text("No camera here: pick the other Worm below, or type the address it shows.", textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Text("Nearby on this Wi-Fi", style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())
        if (nearby.isEmpty()) Text("Looking…", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        nearby.forEach { n ->
            Surface(
                onClick = { codeFor = n }, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            ) { Text(n.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp)) }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = { manual = true }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.Edit, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Type an address")
        }
    }
    if (manual) {
        var addr by remember { mutableStateOf("") }
        // Accepts 192.168.1.20:8787 or the whole http:// address the other Worm shows.
        val parsed = Regex("""^(?:https?://)?([\w.\-]+):(\d{2,5})/?$""").find(addr.trim())?.let { m -> m.groupValues[1] to m.groupValues[2].toInt() }
        AlertDialog(
            onDismissRequest = { manual = false },
            title = { Text("Other Worm's address") },
            text = {
                Column {
                    Text("Open a Wormhole on the other device. Type the address it shows (like 192.168.1.20:8787), then its code.", style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        addr, { addr = it }, singleLine = true, placeholder = { Text("192.168.1.20:8787") },
                        isError = addr.isNotBlank() && parsed == null, modifier = Modifier.padding(top = 8.dp),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri),
                    )
                }
            },
            confirmButton = { TextButton(enabled = parsed != null, onClick = { parsed?.let { (h, p) -> codeFor = Nearby("$h:$p", h, p) }; manual = false }) { Text("Next") } },
            dismissButton = { TextButton(onClick = { manual = false }) { Text("Cancel") } },
        )
    }
    codeFor?.let { n ->
        val scope = rememberCoroutineScope()
        var code by remember { mutableStateOf("") }
        var wrong by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { codeFor = null },
            title = { Text("Code shown on ${n.name}") },
            text = {
                OutlinedTextField(
                    code, { code = it.filter(Char::isDigit).take(6); wrong = false }, singleLine = true, isError = wrong,
                    supportingText = { if (wrong) Text("That's not it, try again") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                )
            },
            confirmButton = {
                TextButton(enabled = code.length == 6, onClick = { scope.launch { if (wh.joinWithCode(n, code)) codeFor = null else wrong = true } }) { Text("Jump") }
            },
            dismissButton = { TextButton(onClick = { codeFor = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ConnectedPane(wh: WormholeViewModel, vm: WormViewModel) {
    var tab by remember { mutableIntStateOf(0) }
    val remoteName by wh.remoteName.collectAsStateWithLifecycle()
    val transfers by wh.transfers.collectAsStateWithLifecycle()
    val share by wh.shareSpots.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        // Reading spots stay private unless you choose to share them (handy between your own devices).
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { wh.setShareSpots(!share) }.padding(horizontal = 16.dp, vertical = 4.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Share reading spots", style = MaterialTheme.typography.bodyLarge)
                Text("For your own devices: both sides can see and swap where you are in each book.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(share, { wh.setShareSpots(it) })
        }
        PrimaryTabRow(selectedTabIndex = tab) {
            Tab(tab == 0, { tab = 0 }, text = { Text(remoteName.ifBlank { "Theirs" }, maxLines = 1, overflow = TextOverflow.Ellipsis) }, icon = { Icon(Icons.Rounded.CallReceived, null) })
            Tab(tab == 1, { tab = 1 }, text = { Text("Yours") }, icon = { Icon(Icons.Rounded.CallMade, null) })
            Tab(tab == 2, { tab = 2 }, text = { Text("Transfers" + transfers.count { it.state == TransferState.Active || it.state == TransferState.Queued }.let { if (it > 0) " ($it)" else "" }) })
        }
        when (tab) {
            0 -> TheirsTab(wh, vm)
            1 -> YoursTab(wh, vm)
            else -> TransfersTab(wh)
        }
    }
}

@Composable
private fun TheirsTab(wh: WormholeViewModel, vm: WormViewModel) {
    val books by wh.remoteBooks.collectAsStateWithLifecycle()
    val mine by vm.books.collectAsStateWithLifecycle()
    val client by wh.remote.collectAsStateWithLifecycle()
    var picked by remember { mutableStateOf(setOf<String>()) }
    val haveIds = mine.map { it.id }.toSet()
    var filter by remember { mutableStateOf(WhFilter()) }
    val shown = remember(books, filter, haveIds) {
        books.filter { b ->
            filter.kind.ok(b.audio) && (!filter.missing || b.id !in haveIds) &&
                filter.matches(b.title, b.authors.joinToString(" "), b.narrators.joinToString(" "), b.series, b.format)
        }.let { l ->
            when (filter.sort) {
                WhSort.Author -> l.sortedWith(compareBy({ it.authors.firstOrNull()?.substringAfterLast(' ')?.lowercase() }, { it.title.lowercase() }))
                WhSort.Series -> l.sortedWith(compareBy({ it.series == null }, { it.series?.lowercase() }, { it.seriesIndex ?: 0f }, { it.title.lowercase() }))
                WhSort.Size -> l.sortedByDescending { it.size }
                WhSort.Title -> l.sortedBy { it.title.lowercase().removePrefix("the ").removePrefix("a ") }
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 100.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                WhFilterBar(filter, missingLabel = "Not in my library", onChange = { filter = it })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${shown.size} of ${plural(books.size, "book")} on their side", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    if (shown.isNotEmpty()) TextButton(onClick = { val ids = shown.map { it.id }.toSet(); picked = if (picked.containsAll(ids)) picked - ids else picked + ids }) {
                        Text(if (picked.containsAll(shown.map { it.id })) "Clear" else "Select all")
                    }
                }
            }
            items(shown, key = { it.id }) { b ->
                val have = b.id in haveIds
                RemoteRow(b, client?.coverUrl(b.id), checked = b.id in picked, have = have) {
                    picked = if (b.id in picked) picked - b.id else picked + b.id
                }
            }
        }
        if (picked.isNotEmpty()) ExtendedFloatingActionButton(
            onClick = { wh.take(books.filter { it.id in picked }, burrowToBurrow = true); picked = emptySet() },
            icon = { Icon(Icons.Rounded.CallReceived, null) }, text = { Text("Pull ${plural(picked.size, "book")}") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).navigationBarsPadding(),
        )
    }
}

@Composable
private fun RemoteRow(b: RemoteBook, cover: String?, checked: Boolean, have: Boolean, onToggle: () -> Unit) {
    Card(
        onClick = onToggle, shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (checked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = if (checked) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked, { onToggle() })
            Box(Modifier.width(48.dp).aspectRatio(if (b.audio) 1f else 0.68f).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                if (b.hasCover && cover != null) AsyncImage(cover, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(b.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(b.author, b.format.uppercase(), formatBytes(b.size), b.files.size.takeIf { it > 1 }?.let { plural(it, "file") }, if (b.hidden) "Burrow" else null).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (have) Text("Already in your library", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun YoursTab(wh: WormholeViewModel, vm: WormViewModel) {
    val all by vm.books.collectAsStateWithLifecycle()
    val unlocked by vm.unlocked.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf(WhFilter()) }
    val books = remember(all, unlocked, filter) {
        all.filter { (!it.hidden || unlocked) && filter.kind.ok(it.isAudio) && filter.matches(it.title, it.author, it.narrator, it.series, it.format) }
            .let { l ->
                when (filter.sort) {
                    WhSort.Author -> l.sortedWith(compareBy({ it.author.substringAfterLast(' ').lowercase() }, { it.sortTitle }))
                    WhSort.Series -> l.sortedWith(compareBy({ it.series == null }, { it.series?.lowercase() }, { it.seriesIndex ?: 0f }, { it.sortTitle }))
                    WhSort.Size -> l.sortedByDescending { it.sizeBytes }
                    WhSort.Title -> l.sortedBy { it.sortTitle }
                }
            }
    }
    var picked by remember { mutableStateOf(setOf<String>()) }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 100.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                WhFilterBar(filter, missingLabel = null, onChange = { filter = it })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Pick books to send through the Wormhole.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    if (books.isNotEmpty()) TextButton(onClick = { val ids = books.map { it.id }.toSet(); picked = if (picked.containsAll(ids)) picked - ids else picked + ids }) {
                        Text(if (picked.containsAll(books.map { it.id })) "Clear" else "Select all")
                    }
                }
            }
            items(books, key = { it.id }) { b -> LocalRow(b, b.id in picked) { picked = if (b.id in picked) picked - b.id else picked + b.id } }
        }
        if (picked.isNotEmpty()) ExtendedFloatingActionButton(
            onClick = { wh.send(books.filter { it.id in picked }); picked = emptySet() },
            icon = { Icon(Icons.Rounded.CallMade, null) }, text = { Text("Send ${plural(picked.size, "book")}") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).navigationBarsPadding(),
        )
    }
}

@Composable
private fun LocalRow(b: Book, checked: Boolean, onToggle: () -> Unit) {
    Card(
        onClick = onToggle, shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (checked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = if (checked) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked, { onToggle() })
            Box(Modifier.width(48.dp).aspectRatio(if (b.isAudio) 1f else 0.68f)) { BookCover(b, Modifier.fillMaxSize(), corner = 8.dp, showKindBadge = false) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(b.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(listOf(b.author, b.format.uppercase(), formatBytes(b.sizeBytes)).joinToString(" · "), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun TransfersTab(wh: WormholeViewModel) {
    val transfers by wh.transfers.collectAsStateWithLifecycle()
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row {
                if (transfers.any { it.state == TransferState.Failed }) TextButton(onClick = wh::retryFailed) { Text("Retry failed") }
                if (transfers.any { it.state == TransferState.Done || it.state == TransferState.Skipped }) TextButton(onClick = { wh.clearFinished() }) { Text("Clear finished") }
            }
            if (transfers.isEmpty()) Text("Nothing moving yet. Pick books on either tab.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(transfers, key = { it.id }) { t ->
            Column(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (t.sending) Icons.Rounded.CallMade else Icons.Rounded.CallReceived, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(t.file, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        when (t.state) {
                            TransferState.Queued -> "waiting"
                            TransferState.Active -> "${formatBytes(t.done)} / ${formatBytes(t.total)}"
                            TransferState.Done -> "done ✓"
                            TransferState.Skipped -> "already there"
                            TransferState.Failed -> "failed"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (t.state == TransferState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (t.state == TransferState.Active) LinearProgressIndicator(progress = { if (t.total > 0) t.done.toFloat() / t.total else 0f }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                t.error?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}


/** Someone wants to send books here: nothing arrives unless you say yes. */
@Composable
private fun OfferDialog(o: com.worm.app.wormhole.Offer, onDecide: (accept: Boolean, trust: Boolean) -> Unit) {
    var trust by remember(o.id) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = {},
        icon = { Icon(Icons.Rounded.CallReceived, null) },
        title = { Text("${o.from} wants to send you ${plural(o.titles.size, "book")}") },
        text = {
            Column {
                Text("${formatBytes(o.bytes)} in total", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(6.dp))
                o.titles.take(8).forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                if (o.titles.size > 8) Text("…and ${o.titles.size - 8} more", style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp).clickable { trust = !trust }) {
                    Checkbox(trust, { trust = it })
                    Text("Accept anything else from ${o.from} until I close the Wormhole", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { Button(onClick = { onDecide(true, trust) }) { Text("Accept") } },
        dismissButton = { TextButton(onClick = { onDecide(false, false) }) { Text("Decline") } },
    )
}

// ------------------------------------------------------------- finding books in long lists

private enum class WhSort(val label: String) { Title("Title"), Author("Author"), Series("Series"), Size("Size") }

private data class WhFilter(
    val query: String = "",
    val kind: com.worm.app.data.KindFilter = com.worm.app.data.KindFilter.All,
    val missing: Boolean = false,
    val sort: WhSort = WhSort.Title,
) {
    /** Every word you type has to appear somewhere: title, author, narrator, series or format. */
    fun matches(vararg fields: String?): Boolean {
        if (query.isBlank()) return true
        val hay = fields.filterNotNull().joinToString(" ").lowercase()
        return query.lowercase().split(' ').filter { it.isNotBlank() }.all { it in hay }
    }
}

private fun com.worm.app.data.KindFilter.ok(audio: Boolean) = when (this) {
    com.worm.app.data.KindFilter.All -> true
    com.worm.app.data.KindFilter.Ebooks -> !audio
    com.worm.app.data.KindFilter.Audiobooks -> audio
}

@Composable
private fun WhFilterBar(f: WhFilter, missingLabel: String?, onChange: (WhFilter) -> Unit) {
    var sortOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        androidx.compose.material3.OutlinedTextField(
            value = f.query, onValueChange = { onChange(f.copy(query = it)) },
            placeholder = { Text("Title, author, narrator, series…") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = { if (f.query.isNotEmpty()) IconButton(onClick = { onChange(f.copy(query = "")) }) { Icon(Icons.Rounded.Close, "Clear search") } },
            singleLine = true, shape = RoundedCornerShape(50), modifier = Modifier.fillMaxWidth(),
        )
        androidx.compose.foundation.layout.FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            com.worm.app.data.KindFilter.entries.forEach { k ->
                FilterChip(selected = f.kind == k, onClick = { onChange(f.copy(kind = k)) }, label = { Text(k.label) })
            }
            if (missingLabel != null) FilterChip(selected = f.missing, onClick = { onChange(f.copy(missing = !f.missing)) }, label = { Text(missingLabel) })
            Box {
                androidx.compose.material3.AssistChip(
                    onClick = { sortOpen = true }, label = { Text("Sort: ${f.sort.label}") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Sort, null, Modifier.size(18.dp)) },
                )
                androidx.compose.material3.DropdownMenu(sortOpen, { sortOpen = false }) {
                    WhSort.entries.forEach { s -> androidx.compose.material3.DropdownMenuItem(text = { Text(s.label) }, onClick = { onChange(f.copy(sort = s)); sortOpen = false }) }
                }
            }
        }
    }
}

private fun hasCameraHardware(context: android.content.Context) =
    context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
