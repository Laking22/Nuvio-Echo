package com.nuvio.tv.ui.newlook.preview

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.nuvio.tv.ui.newlook.home.AmbientGlow
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the ambient glow the way Home uses it (inside a lazy list item, right at the top of the screen) and checks
 * there's glow on all four sides. A TV once showed it missing above and left of the hero; this setup
 * reproduced that on the JVM before the fix.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36], qualifiers = "w960dp-h540dp-land-xhdpi", application = Application::class)
class AmbientGlowRenderTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `glow shows on all four sides of a window near the top of a lazy list`() {
        // As the hero with the menu hidden: window at (24, 18) dp, 912 × 390, at 2 px per dp.
        rule.setContent {
            LazyColumn(Modifier.fillMaxSize().background(Color.Black)) {
                item {
                    Box(Modifier.size(960.dp, 422.dp)) {
                        AmbientGlow(Color.Red, Modifier.offset { IntOffset(24.dp.roundToPx(), 18.dp.roundToPx()) }.size(912.dp, 390.dp))
                        Box(Modifier.offset(24.dp, 18.dp).size(912.dp, 390.dp).clip(RoundedCornerShape(16.dp)).background(Color.DarkGray))
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(1500)
        rule.waitForIdle()
        var bitmap: Bitmap? = null
        rule.runOnUiThread {
            val view = rule.activity.window.decorView
            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        val image = bitmap!!
        fun red(x: Int, y: Int) = android.graphics.Color.red(image.getPixel(x, y))
        // Window in px: x 48..1872, y 36..816. Sample just outside each edge, at the middle and near the ends.
        val above = listOf(200, 960, 1700).map { red(it, 20) }
        val left = listOf(150, 420, 700).map { red(30, it) }
        val right = listOf(150, 420, 700).map { red(1890, it) }
        val below = listOf(200, 960, 1700).map { red(it, 832) }
        listOf("above" to above, "left" to left, "right" to right, "below" to below).forEach { (side, values) ->
            assertTrue("no glow $side the window: $values", values.all { it > 20 })
        }
    }
}
