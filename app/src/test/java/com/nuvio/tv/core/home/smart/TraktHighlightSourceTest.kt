package com.nuvio.tv.core.home.smart

import com.nuvio.tv.data.remote.dto.trakt.TraktListIdsDto
import com.nuvio.tv.data.remote.dto.trakt.TraktListSummaryDto
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TraktHighlightSourceTest {

    private fun list(name: String, items: Int? = 60, privacy: String? = "public") = TraktListSummaryDto(
        name = name, privacy = privacy, itemCount = items, ids = TraktListIdsDto(trakt = 1L, slug = "x")
    )

    @Test
    fun `a liked public list of a sensible size with a good name is a theme`() {
        assertTrue(TraktHighlightSource.isUsable(list("Mind Bending Sci-Fi"), likes = 120))
    }

    @Test
    fun `lists people keep for themselves aren't themes`() {
        assertFalse(TraktHighlightSource.isUsable(list("My Watchlist"), likes = 500))
        assertFalse(TraktHighlightSource.isUsable(list("Movies I've Watched"), likes = 500))
        assertFalse(TraktHighlightSource.isUsable(list("Plex Library Movies"), likes = 500))
        assertFalse(TraktHighlightSource.isUsable(list("My Favorite Films"), likes = 500))
    }

    @Test
    fun `too short, too long, unliked or private lists are left out`() {
        assertFalse(TraktHighlightSource.isUsable(list("Great Heist Movies", items = 8), likes = 120))
        assertFalse(TraktHighlightSource.isUsable(list("Great Heist Movies", items = 2_000), likes = 120))
        assertFalse(TraktHighlightSource.isUsable(list("Great Heist Movies", items = null), likes = 120))
        assertFalse(TraktHighlightSource.isUsable(list("Great Heist Movies"), likes = 1))
        assertFalse(TraktHighlightSource.isUsable(list("Great Heist Movies", privacy = "private"), likes = 120))
    }

    @Test
    fun `names the highlight filter turns down are left out`() {
        // One word, and a swear word: the same rules as AI-written names.
        assertFalse(TraktHighlightSource.isUsable(list("Horror"), likes = 120))
        assertFalse(TraktHighlightSource.isUsable(list("Holy Shit Movies"), likes = 120))
    }
}
