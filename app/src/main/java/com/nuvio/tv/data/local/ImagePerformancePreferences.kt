package com.nuvio.tv.data.local

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ImagePerformancePreferences @Inject constructor(
    @param:ApplicationContext context: Context
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    init {
        migrateHardwareBitmapsDefault(preferences)
    }

    val rgb565Enabled: Boolean
        get() = preferences.getBoolean(KEY_RGB565_ENABLED, true)

    fun setRgb565Enabled(enabled: Boolean): Boolean {
        return preferences.edit()
            .putBoolean(KEY_RGB565_ENABLED, enabled)
            .commit()
    }

    /**
     * Decode images straight into graphics memory (one copy) instead of app memory plus a GPU copy. On by default:
     * on a 32-bit TV box app memory was holding every poster and backdrop twice. Code that reads
     * pixels asks for software bitmaps itself (allowHardware(false); Coil does so for transformations).
     */
    val hardwareBitmapsEnabled: Boolean
        get() = preferences.getBoolean(KEY_HARDWARE_BITMAPS_ENABLED, true)

    fun setHardwareBitmapsEnabled(enabled: Boolean): Boolean {
        return preferences.edit()
            .putBoolean(KEY_HARDWARE_BITMAPS_ENABLED, enabled)
            .commit()
    }

    internal companion object {
        /**
         * Once: a saved "off" from the earlier A/B test becomes on, so the new default reaches those devices too
         * (it only applied where nothing was saved). Switching it off again afterwards sticks.
         */
        fun migrateHardwareBitmapsDefault(preferences: android.content.SharedPreferences) {
            if (preferences.getBoolean(KEY_HARDWARE_BITMAPS_MIGRATED, false)) return
            preferences.edit()
                .putBoolean(KEY_HARDWARE_BITMAPS_ENABLED, true)
                .putBoolean(KEY_HARDWARE_BITMAPS_MIGRATED, true)
                .commit()
        }

        const val KEY_HARDWARE_BITMAPS_MIGRATED = "hardware_bitmaps_default_on_migrated"
        const val PREFERENCES_NAME = "image_performance"
        const val KEY_RGB565_ENABLED = "rgb565_enabled"
        const val KEY_HARDWARE_BITMAPS_ENABLED = "hardware_bitmaps_enabled"
    }
}
