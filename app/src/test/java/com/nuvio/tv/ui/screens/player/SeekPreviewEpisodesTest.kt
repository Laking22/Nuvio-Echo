package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeekPreviewEpisodesTest {

    private fun stream(name: String, filename: String? = null) = Stream(
        name = name,
        title = null,
        description = null,
        url = "https://addon.example/x",
        ytId = null,
        infoHash = null,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = filename?.let {
            StreamBehaviorHints(notWebReady = null, bingeGroup = null, countryWhitelist = null, proxyHeaders = null, filename = it)
        },
        addonName = "Addon",
        addonLogo = null
    )

    @Test
    fun `the same episode is trusted`() {
        assertTrue(SeekPreviewEpisodes.names(stream("1080p", "From.S01E01.1080p.WEB.x265.mkv"), 1, 1))
        assertTrue(SeekPreviewEpisodes.names(stream("From 1x01 720p"), 1, 1))
        assertTrue(SeekPreviewEpisodes.names(stream("[Sub] Bocchi the Rock! - 03 [1080p].mkv"), 1, 3))
    }

    @Test
    fun `another episode, a pack or no episode at all is not`() {
        assertFalse(SeekPreviewEpisodes.names(stream("From.S01E03.1080p.mkv"), 1, 1))
        assertFalse(SeekPreviewEpisodes.names(stream("From S01E01-E10 1080p pack"), 1, 1))
        assertFalse(SeekPreviewEpisodes.names(stream("PenguPlay 1080p"), 1, 1))
        assertFalse(SeekPreviewEpisodes.names(stream("[Sub] Bocchi the Rock! - 04 [1080p]"), 1, 3))
    }

    @Test
    fun `a resolution isn't read as an episode`() {
        assertFalse(SeekPreviewEpisodes.names(stream("Show 1080p - 720 x265"), 1, 1))
        assertTrue(SeekPreviewEpisodes.names(stream("Show S02E05 1080p"), 2, 5))
    }
}
