package com.nuvio.tv.ui.screens.browse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PosterMeshTest {

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun `biggest colour comes first and spots sit where the colour is`() {
        val w = 24
        val h = 36
        // Top two thirds deep blue, bottom third orange.
        val pixels = IntArray(w * h) { i -> if (i / w < 24) rgb(20, 40, 160) else rgb(240, 120, 20) }
        val mesh = posterMesh(pixels, w, h)!!
        assertEquals(2, mesh.spots.size)
        val (blue, orange) = mesh.spots
        assertTrue(blue.color.blue > 0.5f && blue.color.red < 0.2f)
        assertTrue(orange.color.red > 0.8f)
        assertEquals(0.5f, blue.x, 0.01f)
        assertEquals(12f / 36f, blue.y, 0.01f)
        assertEquals(30f / 36f, orange.y, 0.01f)
        assertEquals(1f, mesh.spots.sumOf { it.share.toDouble() }.toFloat(), 0.001f)
    }

    @Test
    fun `a small saturated accent beats a near-identical shade of the background`() {
        val w = 24
        val h = 36
        val pixels = IntArray(w * h) { i ->
            when {
                i % 7 == 0 -> rgb(14, 14, 14) // a shade of the dark background, too close to count twice
                i < w * 4 -> rgb(220, 20, 30) // a red title band
                else -> rgb(8, 8, 8)
            }
        }
        val spots = posterMesh(pixels, w, h)!!.spots
        assertEquals(2, spots.size)
        assertTrue(spots.any { it.color.red > 0.7f && it.color.green < 0.2f })
    }

    @Test
    fun `at most four distinct spots, all inside the poster`() {
        val random = Random(7)
        repeat(200) {
            val w = 1 + random.nextInt(30)
            val h = 1 + random.nextInt(40)
            val pixels = IntArray(w * h) { rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256)) }
            val spots = posterMesh(pixels, w, h)!!.spots
            assertTrue(spots.size in 1..MeshSpots)
            spots.forEach {
                assertTrue(it.x in 0f..1f && it.y in 0f..1f && it.share > 0f)
            }
        }
    }

    @Test
    fun `transparent or empty input has no mesh`() {
        assertNull(posterMesh(IntArray(4), 2, 2))
        assertNull(posterMesh(IntArray(0), 0, 0))
        assertNull(posterMesh(IntArray(3), 2, 2))
    }
}
