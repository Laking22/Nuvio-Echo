package com.nuvio.tv.core.home

import com.nuvio.tv.data.local.ViewedThresholdUnit
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class BuiltInRankingTest {

    private fun progress(id: String, name: String, positionMin: Int, durationMin: Int, at: Long, type: String = "movie") =
        WatchProgress(
            contentId = id, contentType = type, name = name, poster = null, backdrop = null, logo = null,
            videoId = id, season = null, episode = null, episodeTitle = null,
            position = positionMin * 60_000L, duration = durationMin * 60_000L, lastWatched = at
        )

    private fun watched(id: String, title: String, at: Long, type: String = "movie") =
        WatchedItem(contentId = id, contentType = type, title = title, watchedAt = at)

    private fun preview(id: String, name: String = id, rating: Float? = null) = MetaPreview(
        id = id, type = ContentType.MOVIE, name = name, poster = null, posterShape = PosterShape.POSTER,
        background = "b", logo = null, description = null, releaseInfo = null, imdbRating = rating, genres = emptyList()
    )

    // ---- Because You Watched's seed ----

    @Test
    fun `minutes threshold picks the latest title watched past it`() {
        val seed = BuiltInRanking.mostRecentViewed(
            progress = listOf(
                progress("tt1", "Short look", positionMin = 5, durationMin = 120, at = 300),
                progress("tt2", "Watched a while", positionMin = 25, durationMin = 120, at = 200)
            ),
            watched = emptyList(),
            unit = ViewedThresholdUnit.MINUTES, minutes = 20, percent = 50
        )
        assertEquals("tt2", seed?.id)
    }

    @Test
    fun `percent threshold uses the share of the runtime`() {
        val list = listOf(
            progress("tt1", "Forty percent", positionMin = 40, durationMin = 100, at = 300),
            progress("tt2", "Sixty percent", positionMin = 60, durationMin = 100, at = 200)
        )
        assertEquals("tt2", BuiltInRanking.mostRecentViewed(list, emptyList(), ViewedThresholdUnit.PERCENT, 20, 50)?.id)
        assertEquals("tt1", BuiltInRanking.mostRecentViewed(list, emptyList(), ViewedThresholdUnit.PERCENT, 20, 40)?.id)
    }

    @Test
    fun `a short title finished counts even under the minutes threshold`() {
        val seed = BuiltInRanking.mostRecentViewed(
            listOf(progress("tt1", "Short film", positionMin = 12, durationMin = 13, at = 100)),
            emptyList(), ViewedThresholdUnit.MINUTES, minutes = 20, percent = 50
        )
        assertEquals("tt1", seed?.id)
    }

    @Test
    fun `marked watched counts and the newest wins`() {
        val seed = BuiltInRanking.mostRecentViewed(
            listOf(progress("tt1", "Older", 30, 100, at = 100)),
            listOf(watched("tt9", "Marked watched", at = 500)),
            ViewedThresholdUnit.MINUTES, 20, 50
        )
        assertEquals("tt9", seed?.id)
    }

    @Test
    fun `nothing past the threshold gives no seed, and blank titles never seed`() {
        assertNull(
            BuiltInRanking.mostRecentViewed(
                listOf(progress("tt1", "Barely", 1, 100, at = 100)), emptyList(), ViewedThresholdUnit.MINUTES, 20, 50
            )
        )
        assertNull(
            BuiltInRanking.mostRecentViewed(emptyList(), listOf(watched("tt1", " ", 1)), ViewedThresholdUnit.MINUTES, 20, 50)
        )
    }

    // ---- For You's seeds ----

    @Test
    fun `recent watched is distinct, newest first and capped`() {
        val seeds = BuiltInRanking.recentWatched(
            progress = listOf(
                progress("tt1", "Done", 95, 100, at = 10),
                progress("tt2", "Half", 50, 100, at = 99) // not completed: left out
            ),
            watched = listOf(
                watched("tt3", "Show", at = 30, type = "series"),
                watched("tt3", "Show", at = 40, type = "series"), // another episode of the same show
                watched("tt4", "Film", at = 20)
            ),
            max = 6
        )
        assertEquals(listOf("tt3", "tt4", "tt1"), seeds.map { it.id })
        assertEquals(2, BuiltInRanking.recentWatched(emptyList(), (1..9).map { watched("tt$it", "T$it", it.toLong()) }, max = 2).size)
    }

    // ---- Leaving out what's been watched ----

    @Test
    fun `watched titles are left out by id or by name`() {
        val keep = BuiltInRanking.notWatched(
            progress = listOf(progress("tt1", "The Bear", 10, 30, at = 1)),
            watched = listOf(watched("tmdb:5", "Dune: Part Two", at = 2))
        )
        assertFalse(keep(preview("tmdb:5")))
        assertFalse(keep(preview("tmdb:77", name = "the bear")))
        assertFalse(keep(preview("tmdb:88", name = "Dune - Part Two")))
        assertTrue(keep(preview("tmdb:99", name = "Severance")))
    }

    // ---- For You's scoring ----

    @Test
    fun `a title recommended for several seeds beats one recommended once`() {
        val ranked = BuiltInRanking.scoreForYou(
            recsBySeed = listOf(
                listOf(preview("a"), preview("shared")),
                listOf(preview("b"), preview("shared"))
            ),
            keep = { true }
        )
        assertEquals("shared", ranked.first().id)
    }

    @Test
    fun `newer seeds count more, then the position in each list`() {
        val ranked = BuiltInRanking.scoreForYou(
            recsBySeed = listOf(listOf(preview("new1"), preview("new2")), listOf(preview("old1"))),
            keep = { true }
        )
        assertEquals(listOf("new1", "new2", "old1"), ranked.map { it.id })
    }

    @Test
    fun `left out titles never score and the limit holds`() {
        val ranked = BuiltInRanking.scoreForYou(
            recsBySeed = listOf((1..50).map { preview("m$it") }),
            keep = { it.id != "m1" },
            limit = 30
        )
        assertEquals(30, ranked.size)
        assertFalse(ranked.any { it.id == "m1" })
        assertEquals("m2", ranked.first().id)
    }

    /** Large, random histories: the seeds and scores always hold their rules. */
    @Test
    fun `stress - random histories keep the rules`() {
        val random = Random(42)
        repeat(500) {
            val progress = (0 until random.nextInt(0, 60)).map { i ->
                progress("tt${random.nextInt(0, 30)}", "Title $i", random.nextInt(0, 120), random.nextInt(1, 120), random.nextLong(0, 1_000))
            }
            val watched = (0 until random.nextInt(0, 30)).map { i ->
                watched("tt${random.nextInt(0, 30)}", "Watched $i", random.nextLong(0, 1_000))
            }
            val seeds = BuiltInRanking.recentWatched(progress, watched, max = 6)
            assertTrue(seeds.size <= 6)
            assertEquals(seeds.size, seeds.map { it.id }.toSet().size)
            assertEquals(seeds.sortedByDescending { it.at }, seeds)

            val viewed = BuiltInRanking.mostRecentViewed(progress, watched, ViewedThresholdUnit.MINUTES, 20, 50)
            // Anything finished also passes the viewed threshold, so the viewed seed is never older than For You's.
            if (viewed != null && seeds.isNotEmpty()) assertTrue(viewed.at >= seeds.first().at)

            val recs = (0 until random.nextInt(0, 7)).map { (0 until 20).map { preview("tmdb:${random.nextInt(0, 80)}") } }
            val keep = BuiltInRanking.notWatched(progress, watched)
            val ranked = BuiltInRanking.scoreForYou(recs, keep)
            assertTrue(ranked.size <= 30)
            assertEquals(ranked.size, ranked.map { it.id }.toSet().size)
            assertTrue(ranked.all(keep))
        }
    }
}
