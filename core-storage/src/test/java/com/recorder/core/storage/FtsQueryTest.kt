package com.recorder.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FtsQueryTest {

    @Test
    fun `words become quoted OR terms`() {
        assertEquals("\"supplier\" OR \"deal\"", FtsQuery.sanitize("supplier deal"))
    }

    @Test
    fun `single characters and noise are dropped`() {
        assertEquals("\"deal\"", FtsQuery.sanitize("a deal ?"))
    }

    @Test
    fun `fts operators in user text cannot escape the quotes`() {
        val sanitized = FtsQuery.sanitize("supplier\" OR 1=1 --")
        assertTrue(
            "unbalanced quotes would let user input change the query: $sanitized",
            sanitized.count { it == '"' } % 2 == 0,
        )
        assertTrue(sanitized.contains("\"supplier\""))
    }

    @Test
    fun `empty input yields an empty match expression`() {
        assertEquals("", FtsQuery.sanitize("   ?  !"))
    }
}

class FtsQueryAllTest {

    @Test
    fun `every word must appear`() {
        assertEquals("\"invoice\" AND \"tuesday\"", FtsQuery.all("invoice tuesday"))
    }

    @Test
    fun `punctuation and one-letter words are dropped`() {
        assertEquals("\"paid\" AND \"yet\"", FtsQuery.all("paid? a yet!"))
    }

    @Test
    fun `nothing searchable gives an empty query rather than a broken one`() {
        assertEquals("", FtsQuery.all("  ?  "))
    }
}
