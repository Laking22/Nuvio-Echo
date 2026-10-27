package com.nuvio.tv.core.player

import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoPlayPreferencesTest {

    private var counter = 0

    private fun stream(name: String, size: Long? = null) = Stream(
        name = name,
        title = null,
        description = null,
        url = "https://addon.example/${counter++}",
        ytId = null,
        infoHash = null,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = size?.let {
            StreamBehaviorHints(notWebReady = null, bingeGroup = null, countryWhitelist = null, proxyHeaders = null, videoSize = it)
        },
        addonName = "Addon",
        addonLogo = null
    )

    // --- toggling ---------------------------------------------------------------------------

    @Test
    fun `qualities add up`() {
        val selected = AutoPlayPreferences.toggle(setOf("4k"), "1080p")
        assertEquals(setOf("4k", "1080p"), selected)
    }

    @Test
    fun `pills that can't both hold replace each other`() {
        assertEquals(setOf("4k", "largest"), AutoPlayPreferences.toggle(setOf("4k", "smallest"), "largest"))
        assertEquals(setOf("avc"), AutoPlayPreferences.toggle(setOf("hevc"), "avc"))
        assertEquals(setOf("sdr"), AutoPlayPreferences.toggle(setOf("hdr"), "sdr"))
    }

    @Test
    fun `a picked pill switches off`() {
        assertEquals(setOf("4k"), AutoPlayPreferences.toggle(setOf("4k", "smallest"), "smallest"))
    }

    @Test
    fun `unknown keys are ignored`() {
        assertEquals(setOf("4k"), AutoPlayPreferences.toggle(setOf("4k"), "nope"))
        assertFalse(AutoPlayPreferences.isConfigured(setOf("nope")))
        assertTrue(AutoPlayPreferences.isConfigured(setOf("4k")))
    }

    // --- ranking ----------------------------------------------------------------------------

    @Test
    fun `picked qualities come first, smallest first among them`() {
        val big4k = stream("Movie 2160p REMUX", size = 60_000_000_000)
        val small1080 = stream("Movie 1080p WEB-DL", size = 2_000_000_000)
        val tiny720 = stream("Movie 720p", size = 900_000_000)
        val mid1080 = stream("Movie 1080p BluRay", size = 9_000_000_000)
        val ranked = AutoPlayPreferences.rank(listOf(big4k, tiny720, mid1080, small1080), setOf("4k", "1080p", "smallest"))
        assertEquals(listOf(small1080, mid1080, big4k, tiny720), ranked)
    }

    @Test
    fun `without the picked quality the nearest comes first, a step up before a step down, low quality last`() {
        val sd = stream("Movie 360p")
        val unstated = stream("Movie WEB")
        val hd = stream("Movie 720p")
        val uhd = stream("Movie 2160p")
        val ranked = AutoPlayPreferences.rank(listOf(sd, unstated, hd, uhd), setOf("1080p"))
        assertEquals(listOf(uhd, hd, unstated, sd), ranked)
    }

    @Test
    fun `size written in the text counts when none is stated`() {
        val a = stream("Movie 1080p 💾 7.5 GB")
        val b = stream("Movie 1080p 💾 2,1 GB")
        assertEquals(listOf(b, a), AutoPlayPreferences.rank(listOf(a, b), setOf("1080p", "smallest")))
    }

    @Test
    fun `sources without a size go after sized ones`() {
        val unsized = stream("Movie 1080p")
        val sized = stream("Movie 1080p", size = 5_000_000_000)
        assertEquals(listOf(sized, unsized), AutoPlayPreferences.rank(listOf(unsized, sized), setOf("1080p", "largest")))
    }

    @Test
    fun `no cam leaves cam releases out`() {
        val cam = stream("Movie 2026 HDCAM 1080p")
        val ts = stream("Movie TS 720p")
        val web = stream("Movie 1080p WEB-DL")
        assertEquals(listOf(web), AutoPlayPreferences.rank(listOf(cam, ts, web), setOf("nocam")))
    }

    @Test
    fun `without a match the addon order holds`() {
        val a = stream("Movie 480p")
        val b = stream("Movie 360p")
        assertEquals(listOf(a, b), AutoPlayPreferences.rank(listOf(a, b), setOf("4k")))
    }

    @Test
    fun `codec and picture pills break ties within a quality`() {
        val avcSdr = stream("Movie 1080p x264")
        val hevcHdr = stream("Movie 1080p x265 HDR10")
        assertEquals(listOf(hevcHdr, avcSdr), AutoPlayPreferences.rank(listOf(avcSdr, hevcHdr), setOf("1080p", "hevc", "hdr")))
        assertEquals(listOf(avcSdr, hevcHdr), AutoPlayPreferences.rank(listOf(avcSdr, hevcHdr), setOf("1080p", "avc", "sdr")))
    }

    @Test
    fun `matches needs every picked group but size`() {
        val web1080 = stream("Movie 1080p WEB-DL x264")
        assertTrue(AutoPlayPreferences.matches(web1080, setOf("1080p", "web", "smallest")))
        assertFalse(AutoPlayPreferences.matches(web1080, setOf("1080p", "remux")))
        assertFalse(AutoPlayPreferences.matches(web1080, setOf("4k")))
    }

    @Test
    fun `a remembered link is low quality only when its name or filename says so`() {
        assertTrue(AutoPlayPreferences.isLowQuality("PenguPlay 360p", null))
        assertTrue(AutoPlayPreferences.isLowQuality("Torrentio", "Show.S01E02.480p.WEB.mkv"))
        assertFalse(AutoPlayPreferences.isLowQuality("PenguPlay 1080p \u2022 Anikoto", null))
        assertFalse(AutoPlayPreferences.isLowQuality("PenguPlay", null))
        // A lower resolution in the name wins over the filename's: an addon can share one filename across qualities.
        assertTrue(AutoPlayPreferences.isLowQuality("\uD83D\uDC27 PenguPlay \uD83D\uDC27 360p \u2022 VAPlayer", "Show.S01E02.1080p.mp4"))
        assertFalse(AutoPlayPreferences.isLowQuality("Mirror 1080p", "Show.S01E02.1080p.WEB.mkv"))
    }
}
