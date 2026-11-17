package com.worm.app.opds

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Parses saved real-world feeds (OPDS_SAMPLES dir): Gutenberg (OPDS 1 / Atom) and the OPDS 2 test feed. */
class OpdsParseTest {
    private val dir = System.getenv("OPDS_SAMPLES")?.let(::File)

    @Test fun gutenbergAtom() {
        val f = File(dir, "gut-popular.xml"); assumeTrue(f.isFile)
        val feed = Opds.parseAtom(f.readText(), "https://www.gutenberg.org/ebooks/search.opds/?sort_order=downloads")
        println("gutenberg: title='${feed.title}' entries=${feed.entries.size} nav=${feed.entries.count { it.feed != null }} acq=${feed.entries.count { it.acquisitions.isNotEmpty() }} covers=${feed.entries.count { it.cover != null }} next=${feed.next != null} search=${feed.search}")
        assertTrue(feed.entries.size > 5)
        assertTrue(feed.entries.all { it.title.isNotBlank() })
        assertTrue(feed.entries.any { it.feed != null || it.acquisitions.isNotEmpty() })
        assertTrue(feed.entries.mapNotNull { it.feed }.all { it.startsWith("https://www.gutenberg.org/") })
    }

    @Test fun opds2Json() {
        val f = File(dir, "opds2.json"); assumeTrue(f.isFile)
        val feed = Opds.parseJson(f.readText(), "https://test.opds.io/2.0/home.json")
        println("opds2: title='${feed.title}' entries=${feed.entries.size} nav=${feed.entries.count { it.feed != null }} acq=${feed.entries.count { it.acquisitions.isNotEmpty() }} covers=${feed.entries.count { it.cover != null }} search=${feed.search}")
        assertTrue(feed.entries.isNotEmpty())
    }
}
