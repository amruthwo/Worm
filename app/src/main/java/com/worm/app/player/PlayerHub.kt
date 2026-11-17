package com.worm.app.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.worm.app.data.Book
import com.worm.app.data.Chapter
import com.worm.app.data.Library
import com.worm.app.data.Mp4Chapters
import com.worm.app.data.ReadingProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** What the sleep timer is waiting for. */
sealed interface SleepState {
    data object Off : SleepState
    data class Counting(val endsAt: Long, val totalMs: Long) : SleepState
    data object EndOfChapter : SleepState
}

/**
 * Process-wide audio state shared by the playback service and the UI: which book is loaded, its
 * chapters, and the sleep timer. Transport (play/pause/seek) always goes through the MediaController.
 */
object PlayerHub {
    private val _bookId = MutableStateFlow<String?>(null)
    val bookId: StateFlow<String?> = _bookId.asStateFlow()

    /** The chapter playing now (for widgets); set by the playback service. */
    val chapterTitle = MutableStateFlow<String?>(null)

    private val _chapters = MutableStateFlow<List<Chapter>>(emptyList())
    /** For single-file books: chapters inside the file. For multi-file books: one per track. */
    val chapters: StateFlow<List<Chapter>> = _chapters.asStateFlow()

    val sleep = MutableStateFlow<SleepState>(SleepState.Off)

    /** Bumped when the sleep timer fires so the UI can do a little "goodnight" animation. */
    val sleepFired = MutableStateFlow(0)

    /** [custom] = chapters you matched online (they win over whatever the file has). */
    fun setBook(book: Book?, custom: List<Chapter>? = null) {
        _bookId.value = book?.id
        _chapters.value = when {
            book == null -> emptyList()
            !custom.isNullOrEmpty() -> custom
            book.tracks.size == 1 -> Mp4Chapters.read(File(book.tracks[0].path)).takeIf { it.size > 1 }
                ?: listOf(Chapter(book.title, 0, book.durationMs))
            else -> {
                var t = 0L
                book.tracks.mapIndexed { i, tr -> Chapter(tr.title, t, t + tr.durationMs, i).also { t += tr.durationMs } }
            }
        }
    }

    fun connect(context: Context): ListenableFuture<MediaController> =
        MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java)))
            .buildAsync()

    // --------------------------------------------------------- media items

    const val ROOT = "root"
    const val CONTINUE = "continue"
    const val ALL = "all"
    const val RECENT = "recent"
    const val SHELF_PREFIX = "shelf:"
    const val BOOK_PREFIX = "book:"
    private const val TRACK_SEP = "#"

    fun trackItemId(bookId: String, index: Int) = "$BOOK_PREFIX$bookId$TRACK_SEP$index"
    fun bookIdOf(mediaId: String?): String? = mediaId?.removePrefix(BOOK_PREFIX)?.substringBefore(TRACK_SEP)

    fun artworkUri(context: Context, book: Book): Uri? =
        book.coverPath?.let { Uri.parse("content://${context.packageName}.artwork/${book.id}") }

    /** A browsable/playable tile for Android Auto and other browsers. */
    fun bookItem(context: Context, book: Book, progress: ReadingProgress?): MediaItem {
        val extras = Bundle().apply {
            // Android Auto progress badges.
            val f = progress?.fraction ?: 0f
            putInt(
                "android.media.extra.PLAYBACK_STATUS",
                when { progress?.finished == true -> 2; f > 0.005f -> 1; else -> 0 },
            )
            if (f > 0f) putDouble("androidx.media.MediaItem.Extras.COMPLETION_PERCENTAGE", f.toDouble())
        }
        return MediaItem.Builder()
            .setMediaId(BOOK_PREFIX + book.id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(book.title)
                    .setArtist(book.author)
                    .setSubtitle(book.narrator?.let { "Read by $it" } ?: book.author)
                    .setArtworkUri(artworkUri(context, book))
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
                    .setExtras(extras)
                    .build(),
            )
            .build()
    }

    fun folderItem(id: String, title: String, subtitle: String? = null): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_AUDIO_BOOKS)
                .build(),
        )
        .build()

    /** The actual playlist for a book: one MediaItem per audio file. */
    fun trackItems(context: Context, book: Book): List<MediaItem> = book.tracks.mapIndexed { i, t ->
        MediaItem.Builder()
            .setMediaId(trackItemId(book.id, i))
            .setUri(Uri.fromFile(File(t.path)))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(if (book.tracks.size == 1) book.title else t.title)
                    .setArtist(book.author)
                    .setAlbumTitle(book.title)
                    .setAlbumArtist(book.author)
                    .setArtworkUri(artworkUri(context, book))
                    .setTrackNumber(i + 1)
                    .setTotalTrackCount(book.tracks.size)
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER)
                    .build(),
            )
            .build()
    }

    /** Opens the library on a background-safe thread; tiny helper for the service. */
    internal fun library(context: Context) = Library.get(context)

    fun mainExecutor(context: Context) = ContextCompat.getMainExecutor(context)
}
