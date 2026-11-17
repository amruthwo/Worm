package com.worm.app.convert

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipFile

/** A book assembled in memory before it's written as an EPUB. */
data class DraftBook(
    val meta: EpubMeta,
    val chapters: List<Pair<String, String>>, // (title, XHTML body)
    val resources: List<Triple<String, String, ByteArray>> = emptyList(), // (href, mime, bytes)
    val coverHref: String? = null,
)

/** Writes a [DraftBook]: one section per chapter, contents built from the chapter titles. */
fun DraftBook.writeTo(out: File) {
    EpubWriter(out).use { w ->
        resources.forEach { (href, mime, bytes) -> w.addResource(href, mime, bytes, cover = href == coverHref) }
        val toc = chapters.map { (title, body) ->
            TocItem(title, w.addSection(EpubWriter.page(title, body, meta.language, BASE_CSS)))
        }
        w.finish(meta, toc)
    }
}

private const val BASE_CSS = "body{margin:0 4%;line-height:1.5} h1,h2,h3{line-height:1.25;margin:1.2em 0 .6em} p{margin:0;text-indent:1.4em} p.first,h1+p,h2+p,h3+p{text-indent:0} img{max-width:100%} blockquote{margin:1em 2em;font-style:italic} pre{white-space:pre-wrap}"

private fun esc(s: String) = EpubWriter.esc(s)

/** UTF-8 if it decodes cleanly (with or without BOM), else Windows-1252: covers most stray .txt files. */
fun decodeText(bytes: ByteArray): String {
    val body = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) bytes.copyOfRange(3, bytes.size) else bytes
    if (body.size >= 2 && body[0] == 0xFF.toByte() && body[1] == 0xFE.toByte()) return String(body, 2, body.size - 2, Charsets.UTF_16LE)
    if (body.size >= 2 && body[0] == 0xFE.toByte() && body[1] == 0xFF.toByte()) return String(body, 2, body.size - 2, Charsets.UTF_16BE)
    return try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(body)).toString()
    } catch (_: CharacterCodingException) {
        String(body, Charset.forName("windows-1252"))
    }
}

object TxtConverter {
    /** Lines that start a chapter: "Chapter 12", "CHAPTER TWELVE", "Part One", "Prologue", "Epilogue"… */
    private val heading = Regex(
        """^\s*((chapter|chapitre|kapitel|cap[ií]tulo|part|book|section)\s+([0-9ivxlcdm]+|[a-z\-]+)\b.{0,60}|prologue|epilogue|interlude|introduction|preface|foreword|afterword)\s*$""",
        RegexOption.IGNORE_CASE,
    )

    fun convert(text: String, title: String, author: String?): DraftBook {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        // Hard-wrapped text (no blank lines, lines ~60-80 chars) gets its lines re-joined into paragraphs.
        val blankRatio = lines.count { it.isBlank() }.toDouble() / lines.size.coerceAtLeast(1)
        val hardWrapped = blankRatio < 0.02 && lines.count { it.length in 55..90 } > lines.size * 0.5

        val chapters = mutableListOf<Pair<String, StringBuilder>>()
        var current = StringBuilder(); var currentTitle = title
        val para = StringBuilder()
        var first = true
        fun flushPara() {
            if (para.isBlank()) { para.clear(); return }
            current.append(if (first) "<p class=\"first\">" else "<p>").append(esc(para.toString().trim())).append("</p>\n")
            first = false
            para.clear()
        }
        fun startChapter(name: String) {
            flushPara()
            if (current.isNotBlank()) chapters += currentTitle to current
            current = StringBuilder(); currentTitle = name.trim(); first = true
            current.append("<h2>").append(esc(currentTitle)).append("</h2>\n")
        }
        for (line in lines) {
            when {
                heading.matches(line) && line.trim().length < 90 -> startChapter(line)
                line.isBlank() -> flushPara()
                hardWrapped -> { if (para.isNotEmpty()) para.append(' '); para.append(line.trim()) }
                blankRatio < 0.02 -> { para.append(line.trim()); flushPara() } // one paragraph per line
                else -> { if (para.isNotEmpty()) para.append(' '); para.append(line.trim()) }
            }
        }
        flushPara()
        if (current.isNotBlank()) chapters += currentTitle to current
        return DraftBook(EpubMeta(title, listOfNotNull(author)), splitHuge(chapters.map { it.first to it.second.toString() }))
    }
}

/** Very long chapters (whole novels with no headings) are split so readers stay snappy. */
internal fun splitHuge(chapters: List<Pair<String, String>>, max: Int = 250_000): List<Pair<String, String>> = chapters.flatMap { (t, body) ->
    if (body.length <= max) listOf(t to body)
    else {
        val parts = mutableListOf<String>(); var cur = StringBuilder()
        body.split("</p>\n").forEach { p -> if (cur.length > max) { parts += cur.toString(); cur = StringBuilder() }; if (p.isNotBlank()) cur.append(p).append("</p>\n") }
        if (cur.isNotBlank()) parts += cur.toString()
        parts.mapIndexed { i, b -> (if (i == 0) t else "$t (${i + 1})") to b }
    }
}

object MarkdownConverter {
    private fun inline(s: String): String = esc(s)
        .replace(Regex("""\*\*(.+?)\*\*|__(.+?)__""")) { "<strong>${it.groupValues[1].ifEmpty { it.groupValues[2] }}</strong>" }
        .replace(Regex("""(?<![\w*])\*(?!\s)(.+?)(?<!\s)\*|(?<!\w)_(?!\s)(.+?)(?<!\s)_(?!\w)""")) { "<em>${it.groupValues[1].ifEmpty { it.groupValues[2] }}</em>" }
        .replace(Regex("""`([^`]+)`""")) { "<code>${it.groupValues[1]}</code>" }
        .replace(Regex("""\[([^\]]+)\]\((https?://[^)\s]+)\)""")) { "<a href=\"${it.groupValues[2]}\">${it.groupValues[1]}</a>" }

    fun convert(text: String, title: String, author: String?): DraftBook {
        val lines = text.replace("\r\n", "\n").split('\n')
        // Split chapters at the top heading level actually used (# if present, else ##).
        val top = if (lines.any { it.startsWith("# ") }) 1 else 2
        val chapters = mutableListOf<Pair<String, String>>()
        var cur = StringBuilder(); var curTitle = title
        val para = StringBuilder(); var list: String? = null
        fun flush() {
            if (para.isNotBlank()) { cur.append("<p>").append(inline(para.toString().trim())).append("</p>\n"); para.clear() }
            list?.let { cur.append("</$it>\n"); list = null }
        }
        for (line in lines) {
            val h = Regex("""^(#{1,6})\s+(.*)$""").find(line)
            when {
                h != null -> {
                    flush()
                    val level = h.groupValues[1].length
                    if (level == top) { if (cur.isNotBlank()) chapters += curTitle to cur.toString(); cur = StringBuilder(); curTitle = h.groupValues[2].trim() }
                    cur.append("<h${level.coerceAtMost(3)}>").append(inline(h.groupValues[2].trim())).append("</h${level.coerceAtMost(3)}>\n")
                }
                Regex("""^\s*[-*+]\s+""").containsMatchIn(line) -> {
                    if (para.isNotBlank()) flush()
                    if (list != "ul") { list?.let { cur.append("</$it>\n") }; cur.append("<ul>\n"); list = "ul" }
                    cur.append("<li>").append(inline(line.replaceFirst(Regex("""^\s*[-*+]\s+"""), ""))).append("</li>\n")
                }
                Regex("""^\s*\d+[.)]\s+""").containsMatchIn(line) -> {
                    if (para.isNotBlank()) flush()
                    if (list != "ol") { list?.let { cur.append("</$it>\n") }; cur.append("<ol>\n"); list = "ol" }
                    cur.append("<li>").append(inline(line.replaceFirst(Regex("""^\s*\d+[.)]\s+"""), ""))).append("</li>\n")
                }
                line.startsWith(">") -> { flush(); cur.append("<blockquote><p>").append(inline(line.removePrefix(">").trim())).append("</p></blockquote>\n") }
                Regex("""^\s*([-*_])(\s*\1){2,}\s*$""").matches(line) -> { flush(); cur.append("<hr/>\n") }
                line.isBlank() -> flush()
                else -> { if (list != null) flush(); if (para.isNotEmpty()) para.append(' '); para.append(line.trim()) }
            }
        }
        flush()
        if (cur.isNotBlank()) chapters += curTitle to cur.toString()
        return DraftBook(EpubMeta(title, listOfNotNull(author)), splitHuge(chapters))
    }
}

/** Word documents: paragraphs, headings (chapters at Heading 1 / Title), bold/italic, line breaks, images. */
object DocxConverter {
    private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    fun convert(file: File, fallbackTitle: String): DraftBook = ZipFile(file).use { z ->
        fun text(name: String) = z.getEntry(name)?.let { e -> z.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) } }
        // Relationship id → media path, for images.
        val rels = mutableMapOf<String, String>()
        text("word/_rels/document.xml.rels")?.let { xml ->
            Regex("""<Relationship\b[^>]*>""").findAll(xml).forEach { m ->
                val id = Regex("""Id="([^"]+)"""").find(m.value)?.groupValues?.get(1)
                val target = Regex("""Target="([^"]+)"""").find(m.value)?.groupValues?.get(1)
                if (id != null && target != null && "image" in m.value) rels[id] = "word/" + target.removePrefix("/word/").removePrefix("./")
            }
        }
        val core = text("docProps/core.xml").orEmpty()
        val title = Regex("""<dc:title>([^<]+)</dc:title>""").find(core)?.groupValues?.get(1)?.trim()?.ifBlank { null } ?: fallbackTitle
        val author = Regex("""<dc:creator>([^<]+)</dc:creator>""").find(core)?.groupValues?.get(1)?.trim()?.ifBlank { null }

        val resources = mutableListOf<Triple<String, String, ByteArray>>()
        val usedImages = mutableMapOf<String, String>()
        val chapters = mutableListOf<Pair<String, String>>()
        var cur = StringBuilder(); var curTitle = title

        val p = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        z.getInputStream(z.getEntry("word/document.xml") ?: error("Not a Word document")).use { input ->
            p.setInput(input, "UTF-8")
            var style: String? = null
            val para = StringBuilder()
            var bold = false; var italic = false
            var inRun = false
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                when (p.eventType) {
                    XmlPullParser.START_TAG -> when (p.name) {
                        "p" -> if (p.namespace == W) { para.clear(); style = null }
                        "pStyle" -> style = p.getAttributeValue(W, "val")
                        "r" -> { inRun = true; bold = false; italic = false }
                        "b" -> if (inRun && p.getAttributeValue(W, "val") != "0" && p.getAttributeValue(W, "val") != "false") bold = true
                        "i" -> if (inRun && p.getAttributeValue(W, "val") != "0" && p.getAttributeValue(W, "val") != "false") italic = true
                        "t" -> {
                            val t = esc(p.nextText())
                            para.append(if (bold) "<strong>" else "").append(if (italic) "<em>" else "").append(t)
                                .append(if (italic) "</em>" else "").append(if (bold) "</strong>" else "")
                        }
                        "br" -> para.append("<br/>")
                        "tab" -> para.append(" ")
                        "blip" -> {
                            val rid = (0 until p.attributeCount).firstOrNull { p.getAttributeName(it) == "embed" }?.let { p.getAttributeValue(it) }
                            val path = rid?.let(rels::get)
                            if (path != null) {
                                val href = usedImages.getOrPut(path) {
                                    val ext = path.substringAfterLast('.').lowercase()
                                    val h = "res/img${usedImages.size}.$ext"
                                    z.getEntry(path)?.let { e -> resources += Triple(h, mimeOf(ext), z.getInputStream(e).use { it.readBytes() }) }
                                    h
                                }
                                para.append("<img src=\"../$href\" alt=\"\"/>")
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> when (p.name) {
                        "r" -> inRun = false
                        "p" -> if (p.namespace == W) {
                            val s = style?.lowercase().orEmpty()
                            val body = para.toString().trim()
                            val level = when {
                                s == "title" || s.endsWith("heading1") || s == "heading1" -> 1
                                s.endsWith("heading2") || s == "heading2" -> 2
                                s.startsWith("heading") -> 3
                                else -> 0
                            }
                            if (level == 1 && body.isNotEmpty()) {
                                if (cur.isNotBlank()) chapters += curTitle to cur.toString()
                                cur = StringBuilder(); curTitle = body.replace(Regex("<[^>]+>"), "")
                            }
                            when {
                                body.isEmpty() -> Unit
                                level > 0 -> cur.append("<h$level>").append(body).append("</h$level>\n")
                                else -> cur.append("<p>").append(body).append("</p>\n")
                            }
                        }
                    }
                }
            }
        }
        if (cur.isNotBlank()) chapters += curTitle to cur.toString()
        DraftBook(EpubMeta(title, listOfNotNull(author)), splitHuge(chapters), resources)
    }

    fun mimeOf(ext: String) = when (ext) {
        "png" -> "image/png"; "gif" -> "image/gif"; "svg" -> "image/svg+xml"; "webp" -> "image/webp"; "bmp" -> "image/bmp"
        else -> "image/jpeg"
    }
}
