package com.nuvio.tv.data.local

import android.app.Application
import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36], application = Application::class)
class ImagePerformancePreferencesTest {

    private val context: Context get() = org.robolectric.RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("image_performance", Context.MODE_PRIVATE)

    @Test
    fun `a saved off from the A-B test is switched on once, and off again sticks`() {
        prefs.edit().clear().putBoolean("hardware_bitmaps_enabled", false).commit()

        assertTrue(ImagePerformancePreferences(context).hardwareBitmapsEnabled)

        ImagePerformancePreferences(context).setHardwareBitmapsEnabled(false)
        assertFalse(ImagePerformancePreferences(context).hardwareBitmapsEnabled)
    }

    @Test
    fun `nothing saved reads on`() {
        prefs.edit().clear().commit()
        assertTrue(ImagePerformancePreferences(context).hardwareBitmapsEnabled)
    }
}
