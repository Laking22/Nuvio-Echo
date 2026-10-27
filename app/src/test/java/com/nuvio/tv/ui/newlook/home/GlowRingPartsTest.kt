package com.nuvio.tv.ui.newlook.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlowRingPartsTest {

    @Test
    fun `the strips tile the ring around the box, reaching the corner radius in`() {
        val width = 1660; val height = 860; val spread = 220; val r = 32
        val parts = glowRingParts(maskWidth = 525, maskHeight = 325, width = width, height = height, spread = spread, inner = r)
        assertEquals(4, parts.size)
        val area = parts.sumOf { it.dstSize.width.toLong() * it.dstSize.height }
        val ring = (width + 2L * spread) * (height + 2L * spread) - (width - 2L * r) * (height - 2L * r)
        assertEquals(ring, area)
        // No two strips overlap on screen.
        for (i in parts.indices) for (j in i + 1 until parts.size) {
            val a = parts[i]; val b = parts[j]
            val overlapX = minOf(a.dstOffset.x + a.dstSize.width, b.dstOffset.x + b.dstSize.width) - maxOf(a.dstOffset.x, b.dstOffset.x)
            val overlapY = minOf(a.dstOffset.y + a.dstSize.height, b.dstOffset.y + b.dstSize.height) - maxOf(a.dstOffset.y, b.dstOffset.y)
            assertTrue("strips $i and $j overlap", overlapX <= 0 || overlapY <= 0)
        }
        // Every strip reads from inside the mask.
        parts.forEach { p ->
            assertTrue(p.srcOffset.x >= 0 && p.srcOffset.y >= 0)
            assertTrue(p.srcOffset.x + p.srcSize.width <= 525 && p.srcOffset.y + p.srcSize.height <= 325)
        }
    }

    @Test
    fun `nothing to draw for an empty box`() {
        assertEquals(emptyList<GlowPart>(), glowRingParts(10, 10, 0, 100, 20, 4))
    }
}
