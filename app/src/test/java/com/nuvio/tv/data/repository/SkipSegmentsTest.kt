package com.nuvio.tv.data.repository

import com.nuvio.tv.data.remote.api.TheIntroDbMediaResponse
import com.nuvio.tv.data.remote.api.TheIntroDbSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkipSegmentsTest {

    @Test
    fun `times that can't belong to the file are dropped`() {
        val length = 1_420_000L
        // AniSkip's Bocchi the Rock E1, as submitted: an "op" at the very end and an "ed" at the start.
        assertFalse(SkipSegments.plausible(SkipInterval(1403.05, 1433.0, "op", "aniskip"), length))
        assertFalse(SkipSegments.plausible(SkipInterval(7.193, 97.193, "ed", "aniskip"), length))
        assertFalse(SkipSegments.plausible(SkipInterval(1500.0, 1560.0, "outro", "introdb"), length))
        assertTrue(SkipSegments.plausible(SkipInterval(115.96, 205.96, "op", "aniskip"), length))
        assertTrue(SkipSegments.plausible(SkipInterval(1300.0, 1390.0, "ed", "aniskip"), length))
        // Unknown length: anything goes.
        assertTrue(SkipSegments.plausible(SkipInterval(1403.05, 1433.0, "op", "aniskip"), null))
    }

    @Test
    fun `theintrodb's match for a length that runs past the file is spotted`() {
        // Game of Thrones S1E2 asked for a 53:00 file: credits from 55:56.
        val matched = TheIntroDbMediaResponse(
            intro = listOf(TheIntroDbSegment(107_000, 213_000)),
            recap = listOf(TheIntroDbSegment(17_000, 106_000)),
            credits = listOf(TheIntroDbSegment(3_356_000, null))
        )
        assertTrue(matched.startsPast(3_179_880))
        assertFalse(matched.startsPast(3_420_000))
        assertFalse(TheIntroDbMediaResponse(intro = listOf(TheIntroDbSegment(5_000, 106_000))).startsPast(3_179_880))
    }

    @Test
    fun `theintrodb intros start the file without a start, credits end it without an end`() {
        val response = TheIntroDbMediaResponse(
            intro = listOf(TheIntroDbSegment(startMs = null, endMs = 38_000)),
            credits = listOf(TheIntroDbSegment(startMs = 3_141_000, endMs = null))
        )
        val tv = response.toSkipIntervals(movie = false, durationMs = 3_165_000)
        assertEquals(
            listOf(SkipInterval(0.0, 38.0, "intro", "theintrodb"), SkipInterval(3141.0, 3165.0, "outro", "theintrodb")),
            tv
        )
        assertEquals("movie-credits", response.toSkipIntervals(movie = true, durationMs = null).last().type)
        assertEquals(Double.MAX_VALUE, response.toSkipIntervals(movie = true, durationMs = null).last().endTime, 0.0)
    }
}
