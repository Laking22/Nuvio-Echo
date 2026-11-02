package com.nuvio.tv.core.home

import com.nuvio.tv.data.local.BetaPerformanceSettings

/**
 * Custom enrichment: numbered spots among Home's rows where the day's Smart Highlights go, instead of being spread
 * through the page. Highlight spots take highlighted rows and grand spots take grand ones; how many of each comes from
 * the Enrichment level. They're built-in keys ([HomeRowOrder.BUILT_IN_PREFIX]), so the catalog order screen moves
 * them like any built-in row and their places are saved with the built-in rows'.
 */
object EnrichmentSlots {
    private const val HIGHLIGHT_PREFIX = HomeRowOrder.BUILT_IN_PREFIX + "slot_highlight_"
    private const val GRAND_PREFIX = HomeRowOrder.BUILT_IN_PREFIX + "slot_grand_"

    /** Highlight spots per level: Low 3, Medium 3, Heavy 6. */
    fun highlightCount(enrichment: Int): Int = when (enrichment) {
        BetaPerformanceSettings.ENRICHMENT_LOW, BetaPerformanceSettings.ENRICHMENT_MEDIUM -> 3
        BetaPerformanceSettings.ENRICHMENT_HEAVY -> 6
        else -> 0
    }

    /** Grand spots per level: none at Low (it has no grand highlights), Medium 2, Heavy 3. */
    fun grandCount(enrichment: Int): Int = when (enrichment) {
        BetaPerformanceSettings.ENRICHMENT_MEDIUM -> 2
        BetaPerformanceSettings.ENRICHMENT_HEAVY -> 3
        else -> 0
    }

    /**
     * Every spot for [enrichment], in the order they're first spread down the page: a grand spot after every two
     * highlight spots, as the automatic placement mixes them.
     */
    fun keys(enrichment: Int): List<String> {
        val highlights = (1..highlightCount(enrichment)).map { HIGHLIGHT_PREFIX + it }.toMutableList()
        val grands = (1..grandCount(enrichment)).map { GRAND_PREFIX + it }.toMutableList()
        val out = ArrayList<String>(highlights.size + grands.size)
        while (highlights.isNotEmpty() || grands.isNotEmpty()) {
            repeat(2) { highlights.removeFirstOrNull()?.let(out::add) }
            grands.removeFirstOrNull()?.let(out::add)
        }
        return out
    }

    /** The spots Home and the catalog order screen use now: none unless custom enrichment is on. */
    fun active(settings: BetaPerformanceSettings): List<String> =
        if (settings.customEnrichment && settings.smartHighlights) keys(settings.enrichment) else emptyList()

    fun isSlot(key: String): Boolean = key.startsWith(HIGHLIGHT_PREFIX) || key.startsWith(GRAND_PREFIX)

    fun isGrand(key: String): Boolean = key.startsWith(GRAND_PREFIX)

    /** The spot's number as shown ("Highlight #2"). */
    fun number(key: String): Int = key.substringAfterLast('_').toIntOrNull() ?: 0
}
