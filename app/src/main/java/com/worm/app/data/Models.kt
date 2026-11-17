package com.worm.app.data

import java.io.File

enum class BookKind { Ebook, Audiobook }

/**
 * One thing in the library: an ebook file, a single-file audiobook (.m4b, .mp3…), or a folder of
 * audio tracks that together make one audiobook.
 */
data class Book(
    /** Stable id. Ebooks use KOReader's partial MD5 so progress survives moves and matches KOSync. */
    val id: String,
    /** The ebook / audio file, or the folder holding an audiobook's tracks. */
    val path: String,
    val kind: BookKind,
    val format: String,
    val title: String,
    val authors: List<String> = emptyList(),
    val narrators: List<String> = emptyList(),
    val series: String? = null,
    val seriesIndex: Float? = null,
    val description: String? = null,
    val language: String? = null,
    val sizeBytes: Long = 0,
    val durationMs: Long = 0,
    val addedAt: Long = 0,
    /** Cached cover image in app storage, if the book has one. */
    val coverPath: String? = null,
    /** Audiobook tracks in play order (one entry for single-file audiobooks). */
    val tracks: List<Track> = emptyList(),
    /** True when the book lives in the Burrow (Worm's private, PIN-locked storage). */
    val hidden: Boolean = false,
) {
    val file: File get() = File(path)
    val isAudio: Boolean get() = kind == BookKind.Audiobook
    val author: String get() = authors.joinToString(", ").ifEmpty { "Unknown author" }
    val narrator: String? get() = narrators.joinToString(", ").ifEmpty { null }
    /** Folder the book sits in, for "group by folder" views. */
    val folder: String get() = File(path).parent.orEmpty()
    val sortTitle: String get() = title.lowercase().removePrefix("the ").removePrefix("a ").removePrefix("an ")
}

data class Track(val path: String, val title: String, val durationMs: Long)

data class Chapter(val title: String, val startMs: Long, val endMs: Long, val trackIndex: Int = 0)

/**
 * Where you are in a book.
 * Ebooks: [locator] is an EPUB CFI from foliate-js. Audiobooks: [trackIndex] + [positionMs].
 */
data class ReadingProgress(
    val fraction: Float = 0f,
    val locator: String? = null,
    val trackIndex: Int = 0,
    val positionMs: Long = 0,
    val updatedAt: Long = 0,
    val finished: Boolean = false,
    /** Seconds spent reading/listening, for stats. */
    val timeSpentSec: Long = 0,
    /** "Remove from Resume": hidden from the resume row until you open the book again. */
    val dismissedAt: Long = 0,
    /** When you last actually moved (turned a page), as opposed to just opening the book. Used to decide sync conflicts. */
    val movedAt: Long = 0,
    /** KOReader-style position, kept alongside the CFI for KOSync (and pulled positions not yet opened). */
    val xpointer: String? = null,
) {
    val started: Boolean get() = positionMs > 0 || trackIndex > 0 || locator != null || fraction > 0f
    val showInResume: Boolean get() = started && !finished && updatedAt > dismissedAt
}

/**
 * Your edits to a book's details (or what magic match found). Kept separately from the files,
 * so nothing on disk is rewritten; null fields fall back to what the file says.
 */
data class Override(
    val title: String? = null,
    val authors: List<String>? = null,
    val narrators: List<String>? = null,
    val series: String? = null,
    val seriesIndex: Float? = null,
    val description: String? = null,
    val coverPath: String? = null,
    val chapters: List<Chapter>? = null,
    /** Audible id, when matched, so chapter names can be fetched later. */
    val asin: String? = null,
)

/** A custom shelf (Worm's "folders"): any mix of books and audiobooks, in the order you arrange them. */
data class Shelf(
    val id: String,
    val name: String,
    val bookIds: List<String>,
    val emoji: String? = null,
    /** Hidden shelves only show while the Burrow is unlocked. */
    val hidden: Boolean = false,
)

/** A directory the user pointed Worm at. */
data class Source(
    val path: String,
    /** Keep this folder's media out of other apps (music players, galleries) with a .nomedia file. */
    val noMedia: Boolean = false,
)

data class Bookmark(
    val id: String,
    val bookId: String,
    val locator: String,
    val label: String,
    val createdAt: Long,
    val positionMs: Long = 0,
    val trackIndex: Int = 0,
)

data class Highlight(
    val id: String,
    val bookId: String,
    val cfi: String,
    val text: String,
    val color: String,
    val note: String? = null,
    val createdAt: Long,
)

enum class ViewMode { List, Grid2, Grid3 }

enum class SortKey(val label: String) {
    Recent("Recently opened"),
    Added("Recently added"),
    Title("Title"),
    Author("Author"),
    Series("Series"),
    Narrator("Narrator"),
    Length("Length"),
    Progress("Progress"),
}

enum class KindFilter(val label: String) { All("Everything"), Ebooks("Books"), Audiobooks("Audiobooks") }

enum class GroupBy(val label: String) { None("No grouping"), Author("Author"), Series("Series"), Narrator("Narrator"), Folder("Folder") }

object Formats {
    val EBOOK = setOf("epub", "mobi", "azw", "azw3", "kf8", "prc", "fb2", "fbz", "cbz")
    /** Formats Worm can convert into EPUB (see Metamorphosis). */
    val CONVERTIBLE = setOf("txt", "html", "htm", "xhtml", "md", "fb2", "fbz", "mobi", "azw", "azw3", "prc", "cbz", "docx")
    val AUDIO_BOOK_FILE = setOf("m4b")
    val AUDIO = setOf("mp3", "m4a", "m4b", "aac", "ogg", "oga", "opus", "flac", "wav", "wma", "mka")

    fun isEbook(f: File) = f.isFile && f.extension.lowercase() in EBOOK
    fun isAudio(f: File) = f.isFile && f.extension.lowercase() in AUDIO
}

/** One day's reading and listening, for weekly/monthly stats. */
data class DayActivity(val readSec: Long = 0, val listenSec: Long = 0, val finished: Int = 0)
