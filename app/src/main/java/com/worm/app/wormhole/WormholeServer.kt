package com.worm.app.wormhole

import android.content.Context
import com.worm.app.R
import com.worm.app.data.Book
import com.worm.app.data.Json
import com.worm.app.data.ReadingProgress
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.RandomAccessFile
import java.net.URLEncoder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread

/** Something someone wants to send us. Nothing is accepted until you say yes. */
data class Offer(
    val id: String,
    val from: String,
    val titles: List<String>,
    val files: Set<String>,   // "folder/name" keys allowed once accepted
    val bytes: Long,
    @Volatile var status: String = "pending", // pending | accepted | declined
)

/** Someone who connected to us: another Worm (with its own server) or a web browser. */
data class WormholePeer(val name: String, val urls: List<String>, val token: String?, val isBrowser: Boolean)

/**
 * Wormhole's tiny HTTP server. Runs only while the Wormhole screen is open.
 *
 * Auth: Worm apps send the token from the QR code; browsers type the 6-digit code once and get a
 * cookie. Burrow books are only listed while [shareHidden] is on.
 */
class WormholeServer(
    private val context: Context,
    port: Int,
    val token: String,
    val code: String,
    private val deviceName: String,
    private val catalog: () -> List<Book>,
    private val progress: () -> Map<String, ReadingProgress>,
    private val uploadRoot: () -> File,
    private val onPeer: (WormholePeer) -> Unit,
    private val onReceived: (File) -> Unit,
    private val onProgressIn: (Map<String, Spot>) -> Unit,
    /** Bytes arriving for an accepted offer: (folder/name key, display title, received, total, finished). */
    private val onIncoming: (String, String, Long, Long, Boolean) -> Unit = { _, _, _, _, _ -> },
    private val onOffer: (Offer) -> Unit,
    /** Reading spots are private unless you switch on sharing for this session. */
    private val spotsAllowed: () -> Boolean,
) : NanoHTTPD(port) {

    private val offers = java.util.concurrent.ConcurrentHashMap<String, Offer>()

    /** Called from the UI when you accept or decline. */
    fun decide(id: String, accept: Boolean) { offers[id]?.status = if (accept) "accepted" else "declined" }

    @Volatile var shareHidden = false
    private var failedLogins = 0
    private var lockedUntil = 0L

    fun startServer() = start(60_000, false)

    private fun books() = catalog().filter { shareHidden || !it.hidden }
    private fun book(id: String) = books().firstOrNull { it.id == id }

    /** The files that make up a book: the ebook / single audio file, or each track. */
    private fun filesOf(b: Book): List<File> = if (b.isAudio) b.tracks.map { File(it.path) } else listOf(File(b.path))

    override fun serve(session: IHTTPSession): Response = try {
        route(session)
    } catch (e: Exception) {
        android.util.Log.e("Worm", "Wormhole server error on ${session.uri}", e)
        json(Response.Status.INTERNAL_ERROR, JSONObject().put("error", e.message ?: "error"))
    }.also {
        it.addHeader("Access-Control-Allow-Origin", "*")
        it.addHeader("Cache-Control", "no-store")
    }

    private fun authed(s: IHTTPSession): Boolean {
        val t = s.headers["x-worm-token"] ?: s.cookies.read("worm") ?: s.parameters["t"]?.firstOrNull()
        return t == token
    }

    private fun route(s: IHTTPSession): Response {
        val uri = s.uri
        when {
            uri == "/" || uri == "/index.html" -> return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", portalHtml())
            uri == "/font.ttf" -> return resource(R.font.bricolage, "font/ttf")
            uri == "/api/login" && s.method == Method.POST -> return login(s)
            uri == "/api/hello" -> return json(Response.Status.OK, JSONObject().put("app", "worm").put("name", deviceName))
        }
        if (!authed(s)) return json(Response.Status.UNAUTHORIZED, JSONObject().put("error", "Enter the code shown in Worm"))

        val parts = uri.trim('/').split('/').map { java.net.URLDecoder.decode(it, "UTF-8") }
        return when {
            uri == "/api/peer" && s.method == Method.POST -> registerPeer(s)
            uri == "/api/books" -> listBooks()
            uri.startsWith("/api/progress") && !spotsAllowed() -> json(Response.Status.FORBIDDEN, JSONObject().put("error", "Reading spots aren't shared"))
            uri == "/api/progress" && s.method == Method.GET -> progressOut()
            uri == "/api/progress" && s.method == Method.POST -> progressIn(s)
            parts.size == 2 && parts[0] == "cover" -> cover(parts[1])
            parts.size == 3 && parts[0] == "f" -> serveFile(s, parts[1], parts[2].toIntOrNull() ?: 0)
            parts.size == 2 && parts[0] == "zip" -> zipBook(parts[1])
            uri == "/api/offer" && s.method == Method.POST -> offer(s)
            parts.size == 3 && parts[0] == "api" && parts[1] == "offer" -> json(Response.Status.OK, JSONObject().put("status", offers[parts[2]]?.status ?: "unknown"))
            uri == "/api/upload/status" -> uploadStatus(s)
            uri == "/api/upload" && (s.method == Method.PUT || s.method == Method.POST) -> upload(s)
            else -> json(Response.Status.NOT_FOUND, JSONObject().put("error", "not found"))
        }
    }

    // ------------------------------------------------------------------ auth

    private fun login(s: IHTTPSession): Response {
        val now = System.currentTimeMillis()
        if (now < lockedUntil) return json(Response.Status.FORBIDDEN, JSONObject().put("error", "Too many tries. Wait a few seconds."))
        val body = readBody(s)
        val entered = runCatching { JSONObject(body).getString("code") }.getOrDefault(body).trim()
        if (entered != code) {
            if (++failedLogins >= 5) { lockedUntil = now + 30_000; failedLogins = 0 }
            return json(Response.Status.UNAUTHORIZED, JSONObject().put("error", "Wrong code"))
        }
        failedLogins = 0
        val isApp = s.headers["x-worm-client"] != null
        if (!isApp) onPeer(WormholePeer("Web browser (${s.remoteIpAddress})", emptyList(), null, isBrowser = true))
        return json(Response.Status.OK, JSONObject().put("token", token).put("name", deviceName)).also {
            it.addHeader("Set-Cookie", "worm=$token; Path=/; HttpOnly; SameSite=Strict")
        }
    }

    private fun registerPeer(s: IHTTPSession): Response {
        val j = JSONObject(readBody(s))
        val urls = j.optJSONArray("urls")?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty()
        onPeer(WormholePeer(j.optString("name", "Worm"), urls, j.optString("token").ifEmpty { null }, isBrowser = false))
        return json(Response.Status.OK, JSONObject().put("name", deviceName))
    }

    // --------------------------------------------------------------- catalog

    private fun listBooks(): Response {
        val arr = JSONArray()
        books().forEach { b ->
            val files = filesOf(b)
            arr.put(
                JSONObject()
                    .put("id", b.id).put("title", b.title).put("authors", JSONArray(b.authors)).put("narrators", JSONArray(b.narrators))
                    .put("series", b.series ?: JSONObject.NULL).put("seriesIndex", b.seriesIndex?.toDouble() ?: JSONObject.NULL)
                    .put("audio", b.isAudio).put("format", b.format).put("size", b.sizeBytes).put("duration", b.durationMs)
                    .put("hidden", b.hidden).put("cover", b.coverPath != null)
                    .put("folder", if (b.isAudio && b.tracks.size > 1) File(b.path).name.substringBefore('#') else JSONObject.NULL)
                    .put("files", JSONArray(files.map { JSONObject().put("name", it.name).put("size", it.length()) })),
            )
        }
        return json(Response.Status.OK, JSONObject().put("name", deviceName).put("books", arr))
    }

    private fun progressOut(): Response {
        val ids = books().map { it.id }.toSet()
        val o = JSONObject()
        val byId = books().associateBy { it.id }
        progress().filterKeys { it in ids }.forEach { (k, v) -> o.put(k, Spot.json(v, byId[k])) }
        return json(Response.Status.OK, o)
    }

    private fun progressIn(s: IHTTPSession): Response {
        val o = JSONObject(readBody(s))
        onProgressIn(Spot.parse(o))
        return json(Response.Status.OK, JSONObject().put("ok", true))
    }

    private fun cover(id: String): Response {
        val f = book(id)?.coverPath?.let(::File)?.takeIf { it.isFile } ?: return notFound()
        return newFixedLengthResponse(Response.Status.OK, "image/jpeg", FileInputStream(f), f.length()).also { it.addHeader("Cache-Control", "max-age=3600") }
    }

    // ------------------------------------------------------------- streaming

    private fun serveFile(s: IHTTPSession, id: String, index: Int): Response {
        val b = book(id) ?: return notFound()
        val file = filesOf(b).getOrNull(index)?.takeIf { it.isFile } ?: return notFound()
        val len = file.length()
        val mime = mimeOf(file)
        val range = s.headers["range"]?.removePrefix("bytes=")?.split('-')
        val resp = if (range != null && range[0].isNotEmpty()) {
            val start = range[0].toLong()
            val end = range.getOrNull(1)?.takeIf { it.isNotEmpty() }?.toLong()?.coerceAtMost(len - 1) ?: (len - 1)
            if (start >= len) {
                return newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, "text/plain", "").also { it.addHeader("Content-Range", "bytes */$len") }
            }
            val input = FileInputStream(file).apply { skipFully(start) }
            newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mime, Bounded(input, end - start + 1), end - start + 1).also {
                it.addHeader("Content-Range", "bytes $start-$end/$len")
            }
        } else newFixedLengthResponse(Response.Status.OK, mime, FileInputStream(file), len)
        resp.addHeader("Accept-Ranges", "bytes")
        if (s.parameters["dl"] != null) resp.addHeader("Content-Disposition", "attachment; filename*=UTF-8''" + enc(file.name))
        return resp
    }

    /** Multi-file audiobooks as one .zip for browsers (no recompression: audio's already compressed). */
    private fun zipBook(id: String): Response {
        val b = book(id) ?: return notFound()
        val pipeIn = PipedInputStream(1 shl 16)
        val pipeOut = PipedOutputStream(pipeIn)
        val folder = b.title.replace(Regex("""[\\/:*?"<>|]"""), "_")
        thread(name = "wormhole-zip") {
            runCatching {
                ZipOutputStream(pipeOut).use { zip ->
                    zip.setLevel(0)
                    filesOf(b).forEach { f ->
                        zip.putNextEntry(ZipEntry("$folder/${f.name}"))
                        f.inputStream().use { it.copyTo(zip, 1 shl 16) }
                        zip.closeEntry()
                    }
                }
            }
        }
        return newChunkedResponse(Response.Status.OK, "application/zip", pipeIn).also {
            it.addHeader("Content-Disposition", "attachment; filename*=UTF-8''" + enc("$folder.zip"))
        }
    }

    // --------------------------------------------------------------- uploads

    private fun safeName(raw: String, fallback: String) = raw.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().trimStart('.').ifEmpty { fallback }

    /** Uploads land in <library>/Wormhole/[folder]/name (folder is optional: audiobook track sets). */
    private fun uploadTarget(s: IHTTPSession): Pair<File, File> {
        val folder = s.parameters["folder"]?.firstOrNull()?.let { safeName(it, "") }.orEmpty()
        val name = safeName(s.parameters["name"]?.firstOrNull() ?: "book", "book")
        val dir = if (folder.isEmpty()) uploadRoot() else File(uploadRoot(), folder)
        return File(dir, name) to File(dir, ".part-$name")
    }

    private fun uploadStatus(s: IHTTPSession): Response {
        if (!allowed(s)) return json(Response.Status.FORBIDDEN, JSONObject().put("error", "Not accepted by the receiver"))
        val (final, part) = uploadTarget(s)
        val size = s.parameters["size"]?.firstOrNull()?.toLongOrNull()
        val complete = final.exists() && (size == null || final.length() == size)
        return json(Response.Status.OK, JSONObject().put("received", if (complete) -1 else part.length()))
    }

    /** POST {from, items:[{title, folder?, files:[{name,size}]}]} → {id}. Then poll /api/offer/{id}. */
    private fun offer(s: IHTTPSession): Response {
        val j = JSONObject(readBody(s))
        val items = j.optJSONArray("items") ?: return json(Response.Status.BAD_REQUEST, JSONObject().put("error", "items"))
        val titles = mutableListOf<String>(); val keys = mutableSetOf<String>(); var bytes = 0L
        for (i in 0 until items.length()) {
            val it = items.getJSONObject(i)
            titles += it.optString("title")
            val folder = safeName(it.optString("folder"), "")
            val files = it.optJSONArray("files") ?: continue
            for (k in 0 until files.length()) {
                val f = files.getJSONObject(k)
                keys += "$folder/${safeName(f.optString("name"), "book")}"
                bytes += f.optLong("size")
            }
        }
        val from = j.optString("from").ifBlank { "Web browser (${s.remoteIpAddress})" }
        val o = Offer(java.util.UUID.randomUUID().toString(), from, titles, keys, bytes)
        offers[o.id] = o
        onOffer(o)
        return json(Response.Status.OK, JSONObject().put("id", o.id).put("status", o.status))
    }

    /** Uploads only go through for files in an offer you accepted. */
    private fun allowed(s: IHTTPSession): Boolean {
        val o = offers[s.parameters["offer"]?.firstOrNull() ?: return false] ?: return false
        if (o.status != "accepted") return false
        val folder = s.parameters["folder"]?.firstOrNull()?.let { safeName(it, "") }.orEmpty()
        val name = safeName(s.parameters["name"]?.firstOrNull() ?: "book", "book")
        return "$folder/$name" in o.files
    }

    /** PUT ?offer=&folder=&name=&offset=&size= with the raw bytes (from offset). Resumable. */
    private fun upload(s: IHTTPSession): Response {
        if (!allowed(s)) return json(Response.Status.FORBIDDEN, JSONObject().put("error", "Not accepted by the receiver"))
        val (final, part) = uploadTarget(s)
        val offset = s.parameters["offset"]?.firstOrNull()?.toLongOrNull() ?: 0L
        val total = s.parameters["size"]?.firstOrNull()?.toLongOrNull()
        val length = s.headers["content-length"]?.toLongOrNull() ?: return json(Response.Status.BAD_REQUEST, JSONObject().put("error", "length"))
        final.parentFile?.mkdirs()
        if (offset == 0L) part.delete()
        if (part.length() != offset) return json(Response.Status.BAD_REQUEST, JSONObject().put("error", "offset").put("received", part.length()))
        RandomAccessFile(part, "rw").use { raf ->
            raf.seek(offset)
            val buf = ByteArray(1 shl 16)
            var left = length
            val input = s.inputStream
            val key = "${final.parentFile?.name}/${final.name}"
            val title = s.parameters["folder"]?.firstOrNull()?.takeIf { it.isNotBlank() } ?: final.nameWithoutExtension
            val size = total ?: (offset + length)
            var lastTick = 0L
            while (left > 0) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) break
                raf.write(buf, 0, n)
                left -= n
                val now = System.currentTimeMillis()
                if (now - lastTick > 250) { lastTick = now; onIncoming(key, title, raf.filePointer, size, false) }
            }
            onIncoming(key, title, raf.filePointer, size, total != null && raf.filePointer >= total)
        }
        if (total != null && part.length() >= total) {
            var target = final
            var n = 2
            while (target.exists()) target = File(final.parentFile, "${final.nameWithoutExtension} ($n).${final.extension}").also { n++ }
            part.renameTo(target)
            onReceived(target)
            return json(Response.Status.OK, JSONObject().put("done", true).put("name", target.name))
        }
        return json(Response.Status.OK, JSONObject().put("received", part.length()))
    }

    // ----------------------------------------------------------------- utils

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun readBody(s: IHTTPSession): String {
        val len = s.headers["content-length"]?.toIntOrNull() ?: return ""
        val buf = ByteArray(len)
        var read = 0
        while (read < len) {
            val n = s.inputStream.read(buf, read, len - read)
            if (n < 0) break
            read += n
        }
        return String(buf, 0, read)
    }

    private fun json(status: Response.Status, body: JSONObject) = newFixedLengthResponse(status, "application/json", body.toString())
    private fun notFound() = json(Response.Status.NOT_FOUND, JSONObject().put("error", "not found"))

    private fun resource(id: Int, mime: String): Response {
        val bytes = context.resources.openRawResource(id).use { it.readBytes() }
        return newFixedLengthResponse(Response.Status.OK, mime, bytes.inputStream(), bytes.size.toLong()).also { it.addHeader("Cache-Control", "max-age=86400") }
    }

    private var portalCache: String? = null
    private fun portalHtml(): String = portalCache ?: context.assets.open("wormhole/portal.html").bufferedReader().readText()
        .replace("{{NAME}}", deviceName.replace("<", "&lt;")).also { portalCache = it }

    private fun mimeOf(f: File) = when (f.extension.lowercase()) {
        "epub" -> "application/epub+zip"
        "mobi", "azw", "azw3" -> "application/x-mobipocket-ebook"
        "pdf" -> "application/pdf"
        "mp3" -> "audio/mpeg"
        "m4a", "m4b", "aac" -> "audio/mp4"
        "ogg", "oga", "opus" -> "audio/ogg"
        "flac" -> "audio/flac"
        "jpg", "jpeg" -> "image/jpeg"
        else -> "application/octet-stream"
    }

    private fun FileInputStream.skipFully(n: Long) {
        var left = n
        while (left > 0) { val s = skip(left); if (s <= 0) break; left -= s }
    }

    private class Bounded(input: InputStream, private var left: Long) : FilterInputStream(input) {
        override fun read(): Int = if (left <= 0) -1 else super.read().also { if (it >= 0) left-- }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = super.read(b, off, minOf(len.toLong(), left).toInt())
            if (n > 0) left -= n
            return n
        }
    }
}

/** A reading/listening spot plus enough about the book to find it on a device where its id differs. */
data class Spot(val progress: ReadingProgress, val title: String?, val author: String?, val durationMs: Long, val tracks: Int) {
    companion object {
        fun json(p: ReadingProgress, b: Book?): JSONObject = Json.progress(p).apply {
            if (b != null) { put("title", b.title); put("author", b.author); put("durationMs", b.durationMs); put("tracks", b.tracks.size) }
        }
        fun parse(o: JSONObject): Map<String, Spot> = o.keys().asSequence().associateWith { k ->
            val j = o.getJSONObject(k)
            Spot(Json.progress(j), j.optString("title").ifBlank { null }, j.optString("author").ifBlank { null }, j.optLong("durationMs"), j.optInt("tracks"))
        }
    }
}
