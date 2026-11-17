package com.worm.app.ui.screens

import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.foundation.layout.widthIn
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.worm.app.BuildConfigInfo
import com.worm.app.reader.SwitchRow
import com.worm.app.ui.WormViewModel
import com.worm.app.ui.components.PinDialog
import com.worm.app.ui.components.PinMode
import com.worm.app.ui.theme.AppTheme
import com.worm.app.ui.theme.extras
import com.worm.app.ui.theme.scheme
import com.worm.app.util.Biometrics
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun SettingsScreen(vm: WormViewModel, snackbar: SnackbarHostState, onBack: () -> Unit, onPlayGame: () -> Unit, onOpenReadestLibrary: () -> Unit) {
    var tab by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }
    val wide = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 840
    val context = LocalContext.current
    val prefs = vm.prefs
    val theme by vm.theme.collectAsStateWithLifecycle()
    val sources by vm.sources.collectAsStateWithLifecycle()
    val biometric by vm.biometric.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    // Settings that live only in prefs: a counter forces recomposition after writes.
    var tick by remember { mutableIntStateOf(0) }
    fun bump() { tick++ }
    var pinFlow by remember { mutableStateOf<PinMode?>(null) }
    var aboutTaps by remember { mutableIntStateOf(0) }
    var unlockedGame by remember { mutableStateOf(prefs.gameUnlocked) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(::treeUriToPath)?.let(vm::addSource)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Settings") },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                )
                if (!wide) androidx.compose.material3.PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 12.dp) {
                    SettingsTabs.forEachIndexed { i, label ->
                        androidx.compose.material3.Tab(tab == i, { tab = i }, text = { Text(label) })
                    }
                }
            }
        },
    ) { padding ->
        // Tablets and landscape: the tabs become a list down the left, settings on the right.
        if (wide) Column(Modifier.padding(padding).padding(start = 12.dp, top = 8.dp).width(200.dp).verticalScroll(rememberScrollState())) {
            SettingsTabs.forEachIndexed { i, label ->
                androidx.compose.material3.NavigationDrawerItem(
                    label = { Text(label) }, selected = tab == i, onClick = { tab = i },
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }
        Column(
            Modifier.fillMaxSize().padding(padding).padding(start = if (wide) 224.dp else 0.dp).widthIn(max = if (wide) 760.dp else 10_000.dp)
                .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(),
        ) { key(tick) {
            if (tab == 0) Section("Look") {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AppTheme.entries.forEach { t ->
                        val s = t.scheme(); val x = t.extras()
                        val selected = t == theme
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable { vm.setTheme(t) }.padding(4.dp)) {
                            Box(
                                Modifier.size(64.dp).clip(RoundedCornerShape(20.dp))
                                    .background(Brush.linearGradient(listOf(x.backdrop, x.glowA, x.glowB)))
                                    .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp)),
                                contentAlignment = Alignment.Center,
                            ) { Box(Modifier.size(22.dp).clip(CircleShape).background(s.primary)) }
                            Text(t.label, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                Text("Reading pages have their own themes: tap the middle of a page, then Look.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (tab == 0) Section("Home screen widgets") { WidgetSettings(prefs) }
            if (tab == 1) Section("Library folders") {
                Text(
                    "Worm reads books where they already are. Turn on the eye to keep a folder's audio out of music players and other apps (.nomedia).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                sources.forEach { src ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Folder, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(File(src.path).name, style = MaterialTheme.typography.titleSmall)
                            Text(src.path, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { vm.setNoMedia(src.path, !src.noMedia) }) {
                            Icon(if (src.noMedia) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (src.noMedia) "Hidden from other apps" else "Visible to other apps")
                        }
                        IconButton(onClick = { vm.removeSource(src.path) }) { Icon(Icons.Rounded.Delete, "Stop reading this folder") }
                    }
                }
                TextButton(onClick = { picker.launch(null) }) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(6.dp)); Text("Add a folder") }
            }
            if (tab == 1) Section("Backup") { LibraryBackup(snackbar) }

            if (tab == 2) Section("Listening") {
                Stepper("Skip back", prefs.skipBackSec, listOf(5, 10, 15, 20, 30, 45, 60), "s") { prefs.skipBackSec = it; bump() }
                Stepper("Skip forward", prefs.skipForwardSec, listOf(10, 15, 20, 30, 45, 60, 90), "s") { prefs.skipForwardSec = it; bump() }
                Stepper("Rewind when resuming", prefs.resumeRewindSec, listOf(0, 2, 5, 10, 15, 30), "s") { prefs.resumeRewindSec = it; bump() }
                SwitchRow("Smart rewind (more after longer breaks)", prefs.smartRewind) { prefs.smartRewind = it; bump() }
                SwitchRow("Lower volume for notifications instead of pausing", prefs.duckForNotifications) { prefs.duckForNotifications = it; bump() }
                SwitchRow("Car, earbud & watch next/previous jump chapters (off: skip by the seconds above)", prefs.chapterSkipButtons) { prefs.chapterSkipButtons = it; bump() }
                SwitchRow("Shake to add time to the sleep timer", prefs.shakeToExtend) { prefs.shakeToExtend = it; bump() }
            }

            if (tab == 3) Section("Reading") {
                SwitchRow("Volume keys turn pages", prefs.volumeKeysTurnPages) { prefs.volumeKeysTurnPages = it; bump() }
                SwitchRow("Keep screen on while reading", prefs.keepScreenOn) { prefs.keepScreenOn = it; bump() }
            }
            if (tab == 3) Section("Dictionaries") { DictionarySettings(snackbar) }

            if (tab == 4) Section("Sync") { SyncSettings(vm, onOpenReadestLibrary) }

            if (tab == 5) Section("The Burrow") {
                Text(
                    "Books you hide in the Burrow move into Worm's private storage: invisible to every other app, and only shown here after your PIN or fingerprint.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (Biometrics.available(context)) SwitchRow("Unlock with fingerprint / face", biometric) { vm.setBiometric(it) }
                SwitchRow("Block screenshots while Burrow books are showing", prefs.secureScreens) { prefs.secureScreens = it; bump() }
                Stepper("Relock after leaving the app", prefs.autoLockSeconds, listOf(0, 10, 30, 60, 300), "s") { prefs.autoLockSeconds = it; bump() }
                TextButton(onClick = { pinFlow = if (vm.pinIsSet) PinMode.Verify else PinMode.Create }) {
                    Text(if (vm.pinIsSet) "Change PIN" else "Set a PIN")
                }
            }

            if (tab == 6) Section("About") {
                // Ten taps here wakes something up. (Like Android's build-number trick.)
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable {
                        if (unlockedGame) { onPlayGame(); return@clickable }
                        aboutTaps++
                        val left = 10 - aboutTaps
                        if (left <= 0) { prefs.gameUnlocked = true; unlockedGame = true; com.worm.app.widget.Widgets.syncGameWidget(context); vm.say("🪱 You found Bookworm! There's a widget for it now, too.") }
                    },
                ) {
                    Text("Worm ${BuildConfigInfo.versionName(context)}", style = MaterialTheme.typography.titleMedium)
                Text("Read it. Hear it. Worm it.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // Quick inline hint (no slow snackbars) for the last few taps.
                val left = 10 - aboutTaps
                androidx.compose.animation.AnimatedVisibility(!unlockedGame && left in 1..4) {
                    Text(
                        if (left == 1) "Something is wriggling… 1 more" else "Something is wriggling… $left more",
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                    )
                }
                }
                if (unlockedGame) TextButton(onClick = onPlayGame) { Text("🪱 Play Bookworm") }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Free software under the GNU GPL v3 (or later).\n" +
                        "Built on: foliate-js (MIT) for books, AndroidX Media3 (Apache 2.0) for audio, Coil (Apache 2.0), " +
                        "Bricolage Grotesque (OFL).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(32.dp))
        } }
    }

    pinFlow?.let { mode ->
        PinDialog(
            mode = mode,
            onDismiss = { pinFlow = null },
            check = { vm.checkPin(it) },
            onSuccess = { code ->
                when (mode) {
                    PinMode.Verify -> pinFlow = PinMode.Create
                    PinMode.Create -> { pinFlow = null; scope.launch { vm.createPin(code) } }
                    PinMode.Unlock -> pinFlow = null
                }
            },
        )
    }
}

@Composable
fun Section(title: String, content: @Composable () -> Unit) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp, start = 4.dp))
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer, contentColor = MaterialTheme.colorScheme.onSurface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) { content() }
    }
}

@Composable
private fun Stepper(label: String, value: Int, options: List<Int>, unit: String, onPick: (Int) -> Unit) {
    Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 6.dp))
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { o ->
            FilterChip(selected = o == value, onClick = { onPick(o) }, label = { Text(if (o == 0) "Off" else "$o$unit") })
        }
    }
}


/** KOSync (KOReader) and Readest sign-in. Progress for books (not audiobooks) syncs while you read. */
@Composable
private fun SyncSettings(vm: WormViewModel, onOpenReadestLibrary: () -> Unit) {
    val context = LocalContext.current
    val prefs = vm.prefs
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    // ---------------- KOSync
    var koUser by remember { mutableStateOf(prefs.kosyncUser) }
    var server by remember { mutableStateOf(prefs.kosyncServer) }
    var user by remember { mutableStateOf(prefs.kosyncUser.orEmpty()) }
    var pass by remember { mutableStateOf("") }

    fun run(block: () -> String) {
        busy = true; message = null
        scope.launch {
            message = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching(block).getOrElse { it.message ?: "Something went wrong" }
            }
            busy = false
        }
    }

    Text("KOReader sync (KOSync)", style = MaterialTheme.typography.titleSmall)
    Text(
        "Shares your reading spot with KOReader and anything else that speaks KOSync. Books match by file, so use the same files on each device.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (koUser != null) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
            Text("Signed in as $koUser", modifier = Modifier.weight(1f))
            TextButton(onClick = { prefs.kosyncUser = null; prefs.kosyncKey = null; koUser = null }) { Text("Sign out") }
        }
    } else {
        androidx.compose.material3.OutlinedTextField(server, { server = it }, label = { Text("Server") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        androidx.compose.material3.OutlinedTextField(user, { user = it.trim() }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        androidx.compose.material3.OutlinedTextField(
            pass, { pass = it }, label = { Text("Password") }, singleLine = true,
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Row {
            TextButton(enabled = !busy && user.isNotBlank() && pass.isNotBlank(), onClick = {
                run {
                    val key = com.worm.app.sync.KOSyncClient.keyFor(pass)
                    com.worm.app.sync.KOSyncClient(server, user, key).authorize()
                    prefs.kosyncServer = server.trim(); prefs.kosyncUser = user; prefs.kosyncKey = key
                    koUser = user; pass = ""
                    "Connected to KOSync ✨"
                }
            }) { Text("Sign in") }
            TextButton(enabled = !busy && user.isNotBlank() && pass.isNotBlank(), onClick = {
                run {
                    com.worm.app.sync.KOSyncClient.register(server, user, pass)
                    val key = com.worm.app.sync.KOSyncClient.keyFor(pass)
                    prefs.kosyncServer = server.trim(); prefs.kosyncUser = user; prefs.kosyncKey = key
                    koUser = user; pass = ""
                    "Account created and connected ✨"
                }
            }) { Text("Create account") }
        }
    }

    androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 12.dp))

    // ---------------- Readest
    val readest = remember { com.worm.app.sync.ReadestClient(context) }
    var readestEmail by remember { mutableStateOf(readest.email.takeIf { readest.signedIn }) }
    var email by remember { mutableStateOf("") }
    var rpass by remember { mutableStateOf("") }
    Text("Readest", style = MaterialTheme.typography.titleSmall)
    Text(
        "Shares your reading spot with Readest's cloud sync (an unofficial connection, so it could break if Readest changes). Email-and-password accounts only.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (readestEmail != null) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
            Text("Signed in as $readestEmail", modifier = Modifier.weight(1f))
            TextButton(onClick = { readest.signOut(); readestEmail = null }) { Text("Sign out") }
        }
    } else {
        androidx.compose.material3.OutlinedTextField(email, { email = it.trim() }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        androidx.compose.material3.OutlinedTextField(
            rpass, { rpass = it }, label = { Text("Password") }, singleLine = true,
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(enabled = !busy && email.isNotBlank() && rpass.isNotBlank(), onClick = {
            run { readest.signIn(email, rpass); readestEmail = email; rpass = ""; "Connected to Readest ✨" }
        }) { Text("Sign in") }
    }
    if (readestEmail != null) TextButton(onClick = onOpenReadestLibrary) { Text("Browse your Readest library") }

    androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 12.dp))

    // ---------------- When to sync
    var pullOpen by remember { mutableStateOf(prefs.syncPullOnOpen) }
    var pushReading by remember { mutableStateOf(prefs.syncPushWhileReading) }
    var bulk by remember { mutableStateOf<String?>(null) }
    com.worm.app.reader.SwitchRow("Check for a newer spot when opening a book", pullOpen) { pullOpen = it; prefs.syncPullOnOpen = it }
    com.worm.app.reader.SwitchRow("Send my spot while reading", pushReading) { pushReading = it; prefs.syncPushWhileReading = it }
    Text(
        "Turn both off for manual sync: tap the sync button at the top of a book, or use these for every book at once.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
        androidx.compose.material3.OutlinedButton(enabled = !busy && (koUser != null || readestEmail != null), onClick = {
            run {
                val n = kotlinx.coroutines.runBlocking { com.worm.app.sync.ProgressSync.pullAll(context) { i, t -> bulk = "Checking ${i + 1} of $t…" } }
                bulk = null
                if (n == 0) "Everything's already up to date" else "Moved ${com.worm.app.util.plural(n, "book")} to a newer spot from elsewhere"
            }
        }) { Text("Pull all") }
        androidx.compose.material3.OutlinedButton(enabled = !busy && (koUser != null || readestEmail != null), onClick = {
            run {
                val n = kotlinx.coroutines.runBlocking { com.worm.app.sync.ProgressSync.pushAll(context) { i, t -> bulk = "Sending ${i + 1} of $t…" } }
                bulk = null
                "Sent your spot in ${com.worm.app.util.plural(n, "book")}"
            }
        }) { Text("Push all") }
    }
    bulk?.let { Text(it, style = MaterialTheme.typography.labelLarge) }
    if (busy) androidx.compose.material3.LinearWavyProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
    message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp)) }
    Text(
        "When you open a book, Worm checks for a newer spot and offers to jump there. Audiobook progress isn't synced (neither service supports it).",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
    )
}

/** Save / bring back everything you've tidied up: titles, series, covers, shelves, spots, bookmarks. */
@Composable
private fun LibraryBackup(snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var restored by remember { mutableStateOf<com.worm.app.data.Backup.Summary?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)!!.use { com.worm.app.data.Backup.export(context, it) } }
            }
            snackbar.showSnackbar(r.fold({ "Saved: ${it.edits} book edits, ${it.shelves} shelves, ${it.spots} spots, ${it.covers} covers" }, { "Couldn't save the backup: ${it.message}" }))
        }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)!!.use { com.worm.app.data.Backup.restore(context, it) } }
            }
            r.onSuccess { restored = it }.onFailure { snackbar.showSnackbar("Couldn't restore: ${it.message}") }
        }
    }
    Text(
        "Keeps your fixed titles, series, covers, shelves, reading and listening spots, bookmarks and highlights in one file. Books, the Burrow and passwords aren't included.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
        androidx.compose.material3.FilledTonalButton(onClick = {
            save.launch("Worm library " + java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date()) + ".zip")
        }) { Text("Back up") }
        androidx.compose.material3.OutlinedButton(onClick = { open.launch(arrayOf("application/zip", "application/octet-stream")) }) { Text("Restore…") }
    }
    restored?.let { r ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {},
            title = { Text("Restored") },
            text = { Text("${r.edits} book edits, ${r.shelves} shelves, ${r.spots} newer spots and ${r.covers} covers. Worm will restart to load them.") },
            confirmButton = {
                TextButton(onClick = {
                    val i = context.packageManager.getLaunchIntentForPackage(context.packageName)!!
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    context.startActivity(i)
                    Runtime.getRuntime().exit(0)
                }) { Text("Restart") }
            },
        )
    }
}

/** Colours and corner shape for Worm's home screen widgets; changes show up right away. */
@Composable
private fun WidgetSettings(prefs: com.worm.app.data.Prefs) {
    val context = LocalContext.current
    var colors by remember { mutableStateOf(prefs.widgetColors) }
    var corners by remember { mutableIntStateOf(prefs.widgetCorners) }
    Text(
        "Long-press your home screen, tap Widgets, and find Worm: keep reading, keep listening, both, now playing, and this week or month.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text("Colours", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("worm" to "Worm", "wallpaper" to "Match wallpaper").forEach { (k, label) ->
            androidx.compose.material3.FilterChip(
                selected = colors == k, onClick = { colors = k; prefs.widgetColors = k; com.worm.app.widget.Widgets.refreshAll(context) },
                label = { Text(label) },
            )
        }
    }
    Text("Corners", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(8 to "Subtle", 24 to "Round", 40 to "Pebble").forEach { (v, label) ->
            androidx.compose.material3.FilterChip(
                selected = corners == v, onClick = { corners = v; prefs.widgetCorners = v; com.worm.app.widget.Widgets.refreshAll(context) },
                label = { Text(label) },
            )
        }
    }
}

private val SettingsTabs = listOf("Look", "Library", "Listening", "Reading", "Sync", "Burrow", "About")

/** Offline StarDict dictionaries: add, list, remove. Looked up before Wiktionary, and work with no connection. */
@Composable
private fun DictionarySettings(snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var dicts by remember { mutableStateOf(com.worm.app.reader.StarDict.installed(context)) }
    var busy by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    com.worm.app.reader.StarDict.import(context, uris.map { u ->
                        val name = context.contentResolver.query(u, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: u.lastPathSegment.orEmpty()
                        name to { context.contentResolver.openInputStream(u)!! }
                    })
                }
            }
            busy = false
            dicts = com.worm.app.reader.StarDict.installed(context)
            snackbar.showSnackbar(r.fold({ "Added “$it”. Define uses it first, even offline." }, { "Couldn't add it: ${it.message}" }))
        }
    }
    Text(
        "Look words up without a connection. Worm uses StarDict dictionaries, the same files KOReader and GoldenDict use. Pick a .zip of one, or its .ifo, .idx and .dict(.dz) files together.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    dicts.forEach { d ->
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Rounded.MenuBook, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(d.name, style = MaterialTheme.typography.titleSmall)
                if (d.words > 0) Text("%,d words".format(d.words), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { com.worm.app.reader.StarDict.remove(context, d); dicts = com.worm.app.reader.StarDict.installed(context) }) { Icon(Icons.Rounded.Delete, "Remove ${d.name}") }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(enabled = !busy, onClick = { pick.launch(arrayOf("*/*")) }) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(6.dp)); Text(if (busy) "Adding…" else "Add a dictionary") }
        TextButton(onClick = {
            runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/koreader/koreader/wiki/Dictionary-support"))) }
        }) { Text("Where to get them") }
    }
}
