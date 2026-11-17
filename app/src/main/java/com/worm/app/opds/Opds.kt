package com.worm.app.opds

import android.content.Context
import android.util.Base64
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.UUID

/** An OPDS catalogue you've added (your Calibre-Web / Kavita / Komga / Audiobookshelf, or a public one). */
data class Catalog(val id: String, val name: String, val url: String, val user: String? = null, val pass: String? = null) {
    /** Basic auth header for private servers. */
    val auth: String? get() = if (user.isNullOrBlank()) null else "Basic " + Base64.encodeToString("$user:${pass.orEmpty()}".toByteArray(), Base64.NO_WRAP)
}

data class Acquisition(val href: String, val type: String?, val size: Long?) {
    /** "EPUB", "M4B", "PDF"… for buttons. */
    val label: String get() = when {
        type == null -> href.substringAfterLast('.', "file").substringBefore('?').uppercase()
        "epub" in type -> "EPUB"
        "mobipocket" in type || "mobi" in type -> "MOBI"
        "amazon" in type -> "AZW3"
        "pdf" in type -> "PDF"
        "fictionbook" in type || "fb2" in type -> "FB2"
        "comicbook" in type || "cbz" in type -> "CBZ"
        "zip" in type -> "ZIP"
        "audio/mpeg" in type -> "MP3"
        "audio" in type || "m4b" in type -> "Audio"
        "text/plain" in type -> "TXT"
        "html" in type -> "HTML"
        else -> type.substringAfter('/').substringBefore(';').uppercase().take(8)
    }
    /** Formats Worm reads directly come first. */
    val rank: Int get() = when (label) { "EPUB" -> 0; "Audio", "MP3" -> 1; "AZW3" -> 2; "MOBI" -> 3; "FB2" -> 4; "CBZ" -> 5; "ZIP" -> 6; "PDF" -> 7; else -> 9 }
}

data class Entry(
    val id: String,
    val title: String,
    val authors: List<String>,
    val summary: String?,
    val cover: String?,
    val acquisitions: List<Acquisition>,
    /** For navigation entries: the feed this opens. */
    val feed: String?,
    val series: String? = null,
)

data class Feed(
    val url: String,
    val title: String,
    val entries: List<Entry>,
    val next: String?,
    /** Search URL template with {searchTerms}, if the catalogue supports it. */
    val search: String?,
)

class OpdsException(message: String) : Exception(message)

object Opds {
    /** Public catalogues offered when you add one. */
    val presets = listOf(
        "Project Gutenberg" to "https://www.gutenberg.org/ebooks.opds/",
    )

    // ------------------------------------------------------------- storage

    private fun sp(c: Context) = c.getSharedPreferences("worm_opds", Context.MODE_PRIVATE)

    fun catalogs(c: Context): List<Catalog> = runCatching {
        val a = JSONArray(sp(c).getString("catalogs", "[]"))
        List(a.length()) { a.getJSONObject(it).let { o -> Catalog(o.getString("id"), o.getString("name"), o.getString("url"), o.optString("user").ifEmpty { null }, o.optString("pass").ifEmpty { null }) } }
    }.getOrDefault(emptyList())

    fun save(c: Context, list: List<Catalog>) = sp(c).edit {
        putString("catalogs", JSONArray(list.map { JSONObject().put("id", it.id).put("name", it.name).put("url", it.url).put("user", it.user ?: "").put("pass", it.pass ?: "") }).toString())
    }

    fun add(c: Context, name: String, url: String, user: String?, pass: String?): Catalog =
        Catalog(UUID.randomUUID().toString(), name.ifBlank { URL(url).host }, url.trim(), user?.ifBlank { null }, pass?.ifBlank { null })
            .also { save(c, catalogs(c) + it) }

    fun remove(c: Context, id: String) = save(c, catalogs(c).filterNot { it.id == id })

    // ------------------------------------------------------------ fetching

    suspend fun load(url: String, auth: String?): Feed = withContext(Dispatchers.IO) {
        val (body, type, finalUrl) = get(url, auth)
        val trimmed = body.trimStart()
        val feed = if (type.contains("json") || trimmed.startsWith("{")) parseJson(trimmed, finalUrl) else parseAtom(trimmed, finalUrl)
        // OpenSearch description → real template.
        val search = feed.search?.let { s -> if ("{searchTerms}" in s) s else runCatching { openSearchTemplate(s, auth) }.getOrNull() }
        feed.copy(search = search)
    }

    fun searchUrl(template: String, query: String): String =
        template.replace("{searchTerms}", java.net.URLEncoder.encode(query, "UTF-8").replace("+", "%20"))
            .replace(Regex("""\{[^}]+\?}"""), "") // optional OpenSearch params

    private fun get(url: String, auth: String?): Triple<String, String, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 30_000; instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Worm/0.4 (Android; OPDS)")
            setRequestProperty("Accept", "application/opds+json, application/atom+xml, application/xml;q=0.9, */*;q=0.5")
            auth?.let { setRequestProperty("Authorization", it) }
        }
        try {
            when (val code = conn.responseCode) {
                401, 403 -> throw OpdsException("This catalogue needs a username and password")
                !in 200..299 -> throw OpdsException("The catalogue said $code")
                else -> Unit
            }
            return Triple(conn.inputStream.bufferedReader().readText(), conn.contentType.orEmpty(), conn.url.toString())
        } catch (e: java.net.UnknownHostException) {
            throw OpdsException("Can't reach ${URL(url).host}")
        } finally { conn.disconnect() }
    }

    private fun resolve(base: String, href: String?): String? = href?.let { runCatching { URI(base).resolve(it.trim()).toString() }.getOrNull() ?: it }

    private fun openSearchTemplate(descUrl: String, auth: String?): String? {
        val (body, _, final) = get(descUrl, auth)
        val urls = Regex("""<Url\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(body).map { it.value }.toList()
        val pick = urls.firstOrNull { "atom" in it || "opds" in it } ?: urls.firstOrNull() ?: return null
        val template = Regex("""template\s*=\s*"([^"]+)"""").find(pick)?.groupValues?.get(1)?.replace("&amp;", "&") ?: return null
        return resolve(final, template)
    }

    // ------------------------------------------------------------- OPDS 1

    internal fun parseAtom(xml: String, base: String): Feed {
        val p = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        p.setInput(xml.reader())
        var feedTitle = ""
        var next: String? = null
        var search: String? = null
        val entries = mutableListOf<Entry>()

        var inEntry = false
        var inAuthor = false
        var title = ""; var id = ""; var summary: String? = null; var cover: String? = null; var thumb: String? = null
        var nav: String? = null; var series: String? = null
        val authors = mutableListOf<String>()
        val acq = mutableListOf<Acquisition>()

        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "entry" -> { inEntry = true; title = ""; id = ""; summary = null; cover = null; thumb = null; nav = null; series = null; authors.clear(); acq.clear() }
                    "author" -> inAuthor = true
                    "name" -> if (inAuthor && inEntry) authors += p.nextText().trim()
                    "title" -> { val t = p.nextText().trim(); if (inEntry) title = t else if (feedTitle.isEmpty()) feedTitle = t }
                    "id" -> if (inEntry) id = p.nextText().trim()
                    "summary", "content" -> if (inEntry && summary == null) summary = p.nextText().replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim().ifEmpty { null }
                    "link" -> {
                        val rel = p.getAttributeValue(null, "rel").orEmpty()
                        val type = p.getAttributeValue(null, "type")
                        val href = resolve(base, p.getAttributeValue(null, "href")) ?: continue
                        val len = p.getAttributeValue(null, "length")?.toLongOrNull()
                        if (inEntry) when {
                            rel.startsWith("http://opds-spec.org/acquisition") -> if (!rel.endsWith("/buy") && !rel.endsWith("/subscribe")) acq += Acquisition(href, type, len)
                            rel == "http://opds-spec.org/image" || rel == "http://opds-spec.org/cover" -> cover = href
                            rel == "http://opds-spec.org/image/thumbnail" || rel == "http://opds-spec.org/thumbnail" -> thumb = href
                            type?.contains("atom+xml") == true || type?.contains("opds") == true -> if (nav == null) nav = href
                        } else when (rel) {
                            "next" -> next = href
                            "search" -> if (search == null || type?.contains("atom") == true) search = href
                        }
                    }
                    "series", "belongs-to-collection" -> if (inEntry) series = p.getAttributeValue(null, "name") ?: runCatching { p.nextText() }.getOrNull()
                }
                XmlPullParser.END_TAG -> when (p.name) {
                    "author" -> inAuthor = false
                    "entry" -> {
                        inEntry = false
                        entries += Entry(id.ifEmpty { title }, title, authors.toList(), summary, cover ?: thumb, acq.sortedBy { it.rank }, if (acq.isEmpty()) nav else null, series)
                    }
                }
            }
        }
        return Feed(base, feedTitle, entries, next, search)
    }

    // ------------------------------------------------------------- OPDS 2

    internal fun parseJson(json: String, base: String): Feed {
        val o = JSONObject(json)
        val links = o.optJSONArray("links")
        fun linkRel(rel: String): String? = links?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { l -> relOf(l).contains(rel) }?.optString("href") }
        val entries = mutableListOf<Entry>()
        fun pubs(arr: JSONArray?) = arr?.let { a -> (0 until a.length()).forEach { entries += publication(a.getJSONObject(it), base) } }
        fun navs(arr: JSONArray?) = arr?.let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.forEach { n ->
                entries += Entry(n.optString("href"), n.optString("title"), emptyList(), null, null, emptyList(), resolve(base, n.optString("href")))
            }
        }
        navs(o.optJSONArray("navigation"))
        pubs(o.optJSONArray("publications"))
        o.optJSONArray("groups")?.let { g ->
            (0 until g.length()).map { g.getJSONObject(it) }.forEach { grp -> navs(grp.optJSONArray("navigation")); pubs(grp.optJSONArray("publications")) }
        }
        return Feed(
            base, o.optJSONObject("metadata")?.optString("title").orEmpty(), entries,
            resolve(base, linkRel("next")), resolve(base, linkRel("search")),
        )
    }

    private fun relOf(l: JSONObject): List<String> = when (val r = l.opt("rel")) {
        is JSONArray -> List(r.length()) { r.getString(it) }
        is String -> listOf(r)
        else -> emptyList()
    }

    private fun publication(pub: JSONObject, base: String): Entry {
        val m = pub.optJSONObject("metadata") ?: JSONObject()
        fun names(key: String): List<String> = when (val v = m.opt(key)) {
            is String -> listOf(v)
            is JSONObject -> listOf(v.optString("name"))
            is JSONArray -> List(v.length()) { v.opt(it).let { x -> if (x is JSONObject) x.optString("name") else x.toString() } }
            else -> emptyList()
        }.filter { it.isNotBlank() }
        val links = pub.optJSONArray("links")
        val acq = links?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()
            .filter { l -> relOf(l).any { it.startsWith("http://opds-spec.org/acquisition") } || l.optString("type").let { t -> "epub" in t || "audio" in t } }
            .mapNotNull { l -> resolve(base, l.optString("href"))?.let { Acquisition(it, l.optString("type").ifEmpty { null }, l.optJSONObject("properties")?.optLong("size")) } }
        val img = pub.optJSONArray("images")?.optJSONObject(0)?.optString("href")
        return Entry(
            m.optString("identifier").ifEmpty { m.optString("title") }, m.optString("title"), names("author"),
            m.optString("description").replace(Regex("<[^>]+>"), " ").trim().ifEmpty { null }, resolve(base, img),
            acq.sortedBy { it.rank }, null,
            m.optJSONObject("belongsTo")?.optJSONArray("series")?.optJSONObject(0)?.optString("name"),
        )
    }
}
