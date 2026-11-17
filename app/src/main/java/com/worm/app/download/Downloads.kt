package com.worm.app.download

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import android.webkit.CookieManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.worm.app.R
import com.worm.app.convert.SilkSpinner
import com.worm.app.data.Formats
import com.worm.app.data.Library
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.UUID
import java.util.zip.ZipFile
import kotlin.coroutines.coroutineContext

enum class DlState { Queued, Running, Paused, Done, Failed }

data class Download(
    val id: String,
    val url: String,
    val fileName: String,
    val referer: String? = null,
    val userAgent: String? = null,
    val mime: String? = null,
    val total: Long = -1,
    val done: Long = 0,
    val state: DlState = DlState.Queued,
    val error: String? = null,
    /** Where it ended up (file, or folder for unzipped audiobooks). */
    val savedTo: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
    /** Authorization header (OPDS catalogues on private servers). */
    val auth: String? = null,
)

/**
 * Worm's download manager: resumable HTTP(S) downloads that land straight in your library.
 * The queue lives on disk; a foreground worker runs it so downloads keep going outside the app.
 */
object Downloads {
    private const val WORK = "worm-downloads"
    private val _items = MutableStateFlow<List<Download>>(emptyList())
    val items: StateFlow<List<Download>> = _items.asStateFlow()
    private var loaded = false

    private fun file(c: Context) = File(c.filesDir, "downloads.json")

    @Synchronized
    fun load(c: Context) {
        if (loaded) return
        loaded = true
        _items.value = runCatching {
            val a = JSONArray(file(c).readText())
            List(a.length()) { a.getJSONObject(it) }.map { o ->
                Download(
                    o.getString("id"), o.getString("url"), o.getString("name"), o.optString("ref").ifEmpty { null },
                    o.optString("ua").ifEmpty { null }, o.optString("mime").ifEmpty { null }, o.optLong("total", -1), o.optLong("done"),
                    DlState.valueOf(o.optString("state", "Queued")).let { if (it == DlState.Running) DlState.Queued else it },
                    o.optString("err").ifEmpty { null }, o.optString("saved").ifEmpty { null }, o.optLong("at"),
                    o.optString("auth").ifEmpty { null },
                )
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    private fun save(c: Context) = file(c).writeText(JSONArray(_items.value.map { d ->
        JSONObject().put("id", d.id).put("url", d.url).put("name", d.fileName).put("ref", d.referer ?: "").put("ua", d.userAgent ?: "")
            .put("mime", d.mime ?: "").put("total", d.total).put("done", d.done).put("state", d.state.name)
            .put("err", d.error ?: "").put("saved", d.savedTo ?: "").put("at", d.addedAt).put("auth", d.auth ?: "")
    }).toString())

    internal fun update(c: Context, id: String, persist: Boolean = true, f: (Download) -> Download) {
        _items.update { l -> l.map { if (it.id == id) f(it) else it } }
        if (persist) save(c)
    }

    fun enqueue(c: Context, url: String, fileName: String, referer: String?, userAgent: String?, mime: String?, size: Long, auth: String? = null) {
        load(c)
        _items.update { it + Download(UUID.randomUUID().toString(), url, cleanName(fileName), referer, userAgent, mime, size, auth = auth) }
        save(c)
        start(c)
    }

    fun start(c: Context) = WorkManager.getInstance(c).enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<DownloadWorker>().build())

    fun pause(c: Context, id: String) { update(c, id) { if (it.state == DlState.Queued || it.state == DlState.Running) it.copy(state = DlState.Paused) else it } }
    fun resume(c: Context, id: String) { update(c, id) { it.copy(state = DlState.Queued, error = null) }; start(c) }
    fun remove(c: Context, id: String) {
        _items.value.firstOrNull { it.id == id }?.let { d -> partFile(c, d).delete() }
        _items.update { l -> l.filterNot { it.id == id } }
        save(c)
    }
    fun clearFinished(c: Context) { _items.update { l -> l.filterNot { it.state == DlState.Done } }; save(c) }

    /** Downloads land in "Worm Downloads" inside your first library folder (so they're in your library right away). */
    fun destination(c: Context): File {
        val lib = Library.get(c)
        val base = lib.sources.value.firstOrNull()?.path?.let(::File)
            ?: File(Environment.getExternalStorageDirectory(), "Books").also { it.mkdirs(); lib.addSource(it.absolutePath) }
        return File(base, "Worm Downloads").apply { mkdirs() }
    }

    internal fun partFile(c: Context, d: Download) = File(destination(c), ".part-${d.id}")

    fun cleanName(raw: String): String {
        val decoded = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
        return decoded.substringAfterLast('/').replace(Regex("""[\\/:*?"<>|]"""), "_").trim().trimStart('.').take(180).ifEmpty { "download" }
    }

    /** Best file name from the URL / Content-Disposition / mime type. */
    fun guessName(url: String, contentDisposition: String?, mime: String?): String {
        val fromHeader = contentDisposition?.let {
            Regex("""filename\*\s*=\s*(?:UTF-8'')?([^;]+)""", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)?.trim('"', ' ')
                ?: Regex("""filename\s*=\s*"?([^";]+)"?""", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)?.trim()
        }
        var name = cleanName(fromHeader ?: url.substringBefore('?').substringAfterLast('/'))
        if ('.' !in name) extensionFor(mime)?.let { name += ".$it" }
        return name
    }

    fun extensionFor(mime: String?) = when (mime?.substringBefore(';')?.trim()?.lowercase()) {
        "application/epub+zip" -> "epub"
        "application/x-mobipocket-ebook" -> "mobi"
        "application/vnd.amazon.ebook", "application/vnd.amazon.mobi8-ebook" -> "azw3"
        "application/pdf" -> "pdf"
        "application/x-fictionbook+xml" -> "fb2"
        "application/vnd.comicbook+zip", "application/x-cbz" -> "cbz"
        "application/zip", "application/x-zip-compressed" -> "zip"
        "audio/mpeg" -> "mp3"
        "audio/mp4", "audio/x-m4a" -> "m4a"
        "audio/x-m4b" -> "m4b"
        "text/plain" -> "txt"
        else -> null
    }

    /** Is this something Worm can use (or unpack into something it can)? */
    fun isBookish(name: String, mime: String?): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in Formats.EBOOK || ext in Formats.AUDIO || ext in setOf("zip", "pdf", "txt", "docx", "md", "html", "htm", "azw", "azw3", "prc", "fb2", "fbz", "torrent") ||
            extensionFor(mime) != null
    }
}

class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val notifId = 6160

    override suspend fun doWork(): Result {
        val c = applicationContext
        val startedAt = System.currentTimeMillis()
        val ranThisTime = mutableSetOf<String>()
        Downloads.load(c)
        com.worm.app.notify.WormNotify.channels(c)
        setForeground(info("Getting ready…", -1f, null))
        while (true) {
            coroutineContext.ensureActive()
            val next = Downloads.items.value.firstOrNull { it.state == DlState.Queued } ?: break
            Downloads.update(c, next.id) { it.copy(state = DlState.Running, error = null) }
            ranThisTime += next.id
            val result = runCatching { fetch(next) }
            val now = Downloads.items.value.firstOrNull { it.id == next.id } ?: continue
            if (now.state == DlState.Paused) continue
            result.onSuccess { saved ->
                Downloads.update(c, next.id) { it.copy(state = DlState.Done, savedTo = saved.absolutePath, done = it.total.coerceAtLeast(it.done)) }
            }.onFailure { e ->
                android.util.Log.w("Worm", "Download failed: ${next.url}", e)
                Downloads.update(c, next.id) { it.copy(state = DlState.Failed, error = e.message ?: e.javaClass.simpleName) }
            }
        }
        val lib = Library.get(c)
        lib.scan()
        val finished = Downloads.items.value.filter { it.state == DlState.Done && it.savedTo != null && it.addedAt > startedAt - 86_400_000 }
            .filter { it.id in ranThisTime }
        if (SilkSpinner.canNotify(c) && finished.isNotEmpty()) {
            val first = finished.first()
            val book = lib.books.value.firstOrNull { b -> b.path == first.savedTo || b.tracks.any { it.path.startsWith(first.savedTo!!) } || b.path.startsWith(first.savedTo!!) }
            val one = finished.size == 1 && book != null
            NotificationManagerCompat.from(c).notify(
                notifId + 1,
                com.worm.app.notify.WormNotify.done(
                    c, com.worm.app.notify.WormNotify.CH_DOWNLOADS,
                    if (one) "Downloaded “${book!!.title}”" else "Downloaded ${finished.size} files",
                    if (one) "It's in your library now 🪱" else finished.joinToString(", ") { it.fileName },
                    largeIcon = com.worm.app.notify.WormNotify.cover(book?.coverPath),
                    contentIntent = com.worm.app.notify.WormNotify.open(c, 61, screen = "downloads"),
                    actions = if (one) listOf((if (book!!.isAudio) "Listen now" else "Read now") to com.worm.app.notify.WormNotify.open(c, 62, bookPath = book.path)) else emptyList(),
                ),
            )
        }
        return Result.success()
    }

    /** Downloads (resuming a partial file), then files it: books as-is, zips unpacked into a folder. */
    private suspend fun fetch(d: Download): File = withContext(Dispatchers.IO) {
        val c = applicationContext
        val part = Downloads.partFile(c, d)
        val from = part.length()
        val conn = (URL(d.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000; readTimeout = 60_000; instanceFollowRedirects = true
            d.userAgent?.let { setRequestProperty("User-Agent", it) }
            d.referer?.let { setRequestProperty("Referer", it) }
            d.auth?.let { setRequestProperty("Authorization", it) }
            // Same cookies the browser has, so downloads behind a sign-in work.
            CookieManager.getInstance().getCookie(d.url)?.let { setRequestProperty("Cookie", it) }
            if (from > 0) setRequestProperty("Range", "bytes=$from-")
        }
        try {
            val code = conn.responseCode
            if (code !in listOf(200, 206)) error("Server said $code")
            // Some sites answer a direct link with a "thanks for downloading" page. Don't save that as a book.
            val type = conn.contentType.orEmpty().lowercase()
            val wantsPage = d.fileName.substringAfterLast('.', "").lowercase() in setOf("html", "htm", "xhtml")
            if ((type.startsWith("text/html") || type.startsWith("application/xhtml")) && !wantsPage) error("The site sent a web page instead of the file. Open the page in the browser and tap its download button.")
            val append = code == 206
            val total = if (append) from + conn.contentLengthLong.coerceAtLeast(0) else conn.contentLengthLong
            Downloads.update(c, d.id) { it.copy(total = if (total > 0) total else it.total) }
            RandomAccessFile(part, "rw").use { raf ->
                if (append) raf.seek(from) else raf.setLength(0)
                var done = if (append) from else 0L
                var lastUi = 0L
                val buf = ByteArray(1 shl 16)
                conn.inputStream.use { input ->
                    while (true) {
                        coroutineContext.ensureActive()
                        if (Downloads.items.value.firstOrNull { it.id == d.id }?.state == DlState.Paused) return@withContext part
                        val n = input.read(buf)
                        if (n < 0) break
                        raf.write(buf, 0, n)
                        done += n
                        val t = System.currentTimeMillis()
                        if (t - lastUi > 400) {
                            lastUi = t
                            Downloads.update(c, d.id, persist = false) { it.copy(done = done) }
                            if (SilkSpinner.canNotify(c)) NotificationManagerCompat.from(c).notify(notifId, info(d.fileName, if (total > 0) done.toFloat() / total else -1f, d.id).notification)
                        }
                    }
                }
                Downloads.update(c, d.id) { it.copy(done = done) }
            }
        } finally { conn.disconnect() }
        place(d, part)
    }

    /** Checks the bytes match what the name promises (a "book" that's really a web page gets caught here). */
    private fun validate(name: String, f: File) {
        val head = ByteArray(80)
        val n = f.inputStream().use { it.read(head) }.coerceAtLeast(0)
        val text = String(head, 0, n, Charsets.ISO_8859_1).trimStart('\uFEFF', ' ', '\n', '\r', '\t').lowercase()
        val looksLikePage = text.startsWith("<!doctype html") || text.startsWith("<html") || (text.startsWith("<?xml") && "html" in text)
        val ext = name.substringAfterLast('.', "").lowercase()
        val ok = when (ext) {
            "epub", "cbz", "zip", "docx", "fbz" -> n >= 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
            "mobi", "azw", "azw3", "prc", "kf8" -> n >= 68 && String(head, 60, 8, Charsets.ISO_8859_1) in setOf("BOOKMOBI", "TEXtREAd")
            "pdf" -> text.startsWith("%pdf")
            "m4a", "m4b", "mp4", "aac" -> n >= 8 && String(head, 4, 4, Charsets.ISO_8859_1) == "ftyp" || ext == "aac"
            "mp3" -> text.startsWith("id3") || (n >= 2 && head[0] == 0xFF.toByte() && (head[1].toInt() and 0xE0) == 0xE0)
            else -> !looksLikePage || ext in setOf("html", "htm", "xhtml", "txt", "md")
        }
        if (!ok) {
            f.delete()
            error(if (looksLikePage) "The site sent a web page instead of the file. Open the page in the browser and tap its download button."
                  else "That doesn't look like a real .$ext file")
        }
    }

    private fun place(d: Download, part: File): File {
        val dir = Downloads.destination(applicationContext)
        val name = d.fileName
        // A .torrent file: hand it to the torrent client (which asks which files you want).
        if (name.endsWith(".torrent", true) || d.mime?.contains("bittorrent") == true) {
            val bytes = part.readBytes(); part.delete()
            com.worm.app.torrent.TorrentEngine.addTorrentBytes(applicationContext, bytes)
            return dir
        }
        validate(name, part)
        val isZip = name.endsWith(".zip", true) || runCatching { part.inputStream().use { s -> val b = ByteArray(4); s.read(b); b[0] == 0x50.toByte() && b[1] == 0x4B.toByte() } }.getOrDefault(false) &&
            name.substringAfterLast('.', "").lowercase() !in setOf("epub", "cbz", "docx", "fbz")
        if (isZip) {
            // Zipped audiobooks / book bundles: unpack into their own folder, keeping only usable files.
            val folder = unique(dir, name.substringBeforeLast('.'), isDir = true).apply { mkdirs() }
            var kept = 0
            ZipFile(part).use { z ->
                z.entries().asSequence().filter { !it.isDirectory }.forEach { e ->
                    val rel = e.name.replace('\\', '/').split('/').filter { it.isNotBlank() && it != ".." }
                    val leaf = rel.lastOrNull() ?: return@forEach
                    val ext = leaf.substringAfterLast('.', "").lowercase()
                    if (ext !in Formats.EBOOK && ext !in Formats.AUDIO && ext !in setOf("jpg", "jpeg", "png", "pdf", "txt", "cue", "nfo")) return@forEach
                    val target = File(folder, rel.drop(if (rel.size > 1) 1 else 0).joinToString("/").ifEmpty { leaf })
                    target.parentFile?.mkdirs()
                    z.getInputStream(e).use { input -> target.outputStream().use { input.copyTo(it) } }
                    kept++
                }
            }
            part.delete()
            check(kept > 0) { "That zip had no books or audio in it" }
            Library.get(applicationContext).notifyMediaStore(*folder.walkTopDown().filter { it.isFile }.map { it.absolutePath }.toList().toTypedArray())
            return folder
        }
        val target = unique(dir, name, isDir = false)
        check(part.renameTo(target)) { "Couldn't save ${target.name}" }
        Library.get(applicationContext).notifyMediaStore(target.absolutePath)
        return target
    }

    private fun unique(dir: File, name: String, isDir: Boolean): File {
        var f = File(dir, name)
        var n = 2
        val base = if (isDir) name else name.substringBeforeLast('.')
        val ext = if (isDir || '.' !in name) "" else "." + name.substringAfterLast('.')
        while (f.exists()) f = File(dir, "$base ($n)$ext").also { n++ }
        return f
    }

    private fun info(name: String, fraction: Float, downloadId: String?): ForegroundInfo {
        val c = applicationContext
        val waiting = Downloads.items.value.count { it.state == DlState.Queued }
        val n = com.worm.app.notify.WormNotify.progress(
            c, com.worm.app.notify.WormNotify.CH_DOWNLOADS,
            title = "Downloading" + if (waiting > 0) " · $waiting more waiting" else "",
            text = name,
            fraction = fraction,
            contentIntent = com.worm.app.notify.WormNotify.open(c, 60, screen = "downloads"),
            actions = downloadId?.let {
                listOf(
                    com.worm.app.notify.WormNotify.action(c, 63, "Pause", com.worm.app.notify.NotifyActions.PAUSE_DOWNLOAD, it),
                    com.worm.app.notify.WormNotify.action(c, 64, "Cancel", com.worm.app.notify.NotifyActions.CANCEL_DOWNLOAD, it),
                )
            }.orEmpty(),
        )
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(notifId, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(notifId, n)
    }
}
