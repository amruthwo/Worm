package com.worm.app.data

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * Adds audiobook chapters, tags and cover art to an MP4/M4A file, producing an .m4b.
 *
 * - Chapters: Nero `chpl` box in moov/udta (read by ffmpeg/ffprobe, Audiobookshelf, Worm and most
 *   audiobook players).
 * - Tags: iTunes-style moov/udta/meta/ilst (title, artist, album, composer = narrator, genre,
 *   media type = audiobook, description, cover).
 * - Brand: ftyp becomes "M4B ".
 *
 * The audio data is copied byte-for-byte; if moov sits before mdat, every chunk offset is shifted
 * to account for the bigger header.
 */
object Mp4Tagger {

    data class Tags(
        val title: String,
        val artist: String?,
        val narrator: String?,
        val album: String? = title,
        val description: String? = null,
        val year: String? = null,
        val series: String? = null,
        val seriesIndex: Float? = null,
        val cover: ByteArray? = null,
    )

    private class Box(val type: String, val start: Long, val size: Long, val header: Int)

    fun write(src: File, out: File, tags: Tags, chapters: List<Chapter>) {
        RandomAccessFile(src, "r").use { raf ->
            val top = children(raf, 0, raf.length())
            val moov = top.first { it.type == "moov" }
            val oldMoov = ByteArray(moov.size.toInt()).also { raf.seek(moov.start); raf.readFully(it) }
            val newFtyp = ftyp()
            var newMoov = rebuildMoov(oldMoov, tags, chapters)

            // Where every top-level box lands in the new file.
            fun layout(moovBytes: Int): Map<Box, Long> {
                var pos = 0L
                return top.associateWith { b ->
                    val at = pos
                    pos += when (b.type) { "ftyp" -> newFtyp.size.toLong(); "moov" -> moovBytes.toLong(); else -> b.size }
                    at
                }
            }
            val newStart = layout(newMoov.size)
            val mdats = top.filter { it.type == "mdat" }
            val shift = { offset: Long ->
                val box = mdats.firstOrNull { offset >= it.start && offset < it.start + it.size } ?: top.first { offset >= it.start && offset < it.start + it.size }
                offset + (newStart.getValue(box) - box.start)
            }
            newMoov = patchChunkOffsets(newMoov, shift)

            out.parentFile?.mkdirs()
            RandomAccessFile(out, "rw").use { w ->
                w.setLength(0)
                val buf = ByteArray(1 shl 20)
                for (b in top) {
                    when (b.type) {
                        "ftyp" -> w.write(newFtyp)
                        "moov" -> w.write(newMoov)
                        else -> {
                            raf.seek(b.start)
                            var left = b.size
                            while (left > 0) {
                                val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                                if (n < 0) break
                                w.write(buf, 0, n); left -= n
                            }
                        }
                    }
                }
            }
        }
    }

    // --------------------------------------------------------------- boxes

    private fun children(raf: RandomAccessFile, from: Long, to: Long): List<Box> {
        val out = mutableListOf<Box>()
        var pos = from
        while (pos + 8 <= to) {
            raf.seek(pos)
            var size = raf.readInt().toLong() and 0xFFFFFFFFL
            val type = String(ByteArray(4).also(raf::readFully), Charsets.ISO_8859_1)
            var header = 8
            if (size == 1L) { size = raf.readLong(); header = 16 } else if (size == 0L) size = to - pos
            if (size < header) break
            out += Box(type, pos, size, header)
            pos += size
        }
        return out
    }

    private class Node(val type: String, val payload: ByteArray, val kids: List<Node>?)

    private val CONTAINERS = setOf("moov", "trak", "mdia", "minf", "stbl", "edts", "dinf", "udta")

    private fun parse(bytes: ByteArray, from: Int, to: Int): List<Node> {
        val bb = ByteBuffer.wrap(bytes)
        val out = mutableListOf<Node>()
        var pos = from
        while (pos + 8 <= to) {
            var size = bb.getInt(pos).toLong() and 0xFFFFFFFFL
            val type = String(bytes, pos + 4, 4, Charsets.ISO_8859_1)
            var header = 8
            if (size == 1L) { size = bb.getLong(pos + 8); header = 16 } else if (size == 0L) size = (to - pos).toLong()
            val end = (pos + size).toInt()
            out += if (type in CONTAINERS) Node(type, ByteArray(0), parse(bytes, pos + header, end))
            else Node(type, bytes.copyOfRange(pos + header, end), null)
            pos = end
        }
        return out
    }

    private fun serialize(n: Node): ByteArray {
        val body = n.kids?.let { k -> ByteArrayOutputStream().apply { k.forEach { write(serialize(it)) } }.toByteArray() } ?: n.payload
        return box(n.type, body)
    }

    private fun box(type: String, body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(body.size + 8)
        DataOutputStream(out).apply { writeInt(body.size + 8); write(type.toByteArray(Charsets.ISO_8859_1)); write(body) }
        return out.toByteArray()
    }

    private fun bytes(block: DataOutputStream.() -> Unit): ByteArray =
        ByteArrayOutputStream().also { DataOutputStream(it).block() }.toByteArray()

    private fun ftyp() = box("ftyp", bytes {
        write("M4B ".toByteArray()); writeInt(0)
        listOf("M4B ", "M4A ", "mp42", "isom").forEach { write(it.toByteArray()) }
    })

    private fun rebuildMoov(moovBox: ByteArray, tags: Tags, chapters: List<Chapter>): ByteArray {
        val moov = parse(moovBox, 0, moovBox.size).first()
        val kids = moov.kids!!.filter { it.type != "udta" } + Node("udta", ByteArray(0), listOfNotNull(
            chpl(chapters).takeIf { chapters.size > 1 }?.let { Node("chpl", it, null) },
            Node("meta", meta(tags), null),
        ))
        return serialize(Node("moov", ByteArray(0), kids))
    }

    /** Nero chapters: times in 100 ns units, at most 255 entries. */
    private fun chpl(chapters: List<Chapter>): ByteArray = bytes {
        val list = if (chapters.size <= 255) chapters else chapters.chunked((chapters.size + 254) / 255).map { it.first() }
        writeByte(1); write(byteArrayOf(0, 0, 0)); writeInt(0)
        writeByte(list.size)
        list.forEach { c ->
            writeLong(c.startMs * 10_000)
            val t = c.title.toByteArray(Charsets.UTF_8).let { if (it.size > 255) it.copyOf(255) else it }
            writeByte(t.size); write(t)
        }
    }

    private fun meta(t: Tags): ByteArray = bytes {
        writeInt(0) // full box version/flags
        write(box("hdlr", bytes { writeInt(0); writeInt(0); write("mdir".toByteArray()); write("appl".toByteArray()); writeInt(0); writeInt(0); writeByte(0) }))
        val items = ByteArrayOutputStream()
        fun text(key: String, v: String?) { if (!v.isNullOrBlank()) items.write(item(key, 1, v.toByteArray(Charsets.UTF_8))) }
        text("©nam", t.title)
        text("©ART", t.artist)
        text("aART", t.artist)
        text("©alb", t.album ?: t.title)
        text("©wrt", t.narrator)
        text("©gen", "Audiobook")
        text("©day", t.year)
        text("desc", t.description?.take(255))
        text("ldes", t.description)
        t.series?.let { s -> text("©grp", s + (t.seriesIndex?.let { " #${it.toString().removeSuffix(".0")}" } ?: "")) }
        items.write(item("stik", 21, byteArrayOf(2))) // media type: audiobook
        t.cover?.let { c -> items.write(item("covr", if (c.size > 3 && c[0] == 0x89.toByte() && c[1] == 'P'.code.toByte()) 14 else 13, c)) }
        write(box("ilst", items.toByteArray()))
    }

    private fun item(key: String, dataType: Int, payload: ByteArray): ByteArray =
        box(key, box("data", bytes { writeInt(dataType); writeInt(0); write(payload) }))

    // ------------------------------------------------------- chunk offsets

    private fun patchChunkOffsets(moov: ByteArray, shift: (Long) -> Long): ByteArray {
        val bb = ByteBuffer.wrap(moov)
        fun walk(from: Int, to: Int) {
            var pos = from
            while (pos + 8 <= to) {
                val size = bb.getInt(pos).toLong() and 0xFFFFFFFFL
                val type = String(moov, pos + 4, 4, Charsets.ISO_8859_1)
                val end = (pos + size).toInt()
                when (type) {
                    in CONTAINERS -> walk(pos + 8, end)
                    "stco" -> {
                        val n = bb.getInt(pos + 12)
                        for (i in 0 until n) {
                            val at = pos + 16 + i * 4
                            val v = shift(bb.getInt(at).toLong() and 0xFFFFFFFFL)
                            check(v <= 0xFFFFFFFFL) { "File too large for 32-bit offsets" }
                            bb.putInt(at, v.toInt())
                        }
                    }
                    "co64" -> {
                        val n = bb.getInt(pos + 12)
                        for (i in 0 until n) { val at = pos + 16 + i * 8; bb.putLong(at, shift(bb.getLong(at))) }
                    }
                }
                pos = end
            }
        }
        walk(0, moov.size)
        return moov
    }
}
