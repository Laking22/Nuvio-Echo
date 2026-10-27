package com.nuvio.tv.core.intro

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the playback intro is on screen. The intro is drawn above the whole app so it can
 * start the moment media is opened (on the source screen when it auto-plays) and carry on,
 * unbroken, into the player. While "Playback intro" is on, everything that starts playing media
 * starts it: opening the player, next episode (by hand or automatically), picking an episode,
 * a source or a quality.
 */
object PlaybackIntroState {
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    @Volatile
    private var handedOffAtMs = 0L

    /** Starts the intro unless one is already playing. */
    fun start() {
        if (_active.value) return
        _active.value = true
    }

    /**
     * Starts the intro for a title that's about to open in the player (the source screen
     * auto-playing it), so the player opening next doesn't play it a second time.
     */
    fun startBeforePlayer() {
        handedOffAtMs = SystemClock.elapsedRealtime()
        start()
    }

    /** For the player opening: starts the intro unless the screen that opened it already did. */
    fun startOnPlayerOpen() {
        val handedOff = handedOffAtMs
        handedOffAtMs = 0L
        if (handedOff != 0L && SystemClock.elapsedRealtime() - handedOff < HANDOFF_WINDOW_MS) return
        start()
    }

    fun finish() {
        _active.value = false
    }

    /** Longest a source screen's intro waits for its player before it no longer counts. */
    private const val HANDOFF_WINDOW_MS = 30_000L
}
