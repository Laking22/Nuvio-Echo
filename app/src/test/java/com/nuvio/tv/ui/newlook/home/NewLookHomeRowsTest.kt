package com.nuvio.tv.ui.newlook.home

import androidx.compose.ui.unit.dp
import com.nuvio.tv.core.home.BuiltInRow
import com.nuvio.tv.core.home.BuiltInRowsRepository
import com.nuvio.tv.data.local.BetaPerformanceSettings
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.catalogRowLegacyKey
import com.nuvio.tv.ui.screens.home.HeroCarouselRow
import com.nuvio.tv.ui.screens.home.HeroPreview
import com.nuvio.tv.ui.screens.home.MODERN_CONTINUE_WATCHING_ROW_KEY
import com.nuvio.tv.ui.screens.home.MODERN_UPCOMING_ROW_KEY
import com.nuvio.tv.ui.screens.home.ModernCarouselItem
import com.nuvio.tv.ui.screens.home.ModernPayload
import com.nuvio.tv.ui.util.asStable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class NewLookHomeRowsTest {

    private val titles = BuiltInTitles(
        trending = "Trending This Week",
        becauseYouWatched = "Because You Watched %s",
        forYou = "For You",
        topMovies = "Top 20 Movies Today",
        topShows = "Top 20 Shows Today",
        movie = "Movie",
        show = "Show"
    )

    private fun meta(id: String) = MetaPreview(
        id = id, type = ContentType.MOVIE, name = "Title $id", poster = "p/$id", posterShape = PosterShape.POSTER,
        background = "b/$id", logo = null, description = null, releaseInfo = "2026", imdbRating = 7.5f, genres = emptyList()
    )

    private fun item(key: String, payload: ModernPayload = ModernPayload.Catalog(key, key, "movie", "", key, null, "movie")) =
        ModernCarouselItem(
            key = key, title = key, subtitle = null, imageUrl = null,
            heroPreview = HeroPreview(
                title = key, logo = null, description = null, contentTypeText = null, yearText = null, imdbText = null,
                genres = emptyList<String>().asStable(), poster = null, backdrop = null, imageUrl = null
            ),
            payload = payload
        )

    private fun catalog(addon: String, type: String, id: String, size: Int = 5) = HeroCarouselRow(
        key = "row_${addon}_${type}_$id", title = "$addon $type $id", globalRowIndex = 0,
        items = (0 until size).map { item("${addon}_${id}_$it") }.asStable(),
        catalogId = id, addonId = addon, apiType = type
    )

    private fun wide(key: String) = HeroCarouselRow(key = key, title = key, globalRowIndex = 0, items = listOf(item("$key-0")).asStable())

    private fun collection(id: String) = HeroCarouselRow(
        key = "collection_$id", title = "Collection $id", globalRowIndex = 0,
        items = (0 until 3).map {
            item(
                "folder$it",
                ModernPayload.CollectionFolder(
                    focusKey = "f$it", collectionId = id, collectionTitle = "C", folderId = "f$it",
                    posterShape = PosterShape.LANDSCAPE, focusGlowEnabled = false, focusGifEnabled = false,
                    focusGifUrl = null, heroBackdropUrl = null, heroVideoUrl = null, titleLogoUrl = null
                )
            )
        }.asStable()
    )

    private val fullBuiltIns = BuiltInRowsRepository.Rows(
        trendingWeek = (1..20).map { meta("tmdb:t$it") },
        topMoviesToday = (1..20).map { meta("tmdb:m$it") },
        topShowsToday = (1..20).map { meta("tmdb:s$it") },
        becauseYouWatched = BuiltInRowsRepository.BecauseYouWatched("Dune", (1..10).map { meta("tmdb:b$it") }),
        forYou = (1..20).map { meta("tmdb:f$it") }
    )

    private val presentation = listOf(
        wide(MODERN_CONTINUE_WATCHING_ROW_KEY),
        catalog("cinemeta", "series", "top"),
        catalog("cinemeta", "movie", "top"),
        collection("marvel"),
        catalog("tmdb", "movie", "popular")
    )

    private fun build(settings: BetaPerformanceSettings = BetaPerformanceSettings(), rows: List<HeroCarouselRow> = presentation) =
        buildNewLookRows(rows, fullBuiltIns, settings, titles)

    @Test
    fun `default layout keeps Continue Watching first and places the built-ins as before`() {
        val keys = build().map { it.key }
        assertEquals(
            listOf(
                MODERN_CONTINUE_WATCHING_ROW_KEY,
                BuiltInRow.TRENDING_WEEK.rowKey, BuiltInRow.BECAUSE_YOU_WATCHED.rowKey, BuiltInRow.FOR_YOU.rowKey,
                "row_cinemeta_series_top", BuiltInRow.TOP_SHOWS_TODAY.rowKey,
                "row_cinemeta_movie_top", BuiltInRow.TOP_MOVIES_TODAY.rowKey,
                "collection_marvel",
                "row_tmdb_movie_popular"
            ),
            keys
        )
    }

    @Test
    fun `row kinds - wide, poster, collection and highlighted built-ins`() {
        val rows = build().associateBy { it.title }
        assertEquals(RowKind.WIDE, rows.getValue(MODERN_CONTINUE_WATCHING_ROW_KEY).kind)
        assertEquals(RowKind.POSTER, rows.getValue("cinemeta movie top").kind)
        assertEquals(RowKind.COLLECTION, rows.getValue("Collection marvel").kind)
        assertEquals(PosterShape.LANDSCAPE, rows.getValue("Collection marvel").folderShape)
        assertEquals(RowKind.HIGHLIGHT, rows.getValue("Trending This Week").kind)
        assertTrue(rows.getValue("Top 20 Movies Today").ranked)
        assertFalse(rows.getValue("Trending This Week").ranked)
        assertEquals("Because You Watched Dune", rows.getValue("Because You Watched Dune").title)
    }

    @Test
    fun `hidden built-ins and built-ins with too few titles don't show`() {
        val settings = BetaPerformanceSettings(hiddenBuiltInRows = setOf(BuiltInRow.TRENDING_WEEK.key))
        val keys = buildNewLookRows(
            presentation,
            fullBuiltIns.copy(forYou = fullBuiltIns.forYou.take(MIN_BUILT_IN_ITEMS - 1), becauseYouWatched = null),
            settings,
            titles
        ).map { it.key }
        assertFalse(BuiltInRow.TRENDING_WEEK.rowKey in keys)
        assertFalse(BuiltInRow.FOR_YOU.rowKey in keys)
        assertFalse(BuiltInRow.BECAUSE_YOU_WATCHED.rowKey in keys)
        assertTrue(BuiltInRow.TOP_MOVIES_TODAY.rowKey in keys)
    }

    @Test
    fun `plain built-ins become poster rows without rank numbers`() {
        val settings = BetaPerformanceSettings(plainBuiltInRows = setOf(BuiltInRow.TOP_MOVIES_TODAY.key))
        val row = build(settings).single { it.key == BuiltInRow.TOP_MOVIES_TODAY.rowKey }
        assertEquals(RowKind.POSTER, row.kind)
        assertFalse(row.ranked)
        assertEquals("p/tmdb:m1", row.items.first().heroPreview.poster)
    }

    @Test
    fun `catalogs and collections highlight by their catalog order key`() {
        val settings = BetaPerformanceSettings(
            highlightedRows = setOf(catalogRowLegacyKey("tmdb", "movie", "popular"), "collection_marvel")
        )
        val rows = build(settings).associateBy { it.title }
        assertEquals(RowKind.HIGHLIGHT, rows.getValue("tmdb movie popular").kind)
        assertEquals(RowKind.HIGHLIGHT, rows.getValue("Collection marvel").kind)
        assertTrue(rows.getValue("Collection marvel").collection)
        assertEquals(RowKind.POSTER, rows.getValue("cinemeta movie top").kind)
    }

    @Test
    fun `the order screen's saved places are followed`() {
        val tmdb = catalogRowLegacyKey("tmdb", "movie", "popular")
        val settings = BetaPerformanceSettings(
            homeRowOrder = listOf(
                catalogRowLegacyKey("cinemeta", "series", "top"),
                catalogRowLegacyKey("cinemeta", "movie", "top"),
                "collection_marvel",
                tmdb,
                BuiltInRow.TRENDING_WEEK.rowKey
            )
        )
        val keys = build(settings).map { it.key }
        assertEquals(BuiltInRow.TRENDING_WEEK.rowKey, keys.last())
        assertEquals("row_tmdb_movie_popular", keys[keys.size - 2])
    }

    @Test
    fun `upcoming episodes keep their place among the catalogs`() {
        val rows = listOf(
            wide(MODERN_CONTINUE_WATCHING_ROW_KEY),
            catalog("cinemeta", "series", "top"),
            wide(MODERN_UPCOMING_ROW_KEY),
            catalog("cinemeta", "movie", "top")
        )
        val out = build(rows = rows)
        assertEquals(
            listOf(
                MODERN_CONTINUE_WATCHING_ROW_KEY,
                BuiltInRow.TRENDING_WEEK.rowKey, BuiltInRow.BECAUSE_YOU_WATCHED.rowKey, BuiltInRow.FOR_YOU.rowKey,
                "row_cinemeta_series_top", BuiltInRow.TOP_SHOWS_TODAY.rowKey,
                MODERN_UPCOMING_ROW_KEY,
                "row_cinemeta_movie_top", BuiltInRow.TOP_MOVIES_TODAY.rowKey
            ),
            out.map { it.key }
        )
        assertEquals(RowKind.WIDE, out.single { it.key == MODERN_UPCOMING_ROW_KEY }.kind)
    }

    @Test
    fun `no catalogs yet - built-ins still show after Continue Watching`() {
        val keys = build(rows = listOf(wide(MODERN_CONTINUE_WATCHING_ROW_KEY))).map { it.key }
        assertEquals(MODERN_CONTINUE_WATCHING_ROW_KEY, keys.first())
        assertEquals(6, keys.size)
    }

    @Test
    fun `built-in cards open the title's details from TMDB ids`() {
        val row = build().single { it.key == BuiltInRow.TRENDING_WEEK.rowKey }
        val payload = row.items.first().payload as ModernPayload.Catalog
        assertEquals("tmdb:t1", payload.itemId)
        assertEquals("", payload.addonBaseUrl)
        assertEquals(row.items.size, row.items.map { it.key }.toSet().size)
        assertNull(row.source)
    }

    @Test
    fun `placeholder rows are recognised only while loading`() {
        val loading = catalog("a", "movie", "x").let { r ->
            r.copy(isLoading = true, items = (0 until 3).map { item("__placeholder_$it", ModernPayload.Catalog("f", "__placeholder_$it", "movie", "", "", null, "movie")) }.asStable())
        }
        val row = build(rows = listOf(loading)).single { it.source != null }
        assertTrue(row.isPlaceholder)
        assertFalse(build().single { it.title == "tmdb movie popular" }.isPlaceholder)
    }

    /** Big random Homes: every presentation row once in order, every shown built-in once, nothing else. */
    @Test
    fun `stress - random homes and settings keep every row exactly once`() {
        val random = Random(7)
        repeat(1_000) { round ->
            val rows = buildList {
                if (random.nextBoolean()) add(wide(MODERN_CONTINUE_WATCHING_ROW_KEY))
                repeat(random.nextInt(0, 30)) { i ->
                    when (random.nextInt(4)) {
                        0 -> add(collection("c$i"))
                        1 -> if (none { it.key == MODERN_UPCOMING_ROW_KEY }) add(wide(MODERN_UPCOMING_ROW_KEY))
                        else -> add(catalog("addon${i % 3}", if (random.nextBoolean()) "movie" else "series", "cat$i"))
                    }
                }
            }
            val hidden = BuiltInRow.entries.filter { random.nextInt(4) == 0 }.map { it.key }.toSet()
            val saved = (rows.mapNotNull { r -> r.catalogId?.let { catalogRowLegacyKey(r.addonId!!, r.apiType!!, it) } ?: r.key } +
                BuiltInRow.entries.map { it.rowKey }).shuffled(random).take(random.nextInt(0, 40))
            val settings = BetaPerformanceSettings(hiddenBuiltInRows = hidden, homeRowOrder = saved)
            val out = buildNewLookRows(rows, fullBuiltIns, settings, titles)

            assertEquals("round $round", rows.map { it.key }, out.filter { it.builtIn == null }.map { it.key })
            val shownBuiltIns = out.mapNotNull { it.builtIn }
            assertEquals(BuiltInRow.entries.filter { it.key !in hidden }.toSet(), shownBuiltIns.toSet())
            assertEquals(shownBuiltIns.size, shownBuiltIns.toSet().size)
            if (rows.firstOrNull()?.key == MODERN_CONTINUE_WATCHING_ROW_KEY) assertEquals(MODERN_CONTINUE_WATCHING_ROW_KEY, out.first().key)
        }
    }

    @Test
    fun `grand rows win over highlighted, for catalogs and built-ins, but never for collections`() {
        val movieKey = catalogRowLegacyKey("cinemeta", "movie", "top")
        val settings = BetaPerformanceSettings(
            grandRows = setOf(movieKey, BuiltInRow.TOP_MOVIES_TODAY.key, "collection_marvel"),
            highlightedRows = setOf(movieKey)
        )
        val rows = build(settings).associateBy { it.key }
        assertEquals(RowKind.GRAND, rows.getValue("row_cinemeta_movie_top").kind)
        val top = rows.getValue(BuiltInRow.TOP_MOVIES_TODAY.rowKey)
        assertEquals(RowKind.GRAND, top.kind)
        assertFalse("grand rows drop the rank numbers", top.ranked)
        assertEquals(RowKind.COLLECTION, rows.getValue("collection_marvel").kind)
        // Others unchanged.
        assertEquals(RowKind.HIGHLIGHT, rows.getValue(BuiltInRow.TRENDING_WEEK.rowKey).kind)
    }

    @Test
    fun `a grand row is the hero window's size with its details inside`() {
        val dims = NewLookDimens.of().withGrandWidth(960.dp, NewLookLayout.ContentStartWithSidebar)
        val row = build(BetaPerformanceSettings(grandRows = setOf(BuiltInRow.TRENDING_WEEK.key)))
            .first { it.key == BuiltInRow.TRENDING_WEEK.rowKey }
        assertEquals(830.dp, row.cardWidth(dims))
        assertEquals(NewLookLayout.WindowHeight, row.cardHeight(dims))
        assertEquals(0.dp, row.metaHeight(dims))
    }
}
