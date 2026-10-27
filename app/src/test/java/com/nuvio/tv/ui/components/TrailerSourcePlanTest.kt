package com.nuvio.tv.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class TrailerSourcePlanTest {

    @Test
    fun `separate video and audio always read the video in chunks, sound on or off`() {
        // An earlier version loaded the video with the player's plain source when muted (the default), and YouTube's adaptive
        // files never buffered: trailers stopped playing.
        assertEquals(TrailerSourcePlan.CHUNKED_VIDEO_ONLY, trailerSourcePlan(audioUrl = "https://x.googlevideo.com/a", muted = true))
        assertEquals(TrailerSourcePlan.CHUNKED_VIDEO_AND_AUDIO, trailerSourcePlan(audioUrl = "https://x.googlevideo.com/a", muted = false))
    }

    @Test
    fun `a single URL is loaded as it is`() {
        assertEquals(TrailerSourcePlan.SINGLE_URL, trailerSourcePlan(audioUrl = null, muted = true))
        assertEquals(TrailerSourcePlan.SINGLE_URL, trailerSourcePlan(audioUrl = "", muted = false))
    }
}
