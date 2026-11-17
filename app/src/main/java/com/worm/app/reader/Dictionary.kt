package com.worm.app.reader

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

data class Definition(val partOfSpeech: String, val senses: List<String>)

/** One source's answer: an offline dictionary, Wiktionary, or Wikipedia. */
data class Lookup(
    val source: String,
    /** The word actually found (e.g. "walk" when you selected "walked"). */
    val headword: String,
    val definitions: List<Definition> = emptyList(),
    /** Wikipedia: a short summary instead of definitions. */
    val summary: String? = null,
    val url: String? = null,
)

/**
 * Word lookups.
 *
 * - Offline first: StarDict dictionaries you've added (the same files KOReader uses).
 * - Then Wiktionary, trying the word as written, lowercased, and its base forms
 *   ("walked" → "walk", "Winston's" → "Winston"), and following "past tense of …" entries.
 * - Wikipedia for names, places and ideas.
 *
 * Answers are cached, so looking a word up again is instant.
 */
object Dictionary {
    private val cache = object : LinkedHashMap<String, Result<List<Lookup>>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Result<List<Lookup>>>) = size > 200
    }

    /** Selected text → a clean word or phrase to look up. */
    fun clean(raw: String): String = raw.trim()
        .replace(Regex("\\s+"), " ")
        .trim('.', ',', ';', ':', '!', '?', '"', '“', '”', '‘', '’', '\'', '(', ')', '[', ']', '—', '–', '-', '…', '*', '_')
        .replace(Regex("(?i)['’]s$"), "")

    /** Forms worth trying when the exact word isn't listed. */
    fun candidates(word: String): List<String> {
        val w = clean(word)
        val l = w.lowercase()
        val out = linkedSetOf(w, l)
        if (' ' !in l && l.length > 3) {
            fun add(s: String) { if (s.length >= 2) out += s }
            when {
                l.endsWith("ies") -> add(l.dropLast(3) + "y")
                l.endsWith("ves") -> { add(l.dropLast(3) + "f"); add(l.dropLast(3) + "fe") }
                l.endsWith("es") -> { add(l.dropLast(2)); add(l.dropLast(1)) }
                l.endsWith("s") && !l.endsWith("ss") -> add(l.dropLast(1))
            }
            if (l.endsWith("ied")) add(l.dropLast(3) + "y")
            if (l.endsWith("ed")) { add(l.dropLast(2)); add(l.dropLast(1)); if (l.length > 5 && l[l.length - 3] == l[l.length - 4]) add(l.dropLast(3)) }
            if (l.endsWith("ing")) { add(l.dropLast(3)); add(l.dropLast(3) + "e"); if (l.length > 6 && l[l.length - 4] == l[l.length - 5]) add(l.dropLast(4)) }
            if (l.endsWith("ly")) { add(l.dropLast(2)); if (l.endsWith("ily")) add(l.dropLast(3) + "y") }
            if (l.endsWith("er")) { add(l.dropLast(2)); add(l.dropLast(1)) }
            if (l.endsWith("est")) { add(l.dropLast(3)); add(l.dropLast(2)) }
        }
        return out.toList()
    }

    /** Offline dictionaries, then Wiktionary. Wikipedia is fetched separately ([wikipedia]). */
    suspend fun define(context: Context, word: String, lang: String = "en"): Result<List<Lookup>> {
        val key = "$lang|${clean(word)}"
        synchronized(cache) { cache[key] }?.let { return it }
        val r = withContext(Dispatchers.IO) {
            runCatching {
                val results = mutableListOf<Lookup>()
                StarDict.installed(context).forEach { d -> d.lookup(candidates(word))?.let(results::add) }
                runCatching { wiktionary(word, lang) }.onSuccess { it?.let(results::add) }.onFailure { if (results.isEmpty()) throw it }
                results.toList()
            }
        }
        if (r.isSuccess) synchronized(cache) { cache[key] = r }
        return r
    }

    private fun wiktionary(word: String, lang: String): Lookup? {
        for (c in candidates(word)) {
            val defs = wiktionaryExact(c, lang) ?: continue
            // "past tense of walk": also show walk itself.
            val base = defs.flatMap { it.senses }.firstNotNullOfOrNull { s ->
                Regex("""(?i)\b(?:plural|(?:simple )?past(?: tense)?|past participle|present participle|third-person singular[\w ]*|comparative|superlative|alternative (?:form|spelling)|(?:archaic|obsolete) (?:form|spelling)|inflection|gerund)(?: form)? of\s+([\p{L}'-]+)\.?$""").find(s)?.groupValues?.get(1)
            }
            val more = base?.takeIf { !it.equals(c, true) }?.let { wiktionaryExact(it, lang) }.orEmpty()
            return Lookup("Wiktionary", if (more.isNotEmpty()) "$c → $base" else c, (defs + more).take(6), url = "https://$lang.wiktionary.org/wiki/${enc(c)}")
        }
        return null
    }

    private fun wiktionaryExact(word: String, lang: String): List<Definition>? {
        val (code, body) = get("https://$lang.wiktionary.org/api/rest_v1/page/definition/${enc(word)}")
        if (code == 404) return null
        check(code in 200..299) { "Wiktionary answered $code" }
        val json = JSONObject(body)
        val entries = json.optJSONArray(lang) ?: return null
        return List(entries.length()) { i ->
            val e = entries.getJSONObject(i)
            val defs = e.optJSONArray("definitions")
            Definition(
                e.optString("partOfSpeech"),
                List(defs?.length() ?: 0) { j -> strip(defs!!.getJSONObject(j).optString("definition")) }.filter { it.isNotBlank() }.take(4),
            )
        }.filter { it.senses.isNotEmpty() }.takeIf { it.isNotEmpty() }
    }

    /** A short Wikipedia summary for a name, place or idea. */
    suspend fun wikipedia(word: String, lang: String = "en"): Result<Lookup?> = withContext(Dispatchers.IO) {
        runCatching {
            val q = clean(word)
            for (t in linkedSetOf(q, q.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } })) {
                val (code, body) = get("https://$lang.wikipedia.org/api/rest_v1/page/summary/${enc(t.replace(' ', '_'))}?redirect=true")
                if (code == 404) continue
                check(code in 200..299) { "Wikipedia answered $code" }
                val o = JSONObject(body)
                val extract = o.optString("extract").ifBlank { continue }
                val disambiguation = o.optString("type") == "disambiguation"
                return@runCatching Lookup(
                    "Wikipedia", o.optString("title", t),
                    summary = if (disambiguation) "“${o.optString("title")}” could mean several things. Open Wikipedia to pick one." else extract,
                    url = o.optJSONObject("content_urls")?.optJSONObject("mobile")?.optString("page"),
                )
            }
            null
        }
    }

    private fun get(url: String): Pair<Int, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6000; readTimeout = 8000
            setRequestProperty("User-Agent", "Worm-Reader/0.6 (Android ebook reader)")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            val code = conn.responseCode
            code to (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText().orEmpty()
        } finally { conn.disconnect() }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    internal fun strip(html: String) = html.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n").replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").trim()

    /** Offline dictionary apps (ColorDict, Livio, QuickDic…) register for PROCESS_TEXT. */
    fun openInApp(context: Context, word: String) {
        val intent = Intent(Intent.ACTION_PROCESS_TEXT)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_PROCESS_TEXT, word)
            .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
        runCatching { context.startActivity(Intent.createChooser(intent, "Look up “$word”").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

/**
 * StarDict dictionaries (.ifo + .idx + .dict/.dict.dz), the format KOReader, GoldenDict and
 * ColorDict use. Imported into Worm's own storage; looked up by binary search on the index.
 */
class StarDict private constructor(val dir: File, val name: String, private val ifo: Map<String, String>) {
    private val idxFile = dir.listFiles()?.firstOrNull { it.name.endsWith(".idx") }
    private val dictFile = dir.listFiles()?.firstOrNull { it.name.endsWith(".dict") }
    private val wide = ifo["idxoffsetbits"] == "64"
    private val types = ifo["sametypesequence"]
    val words: Int get() = ifo["wordcount"]?.toIntOrNull() ?: 0

    /** Index loaded on first use: the raw bytes plus where each entry starts. */
    private var idx: ByteArray? = null
    private var starts: IntArray? = null

    @Synchronized private fun load() {
        if (idx != null) return
        val bytes = idxFile?.readBytes() ?: ByteArray(0)
        val list = ArrayList<Int>(words.coerceAtLeast(16))
        var p = 0
        val tail = if (wide) 12 else 8
        while (p < bytes.size) {
            list += p
            while (p < bytes.size && bytes[p] != 0.toByte()) p++
            p += 1 + tail
        }
        idx = bytes; starts = list.toIntArray()
    }

    private fun wordAt(i: Int): String {
        val b = idx!!; val s = starts!![i]
        var e = s; while (b[e] != 0.toByte()) e++
        return String(b, s, e - s, Charsets.UTF_8)
    }

    /** StarDict sorts with ASCII-case-insensitive compare, ties broken by exact compare. */
    private fun cmp(a: String, b: String): Int {
        val c = asciiLower(a).compareTo(asciiLower(b))
        return if (c != 0) c else a.compareTo(b)
    }
    private fun asciiLower(s: String) = buildString(s.length) { s.forEach { ch -> append(if (ch in 'A'..'Z') ch + 32 else ch) } }

    private fun find(word: String): Int? {
        load()
        var lo = 0; var hi = starts!!.size - 1
        val key = asciiLower(word)
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val w = wordAt(mid)
            val c = asciiLower(w).compareTo(key)
            when { c < 0 -> lo = mid + 1; c > 0 -> hi = mid - 1; else -> {
                // Several entries can share a case-folded key; prefer the exact one.
                var i = mid
                while (i > 0 && asciiLower(wordAt(i - 1)) == key) i--
                var best = i
                while (i < starts!!.size && asciiLower(wordAt(i)) == key) { if (wordAt(i) == word) best = i; i++ }
                return best
            } }
        }
        return null
    }

    private fun read(i: Int): String {
        val b = idx!!; var p = starts!![i]
        while (b[p] != 0.toByte()) p++
        p++
        fun u32(at: Int) = ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)
        val off = if (wide) (u32(p) shl 32) or u32(p + 4) else u32(p)
        val size = u32(p + if (wide) 8 else 4).toInt()
        val data = ByteArray(size)
        RandomAccessFile(dictFile, "r").use { it.seek(off); it.readFully(data) }
        return render(data)
    }

    /** Pulls readable text out of an entry, whatever mix of types it holds. */
    private fun render(data: ByteArray): String {
        fun text(type: Char, bytes: ByteArray): String = when (type) {
            'm', 'l', 't', 'y', 'k' -> String(bytes, Charsets.UTF_8)
            'h', 'g', 'x', 'w' -> Dictionary.strip(String(bytes, Charsets.UTF_8).replace(Regex("(?i)</(p|div|li|blockquote|def)>"), "\n"))
            else -> ""
        }
        val seq = types
        if (seq != null) {
            // Every field's type is known in advance; only the last runs to the end.
            if (seq.length == 1) return text(seq[0], data).trim()
            val out = StringBuilder(); var p = 0
            seq.forEachIndexed { n, t ->
                val last = n == seq.lastIndex
                val end = if (last) data.size else (p until data.size).firstOrNull { data[it] == 0.toByte() } ?: data.size
                out.append(text(t, data.copyOfRange(p, end))).append('\n'); p = end + 1
            }
            return out.toString().trim()
        }
        val out = StringBuilder(); var p = 0
        while (p < data.size) {
            val t = data[p].toInt().toChar(); p++
            if (t.isLowerCase()) {
                val end = (p until data.size).firstOrNull { data[it] == 0.toByte() } ?: data.size
                out.append(text(t, data.copyOfRange(p, end))).append('\n'); p = end + 1
            } else {
                if (p + 4 > data.size) break
                val len = ((data[p].toInt() and 0xFF) shl 24) or ((data[p + 1].toInt() and 0xFF) shl 16) or ((data[p + 2].toInt() and 0xFF) shl 8) or (data[p + 3].toInt() and 0xFF)
                p += 4 + len
            }
        }
        return out.toString().trim()
    }

    fun lookup(candidates: List<String>): Lookup? {
        if (idxFile == null || dictFile == null) return null
        for (c in candidates) {
            val i = runCatching { find(c) }.getOrNull() ?: continue
            val body = runCatching { read(i) }.getOrNull()?.takeIf { it.isNotBlank() } ?: continue
            val lines = body.lines().map { it.trim() }.filter { it.isNotEmpty() }
            return Lookup(name, wordAt(i), listOf(Definition("", lines.take(12))))
        }
        return null
    }

    companion object {
        private fun root(context: Context) = File(context.filesDir, "dictionaries").also { it.mkdirs() }

        @Volatile private var loaded: List<StarDict>? = null

        fun installed(context: Context): List<StarDict> = loaded ?: synchronized(this) {
            loaded ?: root(context).listFiles()?.filter { it.isDirectory }?.mapNotNull(::open)?.sortedBy { it.name }.orEmpty().also { loaded = it }
        }

        internal fun open(dir: File): StarDict? {
            val ifoFile = dir.listFiles()?.firstOrNull { it.name.endsWith(".ifo") } ?: return null
            val ifo = ifoFile.readLines().mapNotNull { l -> l.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }.toMap()
            return StarDict(dir, ifo["bookname"] ?: dir.name, ifo)
        }

        fun remove(context: Context, d: StarDict) { d.dir.deleteRecursively(); loaded = null }

        /**
         * Adds a dictionary from the files you picked: a .zip of it, or its .ifo/.idx/.dict(.dz)
         * files together. Compressed .dict.dz / .idx.gz are unpacked once so lookups can seek.
         */
        fun import(context: Context, files: List<Pair<String, () -> java.io.InputStream>>): String {
            val tmp = File(root(context), ".import-${System.currentTimeMillis()}").also { it.mkdirs() }
            try {
                fun save(name: String, input: java.io.InputStream) {
                    val base = name.substringAfterLast('/').takeIf { it.isNotBlank() && !it.startsWith(".") } ?: return
                    when {
                        base.endsWith(".dict.dz") || base.endsWith(".idx.gz") ->
                            File(tmp, base.removeSuffix(".dz").removeSuffix(".gz")).outputStream().use { GZIPInputStream(input).copyTo(it) }
                        base.endsWith(".ifo") || base.endsWith(".idx") || base.endsWith(".dict") || base.endsWith(".syn") ->
                            File(tmp, base).outputStream().use { input.copyTo(it) }
                    }
                }
                files.forEach { (name, open) ->
                    if (name.endsWith(".zip", true)) open().use { raw ->
                        ZipInputStream(raw.buffered()).use { z -> while (true) { val e = z.nextEntry ?: break; if (!e.isDirectory) save(e.name, z) } }
                    } else open().use { save(name, it) }
                }
                val d = open(tmp) ?: throw IllegalArgumentException("No .ifo file: pick the dictionary's .ifo, .idx and .dict(.dz) files, or a .zip of them")
                require(d.idxFile != null && d.dictFile != null) { "“${d.name}” needs its .idx and .dict (or .dict.dz) files too" }
                val dest = File(root(context), d.name.replace(Regex("""[\\/:*?"<>|]"""), "_").take(80))
                dest.deleteRecursively()
                check(tmp.renameTo(dest)) { "Couldn't store the dictionary" }
                loaded = null
                return d.name
            } finally { tmp.deleteRecursively() }
        }
    }
}
