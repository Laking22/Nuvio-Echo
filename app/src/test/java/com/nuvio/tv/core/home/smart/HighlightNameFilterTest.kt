package com.nuvio.tv.core.home.smart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HighlightNameFilterTest {

    @Test
    fun `every built-in name passes`() {
        SmartHighlightCatalog.themes.forEach { theme ->
            assertTrue("${theme.id}: ${theme.title}", HighlightNameFilter.isAcceptable(theme.title))
        }
    }

    @Test
    fun `swearing is rejected however it is disguised`() {
        listOf(
            "Mind Fuck", "Mind fuck", "MIND FUCK", "m1nd f*ck", "Mind F**k", "Mind Fck", "Mindfuckery Night",
            "F U C K Yeah", "Sh!t Show", "Holy Shiiit", "Bad Ass Heroes", "Kick Ass", "Total Bitches", "Big Dick Energy",
            "WTF Endings", "Sexy Spies", "Motherf Movies", "Wanker Comedies", "Horny Teens"
        ).forEach { name -> assertFalse(name, HighlightNameFilter.isAcceptable(name)) }
    }

    @Test
    fun `innocent words that contain a bad one are kept`() {
        listOf(
            "Assassins at Work", "Class Act", "Suspicious Minds", "Thorny Romances", "Swanky Parties",
            "Scunthorpe Stories", "Title Fights", "Grape Expectations", "Passing Time", "Cocktail Hour",
            "Peacock Feathers", "Shift Happens Later", "Hell or High Water"
        ).forEach { name -> assertTrue(name, HighlightNameFilter.isAcceptable(name)) }
    }

    @Test
    fun `odd shapes are rejected`() {
        listOf(
            "Heists", "", "   ", "A Very Long Name That Goes On and On Forever", "Fun 🎉 Picks", "\"Quoted Name\"",
            "#Trending Now", "Visit http://x.y", "One Two Three Four Five Six Seven Eight"
        ).forEach { name -> assertFalse(name, HighlightNameFilter.isAcceptable(name)) }
    }

    @Test
    fun `a blocked name stays blocked whatever its case and spacing`() {
        val blocked = setOf(HighlightNameFilter.normalise("Brain Melters"))
        assertFalse(HighlightNameFilter.isAcceptable("brain  melters", blocked))
        assertFalse(HighlightNameFilter.isAcceptable("BRAIN MELTERS!", blocked))
        assertTrue(HighlightNameFilter.isAcceptable("Brain Teasers", blocked))
    }

    @Test
    fun `normalise maps look-alike characters`() {
        assertEquals("mindfck", HighlightNameFilter.normalise("M1nd F*ck!"))
        assertEquals("shit", HighlightNameFilter.normalise("Sh!t"))
        assertEquals("sheet", HighlightNameFilter.normalise("\$h33t"))
    }
}
