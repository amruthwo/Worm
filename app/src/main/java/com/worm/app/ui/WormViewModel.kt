package com.worm.app.ui

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import com.worm.app.data.Book
import com.worm.app.data.BookKind
import com.worm.app.data.GroupBy
import com.worm.app.data.KindFilter
import com.worm.app.data.Library
import com.worm.app.data.PinLock
import com.worm.app.data.ReadingProgress
import com.worm.app.data.Shelf
import com.worm.app.data.SortKey
import com.worm.app.data.ViewMode
import com.worm.app.player.PlayerHub
import com.worm.app.ui.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Everything the library screen shows, already filtered/sorted/grouped. */
data class Shelfie(
    val groups: List<Pair<String?, List<Book>>>,
    val total: Int,
)

data class LibraryQuery(
    val text: String = "",
    val kind: KindFilter = KindFilter.All,
    val sort: SortKey = SortKey.Recent,
    val descending: Boolean = false,
    val group: GroupBy = GroupBy.None,
    val shelfId: String? = null,
)

class WormViewModel(app: Application) : AndroidViewModel(app) {
    val library = Library.get(app)
    val prefs = library.prefs
    private val pin = PinLock(prefs)

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages
    fun say(msg: String) { _messages.tryEmit(msg) }

    // ----------------------------------------------------------------- look

    private val _theme = MutableStateFlow(prefs.appTheme)
    val theme: StateFlow<AppTheme> = _theme.asStateFlow()
    fun setTheme(t: AppTheme) { prefs.appTheme = t; _theme.value = t }

    private val _viewMode = MutableStateFlow(prefs.viewMode)
    val viewMode: StateFlow<ViewMode> = _viewMode.asStateFlow()
    fun setViewMode(m: ViewMode) { prefs.viewMode = m; _viewMode.value = m }

    // ---------------------------------------------------------------- query

    private val _query = MutableStateFlow(
        LibraryQuery(kind = prefs.kindFilter, sort = prefs.sortKey, descending = prefs.sortDescending, group = prefs.groupBy),
    )
    val query: StateFlow<LibraryQuery> = _query.asStateFlow()

    fun setSearch(text: String) { _query.value = _query.value.copy(text = text) }
    fun setKind(k: KindFilter) { prefs.kindFilter = k; _query.value = _query.value.copy(kind = k) }
    fun setSort(s: SortKey) {
        val q = _query.value
        val desc = if (q.sort == s) !q.descending else false
        prefs.sortKey = s; prefs.sortDescending = desc
        _query.value = q.copy(sort = s, descending = desc)
    }
    fun setGroup(g: GroupBy) { prefs.groupBy = g; _query.value = _query.value.copy(group = g) }
    fun openShelf(id: String?) { _query.value = _query.value.copy(shelfId = id) }

    val books = library.books
    val progress = library.progress
    val shelves = library.shelves
    val sources = library.sources
    val scanning = library.scanning
    val unlocked: StateFlow<Boolean> = library.burrowOpen.asStateFlow()

    val visibleShelves: StateFlow<List<Shelf>> = combine(library.shelves, unlocked) { s, open -> s.filter { !it.hidden || open } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val shelfie: StateFlow<Shelfie> = combine(library.books, library.progress, _query, unlocked, library.shelves) { all, prog, q, open, shelves ->
        arrange(all, prog, q, open, shelves)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, Shelfie(emptyList(), 0))

    /** Books you're partway through, most recent first (the "Continue" carousel). */
    val inProgress: StateFlow<List<Book>> = combine(library.books, library.progress, unlocked) { all, prog, open ->
        all.filter { (!it.hidden || open) && prog[it.id]?.showInResume == true }
            .sortedByDescending { prog[it.id]?.updatedAt ?: 0L }
            .take(20)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private fun arrange(all: List<Book>, prog: Map<String, ReadingProgress>, q: LibraryQuery, open: Boolean, shelves: List<Shelf>): Shelfie {
        var list = all.filter { !it.hidden || open }
        q.shelfId?.let { id ->
            val shelf = shelves.firstOrNull { it.id == id }
            list = shelf?.bookIds?.mapNotNull { bid -> list.firstOrNull { it.id == bid } }.orEmpty()
        }
        list = when (q.kind) {
            KindFilter.All -> list
            KindFilter.Ebooks -> list.filter { it.kind == BookKind.Ebook }
            KindFilter.Audiobooks -> list.filter { it.kind == BookKind.Audiobook }
        }
        val text = q.text.trim().lowercase()
        if (text.isNotEmpty()) {
            val terms = text.split(Regex("\\s+"))
            list = list.filter { b ->
                val hay = listOfNotNull(b.title, b.authors.joinToString(" "), b.narrators.joinToString(" "), b.series, b.format, b.file.name)
                    .joinToString(" ").lowercase()
                terms.all { hay.contains(it) }
            }
        }
        // A shelf keeps its hand-arranged order unless you pick a sort other than "Recent".
        val keepShelfOrder = q.shelfId != null && q.sort == SortKey.Recent
        val sorted = if (keepShelfOrder) list else {
            val cmp: Comparator<Book> = when (q.sort) {
                SortKey.Recent -> compareByDescending<Book> { prog[it.id]?.updatedAt ?: 0L }.thenByDescending { it.addedAt }
                SortKey.Added -> compareByDescending { it.addedAt }
                SortKey.Title -> compareBy { it.sortTitle }
                SortKey.Author -> compareBy<Book> { it.authors.firstOrNull()?.lastNameFirst() ?: "￿" }.thenBy { it.series ?: "" }.thenBy { it.seriesIndex ?: 0f }.thenBy { it.sortTitle }
                SortKey.Series -> compareBy<Book> { it.series?.lowercase() ?: "￿" }.thenBy { it.seriesIndex ?: Float.MAX_VALUE }.thenBy { it.sortTitle }
                SortKey.Narrator -> compareBy<Book> { it.narrators.firstOrNull()?.lowercase() ?: "￿" }.thenBy { it.sortTitle }
                SortKey.Length -> compareBy { if (it.isAudio) it.durationMs else it.sizeBytes * 60 }
                SortKey.Progress -> compareByDescending { prog[it.id]?.fraction ?: 0f }
            }
            list.sortedWith(if (q.descending) cmp.reversed() else cmp)
        }
        val groups: List<Pair<String?, List<Book>>> = when (q.group) {
            GroupBy.None -> listOf(null to sorted)
            GroupBy.Author -> sorted.groupBy { it.authors.firstOrNull() ?: "Unknown author" }.toSortedMap(compareBy { it.lastNameFirst() }).toList()
            GroupBy.Series -> sorted.groupBy { it.series ?: "Standalone" }.toList()
                .sortedWith(compareBy({ it.first == "Standalone" }, { it.first.lowercase() }))
                .map { (k, v) -> k to v.sortedBy { it.seriesIndex ?: Float.MAX_VALUE } }
            GroupBy.Narrator -> sorted.filter { it.isAudio }.groupBy { it.narrators.firstOrNull() ?: "Unknown narrator" }.toSortedMap().toList()
            GroupBy.Folder -> sorted.groupBy { java.io.File(it.folder).name.ifEmpty { "/" } }.toSortedMap(String.CASE_INSENSITIVE_ORDER).toList()
        }
        return Shelfie(groups.map { (k, v) -> k to v }, sorted.size)
    }

    private fun String.lastNameFirst(): String {
        val parts = trim().split(Regex("\\s+"))
        return (if (parts.size > 1) parts.last() + " " + parts.dropLast(1).joinToString(" ") else this).lowercase()
    }

    // -------------------------------------------------------------- library

    fun refresh() = library.refresh()
    fun addSource(path: String) { library.addSource(path); say("Burrowing through ${java.io.File(path).name}…") }
    fun removeSource(path: String) = library.removeSource(path)
    fun setNoMedia(path: String, on: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        library.setNoMedia(path, on)
        say(if (on) "Hidden from other apps" else "Visible to other apps again")
    }

    fun createShelf(name: String, emoji: String?, hidden: Boolean) {
        library.createShelf(name, emoji, hidden)
        say(if (hidden) "Secret shelf “$name” dug into the Burrow" else "Shelf “$name” is ready")
    }
    fun renameShelf(id: String, name: String, emoji: String?) = library.updateShelf(id) { it.copy(name = name, emoji = emoji) }
    fun setShelfHidden(id: String, hidden: Boolean) = library.updateShelf(id) { it.copy(hidden = hidden) }
    fun deleteShelf(id: String) { library.deleteShelf(id); if (_query.value.shelfId == id) openShelf(null) }
    fun toggleOnShelf(shelfId: String, bookId: String) = library.toggleOnShelf(shelfId, bookId)
    fun reorderShelf(shelfId: String, from: Int, to: Int) = library.updateShelf(shelfId) { s ->
        s.copy(bookIds = s.bookIds.toMutableList().apply { add(to.coerceIn(0, size - 1), removeAt(from)) })
    }

    fun setFinished(book: Book, finished: Boolean) {
        library.setFinished(book.id, finished)
        if (finished) say("Finished “${book.title}” 🎉")
    }
    fun resetProgress(book: Book) = library.resetProgress(book.id)
    /** Sends an ebook (and its cover) to your Readest cloud so Readest on other devices has it. */
    fun uploadToReadest(book: Book) = viewModelScope.launch {
        val app = getApplication<android.app.Application>()
        val client = com.worm.app.sync.ProgressSync.readest(app) ?: return@launch say("Sign in to Readest in Settings › Sync first")
        say("Uploading “${book.title}” to Readest…")
        val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val f = java.io.File(book.path)
                client.uploadBook(f, com.worm.app.data.Hashes.partialMd5(f), book.title, book.author, f.extension, book.coverPath?.let { java.io.File(it) })
            }
        }
        say(r.fold({ "“${book.title}” is in your Readest cloud ☁️" }, { "Readest upload failed: ${it.message}" }))
    }

    fun syncAvailable() = com.worm.app.sync.ProgressSync.anyEnabled(getApplication())
    fun pushSpot(book: Book) = viewModelScope.launch {
        runCatching { com.worm.app.sync.ProgressSync.pushOne(getApplication(), book.id) }
            .onSuccess { say(if (it) "Sent your spot in “${book.title}”" else "Nothing to send yet; open the book first") }
            .onFailure { say(it.message ?: "Couldn't send") }
    }
    fun pullSpot(book: Book) = viewModelScope.launch {
        runCatching { com.worm.app.sync.ProgressSync.pullOne(getApplication(), book.id) }
            .onSuccess { say(if (it) "Got a newer spot for “${book.title}”; it'll open there" else "Already up to date") }
            .onFailure { say(it.message ?: "Couldn't check") }
    }
    fun dismissFromResume(book: Book) = library.saveProgress(book.id) { it.copy(dismissedAt = System.currentTimeMillis() + 2000) }

    // ------------------------------------------------------- details & covers

    val overrides = library.overrides

    fun saveEdits(book: Book, title: String, authors: String, narrators: String, series: String, index: String, description: String) {
        val orig = library.original(book.id) ?: book
        fun people(s: String) = s.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
        library.setOverride(book.id) { o ->
            o.copy(
                title = title.trim().takeIf { it.isNotEmpty() && it != orig.title },
                authors = people(authors).takeIf { it != orig.authors },
                narrators = people(narrators).takeIf { it != orig.narrators },
                series = series.trim().takeIf { it.isNotEmpty() && it != orig.series },
                seriesIndex = index.trim().toFloatOrNull()?.takeIf { it != orig.seriesIndex },
                description = description.trim().takeIf { it.isNotEmpty() && it != orig.description },
            )
        }
        say("Saved")
    }

    fun resetEdits(book: Book) {
        library.overrides.value[book.id]?.coverPath?.let { java.io.File(it).delete() }
        library.setOverride(book.id) { null }
        refreshChapters(book.id)
        say("Back to what the file says")
    }

    fun setCoverFromBytes(book: Book, bytes: ByteArray) = viewModelScope.launch(Dispatchers.IO) {
        if (library.setCustomCover(book.id, bytes)) say("New cover ✨") else say("That image didn't work")
    }

    /**
     * Applies an online result. [fillOnly] keeps anything the book already has (magic match);
     * otherwise the result replaces title/author/etc. (picked by hand).
     */
    suspend fun applyHit(book: Book, hit: com.worm.app.data.MetaHit, fillOnly: Boolean, cover: Boolean = true): Boolean {
        val b = library.book(book.id) ?: book
        library.setOverride(book.id) { o ->
            /** fillOnly: take the found value only where the book has nothing. Otherwise: found value wins. */
            fun <T> pick(current: T?, found: T?, empty: (T?) -> Boolean): T? = if (!fillOnly || empty(current)) found else null
            o.copy(
                title = if (fillOnly) o.title else hit.title.takeIf { it.isNotBlank() } ?: o.title,
                authors = pick(b.authors, hit.authors.takeIf { it.isNotEmpty() }) { it.isNullOrEmpty() } ?: o.authors,
                narrators = pick(b.narrators, hit.narrators.takeIf { it.isNotEmpty() }) { it.isNullOrEmpty() } ?: o.narrators,
                series = pick(b.series, hit.series) { it.isNullOrBlank() } ?: o.series,
                seriesIndex = pick(b.seriesIndex, hit.seriesIndex) { it == null } ?: o.seriesIndex,
                description = pick(b.description, hit.description) { it.isNullOrBlank() } ?: o.description,
                asin = hit.asin ?: o.asin,
            )
        }
        var coverDone = false
        if (cover && hit.coverUrl != null && (!fillOnly || b.coverPath == null)) {
            com.worm.app.data.MetaSearch.download(hit.coverUrl)?.let { coverDone = withContext(Dispatchers.IO) { library.setCustomCover(book.id, it) } }
        }
        if (book.isAudio && hit.asin != null) matchChapters(library.book(book.id) ?: book, hit.asin)
        // Still no series? Read it from the titles, or ask Audible's catalogue (it knows series well).
        val now = library.book(book.id) ?: book
        if (now.series == null) {
            val found = com.worm.app.data.MetaSearch.parseSeries(hit.title)
                ?: com.worm.app.data.MetaSearch.seriesFor(now.title, now.authors.firstOrNull())
            found?.let { (series, index) -> library.setOverride(book.id) { o -> o.copy(series = series, seriesIndex = index ?: o.seriesIndex) } }
        }
        return coverDone
    }

    /** Uses Audible's chapter names when the file's own chapters are missing or just numbered. */
    private suspend fun matchChapters(book: Book, asin: String) {
        val online = com.worm.app.data.MetaSearch.chapters(asin)
        if (online.size < 2) return
        val current = if (PlayerHub.bookId.value == book.id) PlayerHub.chapters.value else null
        val generic = Regex("""(?i)^\s*(chapter|track|part|kapitel|chapitre)?\s*\d+\s*$""")
        val single = book.tracks.size == 1
        val onlineTotal = online.last().endMs
        val closeLength = book.durationMs <= 0 || kotlin.math.abs(onlineTotal - book.durationMs) < book.durationMs * 0.03
        val chapters: List<com.worm.app.data.Chapter>? = when {
            single && closeLength -> online
            !single && online.size == book.tracks.size -> {
                var t = 0L
                book.tracks.mapIndexed { i, tr -> com.worm.app.data.Chapter(online[i].title, t, t + tr.durationMs, i).also { t += tr.durationMs } }
            }
            else -> null
        }
        if (chapters != null && (current == null || current.size <= 1 || current.all { generic.matches(it.title) || it.title == book.title } || single)) {
            library.setOverride(book.id) { it.copy(chapters = chapters) }
            refreshChapters(book.id)
        }
    }

    private fun refreshChapters(id: String) {
        if (PlayerHub.bookId.value == id) PlayerHub.setBook(library.book(id), library.overrides.value[id]?.chapters)
    }

    fun magicMatch(book: Book) = viewModelScope.launch {
        _busy.value = "Sniffing out “${book.title}”…"
        val hit = runCatching { com.worm.app.data.MetaSearch.bestMatchIO(book) }.getOrNull()
        if (hit == null) { _busy.value = null; say("No confident match. Try Search instead."); return@launch }
        // Magic match fills gaps (cover, author, narrator, series…) without overwriting what you have.
        applyHit(book, hit, fillOnly = true)
        _busy.value = null
        say("Matched with ${hit.source} ✨")
    }

    private val _matchingTitle = MutableStateFlow<String?>(null)
    val matchingTitle: StateFlow<String?> = _matchingTitle.asStateFlow()
    private val _matching = MutableStateFlow<Pair<Int, Int>?>(null)
    /** Progress of "magic match everything missing" (done, total). */
    val matching: StateFlow<Pair<Int, Int>?> = _matching.asStateFlow()

    fun magicMatchMissing() = viewModelScope.launch {
        val todo = library.visibleBooks().filter { !it.hidden && (it.coverPath == null || it.authors.isEmpty() || (it.isAudio && it.narrators.isEmpty())) }
        if (todo.isEmpty()) { say("Every book already has a cover and an author 🎉"); return@launch }
        var fixed = 0
        todo.forEachIndexed { i, b ->
            _matching.value = i to todo.size
            _matchingTitle.value = b.title
            runCatching { com.worm.app.data.MetaSearch.bestMatchIO(b) }.getOrNull()?.let { applyHit(b, it, fillOnly = true); fixed++ }
        }
        _matching.value = null; _matchingTitle.value = null
        say("Auto-fill matched $fixed of ${todo.size}. Fix-up can help with the rest.")
    }

    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()

    fun burrow(book: Book, hide: Boolean) = viewModelScope.launch {
        _busy.value = if (hide) "Burrowing “${book.title}”…" else "Letting “${book.title}” out…"
        val r = if (hide) library.burrowBook(book) else library.unburrowBook(book)
        _busy.value = null
        r.onSuccess { say(if (hide) "Safe in the Burrow 🪱" else "Back in the open") }
            .onFailure { say(it.message ?: "Couldn't move that book") }
    }

    fun delete(book: Book) = viewModelScope.launch {
        library.deleteBook(book).onSuccess { say("Deleted “${book.title}”") }.onFailure { say(it.message ?: "Couldn't delete") }
    }

    // ------------------------------------------------------------ converters

    data class Transforming(val title: String, val fraction: Float, val index: Int, val total: Int)
    private val _transforming = MutableStateFlow<Transforming?>(null)
    val transforming: StateFlow<Transforming?> = _transforming.asStateFlow()
    private val transformQueue = ArrayDeque<Pair<java.io.File, Book?>>()
    private var transformDelete = false
    private var transformJob: kotlinx.coroutines.Job? = null
    private var transformTotal = 0

    /** Metamorphosis: library books and/or picked files → EPUB, one after another. */
    fun transform(books: List<Book>, files: List<java.io.File> = emptyList(), deleteOriginals: Boolean) {
        transformDelete = deleteOriginals
        books.forEach { transformQueue += java.io.File(it.path) to it }
        files.forEach { transformQueue += it to null }
        transformTotal += books.size + files.size
        if (transformJob?.isActive == true) return
        transformJob = viewModelScope.launch {
            var ok = 0; var failed = 0; var lastError: String? = null
            var index = 0
            while (transformQueue.isNotEmpty()) {
                val (file, book) = transformQueue.removeFirst()
                index++
                val title = book?.title ?: file.nameWithoutExtension
                _transforming.value = Transforming(title, 0f, index, transformTotal)
                runCatching {
                    val report = { f: Float -> _transforming.value = Transforming(title, f, index, transformTotal) }
                    if (book != null) com.worm.app.convert.Metamorphosis.convertBook(getApplication(), book, transformDelete, report)
                    else {
                        val out = com.worm.app.convert.Metamorphosis.destinationFor(file)
                        com.worm.app.convert.Metamorphosis.convert(getApplication(), file, out, null, report)
                        if (transformDelete) file.delete()
                        library.scan()
                    }
                }.onSuccess { ok++ }.onFailure {
                    failed++; lastError = "$title: ${it.message ?: it.javaClass.simpleName}"
                    android.util.Log.e("Worm", "Metamorphosis failed for ${file.path}", it)
                }
            }
            _transforming.value = null
            transformTotal = 0
            say(
                buildString {
                    append("Metamorphosis: $ok became EPUB")
                    if (failed > 0) append(" · $failed couldn't (originals kept). ${lastError.orEmpty()}")
                },
            )
        }
    }

    fun spin(books: List<Book>, bitrate: Int?, deleteOriginals: Boolean) {
        val ctx = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val br = bitrate ?: books.firstOrNull()?.let { com.worm.app.convert.SilkSpinner.suggestedBitrate(it) } ?: 64_000
            com.worm.app.convert.SilkSpinner.enqueue(ctx, books, br, deleteOriginals)
            say("Silk Spinner is spinning ${com.worm.app.util.plural(books.size, "audiobook")} 🕸️ (see the notification)")
        }
    }

    // ------------------------------------------------------------ the Burrow

    val pinIsSet: Boolean get() = pin.isSet
    private val _biometric = MutableStateFlow(prefs.biometric)
    val biometric: StateFlow<Boolean> = _biometric.asStateFlow()
    fun setBiometric(on: Boolean) { prefs.biometric = on; _biometric.value = on }

    suspend fun unlock(code: String): Boolean {
        val ok = withContext(Dispatchers.Default) { pin.check(code) }
        if (ok) library.burrowOpen.value = true
        return ok
    }
    fun unlockTrusted() { library.burrowOpen.value = true }
    suspend fun createPin(code: String) {
        withContext(Dispatchers.Default) { pin.set(code) }
        library.burrowOpen.value = true
        say("PIN set — the Burrow is open")
    }
    suspend fun checkPin(code: String) = withContext(Dispatchers.Default) { pin.check(code) }
    fun lock() {
        library.burrowOpen.value = false
        if (_query.value.shelfId != null && library.shelves.value.firstOrNull { it.id == _query.value.shelfId }?.hidden == true) openShelf(null)
    }

    private var backgroundedAt = 0L
    fun onAppBackground() {
        backgroundedAt = SystemClock.elapsedRealtime()
        if (prefs.autoLockSeconds == 0) lock()
    }
    fun onAppForeground() {
        if (backgroundedAt > 0 && SystemClock.elapsedRealtime() - backgroundedAt > prefs.autoLockSeconds * 1000L) lock()
        backgroundedAt = 0
    }

    // ---------------------------------------------------------------- audio

    private val _controller = MutableStateFlow<MediaController?>(null)
    val controller: StateFlow<MediaController?> = _controller.asStateFlow()

    fun connectPlayer() {
        if (_controller.value != null) return
        val future = PlayerHub.connect(getApplication())
        future.addListener({ runCatching { _controller.value = future.get() } }, PlayerHub.mainExecutor(getApplication()))
    }

    fun play(book: Book) {
        val c = _controller.value ?: run { connectPlayer(); say("Warming up the player…"); return }
        if (PlayerHub.bookId.value == book.id && c.mediaItemCount > 0) { c.play(); return }
        c.setMediaItem(MediaItem.Builder().setMediaId(PlayerHub.BOOK_PREFIX + book.id).build())
        c.prepare()
        c.play()
    }

    fun playChapterOf(book: Book, trackIndex: Int, positionMs: Long) {
        val c = _controller.value ?: return
        if (PlayerHub.bookId.value != book.id) {
            c.setMediaItem(MediaItem.Builder().setMediaId(PlayerHub.trackItemId(book.id, trackIndex)).build(), positionMs)
            c.prepare()
        } else c.seekTo(trackIndex, positionMs)
        c.play()
    }

    override fun onCleared() {
        _controller.value?.release()
        super.onCleared()
    }
}
