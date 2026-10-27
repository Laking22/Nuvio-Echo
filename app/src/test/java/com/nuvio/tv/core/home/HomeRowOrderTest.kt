package com.nuvio.tv.core.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class HomeRowOrderTest {

    private val trending = BuiltInRow.TRENDING_WEEK.rowKey
    private val because = BuiltInRow.BECAUSE_YOU_WATCHED.rowKey
    private val forYou = BuiltInRow.FOR_YOU.rowKey
    private val topMovies = BuiltInRow.TOP_MOVIES_TODAY.rowKey
    private val topShows = BuiltInRow.TOP_SHOWS_TODAY.rowKey

    private val types = mapOf(
        "cinemeta_series_top" to "series",
        "cinemeta_movie_top" to "movie",
        "tmdb_movie_popular" to "movie",
        "collection_marvel" to "collection"
    )

    private fun merge(catalogs: List<String>, saved: List<String> = emptyList(), builtIns: List<BuiltInRow> = BuiltInRow.entries) =
        HomeRowOrder.merge(catalogs, builtIns, saved) { types[it] }

    @Test
    fun `defaults put the personal rows first and each Top 20 after the first catalog of its type`() {
        val merged = merge(listOf("cinemeta_series_top", "cinemeta_movie_top", "tmdb_movie_popular"))
        assertEquals(
            listOf(trending, because, forYou, "cinemeta_series_top", topShows, "cinemeta_movie_top", topMovies, "tmdb_movie_popular"),
            merged
        )
    }

    @Test
    fun `Top 20 without a catalog of its type goes after the first catalog`() {
        val merged = merge(listOf("collection_marvel", "tmdb_movie_popular"))
        assertEquals(listOf(trending, because, forYou, "collection_marvel", topShows, "tmdb_movie_popular", topMovies), merged)
    }

    @Test
    fun `no catalogs at all still lists every built-in row`() {
        val merged = merge(emptyList())
        assertEquals(BuiltInRow.entries.map { it.rowKey }.toSet(), merged.toSet())
        assertEquals(5, merged.size)
    }

    @Test
    fun `a saved built-in follows the row before it`() {
        val catalogs = listOf("cinemeta_series_top", "cinemeta_movie_top", "tmdb_movie_popular")
        val saved = listOf("cinemeta_series_top", "cinemeta_movie_top", trending, "tmdb_movie_popular")
        val merged = merge(catalogs, saved, listOf(BuiltInRow.TRENDING_WEEK))
        assertEquals(listOf("cinemeta_series_top", "cinemeta_movie_top", trending, "tmdb_movie_popular"), merged)
    }

    @Test
    fun `a saved built-in at the top stays at the top`() {
        val catalogs = listOf("cinemeta_series_top", "cinemeta_movie_top")
        val merged = merge(catalogs, listOf(topMovies, "cinemeta_series_top", "cinemeta_movie_top"), listOf(BuiltInRow.TOP_MOVIES_TODAY))
        assertEquals(listOf(topMovies, "cinemeta_series_top", "cinemeta_movie_top"), merged)
    }

    @Test
    fun `when the row before it is gone the built-in follows the next one up`() {
        // The anchor catalog was disabled or its addon removed: the row moves up to the next one that's left.
        val saved = listOf("cinemeta_series_top", "cinemeta_movie_top", topMovies, "tmdb_movie_popular")
        val merged = merge(listOf("cinemeta_series_top", "tmdb_movie_popular"), saved, listOf(BuiltInRow.TOP_MOVIES_TODAY))
        assertEquals(listOf("cinemeta_series_top", topMovies, "tmdb_movie_popular"), merged)
    }

    @Test
    fun `consecutive saved built-ins keep their own order`() {
        val saved = listOf("cinemeta_series_top", forYou, trending, topShows, "cinemeta_movie_top")
        val merged = merge(
            listOf("cinemeta_series_top", "cinemeta_movie_top"),
            saved,
            listOf(BuiltInRow.TRENDING_WEEK, BuiltInRow.FOR_YOU, BuiltInRow.TOP_SHOWS_TODAY)
        )
        assertEquals(listOf("cinemeta_series_top", forYou, trending, topShows, "cinemeta_movie_top"), merged)
    }

    @Test
    fun `catalogs reordered elsewhere keep their order and built-ins follow their anchors`() {
        // Saved when the order was series, movie; the catalog order then changed on another device.
        val saved = listOf("cinemeta_series_top", trending, "cinemeta_movie_top")
        val merged = merge(listOf("cinemeta_movie_top", "cinemeta_series_top"), saved, listOf(BuiltInRow.TRENDING_WEEK))
        assertEquals(listOf("cinemeta_movie_top", "cinemeta_series_top", trending), merged)
    }

    @Test
    fun `unknown and duplicate saved keys are ignored`() {
        val saved = listOf("gone_addon_movie_x", trending, trending, "builtin:not_a_row", "cinemeta_movie_top")
        val merged = merge(listOf("cinemeta_movie_top"), saved, listOf(BuiltInRow.TRENDING_WEEK))
        assertEquals(listOf(trending, "cinemeta_movie_top"), merged)
    }

    @Test
    fun `move steps one place and stops at either end`() {
        val order = listOf("a", trending, "b")
        assertEquals(listOf(trending, "a", "b"), HomeRowOrder.move(order, trending, -1))
        assertEquals(listOf("a", "b", trending), HomeRowOrder.move(order, trending, 1))
        assertEquals(order, HomeRowOrder.move(order, "a", -1))
        assertEquals(order, HomeRowOrder.move(order, "b", 1))
        assertEquals(order, HomeRowOrder.move(order, "missing", 1))
    }

    @Test
    fun `encode and decode round trip, blank entries dropped`() {
        val order = listOf("a_movie_x", trending, "collection_1")
        assertEquals(order, HomeRowOrder.decode(HomeRowOrder.encode(order)))
        assertEquals(emptyList<String>(), HomeRowOrder.decode(null))
        assertEquals(emptyList<String>(), HomeRowOrder.decode(""))
        assertEquals(listOf("a", "b"), HomeRowOrder.decode("a\n\n b \n"))
    }

    @Test
    fun `built-in keys are recognised`() {
        assertTrue(HomeRowOrder.isBuiltIn(trending))
        assertFalse(HomeRowOrder.isBuiltIn("cinemeta_movie_top"))
        BuiltInRow.entries.forEach { assertEquals(it, BuiltInRow.fromRowKey(it.rowKey)) }
    }

    /** Whatever is saved, the result is every catalog once, in order, plus every built-in once. */
    @Test
    fun `stress - random catalogs and saved orders always keep the invariants`() {
        val random = Random(20261013)
        val pool = (0 until 40).map { "addon${it % 5}_${if (it % 2 == 0) "movie" else "series"}_c$it" }
        repeat(2_000) {
            val catalogs = pool.shuffled(random).take(random.nextInt(0, 25))
            val builtIns = BuiltInRow.entries.shuffled(random).take(random.nextInt(0, 6))
            val saved = (pool.shuffled(random).take(random.nextInt(0, 30)) +
                BuiltInRow.entries.map { it.rowKey }.shuffled(random).take(random.nextInt(0, 6)))
                .shuffled(random)
            val merged = HomeRowOrder.merge(catalogs, builtIns, saved) { key -> key.split('_').getOrNull(1) }

            assertEquals(catalogs, merged.filterNot(HomeRowOrder::isBuiltIn))
            assertEquals(builtIns.map { it.rowKey }.sorted(), merged.filter(HomeRowOrder::isBuiltIn).sorted())
            assertEquals(merged.size, merged.toSet().size)

            // Moving any row any way never loses or duplicates one.
            val key = merged.randomOrNull(random) ?: return@repeat
            val moved = HomeRowOrder.move(merged, key, if (random.nextBoolean()) 1 else -1)
            assertEquals(merged.sorted(), moved.sorted())

            // Saving what's shown and merging again gives the same list (the order screen's round trip).
            assertEquals(merged, HomeRowOrder.merge(catalogs, builtIns, merged) { k -> k.split('_').getOrNull(1) })
        }
    }

    @Test
    fun `Continue Watching starts at the top and moves like a built-in row`() {
        val cw = HomeRowOrder.CONTINUE_WATCHING_KEY
        val catalogs = listOf("cinemeta_series_top", "cinemeta_movie_top")
        val fresh = HomeRowOrder.merge(catalogs, BuiltInRow.entries, emptyList(), continueWatching = true) { types[it] }
        assertEquals(listOf(cw, trending, because, forYou), fresh.take(4))

        // Moved below the first catalog, it stays there; the other rows keep their places.
        val moved = HomeRowOrder.move(HomeRowOrder.move(HomeRowOrder.move(HomeRowOrder.move(fresh, cw, 1), cw, 1), cw, 1), cw, 1)
        val again = HomeRowOrder.merge(catalogs, BuiltInRow.entries, moved, continueWatching = true) { types[it] }
        assertEquals(moved, again)
        assertEquals("cinemeta_series_top", again[again.indexOf(cw) - 1])

        // Without Continue Watching on Home, the key isn't listed.
        assertFalse(cw in HomeRowOrder.merge(catalogs, BuiltInRow.entries, moved) { types[it] })
    }
}
