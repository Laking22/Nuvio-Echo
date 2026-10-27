package com.nuvio.tv.ui.newlook.home

import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class FeaturedPickerTest {

    private fun item(id: String, show: Boolean) = MetaPreview(
        id = id,
        type = if (show) ContentType.SERIES else ContentType.MOVIE,
        name = id,
        poster = null,
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList()
    )

    private val pool = (0 until 10).map { item("m$it", show = false) } + (0 until 10).map { item("s$it", show = true) }

    @Test
    fun `titles featured lately wait while there are others`() {
        val recent = (0 until 7).map { "m$it" } + (0 until 7).map { "s$it" }
        val picked = FeaturedPicker.pick(pool, recent, Random(1))
        assertEquals(FeaturedPicker.SLIDES, picked.size)
        assertTrue(picked.none { it.id in recent })
    }

    @Test
    fun `with too few others the longest ago fill in`() {
        val recent = pool.map { it.id }.filter { it != "m9" }
        val picked = FeaturedPicker.pick(pool, recent, Random(2)).map { it.id }.toSet()
        assertTrue("m9" in picked)
        // The oldest remembered (the end of the list) come back before the newest.
        assertTrue("s9" in picked)
        assertTrue("m0" !in picked && "s0" !in picked)
    }

    @Test
    fun `movies and shows take turns`() {
        val picked = FeaturedPicker.pick(pool, emptyList(), Random(3))
        val kinds = picked.map { it.type }
        assertTrue(kinds.zipWithNext().all { (a, b) -> a != b })
    }

    @Test
    fun `history keeps the newest first and stays bounded`() {
        val recent = (0 until 40).map { "old$it" }
        val next = FeaturedPicker.remember(recent, pool.take(6))
        assertEquals(FeaturedPicker.HISTORY, next.size)
        assertEquals(pool.take(6).map { it.id }, next.take(6))
    }
}
