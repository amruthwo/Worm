package com.worm.app.data

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
import kotlin.math.abs

/** One candidate from an online catalogue. */
data class MetaHit(
    val source: String,
    val title: String,
    val authors: List<String>,
    val narrators: List<String> = emptyList(),
    val series: String? = null,
    val seriesIndex: Float? = null,
    val description: String? = null,
    val coverUrl: String? = null,
    val year: String? = null,
    val durationMs: Long = 0,
    val asin: String? = null,
)

/**
 * Looks books up online so covers and missing details can be filled in. Only ever runs when you
 * ask (magic match or search); what's sent is the title/author you're searching for.
 *  - Audible's public catalogue (+ Audnexus for chapter names): best for audiobooks.
 *  - Google Books and Open Library: best for ebooks.
 */
object MetaSearch {
    private const val UA = "Worm/0.1 (Android; book metadata lookup)"
    private val lang3: String get() = runCatching { java.util.Locale.getDefault().isO3Language }.getOrDefault("eng").ifBlank { "eng" }
    private val country: String get() = java.util.Locale.getDefault().country.ifBlank { "US" }

    /**
     * Audiobooks search Audible (square audiobook art, narrators, runtimes) and only fall back to the
     * book catalogues if Audible knows nothing. Books search the book catalogues only, so you never
     * get offered an audiobook cover for an ebook (or the other way round).
     */
    suspend fun search(title: String, author: String?, audio: Boolean): List<MetaHit> = coroutineScope {
        if (audio) {
            val fromAudible = runCatching { audible(title, author) }.getOrDefault(emptyList())
            if (fromAudible.isNotEmpty()) return@coroutineScope fromAudible
        }
        // Books: three catalogues at once, with the subtitle / series tags trimmed off ("…: A Novel").
        val t = searchTitle(title)
        val last = author?.let(::lastName)
        val first = listOf(
            async { runCatching { openLibrary(t, last) }.getOrDefault(emptyList()) },
            async { runCatching { apple(listOfNotNull(t, author).joinToString(" ")) }.getOrDefault(emptyList()) },
            async { runCatching { google(t, last) }.getOrDefault(emptyList()) },
        ).awaitAll().flatten()
        if (first.isNotEmpty() || author == null) return@coroutineScope first
        // Nothing with the author? Try the title alone (author names are spelt many ways).
        listOf(
            async { runCatching { openLibrary(t, null) }.getOrDefault(emptyList()) },
            async { runCatching { apple(t) }.getOrDefault(emptyList()) },
        ).awaitAll().flatten()
    }

    /**
     * Series + number from a title: "All These Worlds (Bobiverse Book 3)", "(The Expanse, #2)",
     * "Leviathan Wakes: The Expanse, Book 1", "Book 3 of the Bobiverse".
     */
    fun parseSeries(title: String): Pair<String, Float?>? {
        val num = """(\d+(?:\.\d+)?)"""
        val word = """(?:Book|Bk\.?|Vol\.?|Volume|Part|No\.?|#)"""
        val patterns = listOf(
            Regex("""\(([^()]+?)[,:]?\s*$word\s*$num\)""", RegexOption.IGNORE_CASE),
            Regex("""\(([^()]+?)\s+$num\)"""),
            Regex("""[:\-–—]\s*([^:\-–—]+?)[,:]?\s*$word\s*$num\s*$""", RegexOption.IGNORE_CASE),
            Regex("""$word\s*$num\s+(?:of|in)\s+(?:the\s+)?(.+?)(?:\s+series)?\s*$""", RegexOption.IGNORE_CASE),
        )
        for ((i, r) in patterns.withIndex()) {
            val m = r.find(title) ?: continue
            val (name, n) = if (i == 3) m.groupValues[2] to m.groupValues[1] else m.groupValues[1] to m.groupValues[2]
            val clean = name.trim().removePrefix("The ").removeSuffix(" Series").removeSuffix(" series").trim()
            if (clean.length in 2..60 && !clean.any { it.isDigit() && clean.length < 3 }) return name.trim().removeSuffix(" Series").removeSuffix(" series") to n.toFloatOrNull()
        }
        return null
    }

    /** Best guess at a book's series: from its own title first, then from Audible's catalogue. */
    suspend fun seriesFor(title: String, author: String?): Pair<String, Float?>? = withContext(Dispatchers.IO) {
        parseSeries(title)?.let { return@withContext it }
        val t = searchTitle(title)
        val hits = runCatching { audible(t, author) }.getOrDefault(emptyList())
        hits.filter { it.series != null && titleSimilarity(t, cleanTitle(it.title)) >= 0.6 && (author == null || it.authors.any { a -> authorMatch(author, a) }) }
            .maxByOrNull { titleSimilarity(t, cleanTitle(it.title)) }?.let { it.series!! to it.seriesIndex }
    }

    /** "The Secret of Secrets: A Novel" → "The Secret of Secrets"; drops (brackets) and "Series, Book 3". */
    fun searchTitle(raw: String): String {
        var t = cleanTitle(raw)
        t = t.replace(Regex("""\s*[\(\[][^\)\]]*[\)\]]"""), " ")
        val beforeColon = t.substringBefore(':').trim()
        if (':' in t && beforeColon.split(' ').size >= 1 && beforeColon.length >= 3) t = beforeColon
        t = t.replace(Regex("""(?i)\s*[-–—,]\s*(a novel|a thriller|a memoir|a mystery|a romance|novel)\s*$"""), "")
        return t.replace(Regex("\\s+"), " ").trim().ifEmpty { raw }
    }

    private fun lastName(author: String) = author.trim().split(Regex("\\s+")).last().trim('.', ',')

    /** Picks the most plausible hit for a book, or null if nothing is close enough to trust. */
    suspend fun bestMatch(book: Book): MetaHit? = candidates(book).firstOrNull()?.takeIf { it.second >= CONFIDENT }?.first

    const val CONFIDENT = 0.62
    const val MAYBE = 0.42

    /** Different ways to ask: titles in the wild are messy, so try a few readings before giving up. */
    fun queryVariants(book: Book): List<Pair<String, String?>> {
        val author = book.authors.firstOrNull()
        val raw = book.title
        val titles = linkedSetOf<String>()
        titles += cleanTitle(raw)
        titles += cleanTitle(raw.replace(Regex("""\s*[\(\[][^\)\]]*[\)\]]"""), " "))
        if (':' in raw) titles += cleanTitle(raw.substringBefore(':'))
        // "Author Name - Title" / "Title - Author Name" from file names.
        var guessedAuthor: String? = null
        if (" - " in raw) {
            val parts = raw.split(" - ").map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.size >= 2) {
                val (first, last) = parts.first() to parts.last()
                val looksLikeName = { s: String -> s.split(' ').size in 2..4 && s.split(' ').all { w -> w.firstOrNull()?.isUpperCase() == true } }
                when {
                    author == null && looksLikeName(first) -> { guessedAuthor = first; titles += cleanTitle(parts.drop(1).joinToString(" ")) }
                    author == null && looksLikeName(last) -> { guessedAuthor = last; titles += cleanTitle(parts.dropLast(1).joinToString(" ")) }
                    else -> titles += cleanTitle(parts.maxBy { it.length })
                }
            }
        }
        val who = author ?: guessedAuthor
        return titles.filter { it.length >= 2 }.flatMap { t -> listOfNotNull(if (who != null) t to who else null, t to null) }.distinct()
    }

    /**
     * Every plausible hit, best first, with a 0–1 confidence. Searches variants until something
     * confident turns up. [title]/[author] override the book's own when the user edits the query.
     */
    suspend fun candidates(book: Book, title: String? = null, author: String? = null): List<Pair<MetaHit, Double>> {
        val queries = if (title != null) listOf(title to author?.takeIf { it.isNotBlank() }) else queryVariants(book).take(5)
        val seen = mutableMapOf<String, Pair<MetaHit, Double>>()
        for ((t, a) in queries) {
            val hits = runCatching { search(t, a, book.isAudio) }.getOrDefault(emptyList())
            hits.forEach { h ->
                val sc = score(book, title ?: t, h, author ?: a)
                val key = h.source + h.title.lowercase() + h.authors.firstOrNull()
                if ((seen[key]?.second ?: -1.0) < sc) seen[key] = h to sc
            }
            if (seen.values.any { it.second >= 0.8 }) break
        }
        return seen.values.sortedByDescending { it.second }
    }

    private fun score(book: Book, title: String, hit: MetaHit, author: String?): Double {
        val ht = cleanTitle(hit.title)
        var s = titleSimilarity(title, ht) * 0.65
        val a = author ?: book.authors.firstOrNull()
        s += when {
            a == null || hit.authors.isEmpty() -> 0.12
            hit.authors.any { authorMatch(a, it) } -> 0.25
            else -> -0.1
        }
        if (hit.coverUrl != null) s += 0.03
        if (book.isAudio && hit.source == "Audible") {
            s += 0.04
            if (book.durationMs > 0 && hit.durationMs > 0) {
                val diff = abs(book.durationMs - hit.durationMs).toDouble() / book.durationMs
                s += when { diff < 0.02 -> 0.2; diff < 0.06 -> 0.08; diff > 0.3 -> -0.15; else -> 0.0 }
            }
        }
        if (!book.isAudio && hit.source != "Audible") s += 0.03
        return s.coerceIn(0.0, 1.0)
    }

    /** Jaccard, but a full title inside a longer one ("X" vs "X: The Y Saga, Book 1") still counts as strong. */
    fun titleSimilarity(a: String, b: String): Double {
        val x = tokens(a); val y = tokens(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        val inter = (x intersect y).size.toDouble()
        val jaccard = inter / (x union y).size
        val small = minOf(x.size, y.size)
        val containment = inter / small
        val contained = if (containment >= 1.0 && (small >= 2 || x == y)) 0.92 else containment * 0.75
        return maxOf(jaccard, contained)
    }

    private fun authorMatch(a: String, b: String): Boolean {
        val x = tokens(a); val y = tokens(b)
        if (x.isEmpty() || y.isEmpty()) return false
        val lastA = a.trim().split(Regex("\\s+")).last().lowercase().trim('.', ',')
        val lastB = b.trim().split(Regex("\\s+")).last().lowercase().trim('.', ',')
        return lastA == lastB || (x intersect y).size >= 2
    }

    /** "We Are Legion (We Are Bob) Bobiverse, Book 1 (Unabridged)" → "We Are Legion (We Are Bob)". */
    fun cleanTitle(raw: String): String = raw
        .replace(Regex("""(?i)\s*[\(\[](unabridged|abridged|dramati[sz]ed|retail|epub|mobi|audiobook)[\)\]]"""), "")
        .replace(Regex("""(?i)[,:\-–]?\s*(book|volume|vol\.?|part|#)\s*\d+(\.\d+)?\s*$"""), "")
        .replace(Regex("""(?i)\s+\S+(\s\S+)?,\s*$"""), { m -> if (m.value.length < 25) "" else m.value })
        .replace(Regex("""(?i)^\s*\d{1,3}\s*[-._]\s*"""), "")
        .replace('_', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun tokens(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), " ").split(' ').filter { it.length > 1 }.toSet()
    fun similarity(a: String, b: String): Double {
        val x = tokens(a); val y = tokens(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        return (x intersect y).size.toDouble() / (x union y).size
    }

    // ------------------------------------------------------------ sources

    private fun get(url: String): String? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 12_000
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "application/json")
        }
        return try {
            if (conn.responseCode !in 200..299) null else conn.inputStream.bufferedReader().readText()
        } finally { conn.disconnect() }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun JSONArray?.names(key: String = "name"): List<String> =
        if (this == null) emptyList() else List(length()) { optJSONObject(it)?.optString(key).orEmpty() }.filter { it.isNotBlank() }

    private fun audible(title: String, author: String?): List<MetaHit> {
        val url = "https://api.audible.com/1.0/catalog/products?num_results=10&products_sort_by=Relevance" +
            "&response_groups=contributors,media,product_attrs,product_desc,series&title=${enc(title)}" +
            (author?.let { "&author=${enc(it)}" } ?: "")
        val json = JSONObject(get(url) ?: return emptyList())
        val products = json.optJSONArray("products") ?: return emptyList()
        return List(products.length()) { i ->
            val p = products.getJSONObject(i)
            val series = p.optJSONArray("series")?.optJSONObject(0)
            val images = p.optJSONObject("product_images")
            MetaHit(
                source = "Audible",
                title = p.optString("title"),
                authors = p.optJSONArray("authors").names(),
                narrators = p.optJSONArray("narrators").names(),
                series = series?.optString("title")?.takeIf { it.isNotBlank() },
                seriesIndex = series?.optString("sequence")?.toFloatOrNull(),
                description = (p.optString("publisher_summary").ifBlank { p.optString("merchandising_summary") })
                    .replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim().ifBlank { null },
                coverUrl = images?.optString("500")?.takeIf { it.isNotBlank() }?.replace("._SL500_", "._SL1000_"),
                year = p.optString("release_date").take(4).ifBlank { null },
                durationMs = p.optLong("runtime_length_min") * 60_000,
                asin = p.optString("asin").ifBlank { null },
            )
        }.filter { it.title.isNotBlank() }.map(::withParsedSeries)
    }

    private fun google(title: String, author: String?): List<MetaHit> {
        val q = title.replace(Regex("[^\\p{L}\\p{N}' ]"), " ").trim() + (author?.let { " inauthor:$it" } ?: "")
        val json = JSONObject(get("https://www.googleapis.com/books/v1/volumes?maxResults=10&printType=books&q=${enc(q)}") ?: return emptyList())
        val items = json.optJSONArray("items") ?: return emptyList()
        return List(items.length()) { i ->
            val v = items.getJSONObject(i).optJSONObject("volumeInfo") ?: JSONObject()
            val links = v.optJSONObject("imageLinks")
            val cover = (links?.optString("extraLarge") ?: links?.optString("large") ?: links?.optString("thumbnail"))
                ?.takeIf { it.isNotBlank() }?.replace("http://", "https://")?.replace("&edge=curl", "")?.replace("zoom=1", "zoom=0")
            MetaHit(
                source = "Google Books",
                title = listOfNotNull(v.optString("title"), v.optString("subtitle").takeIf { it.isNotBlank() }).joinToString(": "),
                authors = v.optJSONArray("authors")?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty(),
                description = v.optString("description").ifBlank { null },
                coverUrl = cover,
                year = v.optString("publishedDate").take(4).ifBlank { null },
            )
        }.filter { it.title.isNotBlank() }.map(::withParsedSeries)
    }

    /** Apple Books catalogue (iTunes Search API): free, no key, big covers. */
    private fun apple(term: String): List<MetaHit> {
        val json = JSONObject(get("https://itunes.apple.com/search?media=ebook&entity=ebook&limit=10&country=$country&term=${enc(term)}") ?: return emptyList())
        val results = json.optJSONArray("results") ?: return emptyList()
        return List(results.length()) { i ->
            val r = results.getJSONObject(i)
            MetaHit(
                source = "Apple Books",
                title = r.optString("trackName"),
                authors = r.optString("artistName").split(Regex("""\s*(&|,| and )\s*""")).filter { it.isNotBlank() },
                description = r.optString("description").replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim().ifBlank { null },
                coverUrl = r.optString("artworkUrl100").takeIf { it.isNotBlank() }?.replace("100x100bb", "1200x1200bb"),
                year = r.optString("releaseDate").take(4).ifBlank { null },
            )
        }.filter { it.title.isNotBlank() }.map(::withParsedSeries)
    }

    private fun withParsedSeries(h: MetaHit): MetaHit =
        if (h.series != null) h else parseSeries(h.title)?.let { (s, n) -> h.copy(series = s, seriesIndex = n) } ?: h

    private fun openLibrary(title: String, author: String?): List<MetaHit> {
        // Only editions in your language, so the covers are too.
        val url = "https://openlibrary.org/search.json?limit=8&fields=title,author_name,cover_i,first_publish_year,series,language&language=$lang3&title=${enc(title)}" +
            (author?.let { "&author=${enc(it)}" } ?: "")
        val docs = JSONObject(get(url) ?: return emptyList()).optJSONArray("docs") ?: return emptyList()
        return List(docs.length()) { i ->
            val d = docs.getJSONObject(i)
            val cover = d.optLong("cover_i", 0L).takeIf { it > 0 }?.let { "https://covers.openlibrary.org/b/id/$it-L.jpg" }
            MetaHit(
                source = "Open Library",
                title = d.optString("title"),
                authors = d.optJSONArray("author_name")?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty(),
                series = d.optJSONArray("series")?.optString(0)?.takeIf { it.isNotBlank() },
                coverUrl = cover,
                year = d.optInt("first_publish_year", 0).takeIf { it > 0 }?.toString(),
            )
        }.filter { it.title.isNotBlank() }.map(::withParsedSeries)
    }

    /** Chapter names + timings for an Audible title (via the community Audnexus service). */
    suspend fun chapters(asin: String): List<Chapter> = withContext(Dispatchers.IO) {
        runCatching {
            val json = JSONObject(get("https://api.audnex.us/books/$asin/chapters") ?: return@runCatching emptyList())
            val arr = json.optJSONArray("chapters") ?: return@runCatching emptyList()
            List(arr.length()) { i ->
                val c = arr.getJSONObject(i)
                val start = c.optLong("startOffsetMs")
                Chapter(c.optString("title").ifBlank { "Chapter ${i + 1}" }, start, start + c.optLong("lengthMs"))
            }
        }.getOrDefault(emptyList())
    }

    suspend fun download(url: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000; readTimeout = 20_000; instanceFollowRedirects = true
                setRequestProperty("User-Agent", UA)
            }
            try { if (conn.responseCode in 200..299) conn.inputStream.readBytes() else null } finally { conn.disconnect() }
        }.getOrNull()?.takeIf { it.size > 1500 } // Open Library returns a 1x1 gif for missing covers
    }

    suspend fun searchIO(title: String, author: String?, audio: Boolean) = withContext(Dispatchers.IO) { search(title, author, audio) }
    suspend fun bestMatchIO(book: Book) = withContext(Dispatchers.IO) { bestMatch(book) }
    suspend fun candidatesIO(book: Book, title: String? = null, author: String? = null) =
        withContext(Dispatchers.IO) { candidates(book, title, author) }
}
