package com.worm.app.data

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

typealias Progress = (done: Int, total: Int) -> Unit

/**
 * The whole library, shared by the UI and the playback service (Android Auto can start the
 * service without the UI, so everything is persisted and loads instantly from JSON).
 *
 * - Sources: folders the user mapped. Books stay where they are.
 * - The Burrow: Worm's private storage (filesDir/burrow). Books moved there vanish from every other
 *   app and only show in Worm while the Burrow is unlocked.
 */
class Library private constructor(private val context: Context) {
    val prefs = Prefs(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dir = context.filesDir
    val burrow: File get() = File(dir, "burrow").also { it.mkdirs() }
    private val coversDir get() = File(dir, "covers")

    private val booksFile = JsonFile(File(dir, "library.json"))
    private val progressFile = JsonFile(File(dir, "progress.json"))
    private val shelvesFile = JsonFile(File(dir, "shelves.json"))
    private val sourcesFile = JsonFile(File(dir, "sources.json"))
    private val bookmarksFile = JsonFile(File(dir, "bookmarks.json"))
    private val highlightsFile = JsonFile(File(dir, "highlights.json"))
    private val burrowIndex = JsonFile(File(dir, "burrow_index.json"))
    private val overridesFile = JsonFile(File(dir, "overrides.json"))

    private val _overrides = MutableStateFlow(overridesFile.readMap(Json::override))
    val overrides: StateFlow<Map<String, Override>> = _overrides.asStateFlow()

    /** What the files themselves say (cached between launches). */
    private var scanned: List<Book> = booksFile.readList(Json::book)

    private val _books = MutableStateFlow(merge(scanned, _overrides.value))
    /** Every book with your edits applied, including Burrow books (callers filter those unless unlocked). */
    val books: StateFlow<List<Book>> = _books.asStateFlow()

    private fun merge(list: List<Book>, ov: Map<String, Override>): List<Book> = list.map { b ->
        val o = ov[b.id] ?: return@map b
        b.copy(
            title = o.title ?: b.title, authors = o.authors ?: b.authors, narrators = o.narrators ?: b.narrators,
            series = o.series ?: b.series, seriesIndex = o.seriesIndex ?: b.seriesIndex,
            description = o.description ?: b.description,
            coverPath = o.coverPath?.takeIf { File(it).isFile } ?: b.coverPath,
        )
    }

    private fun publish(list: List<Book>) {
        scanned = list
        _books.value = merge(list, _overrides.value)
    }

    /** Raw (un-edited) details, for "reset to the file's info". */
    fun original(id: String): Book? = scanned.firstOrNull { it.id == id }

    fun setOverride(id: String, transform: (Override) -> Override?) {
        _overrides.update { map ->
            val next = transform(map[id] ?: Override())
            if (next == null || next == Override()) map - id else map + (id to next)
        }
        _books.value = merge(scanned, _overrides.value)
        scope.launch { overridesFile.writeMap(_overrides.value, Json::override) }
    }

    /** Saves a picked/downloaded cover into Worm's storage and points the book at it. */
    fun setCustomCover(id: String, bytes: ByteArray): Boolean {
        val out = File(File(dir, "covers_custom"), "$id-${System.currentTimeMillis()}.jpg")
        if (!Metadata.saveCover(bytes, out, max = 900)) return false
        val old = _overrides.value[id]?.coverPath
        setOverride(id) { it.copy(coverPath = out.absolutePath) }
        old?.let { File(it).delete() }
        return true
    }

    private val _progress = MutableStateFlow(progressFile.readMap(Json::progress))
    val progress: StateFlow<Map<String, ReadingProgress>> = _progress.asStateFlow()

    private val _shelves = MutableStateFlow(shelvesFile.readList(Json::shelf))
    val shelves: StateFlow<List<Shelf>> = _shelves.asStateFlow()

    private val _sources = MutableStateFlow(sourcesFile.readList(Json::source))
    val sources: StateFlow<List<Source>> = _sources.asStateFlow()

    private val _bookmarks = MutableStateFlow(bookmarksFile.readList(Json::bookmark))
    val bookmarks: StateFlow<List<Bookmark>> = _bookmarks.asStateFlow()

    private val _highlights = MutableStateFlow(highlightsFile.readList(Json::highlight))
    val highlights: StateFlow<List<Highlight>> = _highlights.asStateFlow()

    /** Whether the Burrow is open right now. Lives here so the car/notification side can respect it. */
    val burrowOpen = MutableStateFlow(false)

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()
    private val scanLock = Mutex()

    fun book(id: String): Book? = _books.value.firstOrNull { it.id == id }
    fun visibleBooks(): List<Book> = _books.value.filter { !it.hidden || burrowOpen.value }

    // ------------------------------------------------------------- sources

    fun addSource(path: String) {
        if (_sources.value.any { it.path == path }) return
        _sources.update { it + Source(path) }
        sourcesFile.writeList(_sources.value, Json::source)
        refresh()
    }

    fun removeSource(path: String) {
        _sources.update { list -> list.filterNot { it.path == path } }
        sourcesFile.writeList(_sources.value, Json::source)
        refresh()
    }

    /** Puts or removes a .nomedia file so music players and galleries skip this folder. */
    fun setNoMedia(path: String, on: Boolean) {
        val marker = File(path, NOMEDIA)
        runCatching { if (on) marker.createNewFile() else marker.delete() }
        _sources.update { list -> list.map { if (it.path == path) it.copy(noMedia = marker.exists()) else it } }
        sourcesFile.writeList(_sources.value, Json::source)
        val media = File(path).walkTopDown().filter { Formats.isAudio(it) }.map { it.absolutePath }.toList()
        notifyMediaStore(*(media + marker.absolutePath).toTypedArray())
    }

    /** Common places people keep books, offered during setup. */
    fun suggestedSources(): List<File> {
        val root = Environment.getExternalStorageDirectory()
        return listOf("Books", "Audiobooks", "Download", "Documents", "Music/Audiobooks", "AudioBooks", "eBooks", "Kindle")
            .map { File(root, it) }.filter { it.isDirectory }.distinctBy { it.canonicalPath.lowercase() }
    }

    // ---------------------------------------------------------------- scan

    private var refreshJob: Job? = null

    fun refresh(): Job {
        refreshJob?.takeIf { it.isActive }?.let { return it }
        return scope.launch { scan() }.also { refreshJob = it }
    }

    suspend fun scan() = scanLock.withLock {
        _scanning.value = true
        try {
            // Re-read everything once whenever the scanner's rules change.
            val fresh = prefs.scanVersion < SCAN_VERSION
            val previous = if (fresh) emptyMap() else scanned.associateBy { it.path }
            val found = mutableListOf<Book>()
            val seen = HashSet<String>()
            for (src in _sources.value) {
                val d = File(src.path)
                if (d.isDirectory) walk(d, hidden = false, previous, found, seen)
            }
            walk(burrow, hidden = true, previous, found, seen)
            // Same file mapped twice (overlapping sources) → keep one.
            val unique = found.distinctBy { it.id }
            publish(unique)
            booksFile.writeList(unique, Json::book)
            prefs.scanVersion = SCAN_VERSION
            pruneCovers(unique)
        } finally {
            _scanning.value = false
        }
    }

    private fun walk(dir: File, hidden: Boolean, previous: Map<String, Book>, out: MutableList<Book>, seen: MutableSet<String>) {
        val canonical = runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath)
        if (!seen.add(canonical)) return
        val files = dir.listFiles()?.filter { !it.name.startsWith(".") } ?: return
        // Your own call wins: merge = the whole tree is one book; split = every file is its own book.
        if (dir.absolutePath in prefs.mergeFolders) {
            val all = dir.walkTopDown().filter { Formats.isAudio(it) && !it.name.startsWith(".") }
                .sortedWith(compareBy<File, String>(NaturalOrder) { it.relativeTo(dir).path }).toList()
            if (all.isNotEmpty()) { folderAudiobook(dir, all, hidden, previous)?.let(out::add) }
            files.filter(Formats::isEbook).forEach { f -> book(f, hidden, previous)?.let(out::add) }
            return
        }
        val splitHere = dir.absolutePath in prefs.splitFolders
        val audio = files.filter(Formats::isAudio).sortedWith(NaturalOrder.byName)
        val (wholeBooks, tracks) = audio.partition { it.extension.equals("m4b", true) }

        wholeBooks.forEach { f -> book(f, hidden, previous)?.let(out::add) }
        if (tracks.size == 1 || (splitHere && tracks.isNotEmpty())) tracks.forEach { book(it, hidden, previous)?.let(out::add) }
        else if (tracks.size >= 2) {
            // A folder of tracks is usually one book — unless the tags say it's several (loose files
            // dumped together). Group by album tag; untagged files in a library root stay separate.
            val isRoot = dir == burrow || _sources.value.any { File(it.path) == dir }
            val byAlbum = tracks.groupBy { albumOf(it) }
            when {
                byAlbum.size == 1 && (byAlbum.keys.first() != null || !isRoot) ->
                    folderAudiobook(dir, tracks, hidden, previous, byAlbum.keys.first())?.let(out::add)
                else -> byAlbum.forEach { (album, files) ->
                    if (album == null || files.size == 1) files.forEach { f -> book(f, hidden, previous)?.let(out::add) }
                    else folderAudiobook(dir, files, hidden, previous, album)?.let(out::add)
                }
            }
        }
        files.filter(Formats::isEbook).forEach { f -> book(f, hidden, previous)?.let(out::add) }

        val subdirs = files.filter { it.isDirectory }.sortedWith(NaturalOrder.byName)
        // "Book/CD 1, Book/CD 2…" with nothing else inside → one audiobook spanning the discs.
        if (audio.isEmpty() && subdirs.size >= 2 && subdirs.all { DISC.matches(it.name) }) {
            val discTracks = subdirs.flatMap { d -> d.listFiles()?.filter(Formats::isAudio)?.sortedWith(NaturalOrder.byName).orEmpty() }
            if (discTracks.isNotEmpty() && subdirs.all { d -> d.listFiles()?.none { it.isDirectory } != false }) {
                folderAudiobook(dir, discTracks, hidden, previous)?.let(out::add)
                return
            }
        }
        subdirs.forEach { walk(it, hidden, previous, out, seen) }
    }

    private fun book(f: File, hidden: Boolean, previous: Map<String, Book>): Book? {
        val old = previous[f.absolutePath]
        if (old != null && old.sizeBytes == f.length() && old.addedAt == f.lastModified() && old.hidden == hidden) return old
        return runCatching {
            val ext = f.extension.lowercase()
            if (ext in Formats.EBOOK) {
                val id = Hashes.partialMd5(f)
                val meta = Metadata.ebook(f)
                Book(
                    id = id, path = f.absolutePath, kind = BookKind.Ebook, format = ext,
                    title = meta.title?.takeIf { it.isNotBlank() } ?: prettyName(f.nameWithoutExtension),
                    authors = meta.authors, series = meta.series, seriesIndex = meta.seriesIndex,
                    description = meta.description, language = meta.language,
                    sizeBytes = f.length(), addedAt = f.lastModified(),
                    coverPath = meta.cover?.let { saveCover(id, it) }, hidden = hidden,
                )
            } else {
                val id = Hashes.md5("audio:${f.name}:${f.length()}")
                val meta = Metadata.audio(f)
                val title = meta.title?.takeIf { it.isNotBlank() } ?: prettyName(f.nameWithoutExtension)
                Book(
                    id = id, path = f.absolutePath, kind = BookKind.Audiobook, format = ext,
                    title = title, authors = meta.authors, narrators = meta.narrators,
                    series = guessSeries(f.parentFile), sizeBytes = f.length(), durationMs = meta.durationMs,
                    addedAt = f.lastModified(),
                    coverPath = (meta.cover ?: f.parentFile?.let(Metadata::folderCover))?.let { saveCover(id, it) },
                    tracks = listOf(Track(f.absolutePath, title, meta.durationMs)), hidden = hidden,
                )
            }
        }.onFailure { android.util.Log.w("Worm", "Couldn't read ${f.path}", it) }.getOrNull()
    }

    private fun albumOf(f: File): String? = runCatching {
        android.media.MediaMetadataRetriever().let { r ->
            try { r.setDataSource(f.absolutePath); r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM) }
            finally { r.release() }
        }
    }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    private fun folderAudiobook(dir: File, files: List<File>, hidden: Boolean, previous: Map<String, Book>, albumTag: String? = null): Book? {
        val size = files.sumOf { it.length() }
        val modified = files.maxOf { it.lastModified() }
        // Several albums can share one folder, so the key includes the album.
        val key = if (albumTag != null && files.size < (dir.listFiles()?.count(Formats::isAudio) ?: 0)) "${dir.absolutePath}#$albumTag" else dir.absolutePath
        val old = previous[key]
        if (old != null && old.sizeBytes == size && old.addedAt == modified && old.tracks.size == files.size && old.hidden == hidden) return old
        val id = Hashes.md5("audio:${if (key == dir.absolutePath) dir.name else key.substringAfterLast('/')}:$size")
        val first = Metadata.audio(files.first())
        val tracks = files.mapIndexed { i, f ->
            val m = if (i == 0) first else Metadata.audio(f, withCover = false)
            Track(f.absolutePath, m.title?.takeIf { it.isNotBlank() } ?: prettyName(f.nameWithoutExtension), m.durationMs)
        }
        val album = albumTag ?: albumOf(files.first())
        val cover = Metadata.folderCover(dir) ?: first.cover
        return Book(
            id = id, path = key, kind = BookKind.Audiobook, format = files.first().extension.lowercase(),
            title = album ?: prettyName(dir.name), authors = first.authors, narrators = first.narrators,
            series = guessSeries(dir.parentFile), sizeBytes = size, durationMs = tracks.sumOf { it.durationMs },
            addedAt = modified, coverPath = cover?.let { saveCover(id, it) }, tracks = tracks, hidden = hidden,
        )
    }

    /**
     * "Author/Series Name/01 - Book" layouts: when the parent folder holds several numbered books,
     * treat it as the series.
     */
    private fun guessSeries(parent: File?): String? {
        if (parent == null || isInBurrow(parent.absolutePath + File.separator) || parent == burrow) return null
        val siblings = parent.listFiles()?.filter { !it.name.startsWith(".") }.orEmpty()
        val numbered = siblings.count { NUMBERED.containsMatchIn(it.name) }
        return if (numbered >= 2 && _sources.value.none { File(it.path) == parent }) prettyName(parent.name) else null
    }

    private fun saveCover(id: String, bytes: ByteArray): String? {
        val out = File(coversDir, "$id.jpg")
        return if (Metadata.saveCover(bytes, out)) out.absolutePath else null
    }

    private fun pruneCovers(books: List<Book>) {
        val keep = (books.mapNotNull { it.coverPath } + _overrides.value.values.mapNotNull { it.coverPath }).toSet()
        coversDir.listFiles()?.forEach { if (it.absolutePath !in keep) it.delete() }
    }

    private fun prettyName(raw: String) = raw.replace('_', ' ').replace(Regex("\\s+"), " ").trim()

    // ------------------------------------------------------------ progress

    private var progressSave: Job? = null

    fun saveProgress(bookId: String, transform: (ReadingProgress) -> ReadingProgress) {
        _progress.update { map ->
            val cur = map[bookId] ?: ReadingProgress()
            val next = transform(cur)
            map + (bookId to next.copy(fraction = next.fraction.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: cur.fraction, updatedAt = System.currentTimeMillis()))
        }
        if (_books.value.firstOrNull { it.id == bookId }?.hidden != true) prefs.lastBookId = bookId
        progressSave?.cancel()
        progressSave = scope.launch {
            delay(800)
            progressFile.writeMap(_progress.value, Json::progress)
            com.worm.app.widget.Widgets.refreshResume(context)
        }
    }

    fun flushProgress() = progressFile.writeMap(_progress.value, Json::progress)

    fun setFinished(bookId: String, finished: Boolean) {
        val was = _progress.value[bookId]?.finished == true
        saveProgress(bookId) { it.copy(finished = finished, fraction = if (finished) 1f else it.fraction) }
        if (finished && !was) logActivity(finished = 1)
    }

    // ------------------------------------------------------------- daily activity (stats + widgets)

    private val activityFile = JsonFile(File(dir, "activity.json"))
    private val _activity = MutableStateFlow(
        activityFile.readMap { o -> DayActivity(o.optLong("r"), o.optLong("l"), o.optInt("f")) },
    )
    /** yyyy-MM-dd → time read, time listened, books finished that day. */
    val activity: StateFlow<Map<String, DayActivity>> = _activity.asStateFlow()
    private var activitySave: Job? = null

    fun logActivity(readSec: Long = 0, listenSec: Long = 0, finished: Int = 0) {
        if (readSec <= 0 && listenSec <= 0 && finished == 0) return
        val day = java.time.LocalDate.now().toString()
        _activity.update { m ->
            val d = m[day] ?: DayActivity()
            m + (day to DayActivity(d.readSec + readSec.coerceAtLeast(0), d.listenSec + listenSec.coerceAtLeast(0), d.finished + finished))
        }
        activitySave?.cancel()
        activitySave = scope.launch {
            delay(3_000)
            activityFile.writeMap(_activity.value) { a -> JSONObject().put("r", a.readSec).put("l", a.listenSec).put("f", a.finished) }
            com.worm.app.widget.Widgets.refreshStats(context)
        }
    }

    fun resetProgress(bookId: String) {
        _progress.update { it - bookId }
        progressFile.writeMap(_progress.value, Json::progress)
    }

    // ------------------------------------------------------------- shelves

    fun createShelf(name: String, emoji: String?, hidden: Boolean): Shelf {
        val s = Shelf(UUID.randomUUID().toString(), name.trim(), emptyList(), emoji, hidden)
        _shelves.update { it + s }
        saveShelves()
        return s
    }

    fun updateShelf(id: String, transform: (Shelf) -> Shelf) {
        _shelves.update { list -> list.map { if (it.id == id) transform(it) else it } }
        saveShelves()
    }

    fun deleteShelf(id: String) {
        _shelves.update { list -> list.filterNot { it.id == id } }
        saveShelves()
    }

    fun moveShelf(from: Int, to: Int) {
        _shelves.update { it.toMutableList().apply { add(to.coerceIn(0, size - 1), removeAt(from)) } }
        saveShelves()
    }

    fun toggleOnShelf(shelfId: String, bookId: String) = updateShelf(shelfId) { s ->
        s.copy(bookIds = if (bookId in s.bookIds) s.bookIds - bookId else s.bookIds + bookId)
    }

    private fun saveShelves() = scope.launch { shelvesFile.writeList(_shelves.value, Json::shelf) }

    // ---------------------------------------------------- bookmarks/highlights

    fun addBookmark(b: Bookmark) {
        _bookmarks.update { it + b }
        scope.launch { bookmarksFile.writeList(_bookmarks.value, Json::bookmark) }
    }

    fun removeBookmark(id: String) {
        _bookmarks.update { list -> list.filterNot { it.id == id } }
        scope.launch { bookmarksFile.writeList(_bookmarks.value, Json::bookmark) }
    }

    fun upsertHighlight(h: Highlight) {
        _highlights.update { list -> list.filterNot { it.id == h.id } + h }
        scope.launch { highlightsFile.writeList(_highlights.value, Json::highlight) }
    }

    fun removeHighlight(id: String) {
        _highlights.update { list -> list.filterNot { it.id == id } }
        scope.launch { highlightsFile.writeList(_highlights.value, Json::highlight) }
    }

    // ----------------------------------------------------------- the Burrow

    /**
     * Moves a book's file(s) into the Burrow, remembering where they came from so "Let out" can
     * put them back. Shared and private storage are different file systems, so this copies then deletes.
     */
    suspend fun burrowBook(book: Book, progress: Progress = { _, _ -> }): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val src = book.file
            val target = uniqueFile(burrow, if (src.exists()) src.name else book.title.replace(Regex("""[\\/:*?"<>|]"""), "_"))
            if (src.exists()) moveTree(src, target, progress)
            else { // album grouped from a shared folder: gather its tracks into one Burrow folder
                target.mkdirs()
                book.tracks.forEachIndexed { i, t -> progress(i, book.tracks.size); moveTree(File(t.path), File(target, File(t.path).name)) { _, _ -> } }
            }
            val index = burrowIndex.read()?.let(::JSONObject) ?: JSONObject()
            index.put(target.absolutePath, src.parent)
            burrowIndex.write(index.toString())
            notifyMediaStore(*(book.tracks.map { it.path } + book.path).toTypedArray())
            scan()
        }
    }

    suspend fun unburrowBook(book: Book, progress: Progress = { _, _ -> }): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val index = burrowIndex.read()?.let(::JSONObject) ?: JSONObject()
            val home = index.optString(book.path).takeIf { it.isNotEmpty() && File(it).isDirectory }
                ?: _sources.value.firstOrNull()?.path
                ?: File(Environment.getExternalStorageDirectory(), "Books").also { it.mkdirs() }.path
            val target = uniqueFile(File(home), book.file.name)
            moveTree(book.file, target, progress)
            index.remove(book.path)
            burrowIndex.write(index.toString())
            notifyMediaStore(*target.walkTopDown().filter { it.isFile }.map { it.absolutePath }.toList().toTypedArray())
            scan()
        }
    }

    private fun moveTree(src: File, target: File, progress: Progress) {
        if (src.renameTo(target)) return
        val files = if (src.isDirectory) src.walkTopDown().filter { it.isFile }.toList() else listOf(src)
        files.forEachIndexed { i, f ->
            progress(i, files.size)
            val dest = if (src.isDirectory) File(target, f.relativeTo(src).path) else target
            dest.parentFile?.mkdirs()
            f.copyTo(dest, overwrite = false)
            dest.setLastModified(f.lastModified())
        }
        progress(files.size, files.size)
        check(src.deleteRecursively()) { "Copied, but couldn't remove the original" }
    }

    suspend fun deleteBook(book: Book): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val paths = book.tracks.map { it.path } + book.path
            if (book.file.exists()) check(book.file.deleteRecursively()) { "Couldn't delete ${book.title}" }
            else book.tracks.forEach { check(File(it.path).delete()) { "Couldn't delete ${it.path}" } } // album grouped from a shared folder
            notifyMediaStore(*paths.toTypedArray())
            publish(scanned.filterNot { it.id == book.id })
            booksFile.writeList(scanned, Json::book)
        }
    }

    /**
     * A converted copy replaces [old] with [new]: your place, listening/reading time, bookmarks,
     * edited details and shelves move over. Ebook positions keep the percentage (old CFIs don't
     * apply to the new file), audiobook positions become one timeline.
     */
    fun migrate(old: Book, new: Book, keepChapters: Boolean) {
        val p = _progress.value[old.id]
        if (p != null) {
            val moved = if (old.isAudio && new.isAudio) {
                val global = old.tracks.take(p.trackIndex).sumOf { it.durationMs } + p.positionMs
                p.copy(trackIndex = 0, positionMs = global)
            } else p.copy(locator = null)
            _progress.update { it - old.id + (new.id to moved) }
            progressFile.writeMap(_progress.value, Json::progress)
        }
        if (old.isAudio && new.isAudio) {
            val marks = _bookmarks.value.map { b ->
                if (b.bookId != old.id) b
                else b.copy(bookId = new.id, trackIndex = 0, positionMs = old.tracks.take(b.trackIndex).sumOf { it.durationMs } + b.positionMs)
            }
            _bookmarks.value = marks
            bookmarksFile.writeList(marks, Json::bookmark)
        }
        _overrides.value[old.id]?.let { o ->
            setOverride(new.id) { (if (keepChapters) o else o.copy(chapters = null)) }
            setOverride(old.id) { null }
        }
        _shelves.update { list -> list.map { s -> if (old.id in s.bookIds) s.copy(bookIds = s.bookIds.map { if (it == old.id) new.id else it }) else s } }
        shelvesFile.writeList(_shelves.value, Json::shelf)
        if (prefs.lastBookId == old.id) prefs.lastBookId = new.id
    }

    /**
     * Removes an audiobook's audio files after a successful conversion. If that leaves its own
     * folder holding only leftovers (cover images, playlists, notes), the folder goes too.
     */
    fun deleteTracks(book: Book) {
        val folder = bookFolder(book)
        book.tracks.forEach { File(it.path).delete() }
        notifyMediaStore(*book.tracks.map { it.path }.toTypedArray())
        val protected = folder == burrow || _sources.value.any { File(it.path) == folder }
        val leftovers = folder.listFiles().orEmpty()
        val onlyJunk = leftovers.none { it.isDirectory || Formats.isAudio(it) || Formats.isEbook(it) } &&
            leftovers.all { it.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp", "gif", "txt", "nfo", "cue", "m3u", "m3u8", "pls", "sfv", "md5", "url", "db", "ini", "log", "") }
        if (!protected && onlyJunk) folder.deleteRecursively()
    }

    private fun uniqueFile(dir: File, name: String): File {
        dir.mkdirs()
        var candidate = File(dir, name)
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty() || File(dir, name).isDirectory) "" else ".$it" }
        var n = 2
        while (candidate.exists()) candidate = File(dir, if (ext.isEmpty()) "$name ($n)" else "$base ($n)$ext").also { n++ }
        return candidate
    }

    /** Folder that holds this audiobook's tracks (or the book file itself). */
    fun bookFolder(book: Book): File = File(book.tracks.firstOrNull()?.path ?: book.path).parentFile ?: File(book.path)

    fun setSplit(folder: File, on: Boolean) {
        prefs.splitFolders = if (on) prefs.splitFolders + folder.absolutePath else prefs.splitFolders - folder.absolutePath
        if (on) prefs.mergeFolders = prefs.mergeFolders - folder.absolutePath
        refresh()
    }

    fun setMerge(folder: File, on: Boolean) {
        prefs.mergeFolders = if (on) prefs.mergeFolders + folder.absolutePath else prefs.mergeFolders - folder.absolutePath
        if (on) prefs.splitFolders = prefs.splitFolders - folder.absolutePath
        refresh()
    }

    fun isInBurrow(path: String) = path.startsWith(burrow.absolutePath + File.separator)

    /** Lets music players and other apps notice files we added, moved or removed. */
    fun notifyMediaStore(vararg paths: String) {
        val shared = paths.filterNot(::isInBurrow)
        if (shared.isNotEmpty()) MediaScannerConnection.scanFile(context, shared.toTypedArray(), null, null)
    }

    companion object {
        const val NOMEDIA = ".nomedia"
        /** Bump when scanning rules change so cached results are rebuilt. */
        const val SCAN_VERSION = 2
        private val DISC = Regex("""(?i)^(cd|disc|disk|part|pt)[\s._-]*\d+.*""")
        private val NUMBERED = Regex("""(?i)^(book\s*)?\d{1,2}(\.\d)?[\s._-]""")

        @Volatile private var instance: Library? = null
        fun get(context: Context): Library =
            instance ?: synchronized(this) { instance ?: Library(context.applicationContext).also { instance = it } }
    }
}

/** "Chapter 2" before "Chapter 10". */
object NaturalOrder : Comparator<String> {
    private val chunk = Regex("""\d+|\D+""")
    override fun compare(a: String, b: String): Int {
        val x = chunk.findAll(a.lowercase()).map { it.value }.toList()
        val y = chunk.findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(x.size, y.size)) {
            val p = x[i]; val q = y[i]
            val c = if (p[0].isDigit() && q[0].isDigit()) {
                p.trimStart('0').length.compareTo(q.trimStart('0').length).takeIf { it != 0 } ?: p.trimStart('0').compareTo(q.trimStart('0'))
            } else p.compareTo(q)
            if (c != 0) return c
        }
        return x.size.compareTo(y.size)
    }
    val byName: Comparator<File> = Comparator { a, b -> compare(a.name, b.name) }
}
