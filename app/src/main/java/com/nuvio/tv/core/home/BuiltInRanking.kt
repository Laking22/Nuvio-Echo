package com.nuvio.tv.core.home

import com.nuvio.tv.data.local.ViewedThresholdUnit
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem
import java.util.Locale

/** The pure part of the built-in rows: which titles seed them and how For You scores recommendations. */
internal object BuiltInRanking {

    data class Seed(val id: String, val type: String, val title: String, val at: Long)

    /** The title most recently past the viewed threshold (per episode for shows), or watched to the end. */
    fun mostRecentViewed(
        progress: List<WatchProgress>,
        watched: List<WatchedItem>,
        unit: ViewedThresholdUnit,
        minutes: Int,
        percent: Int
    ): Seed? {
        val passed = progress.filter { p ->
            when (unit) {
                ViewedThresholdUnit.MINUTES -> p.position >= minutes * 60_000L || p.isCompleted()
                ViewedThresholdUnit.PERCENT -> p.progressPercentage * 100f >= percent
            }
        }.map { Seed(it.contentId, it.contentType, it.name, it.lastWatched) }
        val finished = watched.map { Seed(it.contentId, it.contentType, it.title, it.watchedAt) }
        return (passed + finished).filter { it.title.isNotBlank() }.maxByOrNull { it.at }
    }

    /** Distinct watched titles, newest first: completed by Nuvio's usual rule, or marked watched. */
    fun recentWatched(progress: List<WatchProgress>, watched: List<WatchedItem>, max: Int): List<Seed> {
        val all = progress.filter { it.isCompleted() }.map { Seed(it.contentId, it.contentType, it.name, it.lastWatched) } +
            watched.map { Seed(it.contentId, it.contentType, it.title, it.watchedAt) }
        return all.filter { it.title.isNotBlank() }.sortedByDescending { it.at }.distinctBy { it.id }.take(max)
    }

    /**
     * Leaves out what's been watched or is in progress. TMDB recommendations carry TMDB ids while progress usually
     * carries IMDb ids, so titles are compared by name too.
     */
    fun notWatched(progress: List<WatchProgress>, watched: List<WatchedItem>): (MetaPreview) -> Boolean {
        val ids = (progress.map { it.contentId } + watched.map { it.contentId }).toSet()
        val names = (progress.map { it.name } + watched.map { it.title }).map { it.titleKey() }.filter { it.isNotEmpty() }.toSet()
        return { item -> item.id !in ids && item.name.titleKey() !in names }
    }

    /**
     * For You's candidates: recommendations for each seed (newest seed first) scored so recent seeds count more
     * and a title recommended for several seeds adds up; ties go to the better rated. Best [limit] first.
     */
    fun scoreForYou(recsBySeed: List<List<MetaPreview>>, keep: (MetaPreview) -> Boolean, limit: Int = 30): List<MetaPreview> =
        scoreWeighted(recsBySeed.mapIndexed { rank, recs -> recs to 1.0 / (1 + rank * 0.6) }, keep, limit = limit)

    /**
     * The same scoring with a weight per seed (see [Taste.forYouSeeds]). Titles recommended for something the
     * person marked "not for me" ([penalties], newest first) lose score, and drop out once it falls to nothing.
     */
    fun scoreWeighted(
        weightedRecs: List<Pair<List<MetaPreview>, Double>>,
        keep: (MetaPreview) -> Boolean,
        penalties: List<List<MetaPreview>> = emptyList(),
        limit: Int = 30
    ): List<MetaPreview> {
        val scores = LinkedHashMap<String, Pair<MetaPreview, Double>>()
        weightedRecs.forEach { (recs, weight) ->
            recs.forEachIndexed { position, rec ->
                if (!keep(rec)) return@forEachIndexed
                val score = weight * (1.0 - position * 0.02).coerceAtLeast(0.05)
                val current = scores[rec.id]
                scores[rec.id] = (current?.first ?: rec) to ((current?.second ?: 0.0) + score)
            }
        }
        penalties.forEachIndexed { rank, recs ->
            val weight = Taste.DISLIKE_PENALTY / (1 + rank * 0.6)
            recs.forEachIndexed { position, rec ->
                val current = scores[rec.id] ?: return@forEachIndexed
                scores[rec.id] = current.first to (current.second - weight * (1.0 - position * 0.02).coerceAtLeast(0.05))
            }
        }
        return scores.values
            .filter { it.second > 0.0 }
            .sortedWith(compareByDescending<Pair<MetaPreview, Double>> { it.second }.thenByDescending { it.first.imdbRating ?: 0f })
            .map { it.first }
            .take(limit)
    }

    fun String.titleKey(): String = lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
}
