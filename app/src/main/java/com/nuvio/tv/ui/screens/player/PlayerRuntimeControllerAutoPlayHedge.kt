package com.nuvio.tv.ui.screens.player

import android.os.SystemClock
import android.util.Log
import com.nuvio.tv.R
import com.nuvio.tv.core.player.AutoPlayRace
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** How long a remembered link has to show its first frame before it's checked. */
private const val SLOW_START_CHECK_MS = 1_500L
/**
 * With nothing to show this long after it began, a remembered link is given up for the next source. A link can answer
 * and still not start: one sent its first megabyte at once while the player, which needed a part far into the file,
 * loaded for minutes.
 */
private const val SLOW_PICK_GIVE_UP_MS = 6_000L

/**
 * Race matching sources, for a remembered link (Reuse last link), which plays at once with no fresh matches to race
 * against: if it hasn't shown a frame within [SLOW_START_CHECK_MS] it's checked, and one that sends no video moves on
 * to the next source at once; one still showing nothing [SLOW_PICK_GIVE_UP_MS] in moves on too, so a bad link can't
 * leave the player loading for minutes. Runs for [url], the link being started, when the source page asked for it; a
 * retry of the same link keeps it.
 */
internal fun PlayerRuntimeController.armAutoPlayHedge(url: String) {
    if (url == autoPlayHedgeUrl && autoPlayHedgeJob?.isActive == true) return
    autoPlayHedgeJob?.cancel()
    autoPlayHedgeJob = null
    autoPlayHedgeUrl = null
    if (!AutoPlayRace.takeSlowStartWatch(url)) return
    autoPlayHedgeUrl = url
    val headers = currentHeaders
    val armedAt = SystemClock.elapsedRealtime()
    autoPlayHedgeJob = scope.launch {
        delay(SLOW_START_CHECK_MS)
        if (pickStarted() || currentStreamUrl != url) return@launch
        val delivers = AutoPlayRace.delivers(url, headers)
        if (pickStarted() || currentStreamUrl != url) return@launch
        if (!delivers) {
            Log.w(PlayerRuntimeController.TAG, "AUTOPLAY_RACE: the remembered link sends no video; moving on")
            moveOnFromPick()
            return@launch
        }
        while (SystemClock.elapsedRealtime() - armedAt < SLOW_PICK_GIVE_UP_MS) {
            if (pickStarted() || currentStreamUrl != url) return@launch
            delay(200)
        }
        if (pickStarted() || currentStreamUrl != url) return@launch
        Log.w(PlayerRuntimeController.TAG, "AUTOPLAY_RACE: nothing to show after ${SLOW_PICK_GIVE_UP_MS}ms; moving on")
        moveOnFromPick()
    }
}

/** The link has shown a frame (or is playing, for an engine that reports no frames). */
private fun PlayerRuntimeController.pickStarted(): Boolean = hasRenderedFirstFrame || _uiState.value.isPlaying

/** The next source takes over (Source fallback), or, with that off or none left, the player says so. */
private fun PlayerRuntimeController.moveOnFromPick() {
    val message = context.getString(R.string.player_link_no_video)
    val showError = {
        _uiState.update { it.copy(error = message, showLoadingOverlay = false, isBuffering = false) }
    }
    errorRetryJob?.cancel()
    if (!tryFallbackToNextSource(message, onExhausted = showError)) showError()
}
