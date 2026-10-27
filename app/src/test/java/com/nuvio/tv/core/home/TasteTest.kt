package com.nuvio.tv.core.home

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.data.local.RatedEpisode
import com.nuvio.tv.data.local.RatedTitle
import com.nuvio.tv.data.local.TitleRating
import com.nuvio.tv.data.local.TitleRatings
import com.nuvio.tv.data.local.TitleRatingsStore
import com.nuvio.tv.data.local.readTitleRatings
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TasteTest {

    private fun preview(id: String, name: String = id) = MetaPreview(
        id = id, type = ContentType.MOVIE, name = name, poster = null, posterShape = PosterShape.POSTER,
        background = "b", logo = null, description = null, releaseInfo = null, imdbRating = null, genres = emptyList()
    )

    private fun rated(id: String, rating: TitleRating, at: Long, title: String = id) =
        RatedTitle(id, "movie", title, rating, at)

    private fun ratings(vararg titles: RatedTitle, episodes: List<RatedEpisode> = emptyList()) =
        TitleRatings(titles.associateBy { it.contentId }, episodes.associateBy {
            TitleRatings.episodeKey(it.contentId, it.season, it.episode)
        })

    @Test
    fun `stored ratings read back, skipping entries that don't parse`() {
        val prefs = mutablePreferencesOf(
            stringPreferencesKey("t:tt1") to TitleRatingsStore.encode(TitleRating.LOVE, 10, "movie", "Knives Out"),
            stringPreferencesKey("t:tmdb:42") to TitleRatingsStore.encode(TitleRating.DISLIKE, 20, "series", "A: B"),
            stringPreferencesKey("e:2:5:tt9") to TitleRatingsStore.encode(TitleRating.LIKE, 30, "series", "Show"),
            stringPreferencesKey("t:bad") to "nonsense",
            stringPreferencesKey("other") to "love\t1\tmovie\tX"
        )
        val r = readTitleRatings(prefs)
        assertEquals(TitleRating.LOVE, r.title("tt1"))
        assertEquals("Knives Out", r.titles.getValue("tt1").title)
        assertEquals(TitleRating.DISLIKE, r.title("tmdb:42"))
        assertEquals("A: B", r.titles.getValue("tmdb:42").title)
        assertEquals(mapOf((2 to 5) to TitleRating.LIKE), r.episodesOf("tt9"))
        assertEquals(2, r.titles.size)
    }

    @Test
    fun `loved and liked titles seed For You ahead of watched ones, disliked ones never do`() {
        val watched = listOf(
            BuiltInRanking.Seed("w1", "movie", "Watched new", 300),
            BuiltInRanking.Seed("d1", "movie", "Hated", 250),
            BuiltInRanking.Seed("w2", "movie", "Watched old", 100)
        )
        val r = ratings(
            rated("l1", TitleRating.LOVE, 200, "Loved"),
            rated("k1", TitleRating.LIKE, 150, "Liked"),
            rated("d1", TitleRating.DISLIKE, 260, "Hated")
        )
        val seeds = Taste.forYouSeeds(watched, r, max = 8)
        assertEquals(listOf("w1", "l1", "k1", "w2"), seeds.map { it.seed.id })
        // The loved title outweighs the newer watched one despite coming second.
        assertTrue(seeds[1].weight > seeds[0].weight)
    }

    @Test
    fun `a show whose episodes were liked counts as a weaker seed`() {
        val r = ratings(episodes = listOf(
            RatedEpisode("s1", "series", "Show", 1, 1, TitleRating.LOVE, 50),
            RatedEpisode("s1", "series", "Show", 1, 2, TitleRating.DISLIKE, 60),
            RatedEpisode("s2", "series", "Meh", 1, 1, TitleRating.DISLIKE, 70)
        ))
        val seeds = Taste.ratedSeeds(r)
        assertEquals(listOf("s1"), seeds.map { it.seed.id })
        assertEquals(Taste.EPISODE_FAN_WEIGHT, seeds.single().weight, 1e-9)
    }

    @Test
    fun `recommendations of a disliked title are pushed down or out`() {
        val liked = listOf(preview("a"), preview("b"), preview("c"))
        val dislikedRecs = listOf(preview("b"), preview("x"))
        // From a weak seed, b's score goes below zero and it drops out.
        val weak = BuiltInRanking.scoreWeighted(listOf(liked to 0.5), keep = { true }, penalties = listOf(dislikedRecs))
        assertEquals(listOf("a", "c"), weak.map { it.id })
        // From a full-weight seed, b survives the penalty but falls behind c.
        val full = BuiltInRanking.scoreWeighted(listOf(liked to 1.0), keep = { true }, penalties = listOf(dislikedRecs))
        assertEquals(listOf("a", "c", "b"), full.map { it.id })
    }

    @Test
    fun `the old For You scoring is unchanged`() {
        val recs = listOf(listOf(preview("a"), preview("b")), listOf(preview("b"), preview("c")))
        assertEquals(listOf("b", "a", "c"), BuiltInRanking.scoreForYou(recs, keep = { true }).map { it.id })
    }

    @Test
    fun `rated titles are kept out of recommendations, by id or by name`() {
        val keep = Taste.notRated(ratings(rated("tt1", TitleRating.LIKE, 1, "Knives Out")))
        assertFalse(keep(preview("tt1")))
        assertFalse(keep(preview("tmdb:546554", "Knives Out")))
        assertTrue(keep(preview("tt2", "Glass Onion")))
    }

    @Test
    fun `genre affinity counts likes up and dislikes down`() {
        val affinity = Taste.genreAffinity(listOf(
            listOf(35, 80) to 2.0,   // loved comedy crime
            listOf(35) to 1.0,       // watched comedy
            listOf(27) to -1.2       // disliked horror
        ))
        assertEquals(1.0, affinity.getValue(35), 1e-9)
        assertEquals(2.0 / 3.0, affinity.getValue(80), 1e-9)
        assertEquals(-0.4, affinity.getValue(27), 1e-9)
    }

    @Test
    fun `watched-only affinity matches the planner's`() {
        val genres = listOf(listOf(1, 2), listOf(1), listOf(3, 1))
        assertEquals(
            com.nuvio.tv.core.home.smart.SmartHighlightPlanner.affinity(genres),
            Taste.genreAffinity(genres.map { it to 1.0 })
        )
    }
}
