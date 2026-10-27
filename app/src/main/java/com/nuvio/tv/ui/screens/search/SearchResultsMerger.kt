package com.nuvio.tv.ui.screens.search

import com.nuvio.tv.core.browse.BrowseMediaMode
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import java.text.Normalizer
import java.util.Locale

/**
 * Turns per-catalog search rows into the single grid of the keyboard search screen. Rows are
 * interleaved so every addon's best matches come first, the same title from two catalogs is kept
 * once, and titles that match what was typed move ahead of loose matches.
 */
internal object SearchResultsMerger {

    private val NON_ALPHANUMERIC = Regex("[^\\p{L}\\p{N}]+")
    private val DIACRITICS = Regex("\\p{Mn}+")

    /**
     * @param ranked TMDB's popularity-ranked matches for [query]. When present they set the order:
     * each is replaced by the same title from an addon (its own id, poster and metadata) when one
     * matches, and addon results TMDB doesn't know follow after.
     */
    fun merge(
        rows: List<CatalogRow>,
        query: String,
        filter: BrowseMediaMode = BrowseMediaMode.ALL,
        ranked: List<MetaPreview> = emptyList()
    ): List<MetaPreview> {
        val lists = rows.map { row ->
            row.items.filter { item -> !item.id.startsWith("__placeholder_") && item.matches(filter) }
        }
        val seenIds = HashSet<String>()
        val seenTitles = HashSet<String>()
        val interleaved = ArrayList<MetaPreview>()
        var position = 0
        while (true) {
            var anyLeft = false
            for (list in lists) {
                if (position >= list.size) continue
                anyLeft = true
                val item = list[position]
                if (seenIds.add(item.id) && seenTitles.add(item.titleKey())) interleaved += item
            }
            if (!anyLeft) break
            position++
        }
        val normalizedQuery = normalize(query)
        val byMatch = if (normalizedQuery.isEmpty()) {
            interleaved
        } else {
            val queryWords = normalizedQuery.split(' ').filter { it.isNotEmpty() }
            interleaved
                .withIndex()
                .sortedWith(compareBy({ matchRank(normalize(it.value.name), normalizedQuery, queryWords) }, { it.index }))
                .map { it.value }
        }
        val rankedForFilter = ranked.filter { it.matches(filter) }
        if (rankedForFilter.isEmpty()) return byMatch

        val addonByTitle = LinkedHashMap<String, MetaPreview>()
        byMatch.forEach { addonByTitle.putIfAbsent(it.titleKey(), it) }
        val used = HashSet<String>()
        val result = ArrayList<MetaPreview>(byMatch.size + rankedForFilter.size)
        rankedForFilter.forEach { tmdbItem ->
            val key = tmdbItem.titleKey()
            if (!used.add(key)) return@forEach
            result += addonByTitle[key] ?: tmdbItem
        }
        byMatch.forEach { if (used.add(it.titleKey())) result += it }
        return result
    }

    private fun MetaPreview.titleKey(): String =
        "${normalize(name)}|${releaseInfo?.take(4).orEmpty()}|${isSeries()}"

    /** 0 exact, 1 starts with the query, 2 contains every typed word, 3 anything else. */
    private fun matchRank(title: String, query: String, queryWords: List<String>): Int = when {
        title == query -> 0
        title.startsWith(query) -> 1
        queryWords.all { word -> title.split(' ').any { it.startsWith(word) } } -> 2
        else -> 3
    }

    private fun normalize(value: String): String {
        val decomposed = Normalizer.normalize(value, Normalizer.Form.NFD).replace(DIACRITICS, "")
        return decomposed.lowercase(Locale.ROOT).replace(NON_ALPHANUMERIC, " ").trim()
    }

    private fun MetaPreview.isSeries(): Boolean = type == ContentType.SERIES || type == ContentType.TV

    private fun MetaPreview.matches(filter: BrowseMediaMode): Boolean = when (filter) {
        BrowseMediaMode.ALL, BrowseMediaMode.ANIME -> true
        BrowseMediaMode.MOVIES -> type == ContentType.MOVIE
        BrowseMediaMode.SHOWS -> isSeries()
    }
}
