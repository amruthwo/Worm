package com.worm.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeriesParseTest {
    @Test fun parses() {
        assertEquals("Bobiverse" to 3f, MetaSearch.parseSeries("All These Worlds (Bobiverse Book 3)"))
        assertEquals("The Expanse" to 2f, MetaSearch.parseSeries("Caliban's War (The Expanse, #2)"))
        assertEquals("The Expanse" to 1f, MetaSearch.parseSeries("Leviathan Wakes: The Expanse, Book 1"))
        assertEquals("Bobiverse" to 4f, MetaSearch.parseSeries("Heaven's River (Bobiverse 4)"))
        assertEquals("Tunnel Tales" to 2f, MetaSearch.parseSeries("The Burrow Ledger: Book 2 of the Tunnel Tales series"))
        assertNull(MetaSearch.parseSeries("The Secret of Secrets: A Novel"))
        assertNull(MetaSearch.parseSeries("1984"))
    }

    @Test fun cleansTitles() {
        assertEquals("The Secret of Secrets", MetaSearch.searchTitle("The Secret of Secrets: A Novel"))
        assertEquals("All These Worlds", MetaSearch.searchTitle("All These Worlds (Bobiverse Book 3)"))
    }
}
