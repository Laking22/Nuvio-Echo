package com.nuvio.tv.ui.newlook.home

import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.ui.screens.home.ContinueWatchingItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExpansionPrefetchTargetTest {

    private fun progress(id: String, season: Int, episode: Int) = WatchProgress(
        contentId = id, contentType = "series", name = "Show", poster = null, backdrop = null, logo = null,
        videoId = "$id:$season:$episode", season = season, episode = episode, episodeTitle = null,
        position = 60_000, duration = 3_600_000, lastWatched = 0
    )

    @Test
    fun `a movie fetches itself`() {
        assertEquals(ExpansionTarget("movie", "tt1", null, null), expansionPrefetchTarget("tt1", "movie", emptyList()))
    }

    @Test
    fun `a show fetches the episode its Continue Watching card holds`() {
        val cw = listOf(ContinueWatchingItem.InProgress(progress("tt2", 2, 5)))
        assertEquals(ExpansionTarget("series", "tt2:2:5", 2, 5), expansionPrefetchTarget("tt2", "series", cw))
    }

    @Test
    fun `a show not in Continue Watching fetches its first episode, never a season`() {
        val cw = listOf(ContinueWatchingItem.InProgress(progress("tt9", 1, 3)))
        assertEquals(ExpansionTarget("series", "tt2:1:1", 1, 1), expansionPrefetchTarget("tt2", "series", cw))
    }

    @Test
    fun `loading placeholders fetch nothing`() {
        assertNull(expansionPrefetchTarget("__placeholder_x_0", "movie", emptyList()))
        assertNull(expansionPrefetchTarget("", "movie", emptyList()))
    }
}
