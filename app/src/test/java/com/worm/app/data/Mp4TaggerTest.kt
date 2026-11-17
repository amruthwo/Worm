package com.worm.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Round-trips real ffmpeg output through the tagger and checks it with ffprobe and our own reader. */
class Mp4TaggerTest {
    private fun ffmpegAvailable() = runCatching { ProcessBuilder("ffmpeg", "-version").start().waitFor() == 0 }.getOrDefault(false)

    private fun run(vararg cmd: String): String {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { out }
        return out
    }

    private fun check(faststart: Boolean) {
        val dir = File("build/tmp/tagger").apply { mkdirs() }
        val src = File(dir, "src-$faststart.m4a")
        val out = File(dir, "out-$faststart.m4b")
        val args = mutableListOf("ffmpeg", "-y", "-loglevel", "error", "-f", "lavfi", "-i", "sine=frequency=440:duration=30", "-c:a", "aac", "-b:a", "64k")
        if (faststart) args += listOf("-movflags", "+faststart")
        args += src.path
        run(*args.toTypedArray())
        val chapters = listOf(Chapter("Opening", 0, 10_000), Chapter("Middle bit", 10_000, 20_000), Chapter("Ending ✨", 20_000, 30_000))
        val png = File("../design/worm-icon-512.png").readBytes()
        Mp4Tagger.write(src, out, Mp4Tagger.Tags("Test Book", "Some Author", "A Narrator", description = "About it", cover = png), chapters)

        val probe = run("ffprobe", "-v", "error", "-show_chapters", "-show_format", "-of", "flat", out.path)
        assertTrue(probe, probe.contains("chapters.chapter.2.tags.title=\"Ending ✨\""))
        assertTrue(probe, probe.contains("format.tags.title=\"Test Book\""))
        assertTrue(probe, probe.contains("format.tags.composer=\"A Narrator\""))
        assertTrue(probe, probe.contains("format.tags.major_brand=\"M4B \""))
        // The audio must still decode end to end (bad chunk offsets would break this).
        run("ffmpeg", "-v", "error", "-xerror", "-i", out.path, "-f", "null", "-")
        val read = Mp4Chapters.read(out)
        assertEquals(listOf("Opening", "Middle bit", "Ending ✨"), read.map { it.title })
        assertEquals(10_000L, read[1].startMs)
    }

    @Test fun moovAtEnd() { assumeTrue(ffmpegAvailable()); check(faststart = false) }
    @Test fun moovAtStart() { assumeTrue(ffmpegAvailable()); check(faststart = true) }
}
