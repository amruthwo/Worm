package com.worm.app.ui.screens

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.CollectionsBookmark
import androidx.compose.material.icons.rounded.CompareArrows
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Workspaces
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.FloatingToolbarExitDirection
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.worm.app.R
import com.worm.app.data.Book
import com.worm.app.data.GroupBy
import com.worm.app.data.KindFilter
import com.worm.app.data.ReadingProgress
import com.worm.app.data.Shelf
import com.worm.app.data.SortKey
import com.worm.app.data.ViewMode
import com.worm.app.ui.WormViewModel
import com.worm.app.ui.components.AuroraBackground
import com.worm.app.ui.components.BookCover
import com.worm.app.ui.components.CoverProgress
import com.worm.app.ui.components.PinDialog
import com.worm.app.ui.components.PinMode
import com.worm.app.ui.components.PixelField
import com.worm.app.ui.components.WigglingWorm
import com.worm.app.ui.theme.LocalWormColors
import com.worm.app.ui.theme.Worm
import com.worm.app.util.Biometrics
import com.worm.app.util.formatHours
import com.worm.app.util.plural
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    vm: WormViewModel,
    snackbar: SnackbarHostState,
    onOpenBook: (Book) -> Unit,
    onOpenPlayer: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenWormhole: () -> Unit,
    onOpenTools: () -> Unit,
    onOpenFurthest: (Book) -> Unit,
    onOpenReadestLibrary: () -> Unit,
    onOpenBrowser: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenCatalogs: () -> Unit,
) {
    val shelfie by vm.shelfie.collectAsStateWithLifecycle()
    val books by vm.books.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val inProgress by vm.inProgress.collectAsStateWithLifecycle()
    val shelves by vm.visibleShelves.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val viewMode by vm.viewMode.collectAsStateWithLifecycle()
    val unlocked by vm.unlocked.collectAsStateWithLifecycle()
    val wc = LocalWormColors.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    // Our own hide-on-scroll: the stock behaviour only slides the toolbar by its own height, which
    // leaves it peeking out above the docked player bar.
    var toolbarShown by remember { mutableStateOf(true) }
    var trayOpen by remember { mutableStateOf(false) }
    val toolbarScroll = remember {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                if (available.y < -6f) { toolbarShown = false; trayOpen = false } else if (available.y > 6f) toolbarShown = true
                return androidx.compose.ui.geometry.Offset.Zero
            }
        }
    }
    val controller by vm.controller.collectAsStateWithLifecycle()
    val nowPlayingId by com.worm.app.player.PlayerHub.bookId.collectAsStateWithLifecycle()
    val playerUi = rememberPlayerUi(controller)
    val hasNowPlaying = nowPlayingId != null && playerUi.hasMedia

    var searchOpen by remember { mutableStateOf(false) }
    // Wide screens (landscape on big phones, tablets): two panes, tools in a side panel.
    val wide = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 840
    // Re-checked on every return to the screen, so signing in/out shows or hides the cloud right away.
    var readestSignedIn by remember { mutableStateOf(false) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        readestSignedIn = com.worm.app.sync.ProgressSync.readest(context) != null
        onPauseOrDispose { }
    }
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    // Page-turn buttons scroll the library a screen at a time: page up/down always, and the
    // volume keys too on the E-ink theme (e-readers like Boox map their page buttons to volume).
    val calmTheme = com.worm.app.ui.theme.LocalWormColors.current.calm
    androidx.compose.runtime.DisposableEffect(calmTheme) {
        HomeKeys.handler = { code ->
            val down = code == android.view.KeyEvent.KEYCODE_PAGE_DOWN || (calmTheme && code == android.view.KeyEvent.KEYCODE_VOLUME_DOWN)
            val up = code == android.view.KeyEvent.KEYCODE_PAGE_UP || (calmTheme && code == android.view.KeyEvent.KEYCODE_VOLUME_UP)
            if (down || up) {
                val page = gridState.layoutInfo.viewportSize.height * 0.85f * (if (down) 1 else -1)
                scope.launch { if (calmTheme) gridState.scrollBy(page) else gridState.animateScrollBy(page) }
                true
            } else false
        }
        onDispose { HomeKeys.handler = null }
    }
    // New search text → jump to the top of the results.
    LaunchedEffect(query.text) { if (query.text.isNotEmpty()) gridState.scrollToItem(0) }
    var menu by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf<Book?>(null) }
    var editing by remember { mutableStateOf<String?>(null) }
    var newShelf by remember { mutableStateOf(false) }
    var editShelf by remember { mutableStateOf<Shelf?>(null) }
    var pinMode by remember { mutableStateOf<PinMode?>(null) }
    var afterPin by remember { mutableStateOf<(() -> Unit)?>(null) }

    fun toggleLock() {
        when {
            unlocked -> vm.lock()
            !vm.pinIsSet -> pinMode = PinMode.Create
            vm.biometric.value && Biometrics.available(context) -> Biometrics.prompt(
                context, "Open the Burrow",
                onSuccess = vm::unlockTrusted,
                onFallback = { pinMode = PinMode.Unlock },
            )
            else -> pinMode = PinMode.Unlock
        }
    }

    fun withPin(action: () -> Unit) {
        if (vm.pinIsSet) action() else { afterPin = action; pinMode = PinMode.Create }
    }

    val visible = books.filter { !it.hidden || unlocked }
    val ebookCount = visible.count { !it.isAudio }
    val audioCount = visible.count { it.isAudio }
    val activeShelf = shelves.firstOrNull { it.id == query.shelfId }

    Box(Modifier.fillMaxSize()) {
        AuroraBackground(emptyList())
        if (wc.particles) PixelField(Modifier.fillMaxSize(), count = 34, intensity = 0.5f, colors = wc.dust)
        Row(Modifier.fillMaxSize()) {
        if (wide) SidePanel(
            query = query, shelves = shelves, unlocked = unlocked, viewMode = viewMode, readest = readestSignedIn,
            counts = mapOf(KindFilter.All to visible.size, KindFilter.Ebooks to ebookCount, KindFilter.Audiobooks to audioCount),
            onSearch = vm::setSearch, onKind = vm::setKind,
            onShelf = { vm.openShelf(if (query.shelfId == it.id) null else it.id) },
            onEditShelf = { editShelf = it }, onNewShelf = { newShelf = true },
            onViewMode = vm::setViewMode, onSort = vm::setSort, onGroup = vm::setGroup,
            onLock = { toggleLock() },
            onPlace = { place ->
                when (place) {
                    "books" -> onOpenBrowser(); "catalogs" -> onOpenCatalogs(); "tools" -> onOpenTools()
                    "wormhole" -> onOpenWormhole(); "readest" -> onOpenReadestLibrary(); "downloads" -> onOpenDownloads()
                }
            },
        )
        Box(Modifier.weight(1f).fillMaxHeight()) {
        Scaffold(
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onBackground,
            snackbarHost = { SnackbarHost(snackbar, Modifier.padding(bottom = if (hasNowPlaying) 170.dp else 100.dp)) },
            modifier = Modifier
                .nestedScroll(scroll.nestedScrollConnection)
                .nestedScroll(toolbarScroll),
            topBar = {
                LargeFlexibleTopAppBar(
                    title = { Text(activeShelf?.let { listOfNotNull(it.emoji, it.name).joinToString(" ") } ?: "Worm") },
                    subtitle = {
                        Text(
                            when {
                                activeShelf != null -> plural(shelfie.total, "book")
                                books.isEmpty() && scanning -> "Digging for books…"
                                else -> "${plural(ebookCount, "book")} · ${plural(audioCount, "audiobook")}"
                            },
                        )
                    },
                    navigationIcon = {
                        if (activeShelf != null) IconButton(onClick = { vm.openShelf(null) }) { Icon(Icons.Rounded.Close, "Back to everything") }
                        else Image(painterResource(R.drawable.worm_logo), null, Modifier.padding(start = 8.dp, end = 2.dp).size(48.dp))
                    },
                    actions = {
                        IconButton(onClick = onOpenStats) { Icon(Icons.Rounded.Insights, "Stats & storage") }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                            DropdownMenu(menu, onDismissRequest = { menu = false }, shape = RoundedCornerShape(20.dp)) {
                                DropdownMenuItem(
                                    text = { Text("Downloads") },
                                    leadingIcon = { Icon(Icons.Rounded.Download, null) },
                                    onClick = { menu = false; onOpenDownloads() },
                                )
                                if (activeShelf != null) DropdownMenuItem(
                                    text = { Text("Edit this shelf") },
                                    leadingIcon = { Icon(Icons.Rounded.CollectionsBookmark, null) },
                                    onClick = { menu = false; editShelf = activeShelf },
                                )
                                DropdownMenuItem(
                                    text = { Text("Settings") },
                                    leadingIcon = { Icon(Icons.Rounded.Settings, null) },
                                    onClick = { menu = false; onOpenSettings() },
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f),
                    ),
                    scrollBehavior = scroll,
                    // Short screens (landscape phones): stay compact so the books are visible.
                    expandedHeight = if (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp < 500) TopAppBarDefaults.LargeAppBarCollapsedHeight
                        else TopAppBarDefaults.LargeFlexibleAppBarWithSubtitleExpandedHeight,
                )
            },
        ) { padding ->
            val refreshState = rememberPullToRefreshState()
            PullToRefreshBox(
                isRefreshing = scanning && books.isNotEmpty(),
                onRefresh = { vm.refresh() },
                state = refreshState,
                modifier = Modifier.padding(top = padding.calculateTopPadding()),
                indicator = {
                    PullToRefreshDefaults.LoadingIndicator(
                        state = refreshState,
                        isRefreshing = scanning && books.isNotEmpty(),
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                },
            ) {
                if (visible.isEmpty() && !scanning) {
                    EmptyLibrary(onOpenSettings)
                } else {
                    // Columns follow the screen: phones in portrait look as before; landscape/tablets get more, normal-sized covers.
                    val minCell = when (viewMode) { ViewMode.List -> 340.dp; ViewMode.Grid2 -> 160.dp; ViewMode.Grid3 -> 108.dp }
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Adaptive(minCell),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = if (searchOpen && !wide) 76.dp else 4.dp, bottom = when { wide && hasNowPlaying -> 120.dp; wide -> 24.dp; hasNowPlaying -> 220.dp; else -> 140.dp }),
                        verticalArrangement = Arrangement.spacedBy(if (viewMode == ViewMode.List) 8.dp else 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        if (inProgress.isNotEmpty() && query.text.isEmpty() && activeShelf == null) {
                            item(key = "continue", span = { GridItemSpan(maxLineSpan) }) {
                                ContinueRow(inProgress, progress, activeAudioId = nowPlayingId.takeIf { playerUi.playing }, sync = if (vm.syncAvailable()) SyncActions(vm::pushSpot, vm::pullSpot, onOpenFurthest) else null, onOpen = onOpenBook, onDetails = { details = it }, onRemove = vm::dismissFromResume, modifier = Modifier.animateItem())
                            }
                        }
                        if (!wide) item(key = "shelves", span = { GridItemSpan(maxLineSpan) }) {
                            ShelfRow(
                                shelves = shelves, active = query.shelfId,
                                onPick = { vm.openShelf(if (query.shelfId == it.id) null else it.id) },
                                onLong = { editShelf = it },
                                onNew = { newShelf = true },
                            )
                        }
                        if (!wide) item(key = "filters", span = { GridItemSpan(maxLineSpan) }) {
                            FilterRow(query.kind, vm::setKind)
                        }
                        // Square slots when everything shown is audio, so rows don't get tall empty gaps.
                        val slotAspect = if (shelfie.groups.all { g -> g.second.all { it.isAudio } }) 1f else 0.68f
                        shelfie.groups.forEach { (label, list) ->
                            if (label != null) {
                                item(key = "h:$label", span = { GridItemSpan(maxLineSpan) }) {
                                    Row(Modifier.padding(top = 14.dp, start = 4.dp).animateItem(), verticalAlignment = Alignment.Bottom) {
                                        Text(label, style = MaterialTheme.typography.titleLarge)
                                        Spacer(Modifier.width(8.dp))
                                        Text("${list.size}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                            items(list, key = { "${label}:${it.id}" }) { b ->
                                val p = progress[b.id]
                                val mod = Modifier.animateItem()
                                if (viewMode == ViewMode.List) BookRow(b, p, { onOpenBook(b) }, { details = b }, mod)
                                else BookTile(b, p, compact = viewMode == ViewMode.Grid3, slotAspect = slotAspect, onClick = { onOpenBook(b) }, onLongClick = { details = b }, modifier = mod)
                            }
                        }
                        if (shelfie.total == 0 && visible.isNotEmpty()) {
                            item(key = "nothing", span = { GridItemSpan(maxLineSpan) }) {
                                Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    WigglingWorm(Modifier.size(120.dp, 70.dp))
                                    Text(
                                        if (activeShelf != null) "This shelf is empty. Long-press any book to add it here." else "No books match that.",
                                        textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
                // Floating search: always visible right under the top bar, wherever you've scrolled to.
                androidx.compose.animation.AnimatedVisibility(
                    searchOpen && !wide,
                    enter = androidx.compose.animation.slideInVertically { -it } + fadeIn(),
                    exit = androidx.compose.animation.slideOutVertically { -it } + fadeOut(),
                    modifier = Modifier.align(Alignment.TopCenter),
                ) {
                    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
                    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                    androidx.compose.material3.Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        shadowElevation = 8.dp,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    ) {
                        OutlinedTextField(
                            value = query.text,
                            onValueChange = vm::setSearch,
                            placeholder = { Text("Title, author, narrator, series…") },
                            leadingIcon = { Icon(Icons.Rounded.Search, null) },
                            trailingIcon = {
                                if (query.text.isNotEmpty()) IconButton(onClick = { vm.setSearch("") }) { Icon(Icons.Rounded.Close, "Clear search") }
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(50),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            colors = OutlinedTextFieldDefaults.colors(
                                unfocusedBorderColor = Color.Transparent,
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                            modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        )
                    }
                }
            }
        }

        Column(Modifier.align(Alignment.BottomCenter), horizontalAlignment = Alignment.CenterHorizontally) {
            AnimatedVisibility(
                trayOpen && toolbarShown && !wide,
                modifier = Modifier.align(Alignment.End),
                enter = androidx.compose.animation.expandVertically(expandFrom = Alignment.Bottom) + fadeIn(),
                exit = androidx.compose.animation.shrinkVertically(shrinkTowards = Alignment.Bottom) + fadeOut(),
            ) {
                PlacesTray(
                    readest = readestSignedIn,
                    onPick = { place ->
                        trayOpen = false
                        when (place) {
                            "books" -> onOpenBrowser(); "catalogs" -> onOpenCatalogs(); "tools" -> onOpenTools()
                            "wormhole" -> onOpenWormhole(); "readest" -> onOpenReadestLibrary()
                        }
                    },
                )
            }
            AnimatedVisibility(
                toolbarShown && !wide,
                enter = androidx.compose.animation.slideInVertically { it } + fadeIn(),
                exit = androidx.compose.animation.slideOutVertically { it * 2 } + fadeOut(),
            ) {
            HorizontalFloatingToolbar(
                expanded = true,
                colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(),
                modifier = Modifier.padding(bottom = 12.dp).then(if (hasNowPlaying) Modifier else Modifier.navigationBarsPadding()),
            ) {
                ViewSplitButton(viewMode, vm::setViewMode)
                IconButton(onClick = { searchOpen = !searchOpen; if (!searchOpen) vm.setSearch("") }) {
                    Icon(if (searchOpen) Icons.Rounded.Close else Icons.Rounded.Search, "Search library", tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Box {
                    IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Rounded.Sort, "Sort & group", tint = MaterialTheme.colorScheme.onPrimaryContainer) }
                    SortMenu(sortMenu, query, onDismiss = { sortMenu = false }, onSort = vm::setSort, onGroup = vm::setGroup)
                }
                VerticalDivider(
                    Modifier.height(28.dp).padding(horizontal = 6.dp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.25f),
                )
                FilledIconToggleButton(
                    checked = unlocked,
                    onCheckedChange = { toggleLock() },
                    colors = toggleColors(),
                ) {
                    AnimatedContent(
                        unlocked,
                        transitionSpec = { scaleIn(spring(0.4f, 500f)) + fadeIn() togetherWith fadeOut() },
                        label = "lock",
                    ) { open -> Icon(if (open) Icons.Rounded.LockOpen else Icons.Rounded.Lock, if (open) "Close the Burrow" else "Open the Burrow") }
                }
                // ^ opens the tray of places to go (with names), above the bar.
                IconButton(onClick = { trayOpen = !trayOpen }) {
                    val turn by androidx.compose.animation.core.animateFloatAsState(if (trayOpen) 180f else 0f, label = "tray")
                    Icon(Icons.Rounded.KeyboardArrowUp, if (trayOpen) "Close menu" else "More places", tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.graphicsLayer { rotationZ = turn })
                }
            }
            }
            MiniPlayer(vm, onOpen = onOpenPlayer)
        }
        }
        }
    }

    // ------------------------------------------------------------- dialogs

    pinMode?.let { mode ->
        PinDialog(
            mode = mode,
            title = if (mode == PinMode.Create) "Set a PIN for the Burrow" else null,
            onDismiss = { pinMode = null; afterPin = null },
            check = { vm.unlock(it) },
            onSuccess = { code ->
                pinMode = null
                if (mode == PinMode.Create) scope.launch {
                    vm.createPin(code)
                    afterPin?.invoke(); afterPin = null
                }
            },
        )
    }
    details?.let { b ->
        BookSheet(
            vm = vm, book = b, progress = progress[b.id], shelves = shelves,
            onDismiss = { details = null },
            onOpen = { details = null; onOpenBook(b) },
            onBurrow = { hide -> details = null; if (hide) withPin { vm.burrow(b, true) } else vm.burrow(b, false) },
            onEdit = { details = null; editing = b.id },
            onOpenFurthest = { details = null; onOpenFurthest(b) },
        )
    }
    editing?.let { id -> EditDetailsSheet(vm, id, onDismiss = { editing = null }) }
    if (newShelf) {
        ShelfDialog(
            initial = null,
            onDismiss = { newShelf = false },
            onSave = { name, emoji, hidden ->
                newShelf = false
                if (hidden) withPin { vm.createShelf(name, emoji, true) } else vm.createShelf(name, emoji, false)
            },
        )
    }
    editShelf?.let { s ->
        ShelfDialog(
            initial = s,
            onDismiss = { editShelf = null },
            onSave = { name, emoji, hidden ->
                editShelf = null
                vm.renameShelf(s.id, name, emoji)
                if (hidden != s.hidden) { if (hidden) withPin { vm.setShelfHidden(s.id, true) } else vm.setShelfHidden(s.id, false) }
            },
            onDelete = { editShelf = null; vm.deleteShelf(s.id) },
        )
    }
}

@Composable
private fun toggleColors() = IconButtonDefaults.filledIconToggleButtonColors(
    containerColor = Color.Transparent,
    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    checkedContainerColor = MaterialTheme.colorScheme.onPrimaryContainer,
    checkedContentColor = MaterialTheme.colorScheme.primaryContainer,
)

@Composable
private fun ViewToggle(icon: ImageVector, label: String, checked: Boolean, onClick: () -> Unit) {
    FilledIconToggleButton(checked = checked, onCheckedChange = { onClick() }, colors = toggleColors()) { Icon(icon, label) }
}

@Composable
private fun SortMenu(
    open: Boolean,
    q: com.worm.app.ui.LibraryQuery,
    onDismiss: () -> Unit,
    onSort: (SortKey) -> Unit,
    onGroup: (GroupBy) -> Unit,
) {
    DropdownMenu(open, onDismissRequest = onDismiss, shape = RoundedCornerShape(20.dp)) {
        Text("Sort by", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        SortKey.entries.forEach { k ->
            DropdownMenuItem(
                text = { Text(k.label) },
                trailingIcon = { if (q.sort == k) Icon(if (q.descending) Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward, null) },
                onClick = { onSort(k); onDismiss() },
            )
        }
        HorizontalDivider()
        Text("Group by", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        GroupBy.entries.forEach { g ->
            DropdownMenuItem(
                text = { Text(g.label) },
                trailingIcon = { if (q.group == g) Icon(Icons.Rounded.CollectionsBookmark, null) },
                onClick = { onGroup(g); onDismiss() },
            )
        }
    }
}

@Composable
private fun FilterRow(kind: KindFilter, onKind: (KindFilter) -> Unit) {
    Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        KindFilter.entries.forEach { k ->
            FilterChip(
                selected = kind == k,
                onClick = { onKind(k) },
                label = { Text(k.label) },
                leadingIcon = {
                    Icon(
                        when (k) { KindFilter.All -> Icons.Rounded.CollectionsBookmark; KindFilter.Ebooks -> Icons.Rounded.AutoStories; KindFilter.Audiobooks -> Icons.Rounded.Headphones },
                        null, Modifier.size(FilterChipDefaults.IconSize),
                    )
                },
                shape = RoundedCornerShape(50),
                colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f)),
            )
        }
    }
}

@Composable
private fun ShelfRow(shelves: List<Shelf>, active: String?, onPick: (Shelf) -> Unit, onLong: (Shelf) -> Unit, onNew: () -> Unit) {
    if (shelves.isEmpty()) return
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
        items(shelves, key = { it.id }) { s ->
            val selected = s.id == active
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = when {
                        selected -> MaterialTheme.colorScheme.primary
                        s.hidden -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.8f)
                        else -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.8f)
                    },
                    contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
                ),
                modifier = Modifier.height(40.dp).combinedClickable(onClick = { onPick(s) }, onLongClick = { onLong(s) }),
            ) {
                Row(Modifier.fillMaxHeight().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (s.hidden) { Icon(Icons.Rounded.Lock, null, Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)) }
                    Text(listOfNotNull(s.emoji, s.name).joinToString(" "), style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.width(6.dp))
                    Text("${s.bookIds.size}", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        item {
            // Same size as the shelves, with the faint outline the filter chips use.
            Card(
                onClick = onNew,
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f), contentColor = MaterialTheme.colorScheme.onSurface),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.height(40.dp),
            ) {
                Row(Modifier.fillMaxHeight().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Add, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Shelf", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun ContinueRow(
    books: List<Book>,
    progress: Map<String, ReadingProgress>,
    activeAudioId: String?,
    sync: SyncActions?,
    onOpen: (Book) -> Unit,
    onDetails: (Book) -> Unit,
    onRemove: (Book) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text("Resume reading & listening", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(books, key = { it.id }) { b ->
                val p = progress[b.id]
                var menu by remember { mutableStateOf(false) }
                Box {
                DropdownMenu(menu, onDismissRequest = { menu = false }, shape = RoundedCornerShape(20.dp)) {
                    DropdownMenuItem(text = { Text("Remove from Resume") }, leadingIcon = { Icon(Icons.Rounded.Close, null) }, onClick = { menu = false; onRemove(b) })
                    if (sync != null && !b.isAudio) {
                        DropdownMenuItem(text = { Text("Open at furthest read") }, leadingIcon = { Icon(Icons.Rounded.FastForward, null) }, onClick = { menu = false; sync.furthest(b) })
                        DropdownMenuItem(text = { Text("Pull newest spot") }, leadingIcon = { Icon(Icons.Rounded.CloudDownload, null) }, onClick = { menu = false; sync.pull(b) })
                        DropdownMenuItem(text = { Text("Push my spot") }, leadingIcon = { Icon(Icons.Rounded.CloudUpload, null) }, onClick = { menu = false; sync.push(b) })
                    }
                    DropdownMenuItem(text = { Text("Details & options") }, leadingIcon = { Icon(Icons.Rounded.MoreVert, null) }, onClick = { menu = false; onDetails(b) })
                }
                Card(
                    shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (b.isAudio) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.85f) else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f),
                        contentColor = if (b.isAudio) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                    modifier = Modifier.width(300.dp).height(148.dp).combinedClickable(onClick = { onOpen(b) }, onLongClick = { menu = true }),
                ) {
                    Row(Modifier.fillMaxSize().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        // Same frame for every card; the cover sits centred in it at its own shape.
                        Box(Modifier.width(84.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
                            Box(Modifier.fillMaxWidth().aspectRatio(if (b.isAudio) 1f else 0.68f)) {
                                BookCover(b, Modifier.fillMaxSize(), corner = 14.dp, showKindBadge = false)
                            }
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(if (b.isAudio) "Keep listening" else "Keep reading", style = MaterialTheme.typography.labelMedium)
                            Text(b.title, style = MaterialTheme.typography.titleMedium, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(b.author, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(8.dp))
                            val f = p?.fraction ?: 0f
                            // Wiggles only for the audiobook that's playing right now; otherwise a calm flat bar.
                            if (b.id == activeAudioId) androidx.compose.material3.LinearWavyProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth())
                            else androidx.compose.material3.LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth(), drawStopIndicator = {})
                            Text(
                                if (b.isAudio && b.durationMs > 0) "${formatHours((b.durationMs * (1 - f)).toLong())} left" else "${(f * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
                }
            }
        }
    }
}

@Composable
private fun BookTile(
    book: Book,
    p: ReadingProgress?,
    compact: Boolean,
    slotAspect: Float,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(slotAspect), contentAlignment = Alignment.BottomCenter) {
            // Audiobook covers are square: sit them on the shelf and let the tile breathe.
            Box(Modifier.fillMaxWidth().aspectRatio(if (book.isAudio) 1f else 0.68f)) {
                BookCover(book, Modifier.fillMaxSize(), corner = if (compact) 12.dp else 16.dp)
                Box(Modifier.matchParentSize().padding(0.dp)) { CoverProgress(p?.fraction ?: 0f, book.isAudio) }
                if (book.hidden) Icon(Icons.Rounded.Lock, null, tint = Worm.Mint, modifier = Modifier.align(Alignment.TopStart).padding(6.dp).size(16.dp))
            }
        }
        Text(
            book.title, style = if (compact) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleSmall,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp, start = 2.dp),
        )
        if (!compact) Text(book.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 2.dp))
    }
}

@Composable
private fun BookRow(book: Book, p: ReadingProgress?, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.78f), contentColor = MaterialTheme.colorScheme.onSurface),
        modifier = modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(56.dp).aspectRatio(if (book.isAudio) 1f else 0.68f)) {
                BookCover(book, Modifier.fillMaxSize(), corner = 10.dp, showKindBadge = false)
                Box(Modifier.matchParentSize()) { CoverProgress(p?.fraction ?: 0f, book.isAudio) }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(book.author, book.series?.let { s -> book.seriesIndex?.let { "$s #${it.toString().removeSuffix(".0")}" } ?: s }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    buildString {
                        append(book.format.uppercase())
                        if (book.isAudio && book.durationMs > 0) append(" · ${formatHours(book.durationMs)}")
                        book.narrator?.let { append(" · read by $it") }
                        p?.let { if (it.finished) append(" · finished") else if (it.fraction > 0) append(" · ${(it.fraction * 100).toInt()}%") }
                    },
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(if (book.isAudio) Icons.Rounded.Headphones else Icons.Rounded.AutoStories, null, tint = if (book.isAudio) Worm.Mint else Worm.Pink, modifier = Modifier.padding(horizontal = 6.dp))
        }
    }
}

@Composable
private fun EmptyLibrary(onOpenSettings: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(40.dp))
        WigglingWorm(Modifier.size(220.dp, 120.dp))
        Spacer(Modifier.height(16.dp))
        Text("The worm is hungry", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Add a folder with books (.epub, .mobi, .azw3, .fb2, .cbz) or audiobooks (.m4b, .mp3, folders of tracks) and Worm will find them.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        BigButton("Add a folder", Icons.Rounded.Add, onOpenSettings)
    }
}


/** Sync actions offered on long-press (books only; audiobooks don't sync). */
class SyncActions(val push: (Book) -> Unit, val pull: (Book) -> Unit, val furthest: (Book) -> Unit)


private val ViewModes = listOf(
    Triple(ViewMode.List, Icons.AutoMirrored.Rounded.ViewList, "List"),
    Triple(ViewMode.Grid2, Icons.Rounded.GridView, "Big covers"),
    Triple(ViewMode.Grid3, Icons.Rounded.Apps, "Small covers"),
)

/** Library layout: one button that cycles list → big covers → small covers. */
@Composable
private fun ViewSplitButton(mode: ViewMode, onMode: (ViewMode) -> Unit) {
    val current = ViewModes.first { it.first == mode }
    val next = ViewModes[(ViewModes.indexOf(current) + 1) % ViewModes.size]
    IconButton(onClick = { onMode(next.first) }) {
        AnimatedContent(current, transitionSpec = { scaleIn(spring(0.5f, 500f)) + fadeIn() togetherWith fadeOut() }, label = "view") { c ->
            Icon(c.second, "Layout: ${c.third}. Tap for ${next.third.lowercase()}", tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}


private fun placesFor(readest: Boolean, downloads: Boolean) = buildList {
    add(Triple("books", Icons.Rounded.Language, "Get books"))
    add(Triple("catalogs", Icons.Rounded.CollectionsBookmark, "Catalogs"))
    if (downloads) add(Triple("downloads", Icons.Rounded.Download, "Downloads"))
    add(Triple("tools", Icons.Rounded.CompareArrows, "Tools"))
    add(Triple("wormhole", Icons.Rounded.Workspaces, "Wormhole"))
    if (readest) add(Triple("readest", Icons.Rounded.Cloud, "Readest cloud"))
}

/** The ^ tray: places to go, with their names, stacked like a menu above the arrow. */
@Composable
private fun PlacesTray(readest: Boolean, onPick: (String) -> Unit) {
    androidx.compose.material3.Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shadowElevation = 6.dp,
        modifier = Modifier.padding(bottom = 8.dp, end = 16.dp).widthIn(min = 220.dp),
    ) {
        Column(Modifier.width(IntrinsicSize.Max).padding(vertical = 8.dp)) {
            placesFor(readest, downloads = false).forEach { (key, icon, label) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).clickable { onPick(key) }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    Icon(icon, null, Modifier.size(22.dp))
                    Spacer(Modifier.width(14.dp))
                    Text(label, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/** Left pane on wide screens: everything the floating bar, shelf row and filter row do, with room to breathe. */
@Composable
private fun SidePanel(
    query: com.worm.app.ui.LibraryQuery,
    shelves: List<Shelf>,
    unlocked: Boolean,
    viewMode: ViewMode,
    readest: Boolean,
    counts: Map<KindFilter, Int>,
    onSearch: (String) -> Unit,
    onKind: (KindFilter) -> Unit,
    onShelf: (Shelf) -> Unit,
    onEditShelf: (Shelf) -> Unit,
    onNewShelf: () -> Unit,
    onViewMode: (ViewMode) -> Unit,
    onSort: (SortKey) -> Unit,
    onGroup: (GroupBy) -> Unit,
    onLock: () -> Unit,
    onPlace: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var sortMenu by remember { mutableStateOf(false) }
    @Composable fun Heading(t: String) = Text(t, style = MaterialTheme.typography.labelLarge, color = cs.primary, modifier = Modifier.padding(start = 12.dp, top = 18.dp, bottom = 6.dp))
    @Composable fun Item(icon: ImageVector, label: String, selected: Boolean = false, badge: String? = null, onLong: (() -> Unit)? = null, onClick: () -> Unit) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(50))
                .background(if (selected) cs.secondaryContainer else Color.Transparent)
                .combinedClickable(onClick = onClick, onLongClick = onLong)
                .padding(horizontal = 14.dp),
        ) {
            Icon(icon, null, Modifier.size(22.dp), tint = if (selected) cs.onSecondaryContainer else cs.onSurfaceVariant)
            Spacer(Modifier.width(14.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) cs.onSecondaryContainer else cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (badge != null) Text(badge, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
        }
    }
    androidx.compose.material3.Surface(
        color = cs.surfaceContainerLow.copy(alpha = 0.85f),
        contentColor = cs.onSurface,
        shape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp),
        modifier = Modifier.width(300.dp).fillMaxHeight(),
    ) {
        Column(
            Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 12.dp),
        ) {
            OutlinedTextField(
                value = query.text,
                onValueChange = onSearch,
                placeholder = { Text("Search", maxLines = 1) },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = { if (query.text.isNotEmpty()) IconButton(onClick = { onSearch("") }) { Icon(Icons.Rounded.Close, "Clear search") } },
                singleLine = true,
                shape = RoundedCornerShape(50),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth(),
            )
            Heading("Show")
            KindFilter.entries.forEach { k ->
                Item(
                    when (k) { KindFilter.All -> Icons.Rounded.CollectionsBookmark; KindFilter.Ebooks -> Icons.Rounded.AutoStories; KindFilter.Audiobooks -> Icons.Rounded.Headphones },
                    k.label, selected = query.kind == k, badge = counts[k]?.toString(),
                ) { onKind(k) }
            }
            Heading("Shelves")
            shelves.forEach { s ->
                Item(
                    if (s.hidden) Icons.Rounded.Lock else Icons.Rounded.CollectionsBookmark,
                    listOfNotNull(s.emoji, s.name).joinToString(" "),
                    selected = s.id == query.shelfId, badge = "${s.bookIds.size}", onLong = { onEditShelf(s) },
                ) { onShelf(s) }
            }
            Item(Icons.Rounded.Add, "New shelf", onClick = onNewShelf)
            Heading("Layout")
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp)) {
                ViewModes.forEach { (m, icon, label) -> ViewToggle(icon, label, viewMode == m) { onViewMode(m) } }
            }
            Box {
                Item(Icons.AutoMirrored.Rounded.Sort, "Sort & group", badge = query.sort.label) { sortMenu = true }
                SortMenu(sortMenu, query, onDismiss = { sortMenu = false }, onSort = onSort, onGroup = onGroup)
            }
            Heading("Go to")
            placesFor(readest, downloads = true).forEach { (key, icon, label) -> Item(icon, label) { onPlace(key) } }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(Modifier.padding(horizontal = 12.dp))
            Spacer(Modifier.height(8.dp))
            Item(if (unlocked) Icons.Rounded.LockOpen else Icons.Rounded.Lock, if (unlocked) "Close the Burrow" else "Open the Burrow", selected = unlocked, onClick = onLock)
        }
    }
}

/** Hardware page buttons on the library screen (see MainActivity.dispatchKeyEvent). */
object HomeKeys {
    @Volatile var handler: ((keyCode: Int) -> Boolean)? = null
}
