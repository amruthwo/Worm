package com.worm.app.wormhole

import com.worm.app.data.Json
import com.worm.app.data.ReadingProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.coroutineContext

data class RemoteFile(val name: String, val size: Long)

data class RemoteBook(
    val id: String,
    val title: String,
    val authors: List<String>,
    val narrators: List<String>,
    val series: String?,
    val seriesIndex: Float?,
    val audio: Boolean,
    val format: String,
    val size: Long,
    val durationMs: Long,
    val hidden: Boolean,
    val hasCover: Boolean,
    /** For multi-file audiobooks: the folder name to recreate on this side. */
    val folder: String?,
    val files: List<RemoteFile>,
) {
    val author: String get() = authors.joinToString(", ").ifEmpty { "Unknown author" }
}

/** Talks to another Worm's [WormholeServer]. */
class WormholeClient(val base: String, val token: String) {

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun open(path: String, method: String = "GET", timeout: Int = 15_000): HttpURLConnection =
        (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = timeout
            readTimeout = 30_000
            setRequestProperty("X-Worm-Token", token)
            setRequestProperty("X-Worm-Client", "app")
        }

    private fun getJson(path: String): JSONObject {
        val c = open(path)
        try {
            check(c.responseCode == 200) { "HTTP ${c.responseCode}" }
            return JSONObject(c.inputStream.bufferedReader().readText())
        } finally { c.disconnect() }
    }

    fun coverUrl(id: String) = "$base/cover/$id?t=$token"

    suspend fun books(): Pair<String, List<RemoteBook>> = withContext(Dispatchers.IO) {
        val j = getJson("/api/books")
        val arr = j.getJSONArray("books")
        j.optString("name", "Worm") to List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            val files = o.getJSONArray("files").let { a -> List(a.length()) { a.getJSONObject(it).let { f -> RemoteFile(f.getString("name"), f.getLong("size")) } } }
            fun strings(key: String) = o.optJSONArray(key)?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty()
            RemoteBook(
                o.getString("id"), o.getString("title"), strings("authors"), strings("narrators"),
                o.optString("series").takeIf { !o.isNull("series") && it.isNotBlank() },
                if (o.isNull("seriesIndex")) null else o.optDouble("seriesIndex").toFloat(),
                o.optBoolean("audio"), o.optString("format"), o.optLong("size"), o.optLong("duration"),
                o.optBoolean("hidden"), o.optBoolean("cover"),
                o.optString("folder").takeIf { !o.isNull("folder") && it.isNotBlank() }, files,
            )
        }
    }

    suspend fun progress(): Map<String, Spot> = withContext(Dispatchers.IO) { Spot.parse(getJson("/api/progress")) }

    suspend fun sendProgress(map: Map<String, ReadingProgress>, bookOf: (String) -> com.worm.app.data.Book?) = withContext(Dispatchers.IO) {
        val c = open("/api/progress", "POST")
        try {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            val body = JSONObject().apply { map.forEach { (k, v) -> put(k, Spot.json(v, bookOf(k))) } }
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            check(c.responseCode == 200) { "HTTP ${c.responseCode}" }
        } finally { c.disconnect() }
    }

    /** Tells the host how to reach us back so it can browse our books too. */
    suspend fun introduce(myName: String, myUrls: List<String>, myToken: String) = withContext(Dispatchers.IO) {
        val c = open("/api/peer", "POST")
        try {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use {
                it.write(JSONObject().put("name", myName).put("urls", org.json.JSONArray(myUrls)).put("token", myToken).toString().toByteArray())
            }
            c.responseCode
        } finally { c.disconnect() }
    }

    /** Pulls one file of a book into [destDir], resuming a partial download. False if we already have it. */
    suspend fun download(bookId: String, index: Int, file: RemoteFile, destDir: File, onBytes: (Long) -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            destDir.mkdirs()
            val final = File(destDir, file.name)
            if (final.exists() && final.length() == file.size) return@withContext false
            val part = File(destDir, ".part-${file.name}")
            val from = part.length().coerceAtMost(file.size)
            val c = open("/f/$bookId/$index")
            try {
                if (from > 0) c.setRequestProperty("Range", "bytes=$from-")
                val code = c.responseCode
                check(code == 200 || code == 206) { "HTTP $code" }
                val append = code == 206
                RandomAccessFile(part, "rw").use { raf ->
                    if (append) raf.seek(from) else raf.setLength(0)
                    var done = if (append) from else 0L
                    onBytes(done)
                    val buf = ByteArray(1 shl 16)
                    c.inputStream.use { input ->
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            raf.write(buf, 0, n)
                            done += n
                            onBytes(done)
                        }
                    }
                }
            } finally { c.disconnect() }
            check(part.length() == file.size) { "Transfer interrupted" }
            var target = final
            var n = 2
            while (target.exists()) target = File(destDir, "${final.nameWithoutExtension} ($n).${final.extension}").also { n++ }
            part.renameTo(target)
            true
        }

    /**
     * Asks the other side to accept a batch. [items] = (title, folder, files). Returns the offer id;
     * then call [awaitDecision].
     */
    suspend fun offer(myName: String, items: List<Triple<String, String?, List<File>>>): String = withContext(Dispatchers.IO) {
        val body = JSONObject().put("from", myName).put("items", org.json.JSONArray(items.map { (title, folder, files) ->
            JSONObject().put("title", title).put("folder", folder ?: "")
                .put("files", org.json.JSONArray(files.map { JSONObject().put("name", it.name).put("size", it.length()) }))
        }))
        val c = open("/api/offer", "POST")
        try {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            check(c.responseCode == 200) { "HTTP ${c.responseCode}" }
            JSONObject(c.inputStream.bufferedReader().readText()).getString("id")
        } finally { c.disconnect() }
    }

    /** Waits (up to [timeoutMs]) for the other person to accept or decline. True = accepted. */
    suspend fun awaitDecision(offerId: String, timeoutMs: Long = 180_000): Boolean = withContext(Dispatchers.IO) {
        val until = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < until) {
            coroutineContext.ensureActive()
            when (getJson("/api/offer/$offerId").optString("status")) {
                "accepted" -> return@withContext true
                "declined", "unknown" -> return@withContext false
            }
            kotlinx.coroutines.delay(1000)
        }
        false
    }

    /** Pushes one file of an accepted offer (into [folder] when it's part of a multi-file audiobook). */
    suspend fun upload(file: File, folder: String?, offerId: String, onBytes: (Long) -> Unit): Boolean = withContext(Dispatchers.IO) {
        val q = "offer=${enc(offerId)}&folder=${enc(folder.orEmpty())}&name=${enc(file.name)}&size=${file.length()}"
        val received = getJson("/api/upload/status?$q").optLong("received", 0)
        if (received < 0) return@withContext false
        val offset = received.coerceAtMost(file.length())
        val c = open("/api/upload?$q&offset=$offset", "PUT")
        try {
            c.doOutput = true
            c.setFixedLengthStreamingMode(file.length() - offset)
            c.setRequestProperty("Content-Type", "application/octet-stream")
            var done = offset
            onBytes(done)
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(offset)
                val buf = ByteArray(1 shl 16)
                c.outputStream.use { out ->
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = raf.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        onBytes(done)
                    }
                }
            }
            check(c.responseCode == 200) { "HTTP ${c.responseCode}" }
        } finally { c.disconnect() }
        true
    }

    companion object {
        /** Finds which of the host's addresses answers (first one wins). */
        suspend fun reach(hosts: List<String>, port: Int): String? = withContext(Dispatchers.IO) {
            hosts.firstNotNullOfOrNull { h ->
                runCatching {
                    val c = URL("http://$h:$port/api/hello").openConnection() as HttpURLConnection
                    c.connectTimeout = 2500; c.readTimeout = 2500
                    try { if (c.responseCode == 200 && "worm" in c.inputStream.bufferedReader().readText()) "http://$h:$port" else null }
                    finally { c.disconnect() }
                }.getOrNull()
            }
        }

        /** Trades the 6-digit code shown on the other device for its access token. */
        suspend fun login(base: String, code: String): String? = withContext(Dispatchers.IO) {
            runCatching {
                val c = URL("$base/api/login").openConnection() as HttpURLConnection
                c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 5000
                c.setRequestProperty("X-Worm-Client", "app")
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(JSONObject().put("code", code).toString().toByteArray()) }
                try { if (c.responseCode == 200) JSONObject(c.inputStream.bufferedReader().readText()).getString("token") else null }
                finally { c.disconnect() }
            }.getOrNull()
        }
    }
}
