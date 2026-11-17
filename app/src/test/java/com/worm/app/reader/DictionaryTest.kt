package com.worm.app.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files

class DictionaryTest {
    /** Writes a tiny StarDict dictionary (sorted the StarDict way) and returns its folder. */
    private fun makeDict(entries: List<Pair<String, String>>, type: String? = "m"): File {
        val dir = Files.createTempDirectory("dict").toFile()
        val sorted = entries.sortedWith(compareBy<Pair<String, String>>({ it.first.lowercase() }, { it.first }))
        val dict = ByteArrayOutputStream(); val idx = ByteArrayOutputStream(); val out = DataOutputStream(idx)
        sorted.forEach { (w, d) ->
            val bytes = d.toByteArray()
            out.write(w.toByteArray()); out.write(0); out.writeInt(dict.size()); out.writeInt(bytes.size)
            dict.write(bytes)
        }
        File(dir, "t.dict").writeBytes(dict.toByteArray())
        File(dir, "t.idx").writeBytes(idx.toByteArray())
        File(dir, "t.ifo").writeText("StarDict's dict ifo file\nversion=2.4.2\nwordcount=${sorted.size}\nidxfilesize=${idx.size()}\nbookname=Test Dict\n" + (type?.let { "sametypesequence=$it\n" } ?: ""))
        return dir
    }

    @Test fun findsExactCaseInsensitiveAndBaseForms() {
        val d = StarDict.open(makeDict(listOf("apple" to "a fruit", "Walk" to "to move on foot", "zebra" to "striped animal", "bank" to "river side")))!!
        assertEquals("a fruit", d.lookup(listOf("apple"))!!.definitions[0].senses[0])
        assertEquals("to move on foot", d.lookup(Dictionary.candidates("walked"))!!.definitions[0].senses[0])
        assertEquals("striped animal", d.lookup(Dictionary.candidates("Zebras"))!!.definitions[0].senses[0])
        assertNull(d.lookup(listOf("missing")))
    }

    @Test fun htmlEntriesAreStripped() {
        val d = StarDict.open(makeDict(listOf("cat" to "<b>noun</b><br>a small feline"), type = "h"))!!
        val senses = d.lookup(listOf("cat"))!!.definitions[0].senses
        assertEquals(listOf("noun", "a small feline"), senses)
    }

    @Test fun cleaningAndCandidates() {
        assertEquals("Winston", Dictionary.clean("“Winston’s"))
        assertTrue("happy" in Dictionary.candidates("happily"))
        assertTrue("run" in Dictionary.candidates("running"))
        assertNotNull(Dictionary.candidates("big brother").firstOrNull())
    }
}
