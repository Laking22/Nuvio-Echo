package com.nuvio.tv.data.trailer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImdbTrailerSourceTest {

    private fun video(type: String, seconds: Int?, vararg defs: Pair<String, String>) =
        ImdbVideo(type, seconds, defs.map { (def, mime) -> ImdbPlayback("https://v/$type-$seconds-$def", mime, def) })

    @Test
    fun `prefers the first full-length trailer over a short cut listed before it`() {
        val picked = pickImdbTrailer(
            listOf(
                video("Trailer", 31, "DEF_1080p" to "MP4"),
                video("Trailer", 160, "DEF_1080p" to "MP4", "DEF_720p" to "MP4"),
                video("Trailer", 120, "DEF_1080p" to "MP4")
            ),
            maxHeight = 1080
        )
        assertEquals("https://v/Trailer-160-DEF_1080p", picked?.videoUrl)
    }

    @Test
    fun `takes the tallest MP4 within the cap, then HLS when no MP4 fits`() {
        val v = video("Trailer", 120, "DEF_1080p" to "MP4", "DEF_AUTO" to "M3U8", "DEF_720p" to "MP4", "DEF_SD" to "MP4")
        assertEquals("https://v/Trailer-120-DEF_720p", pickImdbTrailer(listOf(v), maxHeight = 720)?.videoUrl)
        assertEquals("https://v/Trailer-120-DEF_AUTO", pickImdbTrailer(listOf(v), maxHeight = 240)?.videoUrl)
    }

    @Test
    fun `falls back to any trailer, then any video, and to nothing without links`() {
        assertEquals(
            "https://v/Trailer-400-DEF_480p",
            pickImdbTrailer(listOf(video("Clip", 90, "DEF_1080p" to "MP4"), video("Trailer", 400, "DEF_480p" to "MP4")), 1080)?.videoUrl
        )
        assertEquals("https://v/Clip-90-DEF_1080p", pickImdbTrailer(listOf(video("Clip", 90, "DEF_1080p" to "MP4")), 1080)?.videoUrl)
        assertNull(pickImdbTrailer(listOf(ImdbVideo("Trailer", 100, emptyList())), 1080))
    }

    @Test
    fun `reads IMDb's definitions`() {
        assertEquals(1080, imdbDefinitionHeight("DEF_1080p"))
        assertEquals(360, imdbDefinitionHeight("DEF_SD"))
        assertNull(imdbDefinitionHeight("DEF_AUTO"))
    }
}
