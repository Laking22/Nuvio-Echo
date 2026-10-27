package com.nuvio.tv.core.home.smart

import com.nuvio.tv.data.local.BetaPerformanceSettings.Companion.ENRICHMENT_HEAVY
import com.nuvio.tv.data.local.BetaPerformanceSettings.Companion.ENRICHMENT_LOW
import com.nuvio.tv.data.local.BetaPerformanceSettings.Companion.ENRICHMENT_MEDIUM
import kotlin.random.Random

/**
 * Which Smart Highlights Home tries today, and in what order. The order is fixed for a day and a profile, so Home
 * doesn't reshuffle on every visit, and changes the next day. Themes close to what the profile watches come up more.
 */
object SmartHighlightPlanner {

    /**
     * [themes] minus blocked and snoozed ones, best first for [epochDay]. [affinity] is a weight per TMDB genre id
     * (-1..1, from what the profile watched, rated and saved); a theme gains or loses up to [AFFINITY_WEIGHT] from the
     * genres it lists. [lastShown] is the day each theme was last on Home: one shown in the last few days sinks
     * ([recencyPenalty]), so most days bring themes that weren't just there.
     */
    fun order(
        themes: List<HighlightTheme>,
        blocked: Set<String>,
        snoozed: Set<String>,
        epochDay: Long,
        profileSeed: Long,
        affinity: Map<Int, Double>,
        lastShown: Map<String, Long> = emptyMap()
    ): List<HighlightTheme> {
        val random = Random(epochDay * 31 + profileSeed)
        // One draw per theme in catalogue order, so the same day always gives the same numbers.
        val draws = themes.associate { it.id to random.nextDouble() }
        return themes
            .filter { it.id !in blocked && !isSnoozed(it.id, snoozed, epochDay) }
            .sortedByDescending { theme ->
                // Liked genres lift a theme up to 1; genres of disliked titles can sink it to -1.
                val fit = theme.affinityGenres.sumOf { affinity[it] ?: 0.0 }.coerceIn(-1.0, 1.0)
                draws.getValue(theme.id) + fit * AFFINITY_WEIGHT - recencyPenalty(lastShown[theme.id], epochDay)
            }
    }

    /**
     * How far a theme shown on [lastShownDay] sinks on [epochDay]: most the day after, gone after three days. Today's
     * own showing doesn't count, so the order holds all day.
     */
    fun recencyPenalty(lastShownDay: Long?, epochDay: Long): Double = when (epochDay - (lastShownDay ?: return 0.0)) {
        1L -> 0.9
        2L -> 0.6
        3L -> 0.3
        else -> 0.0
    }

    /** True while a "themeId@untilDay" entry for [themeId] is still in force. */
    fun isSnoozed(themeId: String, snoozed: Set<String>, epochDay: Long): Boolean = snoozed.any { entry ->
        entry.substringBefore('@') == themeId && (entry.substringAfter('@', "").toLongOrNull() ?: 0L) > epochDay
    }

    /** Genre weights from the genres of watched titles (one list per title): the most-watched genre is 1. */
    fun affinity(genresPerTitle: List<Collection<Int>>): Map<Int, Double> {
        val counts = HashMap<Int, Int>()
        genresPerTitle.forEach { genres -> genres.toSet().forEach { counts[it] = (counts[it] ?: 0) + 1 } }
        val max = counts.values.maxOrNull() ?: return emptyMap()
        return counts.mapValues { it.value.toDouble() / max }
    }

    /**
     * Most Smart Highlights a level shows, so how many are fetched: Low 3, Medium 5, Heavy no limit (as many as the
     * page has room for, kept [MIN_GAP] apart).
     */
    fun maxRows(enrichment: Int): Int = when (enrichment) {
        ENRICHMENT_LOW -> 3
        ENRICHMENT_MEDIUM -> 5
        ENRICHMENT_HEAVY -> Int.MAX_VALUE
        else -> 0
    }

    /** Today's number of highlights: Low 3, Medium 3 to 5 (the same all day, different the next), Heavy no limit. */
    fun dailyCount(enrichment: Int, epochDay: Long): Int = when (enrichment) {
        ENRICHMENT_MEDIUM -> 3 + Random(epochDay * 7919 + enrichment).nextInt(3)
        else -> maxRows(enrichment)
    }

    /** Medium and Heavy show every third Smart Highlight (the 2nd, 5th, 8th) as a grand row, for variety. */
    fun isGrand(enrichment: Int, index: Int): Boolean = enrichment >= ENRICHMENT_MEDIUM && index % 3 == 1

    /**
     * Where up to [count] highlights go among Home's rows, spread evenly: [anchors] marks rows that already count as
     * highlights and stay where they are (the user's grand rows). Between two highlights sit at least [MIN_GAP] and,
     * where there are enough to place, at most [MAX_GAP] other rows; the first comes after the same gap from the top.
     * Each result is how many of Home's rows come before that highlight (in order). Fewer than [count] when the page
     * runs out of room.
     */
    fun placements(anchors: List<Boolean>, count: Int): List<Int> {
        if (count <= 0) return emptyList()
        val ordinary = anchors.count { !it }
        // Evenly down the page: the k-th highlight wants to follow about k steps of rows.
        val step = (ordinary.toDouble() / (count + 1)).coerceIn(MIN_GAP.toDouble(), MAX_GAP.toDouble())
        val out = ArrayList<Int>(count)
        var seen = 0
        var since = 0
        for (i in anchors.indices) {
            if (anchors[i]) {
                since = 0
                continue
            }
            seen++
            since++
            if (out.size >= count || since < MIN_GAP || rowsBeforeNextAnchor(anchors, i + 1) < MIN_GAP) continue
            val due = seen >= Math.round(step * (out.size + 1)) || since >= MAX_GAP
            if (due) {
                out += i + 1
                since = 0
            }
        }
        return out
    }

    /** Ordinary rows from [from] up to the next anchor; with none after, as many as there could be. */
    private fun rowsBeforeNextAnchor(anchors: List<Boolean>, from: Int): Int {
        for (j in from until anchors.size) if (anchors[j]) return j - from
        return Int.MAX_VALUE
    }

    /** Fewest and most other rows between two highlights. */
    const val MIN_GAP = 2
    const val MAX_GAP = 5

    /** A Smart Highlight with fewer titles than this (after the user's removals) isn't shown. */
    const val MIN_ITEMS = 6

    /** How long "Hide for now" hides a Smart Highlight. */
    const val SNOOZE_DAYS = 30

    /** How far what you watch can lift a theme over its daily draw (0..1). */
    private const val AFFINITY_WEIGHT = 0.6
}
