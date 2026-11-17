package com.worm.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Small JSON (de)serialisers for everything Worm keeps in its private storage. */
object Json {
    private fun JSONArray.strings(): List<String> = List(length()) { getString(it) }
    private fun List<String>.arr() = JSONArray(this)
    private fun JSONObject.str(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null

    fun book(b: Book) = JSONObject().apply {
        put("id", b.id); put("path", b.path); put("kind", b.kind.name); put("format", b.format)
        put("title", b.title); put("authors", b.authors.arr()); put("narrators", b.narrators.arr())
        put("series", b.series); b.seriesIndex?.let { put("seriesIndex", it.toDouble()) }
        put("description", b.description); put("language", b.language)
        put("size", b.sizeBytes); put("duration", b.durationMs); put("added", b.addedAt)
        put("cover", b.coverPath); put("hidden", b.hidden)
        put("tracks", JSONArray(b.tracks.map { JSONObject().put("p", it.path).put("t", it.title).put("d", it.durationMs) }))
    }

    fun book(j: JSONObject) = Book(
        id = j.getString("id"), path = j.getString("path"),
        kind = BookKind.valueOf(j.getString("kind")), format = j.getString("format"),
        title = j.getString("title"),
        authors = j.optJSONArray("authors")?.strings().orEmpty(),
        narrators = j.optJSONArray("narrators")?.strings().orEmpty(),
        series = j.str("series"),
        seriesIndex = if (j.has("seriesIndex")) j.getDouble("seriesIndex").toFloat() else null,
        description = j.str("description"), language = j.str("language"),
        sizeBytes = j.optLong("size"), durationMs = j.optLong("duration"), addedAt = j.optLong("added"),
        coverPath = j.str("cover"), hidden = j.optBoolean("hidden"),
        tracks = j.optJSONArray("tracks")?.let { a ->
            List(a.length()) { a.getJSONObject(it).let { t -> Track(t.getString("p"), t.getString("t"), t.optLong("d")) } }
        }.orEmpty(),
    )

    fun progress(p: ReadingProgress) = JSONObject().apply {
        put("f", p.fraction.takeIf { it.isFinite() }?.toDouble() ?: 0.0); put("loc", p.locator); put("ti", p.trackIndex); put("pos", p.positionMs)
        put("at", p.updatedAt); put("done", p.finished); put("spent", p.timeSpentSec); put("dis", p.dismissedAt); put("mv", p.movedAt); put("xp", p.xpointer)
    }

    fun progress(j: JSONObject) = ReadingProgress(
        fraction = j.optDouble("f", 0.0).toFloat().takeIf { it.isFinite() } ?: 0f, locator = j.str("loc"), trackIndex = j.optInt("ti"),
        positionMs = j.optLong("pos"), updatedAt = j.optLong("at"), finished = j.optBoolean("done"),
        timeSpentSec = j.optLong("spent"), dismissedAt = j.optLong("dis"), movedAt = j.optLong("mv"), xpointer = j.str("xp"),
    )

    fun override(o: Override) = JSONObject().apply {
        put("title", o.title); o.authors?.let { put("authors", it.arr()) }; o.narrators?.let { put("narrators", it.arr()) }
        put("series", o.series); o.seriesIndex?.let { put("seriesIndex", it.toDouble()) }
        put("description", o.description); put("cover", o.coverPath); put("asin", o.asin)
        o.chapters?.let { ch -> put("chapters", JSONArray(ch.map { JSONObject().put("t", it.title).put("s", it.startMs).put("e", it.endMs).put("i", it.trackIndex) })) }
    }

    fun override(j: JSONObject) = Override(
        title = j.str("title"), authors = j.optJSONArray("authors")?.strings(), narrators = j.optJSONArray("narrators")?.strings(),
        series = j.str("series"), seriesIndex = if (j.has("seriesIndex")) j.getDouble("seriesIndex").toFloat() else null,
        description = j.str("description"), coverPath = j.str("cover"), asin = j.str("asin"),
        chapters = j.optJSONArray("chapters")?.let { a ->
            List(a.length()) { a.getJSONObject(it).let { c -> Chapter(c.getString("t"), c.getLong("s"), c.getLong("e"), c.optInt("i")) } }
        },
    )

    fun shelf(s: Shelf) = JSONObject().put("id", s.id).put("name", s.name).put("books", s.bookIds.arr())
        .put("emoji", s.emoji).put("hidden", s.hidden)

    fun shelf(j: JSONObject) = Shelf(
        j.getString("id"), j.getString("name"), j.getJSONArray("books").strings(), j.str("emoji"), j.optBoolean("hidden"),
    )

    fun source(s: Source) = JSONObject().put("path", s.path).put("noMedia", s.noMedia)
    fun source(j: JSONObject) = Source(j.getString("path"), j.optBoolean("noMedia"))

    fun bookmark(b: Bookmark) = JSONObject().put("id", b.id).put("book", b.bookId).put("loc", b.locator)
        .put("label", b.label).put("at", b.createdAt).put("pos", b.positionMs).put("ti", b.trackIndex)

    fun bookmark(j: JSONObject) = Bookmark(
        j.getString("id"), j.getString("book"), j.getString("loc"), j.getString("label"), j.getLong("at"),
        j.optLong("pos"), j.optInt("ti"),
    )

    fun highlight(h: Highlight) = JSONObject().put("id", h.id).put("book", h.bookId).put("cfi", h.cfi)
        .put("text", h.text).put("color", h.color).put("note", h.note).put("at", h.createdAt)

    fun highlight(j: JSONObject) = Highlight(
        j.getString("id"), j.getString("book"), j.getString("cfi"), j.getString("text"), j.getString("color"),
        j.str("note"), j.getLong("at"),
    )
}

/** Atomic-ish JSON file: writes a temp file then renames it over the old one. */
class JsonFile(private val file: File) {
    fun read(): String? = runCatching { file.readText() }.getOrNull()
    fun write(text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    fun <T> readList(parse: (JSONObject) -> T): List<T> = read()?.let { txt ->
        runCatching { JSONArray(txt).let { a -> List(a.length()) { parse(a.getJSONObject(it)) } } }.getOrNull()
    }.orEmpty()

    fun <T> writeList(items: List<T>, ser: (T) -> JSONObject) = write(JSONArray(items.map(ser)).toString())

    fun <T> readMap(parse: (JSONObject) -> T): Map<String, T> = read()?.let { txt ->
        runCatching { JSONObject(txt).let { o -> o.keys().asSequence().associateWith { parse(o.getJSONObject(it)) } } }.getOrNull()
    }.orEmpty()

    fun <T> writeMap(items: Map<String, T>, ser: (T) -> JSONObject) =
        write(JSONObject().apply { items.forEach { (k, v) -> put(k, ser(v)) } }.toString())
}
