package com.worm.app.data

import java.io.File
import java.io.RandomAccessFile

/**
 * Reads chapter markers from .m4b/.m4a files. Media3 doesn't expose them, so we parse the MP4
 * boxes ourselves. Two flavours exist in the wild and we read both:
 *  - QuickTime chapter tracks (a "text" track whose samples are chapter titles) — iTunes, most tools.
 *  - Nero "chpl" boxes in moov/udta — older converters, ffmpeg.
 */
object Mp4Chapters {

    private class Box(val type: String, val start: Long, val dataStart: Long, val end: Long)

    fun read(file: File): List<Chapter> = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val moov = children(raf, 0, raf.length()).firstOrNull { it.type == "moov" } ?: return emptyList()
            val moovKids = children(raf, moov.dataStart, moov.end)
            val duration = movieDurationMs(raf, moovKids)
            val fromTrack = textTrackChapters(raf, moovKids)
            val chapters = fromTrack.ifEmpty { neroChapters(raf, moovKids) }
            withEnds(chapters, duration)
        }
    }.getOrElse { emptyList() }

    private fun withEnds(raw: List<Pair<String, Long>>, durationMs: Long): List<Chapter> {
        val sorted = raw.sortedBy { it.second }
        return sorted.mapIndexed { i, (title, start) ->
            val end = sorted.getOrNull(i + 1)?.second ?: durationMs
            Chapter(title.ifBlank { "Chapter ${i + 1}" }, start, maxOf(end, start))
        }
    }

    private fun children(raf: RandomAccessFile, from: Long, to: Long): List<Box> {
        val out = mutableListOf<Box>()
        var pos = from
        while (pos + 8 <= to) {
            raf.seek(pos)
            var size = raf.readInt().toLong() and 0xFFFFFFFFL
            val type = String(ByteArray(4).also(raf::readFully), Charsets.ISO_8859_1)
            var header = 8L
            if (size == 1L) { size = raf.readLong(); header = 16 }
            else if (size == 0L) size = to - pos
            if (size < header) break
            out += Box(type, pos, pos + header, minOf(pos + size, to))
            pos += size
        }
        return out
    }

    private fun child(raf: RandomAccessFile, parent: Box, type: String) =
        children(raf, parent.dataStart, parent.end).firstOrNull { it.type == type }

    private fun movieDurationMs(raf: RandomAccessFile, moovKids: List<Box>): Long {
        val mvhd = moovKids.firstOrNull { it.type == "mvhd" } ?: return 0
        raf.seek(mvhd.dataStart)
        val version = raf.readUnsignedByte(); raf.skipBytes(3)
        return if (version == 1) {
            raf.skipBytes(16); val ts = raf.readInt().toLong() and 0xFFFFFFFFL; val d = raf.readLong()
            if (ts > 0) d * 1000 / ts else 0
        } else {
            raf.skipBytes(8); val ts = raf.readInt().toLong() and 0xFFFFFFFFL; val d = raf.readInt().toLong() and 0xFFFFFFFFL
            if (ts > 0) d * 1000 / ts else 0
        }
    }

    // ------------------------------------------------------------ Nero chpl

    private fun neroChapters(raf: RandomAccessFile, moovKids: List<Box>): List<Pair<String, Long>> {
        val udta = moovKids.firstOrNull { it.type == "udta" } ?: return emptyList()
        val chpl = child(raf, udta, "chpl") ?: return emptyList()
        raf.seek(chpl.dataStart)
        val version = raf.readUnsignedByte(); raf.skipBytes(3)
        if (version == 1) raf.skipBytes(4)
        val count = raf.readUnsignedByte()
        return List(count) {
            val start100ns = raf.readLong()
            val len = raf.readUnsignedByte()
            val title = String(ByteArray(len).also(raf::readFully), Charsets.UTF_8)
            title to start100ns / 10_000
        }
    }

    // --------------------------------------------------- QuickTime text track

    private fun textTrackChapters(raf: RandomAccessFile, moovKids: List<Box>): List<Pair<String, Long>> {
        val traks = moovKids.filter { it.type == "trak" }
        // Prefer the track an audio track points at via tref/chap; fall back to any text track.
        val chapIds = traks.mapNotNull { t -> child(raf, t, "tref")?.let { child(raf, it, "chap") } }.flatMap { box ->
            raf.seek(box.dataStart)
            List(((box.end - box.dataStart) / 4).toInt()) { raf.readInt() }
        }.toSet()
        val candidates = traks.filter { t ->
            val id = trackId(raf, t)
            val handler = handlerType(raf, t)
            (id in chapIds) || (chapIds.isEmpty() && handler == "text")
        }
        val trak = candidates.firstOrNull() ?: return emptyList()
        val mdia = child(raf, trak, "mdia") ?: return emptyList()
        val timescale = mdhdTimescale(raf, mdia).takeIf { it > 0 } ?: return emptyList()
        val stbl = child(raf, mdia, "minf")?.let { child(raf, it, "stbl") } ?: return emptyList()

        val durations = stts(raf, stbl)
        val sizes = stsz(raf, stbl)
        val offsets = sampleOffsets(raf, stbl, sizes)
        val n = minOf(sizes.size, offsets.size)
        var t = 0L
        val out = mutableListOf<Pair<String, Long>>()
        for (i in 0 until n) {
            val start = t * 1000 / timescale
            t += durations.getOrElse(i) { 0 }
            raf.seek(offsets[i])
            if (sizes[i] < 2) continue
            val len = raf.readUnsignedShort().coerceAtMost(sizes[i].toInt() - 2)
            val bytes = ByteArray(len).also(raf::readFully)
            val title = if (len >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) String(bytes, Charsets.UTF_16)
            else String(bytes, Charsets.UTF_8)
            out += title.trim() to start
        }
        return out
    }

    private fun trackId(raf: RandomAccessFile, trak: Box): Int {
        val tkhd = child(raf, trak, "tkhd") ?: return -1
        raf.seek(tkhd.dataStart)
        val version = raf.readUnsignedByte(); raf.skipBytes(3)
        raf.skipBytes(if (version == 1) 16 else 8)
        return raf.readInt()
    }

    private fun handlerType(raf: RandomAccessFile, trak: Box): String? {
        val hdlr = child(raf, trak, "mdia")?.let { child(raf, it, "hdlr") } ?: return null
        raf.seek(hdlr.dataStart + 8)
        return String(ByteArray(4).also(raf::readFully), Charsets.ISO_8859_1)
    }

    private fun mdhdTimescale(raf: RandomAccessFile, mdia: Box): Long {
        val mdhd = child(raf, mdia, "mdhd") ?: return 0
        raf.seek(mdhd.dataStart)
        val version = raf.readUnsignedByte(); raf.skipBytes(3)
        raf.skipBytes(if (version == 1) 16 else 8)
        return raf.readInt().toLong() and 0xFFFFFFFFL
    }

    private fun stts(raf: RandomAccessFile, stbl: Box): List<Long> {
        val box = child(raf, stbl, "stts") ?: return emptyList()
        raf.seek(box.dataStart + 4)
        val entries = raf.readInt()
        val out = mutableListOf<Long>()
        repeat(entries) {
            val count = raf.readInt()
            val delta = raf.readInt().toLong() and 0xFFFFFFFFL
            repeat(count.coerceAtMost(100_000)) { out += delta }
        }
        return out
    }

    private fun stsz(raf: RandomAccessFile, stbl: Box): List<Long> {
        val box = child(raf, stbl, "stsz") ?: return emptyList()
        raf.seek(box.dataStart + 4)
        val fixed = raf.readInt().toLong() and 0xFFFFFFFFL
        val count = raf.readInt().coerceAtMost(100_000)
        return if (fixed != 0L) List(count) { fixed } else List(count) { raf.readInt().toLong() and 0xFFFFFFFFL }
    }

    private fun sampleOffsets(raf: RandomAccessFile, stbl: Box, sizes: List<Long>): List<Long> {
        val chunkOffsets: List<Long> = child(raf, stbl, "stco")?.let { b ->
            raf.seek(b.dataStart + 4); val n = raf.readInt(); List(n) { raf.readInt().toLong() and 0xFFFFFFFFL }
        } ?: child(raf, stbl, "co64")?.let { b ->
            raf.seek(b.dataStart + 4); val n = raf.readInt(); List(n) { raf.readLong() }
        } ?: return emptyList()
        // stsc: runs of (firstChunk, samplesPerChunk)
        val stsc = child(raf, stbl, "stsc")?.let { b ->
            raf.seek(b.dataStart + 4); val n = raf.readInt()
            List(n) { val first = raf.readInt(); val per = raf.readInt(); raf.readInt(); first to per }
        } ?: listOf(1 to 1)
        val out = mutableListOf<Long>()
        var sample = 0
        for (c in chunkOffsets.indices) {
            val chunkNo = c + 1
            val per = stsc.lastOrNull { it.first <= chunkNo }?.second ?: 1
            var off = chunkOffsets[c]
            repeat(per) {
                if (sample >= sizes.size) return out
                out += off
                off += sizes[sample]
                sample++
            }
        }
        return out
    }
}
