package com.worm.app.player

import android.app.PendingIntent
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.worm.app.MainActivity
import com.worm.app.data.Book
import com.worm.app.data.Library
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * Plays audiobooks in the background: notification, lock screen, Bluetooth buttons, Android Auto.
 *
 * Remembers where you are every few seconds, rewinds a little when you come back after a pause,
 * pauses (or ducks, if you prefer) for notification sounds, and runs the sleep timer.
 */
private val CMD_SPEED = androidx.media3.session.SessionCommand("worm.speed", android.os.Bundle.EMPTY)
private val CMD_SLEEP = androidx.media3.session.SessionCommand("worm.sleep", android.os.Bundle.EMPTY)
private val CMD_BOOKMARK = androidx.media3.session.SessionCommand("worm.bookmark", android.os.Bundle.EMPTY)

class PlaybackService : MediaLibraryService() {
    companion object {
        /** For widgets: whether audio is playing right now. */
        @Volatile var playing = false
        /** Chapter jumps for widgets, whatever the car/earbud setting says. */
        val CMD_NEXT_CHAPTER = androidx.media3.session.SessionCommand("worm.next_chapter", android.os.Bundle.EMPTY)
        val CMD_PREV_CHAPTER = androidx.media3.session.SessionCommand("worm.prev_chapter", android.os.Bundle.EMPTY)
    }

    private lateinit var library: Library
    private lateinit var exo: ExoPlayer
    private lateinit var player: ChapterPlayer
    private var session: MediaLibrarySession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())

    private var pausedAt = 0L
    private var listenedSince = 0L

    override fun onCreate() {
        super.onCreate()
        library = Library.get(this)
        val prefs = library.prefs
        exo = ExoPlayer.Builder(this)
            .setAudioAttributes(attributes(prefs.duckForNotifications), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        exo.playbackParameters = PlaybackParameters(prefs.playbackSpeed)
        player = ChapterPlayer(exo, prefs) { PlayerHub.bookId.value?.let(library::book)?.tracks?.map { it.durationMs }.orEmpty() }
        player.addListener(listener)

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_PLAYER).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setSessionActivity(open)
            .setMediaButtonPreferences(buttons())
            .build()

        scope.launch { saveLoop() }
        scope.launch { PlayerHub.sleep.collectLatest(::runSleepTimer) }
        scope.launch { PlayerHub.sleep.collect { refreshButtons() } }
        scope.launch { while (isActive) { delay(2_000); if (exo.isPlaying) updateChapterTitle() } }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiped away from Recents while paused: stop entirely. While playing, keep going.
        if (!exo.playWhenReady || exo.mediaItemCount == 0) {
            saveNow()
            stopSelf()
        }
    }

    override fun onDestroy() {
        playing = false
        com.worm.app.widget.Widgets.refreshPlayer(this)
        saveNow()
        library.flushProgress()
        stopShakeListener()
        scope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        PlayerHub.setBook(null)
        super.onDestroy()
    }

    private fun attributes(duck: Boolean) = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        // Speech content makes ExoPlayer pause (not duck) for transient interruptions.
        .setContentType(if (duck) C.AUDIO_CONTENT_TYPE_MUSIC else C.AUDIO_CONTENT_TYPE_SPEECH)
        .build()

    private fun buttons(): List<CommandButton> {
        val prefs = library.prefs
        val speed = exo.playbackParameters.speed
        val sleep = PlayerHub.sleep.value
        return listOf(
            CommandButton.Builder(CommandButton.ICON_SKIP_BACK)
                .apply { skipIconRes(prefs.skipBackSec, forward = false)?.let(::setCustomIconResId) }
                .setDisplayName("Back ${prefs.skipBackSec}s")
                .setPlayerCommand(Player.COMMAND_SEEK_BACK)
                .setSlots(CommandButton.SLOT_BACK)
                .build(),
            CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD)
                .apply { skipIconRes(prefs.skipForwardSec, forward = true)?.let(::setCustomIconResId) }
                .setDisplayName("Forward ${prefs.skipForwardSec}s")
                .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
                .setSlots(CommandButton.SLOT_FORWARD)
                .build(),
            CommandButton.Builder(speedIcon(speed))
                .setDisplayName("Speed ${"%.2f".format(speed).trimEnd('0').trimEnd('.')}×")
                .setSessionCommand(CMD_SPEED)
                .setSlots(CommandButton.SLOT_OVERFLOW)
                .build(),
            CommandButton.Builder(CommandButton.ICON_UNDEFINED)
                .setCustomIconResId(com.worm.app.R.drawable.ic_sleep_moon)
                .setDisplayName(
                    when (sleep) {
                        SleepState.Off -> "Sleep timer"
                        SleepState.EndOfChapter -> "Sleep: end of chapter"
                        is SleepState.Counting -> "Sleep: ${((sleep.endsAt - System.currentTimeMillis()) / 60_000 + 1)} min"
                    },
                )
                .setSessionCommand(CMD_SLEEP)
                .setSlots(CommandButton.SLOT_OVERFLOW)
                .build(),
            CommandButton.Builder(CommandButton.ICON_BOOKMARK_UNFILLED)
                .setDisplayName("Bookmark")
                .setSessionCommand(CMD_BOOKMARK)
                .setSlots(CommandButton.SLOT_OVERFLOW)
                .build(),
        )
    }

    private fun speedIcon(s: Float) = when {
        kotlin.math.abs(s - 1f) < 0.01f -> CommandButton.ICON_PLAYBACK_SPEED_1_0
        kotlin.math.abs(s - 1.25f) < 0.06f -> CommandButton.ICON_PLAYBACK_SPEED_1_2
        kotlin.math.abs(s - 1.5f) < 0.01f -> CommandButton.ICON_PLAYBACK_SPEED_1_5
        kotlin.math.abs(s - 1.75f) < 0.06f -> CommandButton.ICON_PLAYBACK_SPEED_1_8
        kotlin.math.abs(s - 2f) < 0.01f -> CommandButton.ICON_PLAYBACK_SPEED_2_0
        kotlin.math.abs(s - 0.8f) < 0.01f -> CommandButton.ICON_PLAYBACK_SPEED_0_8
        else -> CommandButton.ICON_PLAYBACK_SPEED
    }

    private fun refreshButtons() = session?.setMediaButtonPreferences(buttons())

    /** Notification / car / watch buttons. */
    private fun onCustom(action: String) {
        when (action) {
            CMD_NEXT_CHAPTER.customAction -> player.nextChapter()
            CMD_PREV_CHAPTER.customAction -> player.prevChapter()
            CMD_SPEED.customAction -> {
                val steps = listOf(1f, 1.25f, 1.5f, 1.75f, 2f, 0.8f)
                val now = exo.playbackParameters.speed
                val next = steps[(steps.indexOfFirst { kotlin.math.abs(it - now) < 0.01f } + 1).mod(steps.size)]
                exo.playbackParameters = PlaybackParameters(next)
            }
            CMD_SLEEP.customAction -> {
                val now = System.currentTimeMillis()
                PlayerHub.sleep.value = when (val s = PlayerHub.sleep.value) {
                    SleepState.Off -> SleepState.Counting(now + 15 * 60_000L, 15 * 60_000L)
                    is SleepState.Counting -> when {
                        s.totalMs <= 15 * 60_000L -> SleepState.Counting(now + 30 * 60_000L, 30 * 60_000L)
                        s.totalMs <= 30 * 60_000L -> SleepState.Counting(now + 60 * 60_000L, 60 * 60_000L)
                        else -> SleepState.EndOfChapter
                    }
                    SleepState.EndOfChapter -> SleepState.Off
                }
            }
            CMD_BOOKMARK.customAction -> {
                val id = PlayerHub.bookIdOf(exo.currentMediaItem?.mediaId) ?: return
                val ch = PlayerHub.chapters.value.lastOrNull { it.startMs <= globalPosition() }
                library.addBookmark(
                    com.worm.app.data.Bookmark(
                        java.util.UUID.randomUUID().toString(), id, "audio", ch?.title ?: "Bookmark", System.currentTimeMillis(),
                        positionMs = exo.currentPosition, trackIndex = exo.currentMediaItemIndex,
                    ),
                )
            }
        }
        refreshButtons()
    }

    private fun globalPosition(): Long {
        val book = PlayerHub.bookId.value?.let(library::book) ?: return exo.currentPosition
        return if (book.tracks.size > 1) book.tracks.take(exo.currentMediaItemIndex).sumOf { it.durationMs } + exo.currentPosition else exo.currentPosition
    }

    /** Single-file books: show the current chapter as the title in the notification / car / lock screen. */
    private var shownChapter = -1
    private fun updateChapterTitle() {
        if (exo.mediaItemCount != 1) return
        val chapters = PlayerHub.chapters.value.takeIf { it.size > 1 } ?: return
        val i = chapters.indexOfLast { it.startMs <= exo.currentPosition + 250 }.coerceAtLeast(0)
        if (i == shownChapter) return
        shownChapter = i
        PlayerHub.chapterTitle.value = chapters[i].title
        com.worm.app.widget.Widgets.refreshPlayer(this)
        val item = exo.currentMediaItem ?: return
        val book = PlayerHub.bookId.value?.let(library::book) ?: return
        val meta = item.mediaMetadata.buildUpon()
            .setTitle(chapters[i].title)
            .setArtist(book.title)
            .setSubtitle("${book.author} · chapter ${i + 1} of ${chapters.size}")
            .build()
        exo.replaceMediaItem(0, item.buildUpon().setMediaMetadata(meta).build())
    }

    /** Our own numbered icons, so 20, 45, 60 and 90 show their number like 15 and 30 do. */
    private fun skipIconRes(sec: Int, forward: Boolean): Int? = if (forward) when (sec) {
        5 -> com.worm.app.R.drawable.ic_skip_fwd_5
        10 -> com.worm.app.R.drawable.ic_skip_fwd_10
        15 -> com.worm.app.R.drawable.ic_skip_fwd_15
        20 -> com.worm.app.R.drawable.ic_skip_fwd_20
        30 -> com.worm.app.R.drawable.ic_skip_fwd_30
        45 -> com.worm.app.R.drawable.ic_skip_fwd_45
        60 -> com.worm.app.R.drawable.ic_skip_fwd_60
        90 -> com.worm.app.R.drawable.ic_skip_fwd_90
        else -> null
    } else when (sec) {
        5 -> com.worm.app.R.drawable.ic_skip_back_5
        10 -> com.worm.app.R.drawable.ic_skip_back_10
        15 -> com.worm.app.R.drawable.ic_skip_back_15
        20 -> com.worm.app.R.drawable.ic_skip_back_20
        30 -> com.worm.app.R.drawable.ic_skip_back_30
        45 -> com.worm.app.R.drawable.ic_skip_back_45
        60 -> com.worm.app.R.drawable.ic_skip_back_60
        90 -> com.worm.app.R.drawable.ic_skip_back_90
        else -> null
    }

    // ------------------------------------------------------------ listening

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            playing = isPlaying
            com.worm.app.widget.Widgets.refreshPlayer(this@PlaybackService)
            if (isPlaying) {
                exo.setAudioAttributes(attributes(library.prefs.duckForNotifications), true)
                session?.setMediaButtonPreferences(buttons())
                listenedSince = System.currentTimeMillis()
            } else {
                accumulateListening()
                if (exo.playbackState != Player.STATE_ENDED) pausedAt = System.currentTimeMillis()
                saveNow()
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (playWhenReady && pausedAt > 0) {
                rewindAfterPause(System.currentTimeMillis() - pausedAt)
                pausedAt = 0
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val id = PlayerHub.bookIdOf(mediaItem?.mediaId)
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) shownChapter = -1
            if (id != PlayerHub.bookId.value) PlayerHub.setBook(id?.let(library::book), id?.let { library.overrides.value[it]?.chapters })
            if (exo.mediaItemCount > 1) PlayerHub.chapterTitle.value = mediaItem?.mediaMetadata?.title?.toString()
            com.worm.app.widget.Widgets.refreshPlayer(this@PlaybackService)
            if (PlayerHub.sleep.value == SleepState.EndOfChapter && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO &&
                PlayerHub.chapters.value.size > 1 && exo.mediaItemCount > 1
            ) {
                fallAsleep()
            }
            saveNow()
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) {
                PlayerHub.bookId.value?.let { library.setFinished(it, true) }
            }
        }

        override fun onPlaybackParametersChanged(params: PlaybackParameters) {
            library.prefs.playbackSpeed = params.speed
            refreshButtons()
        }
    }

    /** Back up a little after a pause so you catch the thread again; more after longer breaks. */
    private fun rewindAfterPause(pausedMs: Long) {
        val prefs = library.prefs
        val base = prefs.resumeRewindSec * 1000L
        if (base <= 0 || pausedMs < 3_000) return
        val factor = if (!prefs.smartRewind) 1.0 else when {
            pausedMs < 60_000 -> 0.5
            pausedMs < 10 * 60_000 -> 1.0
            pausedMs < 60 * 60_000 -> 2.0
            else -> 4.0
        }
        val back = (base * factor).toLong()
        exo.seekTo((exo.currentPosition - back).coerceAtLeast(0))
    }

    private fun accumulateListening() {
        if (listenedSince == 0L) return
        val secs = (System.currentTimeMillis() - listenedSince) / 1000
        listenedSince = 0
        val id = PlayerHub.bookId.value ?: return
        if (secs > 0) {
            library.saveProgress(id) { it.copy(timeSpentSec = it.timeSpentSec + secs) }
            library.logActivity(listenSec = secs)
        }
    }

    private suspend fun saveLoop() {
        while (scope.isActive) {
            delay(5_000)
            if (exo.isPlaying) {
                accumulateListening()
                listenedSince = System.currentTimeMillis()
                saveNow()
            }
        }
    }

    private fun saveNow() {
        val id = PlayerHub.bookIdOf(exo.currentMediaItem?.mediaId) ?: return
        val book = library.book(id) ?: return
        val index = exo.currentMediaItemIndex
        val pos = exo.currentPosition.coerceAtLeast(0)
        val before = book.tracks.take(index).sumOf { it.durationMs }
        val total = book.durationMs.takeIf { it > 0 } ?: return
        val fraction = ((before + pos).toFloat() / total).coerceIn(0f, 1f)
        library.saveProgress(id) {
            it.copy(trackIndex = index, positionMs = pos, fraction = fraction, finished = it.finished && fraction > 0.98f)
        }
    }

    // ----------------------------------------------------------- sleep timer

    private suspend fun runSleepTimer(state: SleepState) {
        stopShakeListener()
        exo.volume = 1f
        when (state) {
            SleepState.Off -> Unit
            SleepState.EndOfChapter -> while (true) {
                delay(500)
                val chapters = PlayerHub.chapters.value
                if (chapters.size <= 1 || exo.mediaItemCount > 1) continue // multi-file: handled on transition
                val pos = exo.currentPosition
                val ch = chapters.lastOrNull { it.startMs <= pos } ?: continue
                if (ch.endMs - pos in 0..600) { fallAsleep(); return }
            }
            is SleepState.Counting -> {
                if (library.prefs.shakeToExtend) startShakeListener()
                while (true) {
                    val left = state.endsAt - System.currentTimeMillis()
                    if (left <= 0) { fallAsleep(); return }
                    // Fade out over the last 15 seconds.
                    exo.volume = if (left < 15_000) (left / 15_000f).coerceIn(0.05f, 1f) else 1f
                    delay(250)
                }
            }
        }
    }

    private fun fallAsleep() {
        exo.pause()
        exo.volume = 1f
        PlayerHub.sleep.value = SleepState.Off
        PlayerHub.sleepFired.value++
    }

    private var sensors: SensorManager? = null
    private val shake = object : SensorEventListener {
        private var last = 0L
        override fun onSensorChanged(e: SensorEvent) {
            val g = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]) / SensorManager.GRAVITY_EARTH
            val now = System.currentTimeMillis()
            if (g > 2.3f && now - last > 1500) {
                last = now
                val s = PlayerHub.sleep.value as? SleepState.Counting ?: return
                val add = library.prefs.sleepMinutes * 60_000L
                handler.post { PlayerHub.sleep.value = SleepState.Counting(maxOf(s.endsAt, now) + add, s.totalMs + add) }
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private fun startShakeListener() {
        val sm = getSystemService(SENSOR_SERVICE) as? SensorManager ?: return
        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(shake, it, SensorManager.SENSOR_DELAY_UI) }
        sensors = sm
    }

    private fun stopShakeListener() {
        sensors?.unregisterListener(shake)
        sensors = null
    }

    // ----------------------------------------------------- library browsing

    private inner class LibraryCallback : MediaLibrarySession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .add(CMD_SPEED).add(CMD_SLEEP).add(CMD_BOOKMARK).add(CMD_NEXT_CHAPTER).add(CMD_PREV_CHAPTER).build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session).setAvailableSessionCommands(commands).build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: androidx.media3.session.SessionCommand,
            args: android.os.Bundle,
        ): ListenableFuture<androidx.media3.session.SessionResult> {
            onCustom(customCommand.customAction)
            return Futures.immediateFuture(androidx.media3.session.SessionResult(androidx.media3.session.SessionResult.RESULT_SUCCESS))
        }

        private fun audiobooks(): List<Book> = library.visibleBooks().filter { it.isAudio && !it.hidden }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(PlayerHub.folderItem(PlayerHub.ROOT, "Worm"), params))

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val book = PlayerHub.bookIdOf(mediaId)?.let(library::book)?.takeIf { !it.hidden }
            return Futures.immediateFuture(
                if (book != null) LibraryResult.ofItem(PlayerHub.bookItem(this@PlaybackService, book, library.progress.value[book.id]), null)
                else LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE),
            )
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val progress = library.progress.value
            val books = audiobooks()
            val ctx = this@PlaybackService
            val items: List<MediaItem> = when {
                parentId == PlayerHub.ROOT -> buildList {
                    add(PlayerHub.folderItem(PlayerHub.CONTINUE, "Continue listening"))
                    add(PlayerHub.folderItem(PlayerHub.RECENT, "Recently added"))
                    add(PlayerHub.folderItem(PlayerHub.ALL, "All audiobooks"))
                    library.shelves.value.filter { !it.hidden }.forEach { s ->
                        if (s.bookIds.any { id -> books.any { it.id == id } }) {
                            add(PlayerHub.folderItem(PlayerHub.SHELF_PREFIX + s.id, listOfNotNull(s.emoji, s.name).joinToString(" ")))
                        }
                    }
                }
                parentId == PlayerHub.CONTINUE -> books
                    .filter { (progress[it.id]?.fraction ?: 0f) > 0f && progress[it.id]?.finished != true }
                    .sortedByDescending { progress[it.id]?.updatedAt ?: 0L }
                    .map { PlayerHub.bookItem(ctx, it, progress[it.id]) }
                parentId == PlayerHub.RECENT -> books.sortedByDescending { it.addedAt }.take(30)
                    .map { PlayerHub.bookItem(ctx, it, progress[it.id]) }
                parentId == PlayerHub.ALL -> books.sortedBy { it.sortTitle }.map { PlayerHub.bookItem(ctx, it, progress[it.id]) }
                parentId.startsWith(PlayerHub.SHELF_PREFIX) -> {
                    val shelf = library.shelves.value.firstOrNull { it.id == parentId.removePrefix(PlayerHub.SHELF_PREFIX) && !it.hidden }
                    shelf?.bookIds?.mapNotNull { id -> books.firstOrNull { it.id == id } }
                        ?.map { PlayerHub.bookItem(ctx, it, progress[it.id]) }.orEmpty()
                }
                else -> emptyList()
            }
            val paged = items.drop(page * pageSize).take(pageSize)
            return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(paged), params))
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> {
            session.notifySearchResultChanged(browser, query, search(query).size, params)
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val progress = library.progress.value
            val items = search(query).drop(page * pageSize).take(pageSize).map { PlayerHub.bookItem(this@PlaybackService, it, progress[it.id]) }
            return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(items), params))
        }

        private fun search(query: String): List<Book> {
            val q = query.trim().lowercase()
            return audiobooks().filter { b ->
                q.isEmpty() || b.title.lowercase().contains(q) || b.author.lowercase().contains(q) ||
                    b.narrator?.lowercase()?.contains(q) == true || b.series?.lowercase()?.contains(q) == true
            }
        }

        /** Controllers (Auto, the UI, voice search) ask for a book; we expand it to its tracks + saved spot. */
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val first = mediaItems.firstOrNull()
            val requested = PlayerHub.bookIdOf(first?.mediaId)?.let(library::book)
                ?: first?.requestMetadata?.searchQuery?.let { q -> search(q).firstOrNull() }
            if (requested == null || (first?.localConfiguration != null && mediaItems.size > 1)) {
                return super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs)
            }
            return Futures.immediateFuture(resolve(requested, first?.mediaId))
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            val expanded = mediaItems.flatMap { item ->
                if (item.localConfiguration != null) listOf(item)
                else PlayerHub.bookIdOf(item.mediaId)?.let(library::book)?.let { PlayerHub.trackItems(this@PlaybackService, it) }.orEmpty()
            }
            return Futures.immediateFuture(expanded.toMutableList())
        }

        /** "Resume" from Bluetooth, the system media controls, or a car that just connected. */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            // The last thing opened might be an ebook; fall back to the audiobook listened to most recently.
            val progress = library.progress.value
            val last = library.prefs.lastBookId?.let(library::book)?.takeIf { it.isAudio && !it.hidden }
                ?: library.visibleBooks().filter { it.isAudio && !it.hidden && progress[it.id]?.finished != true && (progress[it.id]?.fraction ?: 0f) > 0f }
                    .maxByOrNull { progress[it.id]?.updatedAt ?: 0L }
                ?: return Futures.immediateFailedFuture(UnsupportedOperationException("Nothing to resume"))
            return Futures.immediateFuture(resolve(last, null))
        }

        private fun resolve(book: Book, mediaId: String?): MediaSession.MediaItemsWithStartPosition {
            PlayerHub.setBook(book, library.overrides.value[book.id]?.chapters)
            val p = library.progress.value[book.id]
            val explicitTrack = mediaId?.substringAfter('#', "")?.toIntOrNull()
            val index = explicitTrack ?: p?.trackIndex?.coerceIn(0, book.tracks.lastIndex) ?: 0
            val pos = if (explicitTrack != null || p?.finished == true) 0L else p?.positionMs ?: 0L
            return MediaSession.MediaItemsWithStartPosition(PlayerHub.trackItems(this@PlaybackService, book), index, pos)
        }
    }
}

/**
 * Next/previous from outside the app (steering wheel, earbuds, watch, notification) skip by the
 * user's seconds, or jump chapters if they turned that on. Everything works in whole-book time,
 * so skips and chapter jumps cross file boundaries in multi-file books.
 */
class ChapterPlayer(
    private val exo: ExoPlayer,
    private val prefs: com.worm.app.data.Prefs,
    private val trackLengths: () -> List<Long>,
) : ForwardingPlayer(exo) {

    override fun getAvailableCommands(): Player.Commands = super.getAvailableCommands().buildUpon()
        .add(Player.COMMAND_SEEK_TO_NEXT).add(Player.COMMAND_SEEK_TO_PREVIOUS)
        .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM).add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        .add(Player.COMMAND_SEEK_BACK).add(Player.COMMAND_SEEK_FORWARD)
        .build()

    override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)

    override fun getSeekBackIncrement(): Long = prefs.skipBackSec * 1000L
    override fun getSeekForwardIncrement(): Long = prefs.skipForwardSec * 1000L

    private fun lengths(): List<Long> = trackLengths().takeIf { it.size == exo.mediaItemCount && it.size > 1 }.orEmpty()

    private fun global(): Long {
        val l = lengths()
        return l.take(exo.currentMediaItemIndex).sum() + exo.currentPosition
    }

    private fun seekGlobal(ms: Long) {
        val l = lengths()
        if (l.isEmpty()) {
            val dur = exo.duration
            exo.seekTo(if (dur != C.TIME_UNSET) ms.coerceIn(0, dur) else ms.coerceAtLeast(0)); return
        }
        var acc = 0L
        l.forEachIndexed { i, len ->
            if (ms < acc + len || i == l.lastIndex) { exo.seekTo(i, (ms - acc).coerceIn(0, len)); return }
            acc += len
        }
    }

    override fun seekBack() = seekGlobal(global() - seekBackIncrement)
    override fun seekForward() = seekGlobal(global() + seekForwardIncrement)

    private fun chapters() = PlayerHub.chapters.value.takeIf { it.size > 1 }

    override fun seekToNext() = if (prefs.chapterSkipButtons) nextChapter() else seekForward()
    override fun seekToPrevious() = if (prefs.chapterSkipButtons) prevChapter() else seekBack()

    fun nextChapter() {
        val ch = chapters() ?: return seekForward()
        val pos = global()
        ch.firstOrNull { it.startMs > pos + 500 }?.let { seekGlobal(it.startMs) }
    }

    fun prevChapter() {
        val ch = chapters() ?: return seekBack()
        val pos = global()
        val current = ch.indexOfLast { it.startMs <= pos }.coerceAtLeast(0)
        val target = if (pos - ch[current].startMs > 3_000 || current == 0) ch[current] else ch[current - 1]
        seekGlobal(target.startMs)
    }

    override fun seekToNextMediaItem() = seekToNext()
    override fun seekToPreviousMediaItem() = seekToPrevious()
}
