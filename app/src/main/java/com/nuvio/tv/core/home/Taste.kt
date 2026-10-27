package com.nuvio.tv.core.home

import com.nuvio.tv.core.home.BuiltInRanking.titleKey
import com.nuvio.tv.data.local.TitleRating
import com.nuvio.tv.data.local.TitleRatings
import com.nuvio.tv.domain.model.MetaPreview

/**
 * What the details page's thumbs mean for Home's personal rows. A loved title pulls hardest, a liked one a bit
 * more than a watched one; a title marked "not for me" pushes away what's like it. A show whose episodes were
 * liked or loved (and that has no rating of its own) counts as a weaker like.
 */
internal object Taste {

    data class WeightedSeed(val seed: BuiltInRanking.Seed, val weight: Double)

    const val LOVE_WEIGHT = 2.0
    const val LIKE_WEIGHT = 1.4
    const val EPISODE_FAN_WEIGHT = 1.1
    const val WATCHED_WEIGHT = 1.0
    /** How strongly a disliked title's neighbours are pushed down, relative to a watched seed. */
    const val DISLIKE_PENALTY = 0.8

    /** Positive seeds from ratings: loved, liked, then shows with liked episodes; newest first. */
    fun ratedSeeds(ratings: TitleRatings): List<WeightedSeed> {
        val rated = ratings.titles.values
            .filter { it.rating != TitleRating.DISLIKE && it.title.isNotBlank() }
            .map {
                WeightedSeed(
                    BuiltInRanking.Seed(it.contentId, it.contentType, it.title, it.ratedAt),
                    if (it.rating == TitleRating.LOVE) LOVE_WEIGHT else LIKE_WEIGHT
                )
            }
        val ratedIds = ratings.titles.keys
        val episodeFans = ratings.episodes.values
            .filter { it.contentId !in ratedIds && it.title.isNotBlank() }
            .groupBy { it.contentId }
            .mapNotNull { (id, eps) ->
                val score = eps.sumOf {
                    when (it.rating) {
                        TitleRating.LOVE -> 2
                        TitleRating.LIKE -> 1
                        TitleRating.DISLIKE -> -1
                    }
                }
                if (score <= 0) return@mapNotNull null
                val newest = eps.maxBy { it.ratedAt }
                WeightedSeed(BuiltInRanking.Seed(id, newest.contentType, newest.title, newest.ratedAt), EPISODE_FAN_WEIGHT)
            }
        return (rated + episodeFans).sortedByDescending { it.seed.at }
    }

    /** Titles marked "not for me", newest first. */
    fun dislikedSeeds(ratings: TitleRatings): List<BuiltInRanking.Seed> = ratings.titles.values
        .filter { it.rating == TitleRating.DISLIKE && it.title.isNotBlank() }
        .sortedByDescending { it.ratedAt }
        .map { BuiltInRanking.Seed(it.contentId, it.contentType, it.title, it.ratedAt) }

    /**
     * For You's seeds: watched titles and rated ones together, newest first, each weighted by how it was rated
     * and by how recent it is. A title both watched and rated keeps its rating's weight. At most [max].
     */
    fun forYouSeeds(watched: List<BuiltInRanking.Seed>, ratings: TitleRatings, max: Int): List<WeightedSeed> {
        val disliked = ratings.titles.values.filter { it.rating == TitleRating.DISLIKE }.map { it.contentId }.toSet()
        val byId = LinkedHashMap<String, WeightedSeed>()
        (ratedSeeds(ratings) + watched.filter { it.id !in disliked }.map { WeightedSeed(it, WATCHED_WEIGHT) })
            .forEach { candidate ->
                val existing = byId[candidate.seed.id]
                if (existing == null || candidate.weight > existing.weight) byId[candidate.seed.id] = candidate
            }
        return byId.values
            .sortedByDescending { it.seed.at }
            .take(max)
            .mapIndexed { rank, s -> s.copy(weight = s.weight / (1 + rank * 0.6)) }
    }

    /** Leaves out every rated title (the person already knows it) and anything named like a disliked one. */
    fun notRated(ratings: TitleRatings): (MetaPreview) -> Boolean {
        val ids = ratings.titles.keys
        val names = ratings.titles.values.map { it.title.titleKey() }.filter { it.isNotEmpty() }.toSet()
        return { item -> item.id !in ids && item.name.titleKey() !in names }
    }

    /**
     * Genre weights for Smart Highlights: each entry is a title's genres with a weight (negative for disliked).
     * Summed per genre and scaled so the strongest liked genre is 1; disliked genres can reach -1.
     */
    fun genreAffinity(entries: List<Pair<Collection<Int>, Double>>): Map<Int, Double> {
        val sums = HashMap<Int, Double>()
        entries.forEach { (genres, weight) -> genres.toSet().forEach { sums[it] = (sums[it] ?: 0.0) + weight } }
        val max = sums.values.filter { it > 0 }.maxOrNull() ?: sums.values.maxOfOrNull { kotlin.math.abs(it) } ?: return emptyMap()
        return sums.mapValues { (it.value / max).coerceIn(-1.0, 1.0) }
    }

    fun ratingWeight(rating: TitleRating): Double = when (rating) {
        TitleRating.LOVE -> LOVE_WEIGHT
        TitleRating.LIKE -> LIKE_WEIGHT
        TitleRating.DISLIKE -> -1.2
    }
}
