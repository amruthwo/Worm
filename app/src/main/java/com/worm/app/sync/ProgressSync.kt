package com.worm.app.sync

import android.content.Context
import androidx.core.content.edit
import com.worm.app.data.Hashes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

/** A reading position someone else (KOReader, Readest, another Worm) saved for this book. */
data class RemotePosition(
    val source: String,          // "KOSync" | "Readest"
    val device: String?,
    val fraction: Float,
    val cfi: String?,             // foliate CFI (Readest)
    val xpointer: String?,        // crengine XPointer (KOReader / Readest)
    val updatedAt: Long,          // ms since epoch
)

class SyncException(message: String) : Exception(message)

private fun http(
    method: String,
    url: String,
    headers: Map<String, String> = emptyMap(),
    body: String? = null,
): Pair<Int, String> {
    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = method
        connectTimeout = 12_000; readTimeout = 15_000
        setRequestProperty("User-Agent", "Worm/0.2 (Android)")
        headers.forEach { (k, v) -> setRequestProperty(k, v) }
        if (body != null) { doOutput = true; outputStream.use { it.write(body.toByteArray()) } }
    }
    return try {
        val code = try { conn.responseCode } catch (e: java.net.SocketTimeoutException) {
            throw SyncException("${URL(url).host} isn't responding — the server may be down")
        } catch (e: java.net.UnknownHostException) {
            throw SyncException("Can't reach ${URL(url).host} — check the address or your connection")
        }
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText().orEmpty()
        code to text
    } finally { conn.disconnect() }
}

private fun errorText(body: String) = runCatching { JSONObject(body).let { it.optString("message").ifBlank { it.optString("error").ifBlank { it.optString("msg") } } } }
    .getOrNull()?.ifBlank { null }

// =============================================================================================
// KOSync — the KOReader progress sync protocol (sync.koreader.rocks or any self-hosted server).
// Documents are identified by KOReader's partial MD5, which is exactly Worm's ebook id.
// =============================================================================================

class KOSyncClient(private val server: String, private val user: String, private val key: String) {
    private fun h() = mapOf(
        "Accept" to "application/vnd.koreader.v1+json",
        "Content-Type" to "application/json",
        "x-auth-user" to user,
        "x-auth-key" to key,
    )
    private val base get() = server.trimEnd('/')

    fun authorize() {
        val (code, body) = http("GET", "$base/users/auth", h())
        if (code != 200) throw SyncException(if (code == 401) "Wrong username or password" else errorText(body) ?: "Server said $code")
    }

    fun pull(document: String): RemotePosition? {
        val (code, body) = http("GET", "$base/syncs/progress/$document", h())
        if (code == 401) throw SyncException("KOSync login expired")
        if (code !in 200..299) return null
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val progress = o.optString("progress").ifBlank { return null }
        return RemotePosition(
            source = "KOSync",
            device = o.optString("device").ifBlank { null },
            fraction = o.optDouble("percentage", 0.0).toFloat(),
            cfi = null,
            xpointer = progress.takeIf { it.startsWith("/") },
            updatedAt = o.optLong("timestamp") * 1000,
        )
    }

    fun push(document: String, xpointer: String, fraction: Float, device: String, deviceId: String) {
        val body = JSONObject()
            .put("document", document).put("progress", xpointer).put("percentage", fraction.toDouble())
            .put("device", device).put("device_id", deviceId).toString()
        val (code, text) = http("PUT", "$base/syncs/progress", h(), body)
        if (code !in 200..299) throw SyncException(errorText(text) ?: "KOSync push failed ($code)")
    }

    companion object {
        /** KOSync stores md5(password), never the password itself. */
        fun keyFor(password: String) = Hashes.md5(password)

        fun register(server: String, user: String, password: String) {
            val (code, body) = http(
                "POST", server.trimEnd('/') + "/users/create",
                mapOf("Accept" to "application/vnd.koreader.v1+json", "Content-Type" to "application/json"),
                JSONObject().put("username", user).put("password", keyFor(password)).toString(),
            )
            if (code !in 200..299) throw SyncException(if (code == 402) "That username is taken" else errorText(body) ?: "Couldn't register ($code)")
        }
    }
}

// =============================================================================================
// Readest cloud sync (unofficial client). Readest keeps per-book "configs" with a foliate CFI
// (`location`), a KOReader XPointer and [page, pages]; books are keyed by the same partial MD5.
// Sign-in is Readest's Supabase email/password auth.
// =============================================================================================

class ReadestClient(private val context: Context) {
    private val sp = context.getSharedPreferences("worm_readest", Context.MODE_PRIVATE)

    val email: String? get() = sp.getString("email", null)
    val signedIn: Boolean get() = sp.getString("refresh", null) != null

    fun signIn(email: String, password: String) {
        val (code, body) = http(
            "POST", "$AUTH/token?grant_type=password",
            mapOf("apikey" to ANON, "Content-Type" to "application/json"),
            JSONObject().put("email", email).put("password", password).toString(),
        )
        if (code !in 200..299) throw SyncException(errorText(body)?.let { if ("Invalid login" in it) "Wrong email or password" else it } ?: "Readest sign-in failed ($code)")
        store(JSONObject(body), email)
    }

    fun signOut() = sp.edit { clear() }

    private fun store(o: JSONObject, email: String? = this.email) = sp.edit {
        putString("access", o.getString("access_token"))
        putString("refresh", o.getString("refresh_token"))
        putLong("expires", System.currentTimeMillis() + o.optLong("expires_in", 3600) * 1000 - 60_000)
        putString("email", email)
    }

    private fun token(): String {
        val access = sp.getString("access", null)
        if (access != null && System.currentTimeMillis() < sp.getLong("expires", 0)) return access
        val refresh = sp.getString("refresh", null) ?: throw SyncException("Not signed in to Readest")
        val (code, body) = http(
            "POST", "$AUTH/token?grant_type=refresh_token",
            mapOf("apikey" to ANON, "Content-Type" to "application/json"),
            JSONObject().put("refresh_token", refresh).toString(),
        )
        if (code !in 200..299) { signOut(); throw SyncException("Readest sign-in expired — please sign in again") }
        store(JSONObject(body))
        return sp.getString("access", null)!!
    }

    fun pull(bookHash: String): RemotePosition? {
        val url = "$API/sync?since=0&type=configs&book=${URLEncoder.encode(bookHash, "UTF-8")}&meta_hash="
        val (code, body) = http("GET", url, mapOf("Authorization" to "Bearer ${token()}"))
        if (code !in 200..299) throw SyncException(errorText(body) ?: "Readest pull failed ($code)")
        val configs = JSONObject(body).optJSONArray("configs")
        android.util.Log.d("WormSync", "Readest pull $bookHash: http $code, ${configs?.length() ?: "no"} configs, keys=${JSONObject(body).keys().asSequence().toList()}" +
            (configs?.optJSONObject(0)?.let { " first=${it.keys().asSequence().toList()} hash=${it.optString("bookHash").ifBlank { it.optString("book_hash") }}" } ?: ""))
        if (configs == null) return null
        val c = (0 until configs.length()).map { configs.getJSONObject(it) }
            .filter { (it.optString("bookHash").ifBlank { it.optString("book_hash") }) == bookHash && it.isNull("deleted_at") && it.isNull("deletedAt") }
            .maxByOrNull { updatedAt(it) } ?: return null
        val progress = c.opt("progress").let { p ->
            when (p) {
                is JSONArray -> p
                is String -> runCatching { JSONArray(p) }.getOrNull()
                else -> null
            }
        }
        val fraction = progress?.let { if (it.length() == 2 && it.optDouble(1) > 0) (it.optDouble(0) / it.optDouble(1)).toFloat() else null } ?: 0f
        val location = c.optString("location").ifBlank { null }
        val xpointer = c.optString("xpointer").ifBlank { null }
        if (location == null && xpointer == null) return null
        return RemotePosition("Readest", null, fraction, location, xpointer, updatedAt(c))
    }

    /** A book in your Readest library (cloud), with its saved position if any. */
    data class CloudBook(
        val hash: String,
        val metaHash: String?,
        val title: String,
        val author: String,
        val format: String?,
        val updatedAt: Long,
        val uploaded: Boolean,
    )

    /** Everything in your Readest library (books table), newest first. */
    fun library(): List<CloudBook> {
        val (code, body) = http("GET", "$API/sync?since=0&type=books&book=&meta_hash=", mapOf("Authorization" to "Bearer ${token()}"))
        if (code !in 200..299) throw SyncException(errorText(body) ?: "Readest library failed ($code)")
        val arr = JSONObject(body).optJSONArray("books") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { it.isNull("deleted_at") && it.isNull("deletedAt") }
            .map { b ->
                CloudBook(
                    hash = b.optString("book_hash").ifBlank { b.optString("hash") },
                    metaHash = b.optString("meta_hash").ifBlank { b.optString("metaHash") }.ifBlank { null },
                    title = b.optString("title"),
                    author = b.optString("author"),
                    format = b.optString("format").ifBlank { null },
                    updatedAt = updatedAt(b),
                    uploaded = !b.isNull("uploaded_at") || !b.isNull("uploadedAt"),
                )
            }
            .sortedByDescending { it.updatedAt }
    }

    /** Files you've uploaded to Readest's cloud, grouped by book: (book file key, cover key). */
    data class CloudFiles(val book: String?, val cover: String?, val bookSize: Long)

    fun cloudFiles(): Map<String, CloudFiles> {
        val all = mutableListOf<JSONObject>()
        var page = 1
        while (page <= 40) {
            val (code, body) = http("GET", "$API/storage/list?page=$page&pageSize=100", mapOf("Authorization" to "Bearer ${token()}"))
            if (code !in 200..299) throw SyncException(errorText(body) ?: "Readest storage list failed ($code)")
            val o = JSONObject(body)
            val files = o.optJSONArray("files") ?: break
            for (i in 0 until files.length()) all += files.getJSONObject(i)
            if (page >= o.optInt("totalPages", 1)) break
            page++
        }
        val ebook = Regex("""(?i)\.(epub|mobi|azw3?|fb2|fbz|cbz|pdf|txt)$""")
        return all.filter { !it.isNull("book_hash") }.groupBy { it.getString("book_hash") }.mapValues { (_, files) ->
            val keys = files.map { it.getString("file_key") }
            CloudFiles(
                book = files.filter { ebook.containsMatchIn(it.getString("file_key")) }.maxByOrNull { it.optLong("file_size") }?.getString("file_key"),
                cover = keys.firstOrNull { Regex("""(?i)cover\.(png|jpe?g|webp)$""").containsMatchIn(it) },
                bookSize = files.filter { ebook.containsMatchIn(it.getString("file_key")) }.maxOfOrNull { it.optLong("file_size") } ?: 0,
            )
        }
    }

    /** Short-lived signed links for cloud files (Readest hands these out in batches). */
    fun downloadUrls(keys: List<String>): Map<String, String> = keys.chunked(50).flatMap { chunk ->
        val (code, body) = http(
            "POST", "$API/storage/download",
            mapOf("Authorization" to "Bearer ${token()}", "Content-Type" to "application/json"),
            JSONObject().put("fileKeys", JSONArray(chunk)).toString(),
        )
        if (code !in 200..299) throw SyncException(errorText(body) ?: "Readest download links failed ($code)")
        val map = JSONObject(body).optJSONObject("downloadUrls") ?: JSONObject()
        map.keys().asSequence().mapNotNull { k -> map.optString(k).takeIf { it.isNotBlank() }?.let { k to it } }.toList()
    }.toMap()

    /** Downloads one cloud book file into [dir]; returns the saved file. */
    fun downloadBook(key: String, dir: java.io.File, niceName: String): java.io.File {
        val url = downloadUrls(listOf(key))[key] ?: throw SyncException("Readest didn't give a download link")
        val ext = key.substringAfterLast('.', "epub")
        val safe = niceName.replace(Regex("""[\\/:*?"<>|]"""), "_").take(150)
        var out = java.io.File(dir, "$safe.$ext")
        var n = 2
        while (out.exists()) out = java.io.File(dir, "$safe ($n).$ext").also { n++ }
        dir.mkdirs()
        val tmp = java.io.File(dir, ".readest-${System.currentTimeMillis()}.part")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 15_000; readTimeout = 60_000 }
        try {
            if (conn.responseCode !in 200..299) throw SyncException("Download failed (${conn.responseCode})")
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
        } finally { conn.disconnect() }
        if (!tmp.renameTo(out)) { tmp.delete(); throw SyncException("Couldn't save the book") }
        return out
    }

    private fun updatedAt(o: JSONObject): Long {
        if (o.has("updatedAt") && o.opt("updatedAt") is Number) return o.getLong("updatedAt")
        val iso = o.optString("updated_at").ifBlank { o.optString("updatedAt") }
        return runCatching { java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli() }.getOrDefault(0L)
    }

    /**
     * Puts an ebook in your Readest cloud, like Readest's own "upload": the file (and cover) go
     * to your storage, then the book is added to your Readest library so every device sees it.
     * [hash] is the book's partial MD5, which is the id Readest uses too.
     */
    fun uploadBook(file: java.io.File, hash: String, title: String, author: String, format: String, cover: java.io.File?, onProgress: (Float) -> Unit = {}) {
        val ext = file.extension.lowercase()
        val safe = title.replace(Regex("""[<>:%#"/\\|?*\u0000-\u001F]"""), "_").trim().take(120).ifBlank { hash }
        cover?.takeIf { it.isFile }?.let { c ->
            // Readest keeps covers as <hash>/cover.png.
            val png = runCatching {
                val bmp = android.graphics.BitmapFactory.decodeFile(c.path) ?: return@runCatching null
                java.io.ByteArrayOutputStream().also { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            }.getOrNull()
            if (png != null) runCatching { putToCloud("Readest/Books/$hash/cover.png", hash, png.size.toLong()) { out -> out.write(png) } }
        }
        val total = file.length()
        putToCloud("Readest/Books/$hash/$safe.$ext", hash, total) { out ->
            file.inputStream().use { input ->
                val buf = ByteArray(1 shl 16); var done = 0L
                while (true) { val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n); done += n; onProgress(done.toFloat() / total) }
            }
        }
        val now = System.currentTimeMillis()
        val book = JSONObject()
            .put("hash", hash).put("format", format.uppercase()).put("title", title).put("sourceTitle", title)
            .put("author", author).put("createdAt", now).put("updatedAt", now).put("uploadedAt", now)
            .put("deletedAt", JSONObject.NULL)
        val (code, body) = http(
            "POST", "$API/sync",
            mapOf("Authorization" to "Bearer ${token()}", "Content-Type" to "application/json"),
            JSONObject().put("books", JSONArray(listOf(book))).toString(),
        )
        if (code !in 200..299) throw SyncException(errorText(body) ?: "Readest didn't take the book record ($code)")
    }

    /** Asks Readest for a short-lived upload link, then sends the bytes there. */
    private fun putToCloud(fileName: String, hash: String, size: Long, write: (java.io.OutputStream) -> Unit) {
        val (code, body) = http(
            "POST", "$API/storage/upload",
            mapOf("Authorization" to "Bearer ${token()}", "Content-Type" to "application/json"),
            JSONObject().put("fileName", fileName).put("fileSize", size).put("bookHash", hash).toString(),
        )
        if (code == 403 && body.contains("quota", true)) throw SyncException("Your Readest cloud is full")
        if (code !in 200..299) throw SyncException(errorText(body) ?: "Readest upload failed ($code)")
        val url = JSONObject(body).optString("uploadUrl").ifBlank { throw SyncException("Readest didn't give an upload link") }
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"; doOutput = true; connectTimeout = 15_000; readTimeout = 120_000
            setFixedLengthStreamingMode(size)
        }
        try {
            conn.outputStream.use(write)
            if (conn.responseCode !in 200..299) throw SyncException("Upload failed (${conn.responseCode})")
        } finally { conn.disconnect() }
    }

    /** Sends many positions in one request (Readest takes a list of configs). */
    fun pushMany(items: List<JSONObject>) {
        items.chunked(50).forEach { chunk ->
            val (code, body) = http(
                "POST", "$API/sync",
                mapOf("Authorization" to "Bearer ${token()}", "Content-Type" to "application/json"),
                JSONObject().put("configs", JSONArray(chunk)).toString(),
            )
            if (code !in 200..299) throw SyncException(errorText(body) ?: "Readest push failed ($code)")
        }
    }

    fun push(bookHash: String, cfi: String, xpointer: String?, page: Int, pages: Int) {
        val config = JSONObject()
            .put("bookHash", bookHash)
            .put("location", cfi)
            .put("updatedAt", System.currentTimeMillis())
        xpointer?.let { config.put("xpointer", it) }
        if (pages > 0) config.put("progress", JSONArray(listOf(page + 1, pages)))
        val (code, body) = http(
            "POST", "$API/sync",
            mapOf("Authorization" to "Bearer ${token()}", "Content-Type" to "application/json"),
            JSONObject().put("configs", JSONArray(listOf(config))).toString(),
        )
        if (code !in 200..299) throw SyncException(errorText(body) ?: "Readest push failed ($code)")
    }

    companion object {
        // Readest's public client endpoints (the same ones its own apps use).
        private const val AUTH = "https://vbsyxfusjjqdxkjqlysc.supabase.co/auth/v1"
        private const val API = "https://web.readest.com/api"
        private val ANON = String(
            android.util.Base64.decode(
                "ZXlKaGJHY2lPaUpJVXpJMU5pSXNJblI1Y0NJNklrcFhWQ0o5LmV5SnBjM01pT2lKemRYQmhZbUZ6WlNJc0luSmxaaUk2SW5aaWMzbDRablZ6YW1weFpIaHJhbkZzZVhOaklpd2ljbTlzWlNJNkltRnViMjRpTENKcFlYUWlPakUzTXpReE1qTTJOekVzSW1WNGNDSTZNakEwT1RZNU9UWTNNWDAuM1U1VXFhb3VfMVNnclZlMWVvOXJBcGMwdUtqcWhwUWRVWGh2d1VIbVVmZw==",
                android.util.Base64.DEFAULT,
            ),
        )
    }
}

// =============================================================================================
// The glue: which services are on, pull the freshest remote spot, push ours.
// =============================================================================================

object ProgressSync {
    private fun prefs(c: Context) = c.getSharedPreferences("worm_prefs", Context.MODE_PRIVATE)

    fun deviceId(c: Context): String = prefs(c).getString("device_id", null)
        ?: UUID.randomUUID().toString().replace("-", "").uppercase().also { id -> prefs(c).edit { putString("device_id", id) } }

    fun kosync(c: Context): KOSyncClient? {
        val p = com.worm.app.data.Prefs(c)
        val user = p.kosyncUser ?: return null
        val key = p.kosyncKey ?: return null
        return KOSyncClient(p.kosyncServer, user, key)
    }

    fun readest(c: Context): ReadestClient? = ReadestClient(c).takeIf { it.signedIn }

    fun anyEnabled(c: Context) = kosync(c) != null || readest(c) != null

    /** Newest position from every connected service (errors are skipped, not fatal). */
    // ---- Links: a Worm book ↔ a Readest entry for a different file of the same book.
    private fun linksPrefs(c: Context) = c.getSharedPreferences("worm_readest_links", Context.MODE_PRIVATE)
    fun readestHashFor(c: Context, bookId: String): String = linksPrefs(c).getString(bookId, null) ?: bookId
    fun link(c: Context, bookId: String, readestHash: String?) = linksPrefs(c).edit { if (readestHash == null || readestHash == bookId) remove(bookId) else putString(bookId, readestHash) }
    fun links(c: Context): Map<String, String> = linksPrefs(c).all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()

    suspend fun pull(c: Context, bookHash: String): List<RemotePosition> = withContext(Dispatchers.IO) {
        coroutineScope {
            listOfNotNull(
                kosync(c)?.let { k -> async { runCatching { k.pull(bookHash) }.onFailure { log(it) }.getOrNull() } },
                readest(c)?.let { r -> async { runCatching { r.pull(readestHashFor(c, bookHash)) }.onFailure { log(it) }.getOrNull() } },
            ).awaitAll().filterNotNull()
        }
    }

    suspend fun push(c: Context, bookHash: String, cfi: String, xpointer: String?, fraction: Float, page: Int, pages: Int) =
        withContext(Dispatchers.IO) {
            val p = com.worm.app.data.Prefs(c)
            if (xpointer != null) kosync(c)?.let { k -> runCatching { k.push(bookHash, xpointer, fraction, p.wormholeName, deviceId(c)) }.onFailure { log(it) } }
            readest(c)?.let { r -> runCatching { r.push(readestHashFor(c, bookHash), cfi, xpointer, page, pages) }.onFailure { log(it) } }
        }

    /** Sends every book's saved spot. Returns how many were sent; throws the first hard error. */
    suspend fun pushAll(c: Context, onProgress: (Int, Int) -> Unit): Int = withContext(Dispatchers.IO) {
        val lib = com.worm.app.data.Library.get(c)
        val p = com.worm.app.data.Prefs(c)
        val books = lib.books.value.filter { !it.hidden && !it.isAudio }
        val prog = lib.progress.value
        val withSpot = books.mapNotNull { b -> prog[b.id]?.takeIf { it.locator != null || it.xpointer != null }?.let { b to it } }
        readest(c)?.let { r ->
            r.pushMany(withSpot.filter { it.second.locator != null }.map { (b, pr) ->
                JSONObject().put("bookHash", readestHashFor(c, b.id)).put("location", pr.locator)
                    .put("updatedAt", pr.movedAt.takeIf { it > 0 } ?: pr.updatedAt)
                    .apply { pr.xpointer?.let { put("xpointer", it) } }
            })
        }
        kosync(c)?.let { k ->
            withSpot.filter { it.second.xpointer != null }.forEachIndexed { i, (b, pr) ->
                onProgress(i, withSpot.size)
                k.push(b.id, pr.xpointer!!, pr.fraction, p.wormholeName, deviceId(c))
            }
        }
        withSpot.size
    }

    /** Sends one book's saved spot. False if there's nothing saved here yet. */
    suspend fun pushOne(c: Context, bookId: String): Boolean = withContext(Dispatchers.IO) {
        val pr = com.worm.app.data.Library.get(c).progress.value[bookId] ?: return@withContext false
        val cfi = pr.locator
        if (cfi == null && pr.xpointer == null) return@withContext false
        val p = com.worm.app.data.Prefs(c)
        pr.xpointer?.let { xp -> kosync(c)?.let { k -> runCatching { k.push(bookId, xp, pr.fraction, p.wormholeName, deviceId(c)) }.onFailure { log(it) } } }
        if (cfi != null) readest(c)?.let { r -> r.push(readestHashFor(c, bookId), cfi, pr.xpointer, 0, 0) }
        true
    }

    /** Takes the newest spot saved elsewhere if it's newer than your last page turn here. True if it moved. */
    suspend fun pullOne(c: Context, bookId: String): Boolean = withContext(Dispatchers.IO) {
        val lib = com.worm.app.data.Library.get(c)
        val local = lib.progress.value[bookId]
        val best = pull(c, bookId).maxByOrNull { it.updatedAt } ?: return@withContext false
        if (best.updatedAt <= (local?.movedAt ?: 0L) + 5_000 || (best.cfi == local?.locator && best.xpointer == local?.xpointer)) return@withContext false
        lib.saveProgress(bookId) {
            it.copy(locator = best.cfi, xpointer = best.xpointer, fraction = best.fraction.takeIf { f -> f > 0f } ?: it.fraction, movedAt = best.updatedAt)
        }
        lib.flushProgress()
        true
    }

    /** Checks every book; where another app saved a newer spot, Worm will open there next time. Returns how many moved. */
    suspend fun pullAll(c: Context, onProgress: (Int, Int) -> Unit): Int = withContext(Dispatchers.IO) {
        val lib = com.worm.app.data.Library.get(c)
        val books = lib.books.value.filter { !it.hidden && !it.isAudio }
        var moved = 0
        books.forEachIndexed { i, b ->
            onProgress(i, books.size)
            val local = lib.progress.value[b.id]
            val best = pull(c, b.id).maxByOrNull { it.updatedAt } ?: return@forEachIndexed
            if (best.updatedAt > (local?.movedAt ?: 0L) + 5_000 && (best.cfi != local?.locator || best.xpointer != local?.xpointer)) {
                lib.saveProgress(b.id) {
                    it.copy(locator = best.cfi, xpointer = best.xpointer, fraction = best.fraction.takeIf { f -> f > 0f } ?: it.fraction, movedAt = best.updatedAt)
                }
                moved++
            }
        }
        lib.flushProgress()
        moved
    }

    private fun log(t: Throwable) = android.util.Log.w("WormSync", t.message ?: t.toString())
}
