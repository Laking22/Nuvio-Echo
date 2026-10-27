package com.nuvio.tv.ui.newlook.home

import com.nuvio.tv.core.home.BuiltInRowsRepository
import com.nuvio.tv.core.home.smart.HighlightNameFilter
import com.nuvio.tv.core.home.smart.SmartHighlightRow
import com.nuvio.tv.data.local.BetaPerformanceSettings
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.ui.screens.home.HeroCarouselRow
import com.nuvio.tv.ui.screens.home.HeroPreview
import com.nuvio.tv.ui.screens.home.MODERN_CONTINUE_WATCHING_ROW_KEY
import com.nuvio.tv.ui.screens.home.ModernCarouselItem
import com.nuvio.tv.ui.screens.home.ModernPayload
import com.nuvio.tv.ui.util.asStable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartHighlightRowsTest {

    private val titles = BuiltInTitles("Trending", "Because You Watched %s", "For You", "Top Movies", "Top Shows", "Movie", "Show")
    private val day = 20_000L

    private fun meta(id: String) = MetaPreview(
        id = id, type = ContentType.MOVIE, name = "Title $id", poster = "p/$id", posterShape = PosterShape.POSTER,
        background = "b/$id", logo = null, description = null, releaseInfo = "2026", imdbRating = 7.5f, genres = emptyList()
    )

    private fun item(key: String) = ModernCarouselItem(
        key = key, title = key, subtitle = null, imageUrl = null,
        heroPreview = HeroPreview(
            title = key, logo = null, description = null, contentTypeText = null, yearText = null, imdbText = null,
            genres = emptyList<String>().asStable(), poster = null, backdrop = null, imageUrl = null
        ),
        payload = ModernPayload.Catalog(key, key, "movie", "", key, null, "movie")
    )

    private fun catalog(i: Int) = HeroCarouselRow(
        key = "row_$i", title = "Catalog $i", globalRowIndex = 0,
        items = (0 until 5).map { item("c${i}_$it") }.asStable(),
        catalogId = "cat$i", addonId = "addon", apiType = "movie"
    )

    private val cw = HeroCarouselRow(key = MODERN_CONTINUE_WATCHING_ROW_KEY, title = "CW", globalRowIndex = 0, items = listOf(item("cw")).asStable())

    private fun smart(id: String, title: String = "Theme $id", aiNamed: Boolean = false, size: Int = 10) =
        SmartHighlightRow(id, title, "Theme $id", aiNamed, (1..size).map { meta("tmdb:$id$it") })

    private val threeSmart = listOf(smart("a"), smart("b"), smart("c"))

    private fun build(
        catalogs: Int = 10,
        smart: List<SmartHighlightRow> = threeSmart,
        settings: BetaPerformanceSettings = BetaPerformanceSettings()
    ) = buildNewLookRows(
        listOf(cw) + (0 until catalogs).map(::catalog), BuiltInRowsRepository.Rows(), settings, titles, smart, day
    )

    @Test
    fun `smart highlights spread evenly down the page`() {
        // Continue Watching and 10 catalogs: 11 rows, three highlights after the 3rd, 6th and 8th.
        val keys = build().map { it.key }
        assertEquals("row_1", keys[keys.indexOf("smart:a") - 1])
        assertEquals("row_4", keys[keys.indexOf("smart:b") - 1])
        assertEquals("row_6", keys[keys.indexOf("smart:c") - 1])
        val rows = build()
        assertTrue(rows.filter { it.smart != null }.all { it.kind == RowKind.HIGHLIGHT })
    }

    @Test
    fun `with few rows only those that fit two rows apart show`() {
        val keys = build(catalogs = 3).map { it.key }
        assertEquals(listOf(MODERN_CONTINUE_WATCHING_ROW_KEY, "row_0", "smart:a", "row_1", "row_2", "smart:b"), keys)
    }

    @Test
    fun `turned off, none show`() {
        assertTrue(build(settings = BetaPerformanceSettings(enrichment = BetaPerformanceSettings.ENRICHMENT_OFF)).none { it.smart != null })
    }

    @Test
    fun `heavy enrichment shows more, some as grand rows, and keeps the user's grand rows apart`() {
        val many = ('a'..'j').map { smart(it.toString()) }
        val heavy = BetaPerformanceSettings(enrichment = BetaPerformanceSettings.ENRICHMENT_HEAVY, grandRows = setOf("row_9"))
        val rows = build(catalogs = 30, smart = many, settings = heavy)
        val smartRows = rows.filter { it.smart != null }
        assertTrue(smartRows.size in 5..10)
        assertTrue(smartRows.any { it.kind == RowKind.GRAND })
        // Two to five other rows between highlights (smart ones and the user's grand row alike).
        var since = -1
        for (row in rows) {
            val highlight = row.smart != null || row.kind == RowKind.GRAND
            if (highlight) {
                if (since >= 0) assertTrue("only $since rows apart", since >= 2)
                since = 0
            } else if (since >= 0) {
                since++
            }
        }
    }

    @Test
    fun `Continue Watching follows the saved row order`() {
        val order = listOf("addon_movie_cat0", com.nuvio.tv.core.home.HomeRowOrder.CONTINUE_WATCHING_KEY)
        val keys = build(smart = emptyList(), settings = BetaPerformanceSettings(homeRowOrder = order)).map { it.key }
        assertEquals(listOf("row_0", MODERN_CONTINUE_WATCHING_ROW_KEY, "row_1"), keys.take(3))
    }

    @Test
    fun `blocked and snoozed highlights go at once`() {
        val keys = build(
            settings = BetaPerformanceSettings(blockedHighlights = setOf("a"), snoozedHighlights = setOf("b@${day + 1}"))
        ).map { it.key }
        assertFalse("smart:a" in keys)
        assertFalse("smart:b" in keys)
        assertTrue("smart:c" in keys)
    }

    @Test
    fun `removed titles go and a row left too short is dropped`() {
        val trimmed = BetaPerformanceSettings(highlightExclusions = setOf("a|tmdb:a1", "a|tmdb:a2", "b|tmdb:b1", "b|tmdb:b2", "b|tmdb:b3", "b|tmdb:b4", "b|tmdb:b5"))
        val rows = build(settings = trimmed)
        val a = rows.first { it.key == "smart:a" }
        assertEquals(8, a.items.size)
        assertFalse(a.items.any { it.metaPreview?.id == "tmdb:a1" })
        assertFalse(rows.any { it.key == "smart:b" })
    }

    @Test
    fun `an AI name shows only while AI names are on and goes back to the built-in name once blocked`() {
        val named = listOf(smart("a", title = "Brain Melters", aiNamed = true))
        val on = BetaPerformanceSettings(smartHighlightAiNames = true)
        assertEquals("Brain Melters", build(smart = named, settings = on).first { it.key == "smart:a" }.title)
        assertEquals("Theme a", build(smart = named).first { it.key == "smart:a" }.title)
        val blocked = on.copy(blockedHighlights = setOf(BetaPerformanceSettings.SMART_NAME_PREFIX + HighlightNameFilter.normalise("Brain Melters")))
        val row = build(smart = named, settings = blocked).first { it.key == "smart:a" }
        assertEquals("Theme a", row.title)
        assertFalse(row.smart!!.aiNamed)
    }
}
