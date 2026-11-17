package com.worm.app.convert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.w3c.dom.Document
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Converts sample TXT/MD/DOCX files (WORM_SAMPLES) and checks the EPUB structure: mimetype first
 * and stored, every XHTML/OPF/NCX file well-formed XML, manifest entries present, spine non-empty.
 * For full validation, run W3C EPUBCheck on the files in build/tmp/epubs.
 */
class ConvertersTest {
    private val samples = System.getenv("WORM_SAMPLES")?.let(::File)
    private val out = File("build/tmp/epubs").apply { mkdirs() }

    private fun xml(bytes: ByteArray): Document =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(bytes.inputStream())

    private fun validate(epub: File) {
        ZipFile(epub).use { z ->
            val first = z.entries().nextElement()
            assertEquals("mimetype", first.name)
            assertEquals(java.util.zip.ZipEntry.STORED, first.method)
            val names = z.entries().asSequence().map { it.name }.toSet()
            names.filter { it.endsWith(".xhtml") || it.endsWith(".opf") || it.endsWith(".ncx") || it.endsWith(".xml") }.forEach { n ->
                runCatching { xml(z.getInputStream(z.getEntry(n)).readBytes()) }.onFailure { throw AssertionError("$n isn't well-formed: ${it.message}") }
            }
            val opf = xml(z.getInputStream(z.getEntry("OEBPS/content.opf")).readBytes())
            val items = opf.getElementsByTagNameNS("*", "item")
            for (i in 0 until items.length) {
                val href = items.item(i).attributes.getNamedItem("href").nodeValue
                assertTrue("missing $href", "OEBPS/$href" in names)
            }
            assertTrue(opf.getElementsByTagNameNS("*", "itemref").length > 0)
        }
    }

    @Test fun txt() {
        assumeTrue(samples?.isDirectory == true)
        val book = TxtConverter.convert(decodeText(File(samples, "sample.txt").readBytes()), "The Tunnel Almanac", "Worm Tests")
        assertEquals(listOf("Chapter One", "Chapter Two"), book.chapters.map { it.first }.filter { it.startsWith("Chapter") })
        val f = File(out, "txt.epub"); book.writeTo(f); validate(f)
    }

    @Test fun markdown() {
        assumeTrue(samples?.isDirectory == true)
        val book = MarkdownConverter.convert(File(samples, "sample.md").readText(), "Field Notes", null)
        assertEquals(listOf("Field Notes", "Second Notebook"), book.chapters.map { it.first })
        assertTrue(book.chapters[0].second.contains("<em>small</em>"))
        val f = File(out, "md.epub"); book.writeTo(f); validate(f)
    }

    @Test fun docx() {
        assumeTrue(File(samples, "sample.docx").isFile)
        val book = DocxConverter.convert(File(samples, "sample.docx"), "Doc Sample")
        assertTrue(book.chapters.map { it.first }.toString(), book.chapters.size >= 2)
        assertTrue(book.chapters.joinToString { it.second }, book.chapters.joinToString { it.second }.contains("<strong>bold</strong>"))
        val f = File(out, "docx.epub"); book.writeTo(f); validate(f)
    }

    @Test fun nestedToc() {
        val f = File(out, "nested.epub")
        EpubWriter(f).use { w ->
            val a = w.addSection(EpubWriter.page("A", "<h1 id=\"x\">A</h1><p>a</p>"))
            val b = w.addSection(EpubWriter.page("B", "<h1>B</h1><p>b</p>"))
            w.finish(
                EpubMeta("Nested", listOf("Me"), series = "Tests", seriesIndex = 2f),
                listOf(TocItem("Part A", a), TocItem("A detail", "$a#x", 1), TocItem("Part B", b), TocItem("B deep", b, 1), TocItem("B deeper", b, 2), TocItem("End", b)),
            )
        }
        validate(f)
    }
}
