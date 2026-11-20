package com.worm.app

import android.Manifest
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.worm.app.data.Book
import com.worm.app.data.StorageAccess
import com.worm.app.reader.ReaderKeys
import com.worm.app.reader.ReaderScreen
import com.worm.app.ui.WormViewModel
import com.worm.app.ui.screens.ComingSoonScreen
import com.worm.app.ui.screens.HomeScreen
import com.worm.app.ui.screens.OnboardingScreen
import com.worm.app.ui.screens.PermissionScreen
import com.worm.app.ui.screens.PlayerScreen
import com.worm.app.ui.screens.SettingsScreen
import com.worm.app.ui.screens.SplashScreen
import com.worm.app.ui.screens.StatsScreen
import com.worm.app.ui.screens.treeUriToPath
import com.worm.app.ui.theme.WormTheme
import kotlinx.coroutines.flow.MutableStateFlow

private sealed interface Screen {
    data object Splash : Screen
    data object Permission : Screen
    data object Onboarding : Screen
    data object Home : Screen
    data object Player : Screen
    data class Reader(val bookId: String, val furthest: Boolean = false) : Screen
    data object Settings : Screen
    data object Stats : Screen
    data object Wormhole : Screen
    data object Tools : Screen
    data object Game : Screen
    data object FixUp : Screen
    data object ReadestLibrary : Screen
    data class Browser(val url: String? = null) : Screen
    data object Downloads : Screen
    data object Catalogs : Screen
}

class MainActivity : FragmentActivity() {
    /** Set when the media notification asks to open the player. */
    private val openPlayer = MutableStateFlow(false)
    /** Debug builds only: `am start ... --es open_path /sdcard/...` opens that book (used for testing over adb). */
    private val openPath = MutableStateFlow<String?>(null)
    private val openScreen = MutableStateFlow<String?>(null)
    /** Something shared to Worm or opened with it. */
    private val incoming = MutableStateFlow<com.worm.app.download.Incoming?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        com.worm.app.widget.Widgets.syncGameWidget(this)
        // Widgets stuck on a spinner (after an update or a reboot) come back whenever Worm opens.
        if (savedInstanceState == null) com.worm.app.widget.Widgets.refreshAll(this)
        handle(intent)
        setContent {
            val vm: WormViewModel = viewModel()
            val theme by vm.theme.collectAsStateWithLifecycle()
            WormTheme(theme) { WormRoot(this, vm, openPlayer, openPath, incoming, openScreen) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        if (intent?.action == ACTION_OPEN_PLAYER) openPlayer.value = true
        com.worm.app.download.Importer.parse(intent)?.let { incoming.value = it }
        // From notification buttons: open a book ("Read now") or a screen (Downloads).
        if (intent?.action == ACTION_OPEN_BOOK) intent.getStringExtra(EXTRA_BOOK_PATH)?.let { openPath.value = it }
        if (intent?.action == ACTION_OPEN_SCREEN) intent.getStringExtra(EXTRA_SCREEN)?.let { openScreen.value = it }
        val debuggable = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (debuggable) {
            intent?.getStringExtra("open_path")?.let { openPath.value = it }
            debugTools(intent)
        }
    }

    private var padConsumed = -1

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        com.worm.app.ui.screens.HomeKeys.handler?.let { home ->
            if (event.action == KeyEvent.ACTION_DOWN && home(event.keyCode)) { padConsumed = event.keyCode; return true }
            if (event.action == KeyEvent.ACTION_UP && event.keyCode == padConsumed) { padConsumed = -1; return true }
        }
        ReaderKeys.pad?.let { pad ->
            if (event.action == KeyEvent.ACTION_DOWN && pad(event.keyCode)) { padConsumed = event.keyCode; return true }
            if (event.action == KeyEvent.ACTION_UP && event.keyCode == padConsumed) { padConsumed = -1; return true }
        }
        val handler = ReaderKeys.handler
        if (handler != null && (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || event.keyCode == KeyEvent.KEYCODE_VOLUME_UP)) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (handler(event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) return true
            } else if (event.action == KeyEvent.ACTION_UP) {
                // Swallow the matching key-up so the volume panel doesn't flash.
                return com.worm.app.data.Library.get(this).prefs.volumeKeysTurnPages
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * Debug builds only, for testing converters over adb:
     *   --es debug_source <dir> [--ez debug_remove true]   add/remove a library folder
     *   --es debug_convert <file>                           Metamorphosis on a file
     *   --es debug_spin <folder or file path of a library audiobook>
     */
    private fun debugTools(intent: Intent?) {
        intent ?: return
        val lib = com.worm.app.data.Library.get(this)
        intent.getStringExtra("debug_source")?.let { if (intent.getBooleanExtra("debug_remove", false)) lib.removeSource(it) else lib.addSource(it) }
        intent.getStringExtra("debug_download")?.let { u ->
            com.worm.app.download.Downloads.enqueue(this, u, com.worm.app.download.Downloads.guessName(u, null, null), null, null, null, -1)
        }
        intent.getStringExtra("debug_readest_find")?.let { q ->
            kotlin.concurrent.thread {
                runCatching {
                    val r = com.worm.app.sync.ReadestClient(this)
                    r.library().filter { it.title.contains(q, true) }.forEach { b ->
                        val pos = runCatching { r.pull(b.hash) }.getOrNull()
                        android.util.Log.d("WormSync", "cloud '${b.title}' hash=${b.hash} meta=${b.metaHash} fmt=${b.format} updated=${b.updatedAt} spine=${com.worm.app.reader.spineOf(pos?.cfi)} f=${pos?.fraction}")
                    }
                }.onFailure { android.util.Log.w("WormSync", "find failed: $it") }
            }
        }
        intent.getStringExtra("debug_convert")?.let { path ->
            val vm = androidx.lifecycle.ViewModelProvider(this)[WormViewModel::class.java]
            vm.transform(emptyList(), listOf(java.io.File(path)), deleteOriginals = false)
        }
        intent.getStringExtra("debug_spin")?.let { path ->
            lib.books.value.firstOrNull { it.path == path }?.let { com.worm.app.convert.SilkSpinner.enqueue(this, listOf(it), 64_000, deleteOriginals = intent.getBooleanExtra("debug_delete", false)) }
                ?: android.util.Log.w("Worm", "debug_spin: no library book at $path")
        }
    }

    companion object {
        const val ACTION_OPEN_PLAYER = "com.worm.app.OPEN_PLAYER"
        const val ACTION_OPEN_BOOK = "com.worm.app.OPEN_BOOK"
        const val ACTION_OPEN_SCREEN = "com.worm.app.OPEN_SCREEN"
        const val EXTRA_BOOK_PATH = "book_path"
        const val EXTRA_SCREEN = "screen"
    }
}

@Composable
private fun WormRoot(
    activity: MainActivity,
    vm: WormViewModel,
    openPlayer: MutableStateFlow<Boolean>,
    openPath: MutableStateFlow<String?>,
    incoming: MutableStateFlow<com.worm.app.download.Incoming?>,
    openScreen: MutableStateFlow<String?>,
) {
    val context = LocalContext.current
    var splashDone by rememberSaveable { mutableStateOf(false) }
    var hasAccess by remember { mutableStateOf(StorageAccess.has(context)) }
    var onboarded by remember { mutableStateOf(vm.prefs.onboarded) }
    var stack by remember { mutableStateOf(listOf<Screen>(Screen.Home)) }
    fun go(s: Screen) { stack = stack + s }
    fun back() { if (stack.size > 1) stack = stack.dropLast(1) }

    LifecycleResumeEffect(Unit) {
        hasAccess = StorageAccess.has(context)
        if (hasAccess && onboarded) vm.refresh()
        onPauseOrDispose { }
    }

    LaunchedEffect(Unit) { vm.connectPlayer() }

    val wantsPlayer by openPlayer.collectAsStateWithLifecycle()
    LaunchedEffect(wantsPlayer) {
        if (wantsPlayer) {
            if (stack.lastOrNull() != Screen.Player) go(Screen.Player)
            openPlayer.value = false
        }
    }

    // Notifications permission (Android 13+) so the player notification can show.
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(hasAccess) {
        if (hasAccess && Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // Relock the Burrow after time away; never let screenshots/Recents show it.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_STOP -> vm.onAppBackground()
                Lifecycle.Event.ON_START -> vm.onAppForeground()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        vm.messages.collect { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(it) }
    }

    var pickedSources by remember { mutableStateOf(vm.sources.value.map { it.path }) }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(::treeUriToPath)?.let { pickedSources = (pickedSources + it).distinct() }
    }

    val screen: Screen = when {
        !splashDone -> Screen.Splash
        !hasAccess -> Screen.Permission
        !onboarded -> Screen.Onboarding
        else -> stack.last()
    }

    BackHandler(enabled = screen != Screen.Home && stack.size > 1) { back() }

    // Block screenshots only where Burrow books could actually be on screen.
    val unlocked by vm.unlocked.collectAsStateWithLifecycle()
    val playingId by com.worm.app.player.PlayerHub.bookId.collectAsStateWithLifecycle()
    val secure = vm.prefs.secureScreens && when (screen) {
        is Screen.Reader -> vm.library.book(screen.bookId)?.hidden == true
        Screen.Player -> playingId?.let(vm.library::book)?.hidden == true
        Screen.Home, Screen.Stats, Screen.Settings -> unlocked
        else -> false
    }
    LaunchedEffect(secure) {
        if (secure) activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    fun openBook(b: Book) {
        if (b.isAudio) { vm.play(b); go(Screen.Player) } else go(Screen.Reader(b.id))
    }

    // Shared to Worm / "Open with Worm".
    val shared by incoming.collectAsStateWithLifecycle()
    LaunchedEffect(shared, splashDone, hasAccess) {
        val inc = shared ?: return@LaunchedEffect
        if (!splashDone || !hasAccess) return@LaunchedEffect
        incoming.value = null
        when (inc) {
            is com.worm.app.download.Incoming.Magnet -> {
                com.worm.app.torrent.TorrentEngine.addMagnet(context, inc.uri)
                vm.say("Fetching the torrent's details…")
                go(Screen.Downloads)
            }
            is com.worm.app.download.Incoming.Link ->
                if (com.worm.app.download.Importer.isDirectFile(inc.url)) {
                    com.worm.app.download.Downloads.enqueue(context, inc.url, com.worm.app.download.Downloads.guessName(inc.url, null, null), null, null, null, -1)
                    vm.say("Downloading into your library")
                } else go(Screen.Browser(inc.url))
            is com.worm.app.download.Incoming.Files -> {
                // .torrent files go to the torrent client instead of the library.
                val (torrents, rest) = inc.uris.partition { u ->
                    u.toString().endsWith(".torrent", true) || context.contentResolver.getType(u)?.contains("bittorrent") == true
                }
                torrents.forEach { u -> context.contentResolver.openInputStream(u)?.use { com.worm.app.torrent.TorrentEngine.addTorrentBytes(context, it.readBytes()) } }
                if (rest.isEmpty()) return@LaunchedEffect
                val paths = com.worm.app.download.Importer.importFiles(context, rest)
                val books = paths.mapNotNull { p -> vm.library.books.value.firstOrNull { it.path == p || it.tracks.any { t -> t.path == p } } }
                when {
                    books.isEmpty() -> vm.say("Worm couldn't use that file")
                    inc.openAfter && books.size == 1 -> openBook(books.first())
                    else -> vm.say("Added ${com.worm.app.util.plural(books.size, "book")} to your library 🪱")
                }
            }
        }
    }

    val wantScreen by openScreen.collectAsStateWithLifecycle()
    LaunchedEffect(wantScreen, splashDone) {
        if (!splashDone) return@LaunchedEffect
        when (wantScreen) {
            "downloads" -> go(Screen.Downloads); "tools" -> go(Screen.Tools)
            "player" -> go(Screen.Player); "stats" -> go(Screen.Stats)
            "game" -> if (vm.prefs.gameUnlocked) go(Screen.Game)
        }
        openScreen.value = null
    }

    val debugPath by openPath.collectAsStateWithLifecycle()
    val allBooks by vm.books.collectAsStateWithLifecycle()
    LaunchedEffect(debugPath, allBooks.size, splashDone) {
        val p = debugPath ?: return@LaunchedEffect
        if (!splashDone) return@LaunchedEffect
        allBooks.firstOrNull { it.path == p || it.tracks.any { t -> t.path == p } }?.let { openBook(it); openPath.value = null }
    }

    val calm = com.worm.app.ui.theme.LocalWormColors.current.calm
    AnimatedContent(
        targetState = screen,
        contentKey = { it::class to (it as? Screen.Reader)?.bookId },
        transitionSpec = {
            val to = targetState
            val from = initialState
            when {
                calm -> androidx.compose.animation.EnterTransition.None togetherWith androidx.compose.animation.ExitTransition.None
                to == Screen.Player -> slideInVertically(spring(0.85f, 380f)) { it } + fadeIn() togetherWith fadeOut()
                from == Screen.Player -> fadeIn() togetherWith slideOutVertically(spring(0.85f, 380f)) { it } + fadeOut()
                to is Screen.Reader -> (scaleIn(spring(0.8f, 380f), initialScale = 0.92f) + fadeIn()) togetherWith fadeOut()
                to == Screen.Home -> fadeIn() togetherWith slideOutHorizontally(spring(0.85f, 400f)) { it / 3 } + fadeOut()
                from == Screen.Home -> slideInHorizontally(spring(0.85f, 400f)) { it / 3 } + fadeIn() togetherWith fadeOut()
                else -> fadeIn(spring(stiffness = 380f)) togetherWith (scaleOut(targetScale = 0.96f) + fadeOut())
            }
        },
        label = "screens",
    ) { s ->
        when (s) {
            Screen.Splash -> SplashScreen(onFinished = { splashDone = true })
            Screen.Permission -> PermissionScreen(onGrant = { StorageAccess.request(activity) })
            Screen.Onboarding -> OnboardingScreen(
                suggestions = remember { vm.library.suggestedSources() },
                chosen = pickedSources,
                onToggle = { path, on -> pickedSources = if (on) pickedSources + path else pickedSources - path },
                onPickOther = { folderPicker.launch(null) },
                onDone = {
                    pickedSources.forEach(vm.library::addSource)
                    vm.prefs.onboarded = true
                    onboarded = true
                    vm.refresh()
                },
            )
            Screen.Home -> HomeScreen(
                vm = vm,
                snackbar = snackbar,
                onOpenBook = ::openBook,
                onOpenPlayer = { go(Screen.Player) },
                onOpenStats = { go(Screen.Stats) },
                onOpenSettings = { go(Screen.Settings) },
                onOpenWormhole = { go(Screen.Wormhole) },
                onOpenFurthest = { go(Screen.Reader(it.id, furthest = true)) },
                onOpenReadestLibrary = { go(Screen.ReadestLibrary) },
                onOpenBrowser = { go(Screen.Browser()) },
                onOpenDownloads = { go(Screen.Downloads) },
                onOpenCatalogs = { go(Screen.Catalogs) },
                onOpenTools = { go(Screen.Tools) },
            )
            Screen.Player -> PlayerScreen(vm, onBack = ::back)
            is Screen.Reader -> {
                val book = remember(s.bookId) { vm.library.book(s.bookId) }
                if (book == null) LaunchedEffect(Unit) { back() }
                else ReaderScreen(book, vm.library, onBack = ::back, say = vm::say, startAtFurthest = s.furthest)
            }
            Screen.Settings -> SettingsScreen(vm, snackbar, onBack = ::back, onPlayGame = { go(Screen.Game) }, onOpenReadestLibrary = { go(Screen.ReadestLibrary) })
            Screen.ReadestLibrary -> com.worm.app.ui.screens.ReadestLibraryScreen(vm, onBack = ::back, onOpenBook = ::openBook)
            is Screen.Browser -> com.worm.app.ui.screens.BrowserScreen(vm, onBack = ::back, onOpenDownloads = { go(Screen.Downloads) }, startUrl = s.url)
            Screen.Catalogs -> com.worm.app.ui.screens.OpdsScreen(vm, onBack = ::back, onOpenDownloads = { go(Screen.Downloads) })
            Screen.Downloads -> com.worm.app.ui.screens.DownloadsScreen(onBack = ::back, onOpenBrowser = { go(Screen.Browser()) })
            Screen.Game -> com.worm.app.ui.screens.WormGameScreen(vm.prefs, onBack = ::back)
            Screen.Stats -> StatsScreen(vm, onBack = ::back, onOpenBook = ::openBook)
            Screen.Wormhole -> com.worm.app.ui.screens.WormholeScreen(vm, onBack = ::back)
            Screen.Tools -> com.worm.app.ui.screens.ToolsScreen(vm, onBack = ::back, onOpenBook = ::openBook, onFixUp = { go(Screen.FixUp) })
            Screen.FixUp -> com.worm.app.ui.screens.FixUpScreen(vm, onBack = ::back)
        }
    }

    com.worm.app.ui.screens.TorrentPicker()
    LaunchedEffect(Unit) { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.worm.app.torrent.TorrentEngine.resumeSaved(context) } }

    val busy by vm.busy.collectAsStateWithLifecycle()
    busy?.let { msg ->
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            icon = { LoadingIndicator() },
            text = { Text(msg) },
        )
    }
}
