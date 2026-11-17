package com.worm.app.convert

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.worm.app.MainActivity
import com.worm.app.R
import com.worm.app.data.Book
import com.worm.app.data.Chapter
import com.worm.app.data.Library
import com.worm.app.data.Mp4Chapters
import com.worm.app.data.Mp4Tagger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Silk Spinner: turns MP3s (a folder of tracks, or one long file) into a single chaptered .m4b,
 * on the phone, with the hardware AAC encoder. Jobs queue on disk and run in a foreground worker,
 * so they survive leaving the app.
 */
object SilkSpinner {
    const val WORK = "silk-spinner"
    private const val CHANNEL = "converters"

    data class Job(val bookId: String, val bitrate: Int, val deleteOriginals: Boolean)
    data class Queue(val jobs: List<Job>, val done: Int, val failed: Int, val lastError: String?)

    private fun file(c: Context) = File(c.filesDir, "spinner_queue.json")

    @Synchronized
    fun read(c: Context): Queue = runCatching {
        val j = JSONObject(file(c).readText())
        val a = j.getJSONArray("jobs")
        Queue(
            List(a.length()) { a.getJSONObject(it).let { o -> Job(o.getString("id"), o.optInt("br", 64_000), o.optBoolean("del")) } },
            j.optInt("done"), j.optInt("failed"), j.optString("err").ifEmpty { null },
        )
    }.getOrDefault(Queue(emptyList(), 0, 0, null))

    @Synchronized
    private fun write(c: Context, q: Queue) = file(c).writeText(
        JSONObject().put("jobs", JSONArray(q.jobs.map { JSONObject().put("id", it.bookId).put("br", it.bitrate).put("del", it.deleteOriginals) }))
            .put("done", q.done).put("failed", q.failed).put("err", q.lastError ?: "").toString(),
    )

    fun enqueue(c: Context, books: List<Book>, bitrate: Int, deleteOriginals: Boolean) {
        val q = read(c).let { if (it.jobs.isEmpty()) Queue(emptyList(), 0, 0, null) else it }
        val fresh = books.filter { b -> q.jobs.none { it.bookId == b.id } }.map { Job(it.id, bitrate, deleteOriginals) }
        write(c, q.copy(jobs = q.jobs + fresh))
        WorkManager.getInstance(c).enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<SpinWorker>().build())
    }

    fun cancel(c: Context) {
        WorkManager.getInstance(c).cancelUniqueWork(WORK)
        write(c, Queue(emptyList(), 0, 0, null))
    }

    @Synchronized
    internal fun finish(c: Context, id: String, error: String?) {
        val q = read(c)
        write(c, q.copy(jobs = q.jobs.filterNot { it.bookId == id }, done = q.done + if (error == null) 1 else 0,
            failed = q.failed + if (error == null) 0 else 1, lastError = error ?: q.lastError))
    }

    /** Sensible default: match the source (mono speech at 64k is plenty), capped at 128k. */
    fun suggestedBitrate(book: Book): Int {
        val bps = runCatching {
            val r = MediaMetadataRetriever()
            try { r.setDataSource(book.tracks.first().path); r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toInt() } finally { r.release() }
        }.getOrNull() ?: 96_000
        return listOf(64_000, 96_000, 128_000).minBy { kotlin.math.abs(it - bps.coerceAtMost(128_000)) }
    }

    /** Where the .m4b goes: next to the folder of tracks (or beside a single file). */
    fun destinationFor(book: Book, library: Library): File {
        val folder = library.bookFolder(book)
        val isRoot = folder == library.burrow || library.sources.value.any { File(it.path) == folder }
        val ownFolder = book.tracks.size > 1 && File(book.path).isDirectory && !isRoot
        val dir = if (ownFolder) folder.parentFile ?: folder else folder
        val name = (listOfNotNull(book.title, book.authors.firstOrNull()).joinToString(" - "))
            .replace(Regex("""[\\/:*?"<>|]"""), "_").take(150)
        var out = File(dir, "$name.m4b")
        var n = 2
        while (out.exists()) out = File(dir, "$name ($n).m4b").also { n++ }
        return out
    }

    /** Chapters for the new file: your matched names if they fit, else one per track. */
    fun chaptersFor(book: Book, library: Library, actualTotalMs: Long): List<Chapter> {
        val custom = library.overrides.value[book.id]?.chapters
        if (book.tracks.size == 1) return custom.orEmpty()
        val sum = book.tracks.sumOf { it.durationMs }.coerceAtLeast(1)
        val scale = if (actualTotalMs > 0) actualTotalMs.toDouble() / sum else 1.0
        var t = 0L
        val perTrack = book.tracks.mapIndexed { i, tr ->
            val start = (t * scale).toLong(); t += tr.durationMs
            Chapter(custom?.getOrNull(i)?.title ?: tr.title, start, (t * scale).toLong(), 0)
        }
        return perTrack
    }

    suspend fun encode(context: Context, book: Book, out: File, bitrate: Int, onProgress: (Float) -> Unit) = withContext(Dispatchers.Main) {
        val items = book.tracks.map { t ->
            EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(File(t.path)))).setRemoveVideo(true).build()
        }
        val composition = Composition.Builder(EditedMediaItemSequence.Builder(items).build()).build()
        suspendCancellableCoroutine { cont ->
            val transformer = Transformer.Builder(context)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setEncoderFactory(
                    HardwareAacFirst(
                        context, bitrate,
                        DefaultEncoderFactory.Builder(context)
                            .setRequestedAudioEncoderSettings(AudioEncoderSettings.Builder().setBitrate(bitrate).build())
                            .setEnableFallback(true)
                            .build(),
                    ),
                )
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (cont.isActive) cont.resume(Unit)
                    }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        if (cont.isActive) cont.resumeWithException(exportException)
                    }
                })
                .build()
            transformer.start(composition, out.absolutePath)
            val poller = kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch {
                val holder = ProgressHolder()
                while (isActive) {
                    if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress / 100f)
                    delay(700)
                }
            }
            cont.invokeOnCancellation { transformer.cancel(); poller.cancel() }
            cont.context[kotlinx.coroutines.Job]?.invokeOnCompletion { poller.cancel() }
        }
    }

    fun durationOf(f: File): Long = runCatching {
        val r = MediaMetadataRetriever()
        try { r.setDataSource(f.absolutePath); r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0 } finally { r.release() }
    }.getOrDefault(0L)

    fun ensureChannel(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Converting books", NotificationManager.IMPORTANCE_LOW))
        }
    }

    fun canNotify(c: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(c, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    internal fun channel() = CHANNEL
}

class SpinWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val notifId = 5150
    private var lastSpun: String? = null
    private val library = Library.get(context)

    override suspend fun doWork(): Result {
        com.worm.app.notify.WormNotify.channels(applicationContext)
        setForeground(info("Getting ready…", -1f, 0, 0, null))
        while (true) {
            coroutineContext.ensureActive()
            val q = SilkSpinner.read(applicationContext)
            val job = q.jobs.firstOrNull() ?: break
            val index = q.done + q.failed + 1
            val total = q.jobs.size + q.done + q.failed
            val book = library.book(job.bookId)
            if (book == null) { SilkSpinner.finish(applicationContext, job.bookId, "Book not found"); continue }
            setForeground(info(book.title, 0f, index, total, book))
            val out = SilkSpinner.destinationFor(book, library)
            val tmp = File(out.parentFile, ".spinning-${System.currentTimeMillis()}.m4a")
            val error = runCatching {
                var last = 0L
                SilkSpinner.encode(applicationContext, book, tmp, job.bitrate) { f ->
                    val now = System.currentTimeMillis()
                    if (now - last > 900) {
                        last = now
                        setProgressAsync(workDataOf("title" to book.title, "index" to index, "total" to total, "fraction" to f * 0.95f))
                        if (SilkSpinner.canNotify(applicationContext)) {
                            NotificationManagerCompat.from(applicationContext).notify(notifId, info(book.title, f * 0.95f, index, total, book).notification)
                        }
                    }
                }
                finish(book, tmp, out, job)
            }.exceptionOrNull()
            tmp.delete()
            if (isStopped) { out.takeIf { error != null }?.delete(); return Result.failure() }
            if (error != null) android.util.Log.e("Worm", "Silk Spinner failed for ${book.path}", error)
            SilkSpinner.finish(applicationContext, job.bookId, error?.let { "${book.title}: ${it.message ?: it.javaClass.simpleName}" })
        }
        done(SilkSpinner.read(applicationContext))
        return Result.success()
    }

    /** Tags, checks, swaps in, carries your progress over, and (optionally) removes the MP3s. */
    private suspend fun finish(book: Book, tmp: File, out: File, job: SilkSpinner.Job) = withContext(Dispatchers.IO) {
        val actual = SilkSpinner.durationOf(tmp)
        val expected = book.tracks.sumOf { it.durationMs }
        check(actual > 0) { "The new file doesn't play" }
        if (expected > 0) check(kotlin.math.abs(actual - expected) < expected * 0.02 + 3_000) {
            "Length changed (${actual / 1000}s vs ${expected / 1000}s), so the originals were kept"
        }
        val chapters = SilkSpinner.chaptersFor(book, library, actual)
        val cover = book.coverPath?.let { File(it) }?.takeIf { it.isFile }?.readBytes()
        Mp4Tagger.write(
            tmp, out,
            Mp4Tagger.Tags(
                title = book.title, artist = book.authors.joinToString(", ").ifEmpty { null },
                narrator = book.narrators.joinToString(", ").ifEmpty { null }, description = book.description,
                series = book.series, seriesIndex = book.seriesIndex, cover = cover,
            ),
            chapters,
        )
        // Re-read what we wrote before trusting it.
        check(SilkSpinner.durationOf(out) > 0) { "The tagged file doesn't play" }
        if (chapters.size > 1) check(Mp4Chapters.read(out).size == chapters.size.coerceAtMost(255)) { "Chapters didn't stick" }
        out.setLastModified(System.currentTimeMillis())

        library.scan()
        val created = library.books.value.firstOrNull { it.path == out.absolutePath }
        if (created != null) library.migrate(book, created, keepChapters = false)
        lastSpun = out.absolutePath
        if (job.deleteOriginals) {
            library.deleteTracks(book)
            library.scan()
        }
        library.notifyMediaStore(out.absolutePath)
    }

    /** One segment per track (so you can watch each MP3 get spun), with the book's cover. */
    private fun info(name: String, fraction: Float, index: Int, total: Int, book: Book?): ForegroundInfo {
        val c = applicationContext
        val n = com.worm.app.notify.WormNotify.progress(
            c, com.worm.app.notify.WormNotify.CH_CONVERT,
            title = if (total > 1) "Silk Spinner · $index of $total" else "Silk Spinner",
            text = if (book != null) "Spinning “$name” into one .m4b" else name,
            fraction = fraction,
            segments = book?.tracks?.map { it.durationMs.coerceAtLeast(1) }.orEmpty(),
            largeIcon = com.worm.app.notify.WormNotify.cover(book?.coverPath),
            contentIntent = com.worm.app.notify.WormNotify.open(c, 70, screen = "tools"),
            actions = listOf(com.worm.app.notify.WormNotify.action(c, 71, "Stop", com.worm.app.notify.NotifyActions.STOP_SPINNING)),
        )
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(notifId, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(notifId, n)
    }

    private fun done(q: SilkSpinner.Queue) {
        if (!SilkSpinner.canNotify(applicationContext)) return
        val c = applicationContext
        val text = buildString {
            append("Spun ${q.done} into .m4b")
            if (q.failed > 0) append(" · ${q.failed} couldn't be converted (originals kept)")
            q.lastError?.let { if (q.failed > 0) append("\n$it") }
        }
        val last = lastSpun?.let { path -> library.books.value.firstOrNull { it.path == path } }
        NotificationManagerCompat.from(c).notify(
            notifId + 1,
            com.worm.app.notify.WormNotify.done(
                c, com.worm.app.notify.WormNotify.CH_CONVERT,
                if (q.done == 1 && last != null) "“${last.title}” is spun 🕸️" else "Silk Spinner finished",
                text,
                largeIcon = com.worm.app.notify.WormNotify.cover(last?.coverPath),
                contentIntent = com.worm.app.notify.WormNotify.open(c, 72, bookPath = last?.path),
                actions = if (last != null) listOf("Listen now" to com.worm.app.notify.WormNotify.open(c, 73, bookPath = last.path)) else emptyList(),
            ),
        )
    }
}

/**
 * Android usually hands out a software AAC encoder first. Many phones (Snapdragon ones included)
 * also have a hardware one, so try that, and quietly fall back if it won't take the format.
 */
internal class HardwareAacFirst(
    private val context: Context,
    private val bitrate: Int,
    private val fallback: androidx.media3.transformer.Codec.EncoderFactory,
) : androidx.media3.transformer.Codec.EncoderFactory by fallback {

    override fun createForAudioEncoding(format: androidx.media3.common.Format, logSessionId: android.media.metrics.LogSessionId?): androidx.media3.transformer.Codec {
        val hw = if (android.os.Build.VERSION.SDK_INT >= 29) android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info ->
            info.isEncoder && info.isHardwareAccelerated && !info.isAlias && info.supportedTypes.any { it.equals(MimeTypes.AUDIO_AAC, true) }
        } else null
        if (hw != null && format.sampleRate > 0 && format.channelCount > 0) {
            runCatching {
                val mf = android.media.MediaFormat.createAudioFormat(MimeTypes.AUDIO_AAC, format.sampleRate, format.channelCount).apply {
                    setInteger(android.media.MediaFormat.KEY_BIT_RATE, bitrate)
                    setInteger(android.media.MediaFormat.KEY_AAC_PROFILE, android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                }
                val configured = format.buildUpon().setSampleMimeType(MimeTypes.AUDIO_AAC).setAverageBitrate(bitrate).build()
                return androidx.media3.transformer.DefaultCodec(context, configured, mf, hw.name, false, null)
                    .also { android.util.Log.i("SilkSpinner", "AAC encoder: ${hw.name} (hardware)") }
            }.onFailure { android.util.Log.i("SilkSpinner", "Hardware AAC encoder ${hw.name} declined: ${it.message}") }
        }
        return fallback.createForAudioEncoding(format, logSessionId).also { android.util.Log.i("SilkSpinner", "AAC encoder: ${it.name}") }
    }
}
