package com.nuvio.tv.core.parental

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgeRatingsTest {

    @Test
    fun `us film and tv ratings`() {
        assertEquals("0+", AgeRatings.label("G"))
        assertEquals("13+", AgeRatings.label("PG-13"))
        assertEquals("17+", AgeRatings.label("R"))
        assertEquals("18+", AgeRatings.label("NC-17"))
        assertEquals("0+", AgeRatings.label("TV-Y"))
        assertEquals("7+", AgeRatings.label("TV-Y7"))
        assertEquals("14+", AgeRatings.label("TV-14"))
        assertEquals("17+", AgeRatings.label("TV-MA"))
    }

    @Test
    fun `other boards use their number or their all-ages label`() {
        assertEquals("12+", AgeRatings.label("12A"))
        assertEquals("15+", AgeRatings.label("MA15+"))
        assertEquals("16+", AgeRatings.label("K-16"))
        assertEquals("13+", AgeRatings.label("+13"))
        assertEquals("0+", AgeRatings.label("U"))
        assertEquals("0+", AgeRatings.label("tp"))
        assertEquals("0+", AgeRatings.label("0"))
    }

    @Test
    fun `ambiguous or blank ratings have no age`() {
        assertNull(AgeRatings.label("A"))
        assertNull(AgeRatings.label("Not Rated"))
        assertNull(AgeRatings.label(" "))
        assertNull(AgeRatings.label(null))
    }

    @Test
    fun `bare numbers are recognised so the badge doesn't repeat them`() {
        assertTrue(AgeRatings.isNumericOnly("16"))
        assertFalse(AgeRatings.isNumericOnly("PG-13"))
    }
}
