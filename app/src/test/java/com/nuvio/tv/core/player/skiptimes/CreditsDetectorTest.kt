package com.nuvio.tv.core.player.skiptimes

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CreditsDetectorTest {

    private fun frame(blackShare: Float, brightShare: Float, bright: Int = 0xFFF0F0F0.toInt(), mid: Int = 0xFF606060.toInt()): IntArray {
        val n = 10_000
        val blacks = (n * blackShare).toInt()
        val brights = (n * brightShare).toInt()
        return IntArray(n) { i ->
            when {
                i < blacks -> 0xFF050505.toInt()
                i < blacks + brights -> bright
                else -> mid
            }
        }
    }

    @Test
    fun `lettering on black is lettered, a fade is black, a night scene with lamps is neither`() {
        val credits = CreditsDetector.stats(frame(0.95f, 0.03f))
        assertTrue(credits.isBlack)
        assertTrue(credits.isLettered)
        val fade = CreditsDetector.stats(frame(1f, 0f))
        assertTrue(fade.isBlack)
        assertFalse(fade.isLettered)
        val night = CreditsDetector.stats(frame(0.9f, 0.02f, bright = 0xFFFFB040.toInt()))
        assertTrue(night.isBlack)
        assertFalse(night.isLettered)
        assertFalse(CreditsDetector.stats(frame(0.4f, 0.1f)).isBlack)
    }

    private val lettered = CreditsDetector.stats(frame(0.95f, 0.03f))
    private val scene = CreditsDetector.stats(frame(0.2f, 0.1f))
    private val dark = CreditsDetector.stats(frame(1f, 0f))

    @Test
    fun `credits from 22 minutes in a 24 minute episode`() = runBlocking {
        val duration = 1_440_000L
        val found = CreditsDetector.find(duration, movie = false, grab = { at -> if (at >= 1_320_000L) lettered else scene })
        assertNotNull(found)
        found!!
        assertEquals("outro", found.type)
        assertTrue("start ${found.startTime}", found.startTime in 1_316.0..1_320.0)
        assertEquals(1_440.0, found.endTime, 0.001)
    }

    @Test
    fun `credits followed by a scene end where it starts`() = runBlocking {
        val duration = 3_000_000L
        val found = CreditsDetector.find(duration, movie = false, grab = { at ->
            when {
                at < 2_800_000L -> scene
                at < 2_900_000L -> lettered
                else -> scene
            }
        })!!
        assertTrue("start ${found.startTime}", found.startTime in 2_800.0..2_804.0)
        assertTrue("end ${found.endTime}", found.endTime in 2_900.0..2_904.0)
    }

    @Test
    fun `game of thrones S1E3's dark scene before the credits isn't taken for them`() = runBlocking {
        // The looks logged on a 4K release (3439 s): a dark scene at 3109-3124 s with a speck of
        // light, then the credits from 3379 s to the end.
        val logged = "2989 0.65 0.021 0.95;3004 0.43 0.021 1.00;3019 0.79 0.030 1.00;3034 0.70 0.024 0.94;" +
            "3049 0.67 0.019 0.59;3064 0.40 0.001 0.03;3079 0.57 0.001 0.07;3094 0.63 0.000 0.00;3109 0.84 0.002 1.00;" +
            "3124 0.83 0.000 1.00;3139 0.75 0.000 0.00;3154 0.09 0.017 0.01;3169 0.39 0.028 0.18;3184 0.26 0.301 1.00;" +
            "3199 0.19 0.288 1.00;3214 0.44 0.028 0.45;3229 0.31 0.094 0.03;3244 0.39 0.083 0.96;3259 0.52 0.041 0.95;" +
            "3274 0.60 0.085 1.00;3289 0.44 0.003 0.47;3304 0.43 0.010 0.71;3319 0.46 0.114 0.97;3334 0.44 0.014 0.03;" +
            "3349 0.40 0.006 0.65;3364 0.57 0.000 0.00;3379 0.98 0.004 1.00;3394 0.91 0.021 1.00;3409 0.90 0.023 1.00;" +
            "3424 0.90 0.023 1.00;3436 0.97 0.010 1.00"
        val looks = logged.split(';').associate { look ->
            val (at, black, bright, grey) = look.split(' ')
            at.toLong() * 1000 to CreditsDetector.FrameStats(black.toFloat(), bright.toFloat(), grey.toFloat())
        }
        val found = CreditsDetector.find(3_439_000L, movie = false, grab = { at -> looks[at] })!!
        assertEquals(3_379.0, found.startTime, 0.001)
        assertEquals(3_439.0, found.endTime, 0.001)
    }

    @Test
    fun `a dark scene without lettering, or a short card, isn't credits`() = runBlocking {
        val duration = 1_440_000L
        assertNull(CreditsDetector.find(duration, movie = false, grab = { at -> if (at >= 1_300_000L) dark else scene }))
        assertNull(CreditsDetector.find(duration, movie = false, grab = { at -> if (at in 1_300_000L..1_310_000L) lettered else scene }))
        assertNull(CreditsDetector.find(duration, movie = false, grab = { null }))
    }
}
