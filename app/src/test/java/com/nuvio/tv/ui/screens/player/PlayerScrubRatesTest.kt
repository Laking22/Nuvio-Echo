package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerScrubRatesTest {

    @Test
    fun stepMsForKeyRepeat_rampsWithHold() {
        assertEquals(PlayerScrubRates.STEP_SHORT_MS, PlayerScrubRates.stepMsForKeyRepeat(0))
        assertEquals(PlayerScrubRates.STEP_SHORT_MS, PlayerScrubRates.stepMsForKeyRepeat(5))
        assertEquals(PlayerScrubRates.STEP_MEDIUM_MS, PlayerScrubRates.stepMsForKeyRepeat(6))
        assertEquals(PlayerScrubRates.STEP_MEDIUM_MS, PlayerScrubRates.stepMsForKeyRepeat(19))
        assertEquals(PlayerScrubRates.STEP_LONG_MS, PlayerScrubRates.stepMsForKeyRepeat(20))
        assertEquals(PlayerScrubRates.STEP_LONG_MS, PlayerScrubRates.stepMsForKeyRepeat(39))
        assertEquals(PlayerScrubRates.STEP_VERY_LONG_MS, PlayerScrubRates.stepMsForKeyRepeat(40))
        assertEquals(PlayerScrubRates.STEP_VERY_LONG_MS, PlayerScrubRates.stepMsForKeyRepeat(100))
    }

    @Test
    fun holdingAcrossAFilmTakesAboutThirteenSeconds() {
        // Remotes repeat about 20 times a second; a two-hour film should take a while to cross.
        var covered = 0L
        var repeats = 0
        while (covered < 2 * 60 * 60 * 1000L) covered += PlayerScrubRates.stepMsForKeyRepeat(repeats++)
        val seconds = repeats / 20.0
        assertTrue("crossed in $seconds s", seconds in 11.0..16.0)
    }

    @Test
    fun deltaMsForKeyRepeat_appliesDirection() {
        assertEquals(-PlayerScrubRates.STEP_SHORT_MS, PlayerScrubRates.deltaMsForKeyRepeat(0, forward = false))
        assertEquals(PlayerScrubRates.STEP_LONG_MS, PlayerScrubRates.deltaMsForKeyRepeat(20, forward = true))
    }

    @Test
    fun stepMsForKeyRepeat_clampsNegativeRepeatCount() {
        assertEquals(PlayerScrubRates.STEP_SHORT_MS, PlayerScrubRates.stepMsForKeyRepeat(-1))
    }
}
