package com.worm.app.convert

import java.io.File
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class EpubMeta(
    val title: String,
    val authors: List<String>,
    val language: String = "en",
    val description: String? = null,
    val publisher: String? = null,
    val identifier: String = "urn:uuid:" + UUID.randomUUID(),
    val series: String? = null,
    val seriesIndex: Float? = null,
)

data class TocItem(val label: String, val href: String, val depth: Int = 0)

/**
 * Streams an EPUB 3 file (with an EPUB 2 NCX for older readers): sections and resources are written
 * as they arrive, then [finish] adds the package document, navigation and container.
 */
class EpubWriter(private val out: File) : AutoCloseable {
    private val zip = ZipOutputStream(out.outputStream().buffered(1 shl 16))
    private data class Item(val id: String, val href: String, val mime: String, val props: String? = null)
    private val sections = mutableListOf<Item>()
    private val resources = mutableListOf<Item>()
    private val names = HashSet<String>()
    var fixedLayout = false

    init {
        // "mimetype" must be first and stored uncompressed.
        val bytes = "application/epub+zip".toByteArray()
        val e = ZipEntry("mimetype").apply {
            method = ZipEntry.STORED; size = bytes.size.toLong(); compressedSize = bytes.size.toLong()
            crc = CRC32().apply { update(bytes) }.value
        }
        zip.putNextEntry(e); zip.write(bytes); zip.closeEntry()
        put("META-INF/container.xml", """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>""".toByteArray())
    }

    private fun put(name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
    }

    /** Adds a chapter. [xhtml] must be a complete XHTML document. Returns its href (relative to OEBPS). */
    @Synchronized
    fun addSection(xhtml: String): String {
        val href = "text/s%04d.xhtml".format(sections.size)
        put("OEBPS/$href", xhtml.toByteArray(Charsets.UTF_8))
        sections += Item("s%04d".format(sections.size), href, "application/xhtml+xml", if (xhtml.contains("<svg", true)) "svg" else null)
        return href
    }

    /** Adds an image/stylesheet/font at [href] (relative to OEBPS, e.g. "res/r3.jpg"). */
    @Synchronized
    fun addResource(href: String, mime: String, bytes: ByteArray, cover: Boolean = false) {
        if (!names.add(href)) return
        put("OEBPS/$href", bytes)
        resources += Item("r${resources.size}", href, mime, if (cover) "cover-image" else null)
    }

    val sectionCount: Int get() = sections.size

    @Synchronized
    fun finish(meta: EpubMeta, toc: List<TocItem>) {
        val tocList = toc.ifEmpty { sections.mapIndexed { i, s -> TocItem(if (sections.size == 1) meta.title else "Part ${i + 1}", s.href) } }
        put("OEBPS/nav.xhtml", nav(meta, tocList).toByteArray())
        put("OEBPS/toc.ncx", ncx(meta, tocList).toByteArray())
        put("OEBPS/content.opf", opf(meta).toByteArray())
        zip.close()
    }

    override fun close() = runCatching { zip.close() }.let { }

    private fun opf(m: EpubMeta): String = buildString {
        val cover = resources.firstOrNull { it.props == "cover-image" }
        append("""<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid" prefix="rendition: http://www.idpf.org/vocab/rendition/#">
<metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
""")
        append("<dc:identifier id=\"bookid\">${esc(m.identifier)}</dc:identifier>\n")
        append("<dc:title>${esc(m.title)}</dc:title>\n")
        m.authors.forEachIndexed { i, a -> append("<dc:creator id=\"a$i\">${esc(a)}</dc:creator>\n<meta refines=\"#a$i\" property=\"role\" scheme=\"marc:relators\">aut</meta>\n") }
        append("<dc:language>${esc(m.language.ifBlank { "en" })}</dc:language>\n")
        m.description?.let { append("<dc:description>${esc(it)}</dc:description>\n") }
        m.publisher?.let { append("<dc:publisher>${esc(it)}</dc:publisher>\n") }
        m.series?.let { s ->
            append("<meta property=\"belongs-to-collection\" id=\"series\">${esc(s)}</meta>\n<meta refines=\"#series\" property=\"collection-type\">series</meta>\n")
            m.seriesIndex?.let { append("<meta refines=\"#series\" property=\"group-position\">${it.toString().removeSuffix(".0")}</meta>\n") }
            append("<meta name=\"calibre:series\" content=\"${esc(s)}\"/>\n")
            m.seriesIndex?.let { append("<meta name=\"calibre:series_index\" content=\"$it\"/>\n") }
        }
        append("<meta property=\"dcterms:modified\">${java.time.Instant.now().toString().substring(0, 19)}Z</meta>\n")
        append("<meta name=\"generator\" content=\"Worm Metamorphosis\"/>\n")
        cover?.let { append("<meta name=\"cover\" content=\"${it.id}\"/>\n") }
        if (fixedLayout) append("<meta property=\"rendition:layout\">pre-paginated</meta>\n<meta property=\"rendition:spread\">none</meta>\n")
        append("</metadata>\n<manifest>\n")
        append("<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>\n")
        append("<item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>\n")
        (sections + resources).forEach { append("<item id=\"${it.id}\" href=\"${esc(it.href)}\" media-type=\"${it.mime}\"${it.props?.let { p -> " properties=\"$p\"" } ?: ""}/>\n") }
        append("</manifest>\n<spine toc=\"ncx\">\n")
        sections.forEach { append("<itemref idref=\"${it.id}\"/>\n") }
        append("</spine>\n</package>\n")
    }

    private fun nav(m: EpubMeta, toc: List<TocItem>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>${esc(m.title)}</title></head>
<body><nav epub:type="toc" id="toc"><h1>Contents</h1>
""")
        // Nested <ol>s from a flat list with depths.
        var depth = -1
        toc.forEach { t ->
            val d = t.depth.coerceIn(0, depth + 1)
            when {
                d > depth -> append("<ol>")
                d == depth -> append("</li>")
                else -> { repeat(depth - d) { append("</li></ol>") }; append("</li>") }
            }
            append("<li><a href=\"${esc(t.href)}\">${esc(t.label.ifBlank { "•" })}</a>")
            depth = d
        }
        if (depth >= 0) { append("</li>"); repeat(depth) { append("</ol></li>") }; append("</ol>") }
        append("\n</nav></body></html>\n")
    }

    private fun ncx(m: EpubMeta, toc: List<TocItem>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
<head><meta name="dtb:uid" content="${esc(m.identifier)}"/></head>
<docTitle><text>${esc(m.title)}</text></docTitle>
<navMap>
""")
        var depth = -1
        toc.forEachIndexed { i, t ->
            val d = t.depth.coerceIn(0, depth + 1)
            if (d <= depth) repeat(depth - d + 1) { append("</navPoint>\n") }
            append("<navPoint id=\"n$i\" playOrder=\"${i + 1}\"><navLabel><text>${esc(t.label.ifBlank { "•" })}</text></navLabel><content src=\"${esc(t.href)}\"/>\n")
            depth = d
        }
        repeat(depth + 1) { append("</navPoint>\n") }
        append("</navMap>\n</ncx>\n")
    }

    companion object {
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
            .filter { it == '\n' || it == '\t' || it >= ' ' }

        /** Wraps body HTML (already XHTML-safe) in a full document. */
        fun page(title: String, body: String, lang: String = "en", css: String? = null) = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="$lang" lang="$lang"><head><meta charset="utf-8"/><title>${esc(title)}</title>${css?.let { "<style>$it</style>" } ?: ""}</head>
<body>
$body
</body></html>
"""
    }
}
