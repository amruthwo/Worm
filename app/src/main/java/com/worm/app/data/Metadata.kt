package com.worm.app.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.zip.ZipFile

/** What we could learn from a file before showing it in the library. */
data class Meta(
    val title: String? = null,
    val authors: List<String> = emptyList(),
    val narrators: List<String> = emptyList(),
    val series: String? = null,
    val seriesIndex: Float? = null,
    val description: String? = null,
    val language: String? = null,
    val durationMs: Long = 0,
    val cover: ByteArray? = null,
)

object Hashes {
    /**
     * KOReader's "partial MD5": 1 KB samples at 0, 1K, 4K, 16K … 1G. KOSync and Readest identify
     * books by this, so using it as our id makes progress sync line up with them.
     */
    fun partialMd5(file: File): String {
        val md = MessageDigest.getInstance("MD5")
        RandomAccessFile(file, "r").use { raf ->
            val buf = ByteArray(1024)
            for (i in -1..10) {
                val offset = if (i < 0) 0L else 1024L shl (2 * i)
                if (offset >= raf.length()) break
                raf.seek(offset)
                val n = raf.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().hex()
    }

    fun md5(text: String): String = MessageDigest.getInstance("MD5").digest(text.toByteArray()).hex()
    fun sha1(text: String): String = MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).hex()

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
}

object Metadata {

    fun ebook(file: File): Meta = runCatching {
        when (file.extension.lowercase()) {
            "epub" -> epub(file)
            "mobi", "azw", "azw3", "kf8", "prc" -> mobi(file)
            "fb2" -> file.inputStream().use { fb2(it) }
            "fbz" -> ZipFile(file).use { z ->
                z.entries().asSequence().firstOrNull { it.name.endsWith(".fb2") }?.let { z.getInputStream(it).use(::fb2) } ?: Meta()
            }
            "cbz" -> cbz(file)
            else -> Meta()
        }
    }.getOrElse { Meta() }

    // ---------------------------------------------------------------- EPUB

    private fun epub(file: File): Meta = ZipFile(file).use { zip ->
        val container = zip.getEntry("META-INF/container.xml") ?: return Meta()
        var opfPath: String? = null
        parse(zip.getInputStream(container)) { p ->
            if (p.eventType == XmlPullParser.START_TAG && p.name == "rootfile" && opfPath == null) {
                opfPath = p.getAttributeValue(null, "full-path")
            }
        }
        val opfName = opfPath ?: return Meta()
        val opfEntry = zip.getEntry(opfName) ?: return Meta()
        val base = opfName.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }

        var title: String? = null
        val authors = mutableListOf<String>()
        val narrators = mutableListOf<String>()
        var series: String? = null
        var seriesIndex: Float? = null
        var description: String? = null
        var language: String? = null
        var coverId: String? = null
        var coverHref: String? = null
        val manifest = mutableMapOf<String, String>()
        // EPUB3 refinements: <meta refines="#id" property="role">nrt</meta>
        val roles = mutableMapOf<String, String>()
        val creatorsById = mutableMapOf<String, String>()
        var collectionId: String? = null
        val groupPositions = mutableMapOf<String, String>()

        parse(zip.getInputStream(opfEntry)) { p ->
            if (p.eventType != XmlPullParser.START_TAG) return@parse
            when (p.name.substringAfter(':')) {
                "title" -> if (title == null && p.prefixOrDc()) title = p.nextText().trim()
                "creator" -> if (p.prefixOrDc()) {
                    val role = p.attr("role")
                    val id = p.getAttributeValue(null, "id")
                    val name = p.nextText().trim()
                    if (id != null) creatorsById[id] = name
                    for ((person, r) in People.split(name)) {
                        val effective = r ?: role
                        if (effective == "nrt") narrators += person else if (effective == null || effective == "aut") authors += person
                    }
                }
                "description" -> if (p.prefixOrDc()) description = p.nextText().trim()
                "language" -> if (p.prefixOrDc() && language == null) language = p.nextText().trim()
                "meta" -> {
                    val name = p.getAttributeValue(null, "name")
                    val content = p.getAttributeValue(null, "content")
                    val property = p.getAttributeValue(null, "property")
                    val refines = p.getAttributeValue(null, "refines")?.removePrefix("#")
                    when {
                        name == "cover" -> coverId = content
                        name == "calibre:series" -> series = content
                        name == "calibre:series_index" -> seriesIndex = content?.toFloatOrNull()
                        property == "belongs-to-collection" -> {
                            val id = p.getAttributeValue(null, "id")
                            val text = p.nextText().trim()
                            if (series == null) { series = text; collectionId = id }
                        }
                        property == "group-position" && refines != null -> groupPositions[refines] = p.nextText().trim()
                        property == "role" && refines != null -> roles[refines] = p.nextText().trim()
                    }
                }
                "item" -> {
                    val id = p.getAttributeValue(null, "id") ?: return@parse
                    val href = p.getAttributeValue(null, "href") ?: return@parse
                    manifest[id] = href
                    if (p.getAttributeValue(null, "properties")?.split(' ')?.contains("cover-image") == true) coverHref = href
                }
            }
        }
        roles.forEach { (id, role) ->
            val name = creatorsById[id] ?: return@forEach
            if (role == "nrt") People.names(name).forEach { n -> authors.remove(n); if (n !in narrators) narrators += n }
        }
        if (seriesIndex == null) collectionId?.let { seriesIndex = groupPositions[it]?.toFloatOrNull() }

        val href = coverHref ?: coverId?.let { manifest[it] }
            ?: manifest.entries.firstOrNull { it.key.contains("cover", true) && it.value.isImage() }?.value
        val cover = href?.let { h ->
            val path = normalize(base + decodeHref(h))
            zip.getEntry(path)?.let { e -> zip.getInputStream(e).use { it.readBytes() } }
        }
        Meta(title, authors, narrators, series, seriesIndex, description?.stripHtml(), language, cover = cover)
    }

    private fun XmlPullParser.prefixOrDc() = prefix == null || prefix == "dc" || namespace?.contains("purl.org/dc") == true
    private fun XmlPullParser.attr(local: String): String? =
        (0 until attributeCount).firstOrNull { getAttributeName(it).substringAfter(':') == local }?.let { getAttributeValue(it) }

    private fun String.isImage() = substringAfterLast('.').lowercase() in setOf("jpg", "jpeg", "png", "gif", "webp")
    private fun decodeHref(h: String) = runCatching { java.net.URLDecoder.decode(h, "UTF-8") }.getOrDefault(h)
    private fun normalize(path: String): String {
        val out = ArrayDeque<String>()
        path.split('/').forEach { seg ->
            when (seg) { "", "." -> Unit; ".." -> out.removeLastOrNull(); else -> out.addLast(seg) }
        }
        return out.joinToString("/")
    }

    fun String.stripHtml(): String = replace(Regex("<[^>]+>"), " ").replace("&nbsp;", " ").replace("&amp;", "&")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
        .replace(Regex("\\s+"), " ").trim()

    private fun parse(input: InputStream, onEvent: (XmlPullParser) -> Unit) = input.use {
        val p = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }.newPullParser()
        p.setInput(it, null)
        while (p.next() != XmlPullParser.END_DOCUMENT) onEvent(p)
    }

    // ---------------------------------------------------------- MOBI / AZW3

    /** Reads the PalmDB + MOBI header + EXTH records for title, author and cover. */
    private fun mobi(file: File): Meta = RandomAccessFile(file, "r").use { raf ->
        fun u16(at: Long): Int { raf.seek(at); return raf.readUnsignedShort() }
        fun u32(at: Long): Long { raf.seek(at); return raf.readInt().toLong() and 0xFFFFFFFFL }
        val recordCount = u16(76)
        val offsets = LongArray(recordCount) { u32(78L + it * 8) }
        val r0 = offsets[0]
        raf.seek(r0 + 16)
        val magic = ByteArray(4).also(raf::readFully)
        if (String(magic) != "MOBI") return Meta()
        val headerLen = u32(r0 + 20)
        val fullNameOffset = u32(r0 + 84)
        val fullNameLen = u32(r0 + 88)
        val firstImage = u32(r0 + 108)
        val exthFlags = u32(r0 + 128)
        val title0 = runCatching {
            raf.seek(r0 + fullNameOffset); String(ByteArray(fullNameLen.toInt()).also(raf::readFully), Charsets.UTF_8)
        }.getOrNull()

        var title: String? = null
        val authors = mutableListOf<String>()
        var description: String? = null
        var coverOffset: Long? = null
        var language: String? = null
        if (exthFlags and 0x40L != 0L) {
            val exth = r0 + 16 + headerLen
            raf.seek(exth)
            if (String(ByteArray(4).also(raf::readFully)) == "EXTH") {
                val count = u32(exth + 8)
                var pos = exth + 12
                repeat(count.toInt()) {
                    val type = u32(pos)
                    val len = u32(pos + 4)
                    val data = ByteArray((len - 8).toInt().coerceAtLeast(0)).also { raf.seek(pos + 8); raf.readFully(it) }
                    when (type.toInt()) {
                        100 -> authors += People.split(String(data, Charsets.UTF_8)).map { it.first }
                        103 -> description = String(data, Charsets.UTF_8).stripHtml()
                        503 -> title = String(data, Charsets.UTF_8).trim()
                        524 -> language = String(data, Charsets.UTF_8).trim()
                        201 -> if (data.size >= 4) coverOffset = data.toU32()
                    }
                    pos += len
                }
            }
        }
        val cover = coverOffset?.let { off ->
            val idx = (firstImage + off).toInt()
            if (idx in 0 until recordCount - 1) {
                val start = offsets[idx]
                val end = offsets[idx + 1]
                ByteArray((end - start).toInt()).also { raf.seek(start); raf.readFully(it) }
            } else null
        }
        Meta(title ?: title0, authors, description = description, language = language, cover = cover)
    }

    private fun ByteArray.toU32(): Long =
        ((this[0].toLong() and 0xFF) shl 24) or ((this[1].toLong() and 0xFF) shl 16) or
            ((this[2].toLong() and 0xFF) shl 8) or (this[3].toLong() and 0xFF)

    // ----------------------------------------------------------------- FB2

    private fun fb2(input: InputStream): Meta {
        var title: String? = null
        val authors = mutableListOf<String>()
        var series: String? = null
        var seriesIndex: Float? = null
        var coverId: String? = null
        var cover: ByteArray? = null
        var inTitleInfo = false
        var first = ""; var last = ""; var inAuthor = false
        parse(input) { p ->
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "title-info" -> inTitleInfo = true
                    "book-title" -> if (inTitleInfo) title = p.nextText().trim()
                    "author" -> if (inTitleInfo) { inAuthor = true; first = ""; last = "" }
                    "first-name" -> if (inAuthor) first = p.nextText().trim()
                    "last-name" -> if (inAuthor) last = p.nextText().trim()
                    "sequence" -> if (inTitleInfo) {
                        series = p.getAttributeValue(null, "name")
                        seriesIndex = p.getAttributeValue(null, "number")?.toFloatOrNull()
                    }
                    "image" -> if (inTitleInfo && coverId == null) {
                        coverId = (0 until p.attributeCount).firstOrNull { p.getAttributeName(it).endsWith("href") }
                            ?.let { p.getAttributeValue(it).removePrefix("#") }
                    }
                    "binary" -> if (cover == null && p.getAttributeValue(null, "id") == coverId) {
                        cover = runCatching { android.util.Base64.decode(p.nextText(), android.util.Base64.DEFAULT) }.getOrNull()
                    }
                }
                XmlPullParser.END_TAG -> when (p.name) {
                    "title-info" -> inTitleInfo = false
                    "author" -> if (inAuthor) { inAuthor = false; "$first $last".trim().takeIf { it.isNotEmpty() }?.let(authors::add) }
                }
            }
        }
        return Meta(title, authors, series = series, seriesIndex = seriesIndex, cover = cover)
    }

    // ----------------------------------------------------------------- CBZ

    private fun cbz(file: File): Meta = ZipFile(file).use { z ->
        val first = z.entries().asSequence().filter { !it.isDirectory && it.name.isImage() }.minByOrNull { it.name.lowercase() }
        Meta(cover = first?.let { e -> z.getInputStream(e).use { it.readBytes() } })
    }

    // --------------------------------------------------------------- audio

    fun audio(file: File, withCover: Boolean = true): Meta = runCatching {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.absolutePath)
            fun k(key: Int) = r.extractMetadata(key)?.trim()?.takeIf { it.isNotEmpty() }
            val album = k(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            val title = k(MediaMetadataRetriever.METADATA_KEY_TITLE)
            val artist = k(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST) ?: k(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?: k(MediaMetadataRetriever.METADATA_KEY_AUTHOR)
            // Audiobook taggers usually put the narrator in "composer".
            val narrator = k(MediaMetadataRetriever.METADATA_KEY_COMPOSER)?.takeIf { it != artist }
            Meta(
                title = if (file.extension.equals("m4b", true)) album ?: title else title,
                authors = artist?.splitPeople().orEmpty(),
                narrators = narrator?.splitPeople().orEmpty(),
                durationMs = k(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0,
                cover = if (withCover) r.embeddedPicture else null,
                series = album?.takeIf { file.extension.equals("m4b", true).not() },
            )
        } finally {
            runCatching { r.release() }
        }
    }.getOrElse { Meta() }

    private fun String.splitPeople() = People.split(this).map { it.first }

    /** Cover image for an audiobook folder: cover.jpg/folder.jpg or similar. */
    fun folderCover(dir: File): ByteArray? {
        val imgs = dir.listFiles()?.filter { it.isFile && it.name.substringAfterLast('.').lowercase() in setOf("jpg", "jpeg", "png", "webp") }
            .orEmpty()
        val preferred = imgs.firstOrNull { it.nameWithoutExtension.lowercase() in setOf("cover", "folder", "front", "album", "albumart") }
            ?: imgs.maxByOrNull { it.length() }
        return preferred?.readBytes()
    }

    /** Shrinks a cover to at most [max] px on its long side and saves it as JPEG. */
    fun saveCover(bytes: ByteArray, out: File, max: Int = 600): Boolean = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return false
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= max) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return false
        val scale = max.toFloat() / maxOf(bmp.width, bmp.height)
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
        out.parentFile?.mkdirs()
        out.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        true
    }.getOrDefault(false)
}


/** Tidies people's names from messy tags: "O'Neill, Tom; Piepenbring, Dan (CON)" → Tom O'Neill, Dan Piepenbring. */
object People {
    private val roleCode = Regex("""\s*[\(\[]\s*([A-Za-z .]{2,20})\s*[\)\]]\s*$""")

    /** Returns (name, role) pairs; role is "nrt" for narrators, "aut" for authors, "other" for editors etc., null if unknown. */
    fun split(raw: String): List<Pair<String, String?>> {
        val text = raw.trim()
        if (text.isEmpty()) return emptyList()
        val parts: List<String> = when {
            ';' in text -> text.split(';')
            Regex("""\s(&|and)\s|/""").containsMatchIn(text) -> text.split(Regex("""\s+(?:&|and)\s+|\s*/\s*"""))
            // "Corey, James S. A." is one person written surname-first; "A, B, C" is a list.
            text.count { it == ',' } == 1 && !text.substringBefore(',').trim().contains(' ') -> listOf(text)
            else -> text.split(',')
        }
        return parts.mapNotNull { part ->
            var p = part.trim()
            var role: String? = null
            roleCode.find(p)?.let { m ->
                role = when (m.groupValues[1].trim().lowercase().replace(".", "")) {
                    "nrt", "narrator", "reader", "read by" -> "nrt"
                    "aut", "author" -> "aut"
                    else -> "other"
                }
                p = p.removeRange(m.range).trim()
            }
            if (p.count { it == ',' } == 1) {
                val (last, first) = p.split(',').map { it.trim() }
                if (first.isNotEmpty() && !last.contains(' ')) p = "$first $last"
            }
            p.takeIf { it.isNotEmpty() }?.let { it to role }
        }
    }

    fun names(raw: String): List<String> = split(raw).filter { it.second != "other" }.map { it.first }
}
