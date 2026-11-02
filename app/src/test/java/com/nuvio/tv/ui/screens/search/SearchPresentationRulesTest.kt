package com.nuvio.tv.ui.screens.search

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchPresentationRulesTest {

    @Test
    fun `one character is not yet a submitted search`() {
        assertEquals("", submittedSearchQuery("a"))
    }

    @Test
    fun `minimum query is trimmed and submitted`() {
        assertEquals("ab", submittedSearchQuery(" ab "))
    }
}
