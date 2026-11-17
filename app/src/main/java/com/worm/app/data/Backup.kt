package com.worm.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Saves the work you put into your library (fixed titles, series, covers, shelves, reading and
 * listening spots, bookmarks, highlights) to one .zip, and brings it back on this device or another.
 *
 * Books themselves aren't included, and neither is the Burrow or any password.
 * Restoring merges: the backup's edits win, positions keep whichever is newer, and nothing
 * already here is deleted.
 */
object Backup {
    private const val FORMAT = 1
    private val maps = listOf("overrides.json", "progress.json", "activity.json")
    private val lists = listOf("shelves.json", "bookmarks.json", "highlights.json")
    private const val COVERS = "covers_custom"

    data class Summary(val edits: Int, val shelves: Int, val spots: Int, val covers: Int)

    fun export(context: Context, out: OutputStream): Summary {
        val library = Library.get(context)
        library.flushProgress()
        val dir = context.filesDir
        var covers = 0
        ZipOutputStream(out.buffered()).use { zip ->
            fun put(name: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
            put("worm-backup.json", JSONObject().put("format", FORMAT).put("createdAt", System.currentTimeMillis()).toString(2).toByteArray())
            (maps + lists).forEach { n -> File(dir, n).takeIf { it.isFile }?.let { put(n, it.readBytes()) } }
            File(dir, COVERS).listFiles()?.filter { it.isFile }?.forEach { put("$COVERS/${it.name}", it.readBytes()); covers++ }
        }
        return Summary(library.overrides.value.size, library.shelves.value.size, library.progress.value.size, covers)
    }

    /** Merges a backup into this device's files. Worm needs a restart afterwards to load them. */
    fun restore(context: Context, input: InputStream): Summary {
        val dir = context.filesDir
        val incoming = mutableMapOf<String, ByteArray>()
        val coverDir = File(dir, COVERS).also { it.mkdirs() }
        var covers = 0
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val name = e.name
                when {
                    e.isDirectory -> Unit
                    name.startsWith("$COVERS/") -> {
                        // Flat file names only: nothing in a backup can write outside the covers folder.
                        val base = name.substringAfter('/').takeIf { it.isNotEmpty() && '/' !in it && !it.startsWith(".") }
                        if (base != null) { File(coverDir, base).outputStream().use { zip.copyTo(it) }; covers++ }
                    }
                    name == "worm-backup.json" || name in maps || name in lists -> incoming[name] = zip.readBytes()
                }
            }
        }
        val manifest = incoming["worm-backup.json"]?.let { runCatching { JSONObject(String(it)) }.getOrNull() }
        require(manifest != null && manifest.optInt("format") in 1..FORMAT) { "That isn't a Worm library backup" }

        Library.get(context).flushProgress()
        var edits = 0; var shelves = 0; var spots = 0
        // Covers were saved under whatever path the other install used; point them here.
        val coverPrefix = coverDir.absolutePath + "/"
        fun fixCovers(o: JSONObject) {
            o.optString("coverPath").takeIf { "/$COVERS/" in it }?.let { o.put("coverPath", coverPrefix + it.substringAfterLast('/')) }
        }
        maps.forEach { n ->
            val theirs = incoming[n]?.let { runCatching { JSONObject(String(it)) }.getOrNull() } ?: return@forEach
            val file = File(dir, n)
            val ours = runCatching { JSONObject(file.readText()) }.getOrNull() ?: JSONObject()
            theirs.keys().forEach { k ->
                val t = theirs.getJSONObject(k)
                if (n == "activity.json") {
                    // Same day on both: keep the larger of each number (they're the same history).
                    val mine = ours.optJSONObject(k) ?: JSONObject()
                    ours.put(k, JSONObject().put("r", maxOf(mine.optLong("r"), t.optLong("r"))).put("l", maxOf(mine.optLong("l"), t.optLong("l"))).put("f", maxOf(mine.optInt("f"), t.optInt("f"))))
                } else if (n == "progress.json") {
                    val mine = ours.optJSONObject(k)
                    if (mine == null || t.optLong("updatedAt") > mine.optLong("updatedAt")) { ours.put(k, t); spots++ }
                } else { fixCovers(t); ours.put(k, t); edits++ }
            }
            JsonFile(file).write(ours.toString())
        }
        lists.forEach { n ->
            val theirs = incoming[n]?.let { runCatching { JSONArray(String(it)) }.getOrNull() } ?: return@forEach
            val file = File(dir, n)
            val ours = runCatching { JSONArray(file.readText()) }.getOrNull() ?: JSONArray()
            val byId = LinkedHashMap<String, JSONObject>()
            for (i in 0 until ours.length()) ours.optJSONObject(i)?.let { byId[it.optString("id")] = it }
            for (i in 0 until theirs.length()) theirs.optJSONObject(i)?.let { byId[it.optString("id")] = it; if (n == "shelves.json") shelves++ }
            JsonFile(file).write(JSONArray(byId.values.toList()).toString())
        }
        return Summary(edits, shelves, spots, covers)
    }
}
