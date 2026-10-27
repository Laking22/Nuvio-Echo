package com.nuvio.tv.core.streams

import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamQualityTest {

    private var counter = 0

    private fun stream(
        addon: String,
        name: String?,
        filename: String? = null,
        url: String? = "https://$addon.example/${counter++}",
        infoHash: String? = null
    ) = Stream(
        name = name,
        title = null,
        description = null,
        url = url,
        ytId = null,
        infoHash = infoHash,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = filename?.let {
            StreamBehaviorHints(
                notWebReady = null,
                bingeGroup = null,
                countryWhitelist = null,
                proxyHeaders = null,
                filename = it
            )
        },
        addonName = addon,
        addonLogo = null
    )

    // --- parsing ---------------------------------------------------------------------------

    @Test
    fun `reads resolution from real addon labels`() {
        val cases = mapOf(
            "Dark.Matter.S01E01.2160p.WEBRip.DDP5.1.Atmos.x265-KONTRAST.mkv" to StreamQuality.P2160,
            "PenguPlay 4K • 4KHDHub · R2" to StreamQuality.P2160,
            "1080P • 1.49 GB" to StreamQuality.P1080,
            "The.Bay.2012.LiMiTED.1080p.BluRay.x264-HDEX [PublicHD].mkv [a11 5.5 GB]" to StreamQuality.P1080,
            "dark.matter.s01e01.1080p.web.h264-successfulcrab[EZTVx.to].mkv" to StreamQuality.P1080,
            "Torrentio\n720p" to StreamQuality.P720,
            "Full HD" to StreamQuality.P1080,
            "Movie 1440p" to StreamQuality.P1440,
            "Old.Show.480p.DVDRip" to StreamQuality.P480,
            "DVDRip SD" to StreamQuality.P480,
            "Clip 360p" to StreamQuality.P360,
            "Clip 240p" to StreamQuality.P240,
            "Clip 144p" to StreamQuality.P144,
            "Movie.2023.1080p10bit.WEB-DL.mkv" to StreamQuality.P1080,
            "Show.S01E01.2160p10bit.HDR.mkv" to StreamQuality.P2160,
            "Film.720p8bit.x264" to StreamQuality.P720
        )
        cases.forEach { (text, expected) ->
            assertEquals(text, expected, StreamQualityParser.parseText(text))
        }
    }

    @Test
    fun `ignores tokens embedded in other words`() {
        assertNull(StreamQualityParser.parseText("4KHDHub"))
        assertNull(StreamQualityParser.parseText("HDR10 HEVC"))
        assertNull(StreamQualityParser.parseText("support the project!"))
    }

    @Test
    fun `a picture size counts as its resolution`() {
        assertEquals(StreamQuality.P1080, StreamQualityParser.parseText("1920x1080 HEVC"))
        assertEquals(StreamQuality.P2160, StreamQualityParser.parseText("Resolution: 3840 × 2160"))
        assertEquals(StreamQuality.P720, StreamQualityParser.parseText("1280x720"))
        // A codec after the audio channels isn't a size.
        assertNull(StreamQualityParser.parseText("AAC 5.1x264"))
    }

    @Test
    fun `an audio format is not a picture quality`() {
        assertNull(StreamQualityParser.parseText("Movie.2023.DTS-HD.MA.5.1"))
        assertNull(StreamQualityParser.parseText("TrueHD Atmos 7.1"))
        assertEquals(StreamQuality.P720, StreamQualityParser.parseText("WEB-DL HD"))
    }

    @Test
    fun `filename wins over the display name`() {
        val stream = stream("Showbox", name = "Showbox 4K", filename = "Movie.2020.720p.WEB.mkv")
        assertEquals(StreamQuality.P720, StreamQualityParser.parse(stream))
    }

    @Test
    fun `a lower resolution in the name beats a shared filename`() {
        // PenguPlay labels each quality in the name but gives them all the same 1080p filename.
        val stream = stream("PenguPlay", name = "\uD83D\uDC27 PenguPlay \uD83D\uDC27 360p \u2022 VAPlayer", filename = "Show.S01E02.1080p.mp4")
        assertEquals(StreamQuality.P360, StreamQualityParser.parse(stream))
        // A higher one in the name doesn't lift it: the filename still wins then.
        val upsold = stream("Showbox", name = "Showbox 4K", filename = "Movie.2020.720p.WEB.mkv")
        assertEquals(StreamQuality.P720, StreamQualityParser.parse(upsold))
    }

    @Test
    fun `buckets decoded video sizes, including cropped widescreen`() {
        assertEquals(StreamQuality.P2160, StreamQuality.fromVideoSize(3840, 1600))
        assertEquals(StreamQuality.P1080, StreamQuality.fromVideoSize(1920, 800))
        assertEquals(StreamQuality.P1080, StreamQuality.fromVideoSize(1904, 800))
        // A 720p scope encode (labelled 720p by its addon): not 1080p.
        assertEquals(StreamQuality.P720, StreamQuality.fromVideoSize(1720, 720))
        assertEquals(StreamQuality.P720, StreamQuality.fromVideoSize(1280, 536))
        assertEquals(StreamQuality.P1080, StreamQuality.fromVideoSize(1440, 1080))
        assertEquals(StreamQuality.P720, StreamQuality.fromVideoSize(1280, 720))
        assertEquals(StreamQuality.P480, StreamQuality.fromVideoSize(854, 480))
        assertEquals(StreamQuality.P360, StreamQuality.fromVideoSize(640, 360))
        assertEquals(StreamQuality.P240, StreamQuality.fromVideoSize(426, 240))
        assertEquals(StreamQuality.P144, StreamQuality.fromVideoSize(256, 144))
        assertNull(StreamQuality.fromVideoSize(0, 0))
    }

    // --- menu ------------------------------------------------------------------------------

    @Test
    fun `menu lists only the qualities sources offer, plus the playing one`() {
        val streams = listOf(
            stream("Showbox", "Showbox", filename = "A.2160p.mkv"),
            stream("Showbox", "Showbox", filename = "A.1080p.mkv"),
            stream("PenguPlay", "PenguPlay 1080p"),
            stream("PenguPlay", "support the project!"),
            stream("Torrents", "Torrent 720p", url = null, infoHash = "abc")
        )

        val options = StreamQualityMenu.buildOptions(streams, current = StreamQuality.P480)

        // The only 720p source is a torrent, which can't be switched to in place.
        assertEquals(listOf(StreamQuality.P2160, StreamQuality.P1080, StreamQuality.P480), options.map { it.quality })
        val byQuality = options.associateBy { it.quality }
        assertEquals(1, byQuality.getValue(StreamQuality.P2160).sourceCount)
        assertEquals(2, byQuality.getValue(StreamQuality.P1080).sourceCount)
        assertTrue(byQuality.getValue(StreamQuality.P480).isCurrent)
        assertEquals(0, byQuality.getValue(StreamQuality.P480).sourceCount)
        assertEquals(listOf(StreamQuality.P2160, StreamQuality.P1080), StreamQualityMenu.availableQualities(streams))
    }

    @Test
    fun `keeping the listening language leaves out qualities only offered in another`() {
        val french4k = stream("A", "A", filename = "Movie.2024.2160p.FRENCH.WEB.mkv")
        val english1080 = stream("B", "B", filename = "Movie.2024.1080p.WEB.mkv")
        val streams = listOf(french4k, english1080)
        val inEnglish = streams.filter { AudioLanguageMenu.matches(it, AudioLanguage.ENGLISH, original = AudioLanguage.ENGLISH) }

        assertEquals(listOf(StreamQuality.P1080), StreamQualityMenu.availableQualities(inEnglish))
        // With only French sources at 4K, there's nothing in English to move to.
        assertTrue(StreamQualityMenu.rankForQuality(inEnglish, StreamQuality.P2160, preferredAddon = null).isEmpty())
    }

    @Test
    fun `a stated resolution beats a loose word`() {
        // A 1080p encode of a UHD disc is 1080p.
        assertEquals(StreamQuality.P1080, StreamQualityParser.parseText("Movie.2019.1080p.UHD.BluRay.x264"))
        assertEquals(StreamQuality.P1080, StreamQualityParser.parseText("Movie.2019.UHD.BluRay.1080p.DDP"))
        // A filename saying only "HD" doesn't outrank a name saying 1080p.
        val stream = stream("Showbox", name = "Showbox 1080p", filename = "Movie HD.mkv")
        assertEquals(StreamQuality.P1080, StreamQualityParser.parse(stream))
        // Loose words still count when nothing better is said.
        assertEquals(StreamQuality.P2160, StreamQualityParser.parse(stream("A", name = "UHD")))
        assertEquals(StreamQuality.P720, StreamQualityParser.parse(stream("A", name = "HD")))
    }

    @Test
    fun `reads frame rate suffixes and the best of several tokens`() {
        assertEquals(StreamQuality.P2160, StreamQualityParser.parseText("Concert 2160p60 HDR"))
        assertEquals(StreamQuality.P1080, StreamQualityParser.parseText("Clip.1080p50.mp4"))
        assertEquals(StreamQuality.P2160, StreamQualityParser.parseText("4K | 1080p | 720p"))
        assertNull(StreamQualityParser.parseText("21600 kbps"))
    }

    @Test
    fun `parsing the same stream twice gives the same answer`() {
        val stream = stream("A", name = "A 720p")
        assertEquals(StreamQuality.P720, StreamQualityParser.parse(stream))
        assertEquals(StreamQuality.P720, StreamQualityParser.parse(stream))
        assertNull(StreamQualityParser.parse(stream("B", name = "no label")))
    }

    @Test
    fun `picking a quality prefers the addon already playing`() {
        val pengu1080 = stream("PenguPlay", "PenguPlay 1080p")
        val showbox1080 = stream("Showbox", "Showbox", filename = "A.1080p.mkv")
        val streams = listOf(pengu1080, showbox1080)

        assertEquals(showbox1080, StreamQualityMenu.pickForQuality(streams, StreamQuality.P1080, preferredAddon = "Showbox"))
        assertEquals(pengu1080, StreamQualityMenu.pickForQuality(streams, StreamQuality.P1080, preferredAddon = "Other"))
        assertNull(StreamQualityMenu.pickForQuality(streams, StreamQuality.P720, preferredAddon = "Showbox"))
    }

    @Test
    fun `ranks every source with the quality, the playing addon's first, for the switch to work through`() {
        val a1080 = stream("A", "A", filename = "A.1080p.mkv")
        val b720 = stream("B", "B", filename = "B.720p.mkv")
        val b1080 = stream("B", "B", filename = "B.1080p.mkv")
        val c1080 = stream("C", "C", filename = "C.1080p.mkv")
        val streams = listOf(a1080, b720, b1080, c1080)

        assertEquals(listOf(b1080, a1080, c1080), StreamQualityMenu.rankForQuality(streams, StreamQuality.P1080, preferredAddon = "B"))
        assertEquals(
            listOf(c1080),
            StreamQualityMenu.rankForQuality(streams, StreamQuality.P1080, preferredAddon = "B", isCurrentStream = { it == a1080 || it == b1080 })
        )
        assertEquals(emptyList<Stream>(), StreamQualityMenu.rankForQuality(streams, StreamQuality.P2160, preferredAddon = "B"))
    }

    // --- fallback --------------------------------------------------------------------------

    @Test
    fun `falls back to the next addon in line at the same quality`() {
        val a1080 = stream("A", "A 1080p")
        val b720 = stream("B", "B 720p")
        val b1080 = stream("B", "B 1080p")
        val c1080 = stream("C", "C 1080p")
        val streams = listOf(a1080, b720, b1080, c1080)

        val next = StreamFallbackSelector.next(streams, "A", StreamQuality.P1080, isTried = { it == a1080 })

        assertEquals(b1080, next)
    }

    @Test
    fun `prefers a step down over a step up when the quality is missing`() {
        val a1080 = stream("A", "A 1080p")
        val b4k = stream("B", "B 4K")
        val b720 = stream("B", "B 720p")
        val streams = listOf(a1080, b4k, b720)

        assertEquals(b720, StreamFallbackSelector.next(streams, "A", StreamQuality.P1080, isTried = { it == a1080 }))
    }

    @Test
    fun `wraps around the addon order and tries the failed addon last`() {
        val a1080 = stream("A", "A 1080p")
        val b1080 = stream("B", "B 1080p")
        val b720 = stream("B", "B 720p")
        val c1080 = stream("C", "C 1080p")
        val streams = listOf(a1080, b1080, b720, c1080)
        val tried = mutableSetOf(b1080)

        assertEquals(c1080, StreamFallbackSelector.next(streams, "B", StreamQuality.P1080) { it in tried })
        tried += c1080
        assertEquals(a1080, StreamFallbackSelector.next(streams, "C", StreamQuality.P1080) { it in tried })
        tried += a1080
        assertEquals(b720, StreamFallbackSelector.next(streams, "A", StreamQuality.P1080) { it in tried })
        tried += b720
        assertNull(StreamFallbackSelector.next(streams, "B", StreamQuality.P1080) { it in tried })
    }

    @Test
    fun `unlabeled streams are tried after labeled ones from the same addon`() {
        val support = stream("PenguPlay", "support the project!")
        val pengu4k = stream("PenguPlay", "PenguPlay 4K")
        val a = stream("A", "A 1080p")

        assertEquals(pengu4k, StreamFallbackSelector.next(listOf(a, support, pengu4k), "A", StreamQuality.P1080) { it == a })
    }

    @Test
    fun `2K is a 1080p-class picture and a size is not a resolution`() {
        assertEquals(StreamQuality.P1080, StreamQualityParser.parseText("Movie.2K.Remaster.BluRay.x264"))
        assertNull(StreamQualityParser.parseText("1080 MB 12 seeders"))
        assertNull(StreamQualityParser.parseText("2160MB"))
        assertEquals(StreamQuality.P1080, StreamQualityParser.parseText("Movie 1080 WEB"))
        assertEquals(StreamQuality.P2160, StreamQualityParser.parseText("Movie.2160.HDR"))
    }
}
