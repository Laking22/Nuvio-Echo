package com.nuvio.tv.core.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceLanguagesTest {
    @Test
    fun `codes reduce to their base language`() {
        assertEquals("pt", SourceLanguages.normalize("pt-br"))
        assertEquals("pt", SourceLanguages.normalize("pt_BR"))
        assertEquals("es", SourceLanguages.normalize("mx"))
        assertEquals("hi", SourceLanguages.normalize(" HI "))
        assertNull(SourceLanguages.normalize(""))
        assertNull(SourceLanguages.normalize(null))
    }

    @Test
    fun `defaults are English and the app language`() {
        assertEquals(setOf("en", "fr"), SourceLanguages.defaults("fr"))
        assertEquals(setOf("en"), SourceLanguages.defaults("en"))
    }

    @Test
    fun `only chosen languages run`() {
        val chosen = setOf("en", "es")
        assertTrue(SourceLanguages.allows(listOf("en"), chosen))
        assertTrue(SourceLanguages.allows(listOf("mx"), chosen))
        assertFalse(SourceLanguages.allows(listOf("hi"), chosen))
        assertTrue(SourceLanguages.allows(listOf("hi", "en"), chosen))
    }

    @Test
    fun `untagged and many-language sources always run`() {
        val chosen = setOf("en")
        assertTrue(SourceLanguages.allows(emptyList(), chosen))
        assertTrue(SourceLanguages.allows(listOf(null, ""), chosen))
        assertTrue(SourceLanguages.allows(listOf("multi"), chosen))
    }
}
