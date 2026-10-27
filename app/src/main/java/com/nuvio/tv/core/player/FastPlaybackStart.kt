package com.nuvio.tv.core.player

import android.content.Context
import com.nuvio.tv.data.local.BetaPerformanceSettings
import com.nuvio.tv.data.local.BetaPerformanceSettingsDataStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface BetaPerformanceSettingsEntryPoint {
    fun betaPerformanceSettings(): BetaPerformanceSettingsDataStore
}

/**
 * Beta speed-up switches for code that is built deep inside PlayerRuntimeController, where the
 * settings store isn't injected. Falls back to the defaults if Hilt isn't reachable.
 */
object BetaFeatures {
    fun settings(context: Context): BetaPerformanceSettings = runCatching {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            BetaPerformanceSettingsEntryPoint::class.java
        ).betaPerformanceSettings().current.value
    }.getOrDefault(BetaPerformanceSettings())
}

/**
 * "Fast playback start": begin playing once [START_BUFFER_MS] of media is buffered instead of
 * the player's usual 2.5–5s.
 */
object FastPlaybackStart {
    const val START_BUFFER_MS = 1_500

    fun isEnabled(context: Context): Boolean = BetaFeatures.settings(context).fastPlaybackStart

    fun startBufferMs(context: Context?, configuredMs: Int): Int =
        if (context != null && isEnabled(context)) configuredMs.coerceAtMost(START_BUFFER_MS) else configuredMs
}
