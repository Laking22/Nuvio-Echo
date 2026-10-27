package com.nuvio.tv.ui.newlook.home

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import com.nuvio.tv.core.home.BuiltInRow
import com.nuvio.tv.core.home.BuiltInRowsRepository
import com.nuvio.tv.core.home.HomeRowOrder
import com.nuvio.tv.core.home.smart.HighlightNameFilter
import com.nuvio.tv.core.poster.withCustomPosterUrls
import com.nuvio.tv.core.home.smart.SmartHighlightPlanner
import com.nuvio.tv.core.home.smart.SmartHighlightRow
import com.nuvio.tv.core.home.smart.TraktHighlightSource
import com.nuvio.tv.data.local.BetaPerformanceSettings
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.ui.screens.home.HeroCarouselRow
import com.nuvio.tv.ui.screens.home.HeroPreview
import com.nuvio.tv.ui.screens.home.MODERN_CONTINUE_WATCHING_ROW_KEY
import com.nuvio.tv.ui.screens.home.MODERN_UPCOMING_ROW_KEY
import com.nuvio.tv.ui.screens.home.ModernCarouselItem
import com.nuvio.tv.ui.screens.home.ModernPayload
import com.nuvio.tv.ui.util.asStable

internal enum class RowKind {
    /** Continue Watching (and upcoming episodes): always-wide cards. */
    WIDE,
    /** A catalog row: posters that open out when focused. */
    POSTER,
    /** A mini carousel of wide backdrop cards whose trailers play in turn. */
    HIGHLIGHT,
    /** Collection folders at their own tile shape. */
    COLLECTION,
    /**
     * Hero-sized cards, each its own window like the hero's (logo, details, Watch Now / More Info), glowing in the
     * focused title's colours; the focused card plays its trailer, then focus moves to the next card.
     */
    GRAND
}

@Immutable
internal data class NewLookRow(
    val key: String,
    val title: String,
    val kind: RowKind,
    val items: List<ModernCarouselItem>,
    /** The presentation row it came from (load more, lazy loading); null for built-in rows. */
    val source: HeroCarouselRow? = null,
    val ranked: Boolean = false,
    /** A highlighted collection: folder tiles at highlighted size, animations instead of trailers. */
    val collection: Boolean = false,
    val builtIn: BuiltInRow? = null,
    val folderShape: PosterShape = PosterShape.SQUARE,
    /** Set on a Smart Highlight: holding a title offers to hide or block it. */
    val smart: SmartRowInfo? = null
) {
    val isPlaceholder: Boolean
        get() = source?.isLoading == true && onlyPlaceholders

    /** Nothing real in it yet: loading, or a load that failed. Drawn as focusable skeletons that can be passed. */
    val onlyPlaceholders: Boolean
        get() = items.isNotEmpty() && items.all {
            (it.payload as? ModernPayload.Catalog)?.itemId?.startsWith("__placeholder_") == true
        }

    fun cardWidth(d: NewLookDimens): Dp = when (kind) {
        RowKind.WIDE -> d.cwWidth
        RowKind.POSTER -> d.posterWidth
        RowKind.HIGHLIGHT -> if (ranked) d.rankPad + d.highlightWidth else d.highlightWidth
        RowKind.COLLECTION -> when (folderShape) {
            PosterShape.POSTER -> d.posterWidth
            PosterShape.LANDSCAPE -> d.cwWidth
            PosterShape.SQUARE -> d.posterHeight * 0.8f
        }
        RowKind.GRAND -> d.grandWidth
    }

    fun cardHeight(d: NewLookDimens): Dp = when (kind) {
        RowKind.WIDE -> d.cwHeight
        RowKind.POSTER -> d.posterHeight
        RowKind.HIGHLIGHT -> d.highlightHeight
        RowKind.COLLECTION -> when (folderShape) {
            PosterShape.POSTER -> d.posterHeight
            PosterShape.LANDSCAPE -> d.cwHeight
            PosterShape.SQUARE -> d.posterHeight * 0.8f
        }
        RowKind.GRAND -> d.grandHeight
    }

    fun metaHeight(d: NewLookDimens): Dp = when (kind) {
        // Grand cards carry their details inside, as the hero does.
        RowKind.WIDE, RowKind.GRAND -> Dp(0f)
        RowKind.POSTER, RowKind.COLLECTION -> d.posterMeta
        RowKind.HIGHLIGHT -> if (collection) d.posterMeta else d.highlightMeta
    }

    fun height(d: NewLookDimens): Dp = d.rowHead + cardHeight(d) + metaHeight(d) + d.rowSpace
}

@Immutable
internal data class SmartRowInfo(
    val themeId: String,
    /** The theme's own name, for the block option when [title][NewLookRow.title] was written by Gemini. */
    val catalogueTitle: String,
    val aiNamed: Boolean
)

internal data class BuiltInTitles(
    val trending: String,
    val becauseYouWatched: String,
    val forYou: String,
    val topMovies: String,
    val topShows: String,
    val movie: String,
    val show: String
)

/**
 * Home's rows in the new look: the presentation's rows (Continue Watching, catalogs, collections) with
 * Nuvio's built-in rows and Continue Watching (with the upcoming episodes after it) placed among them (see
 * [HomeRowOrder]), any row the user highlighted shown highlighted, and today's Smart Highlights spread evenly
 * through it all (see [SmartHighlightPlanner.placements]).
 */
internal fun buildNewLookRows(
    presentation: List<HeroCarouselRow>,
    builtIns: BuiltInRowsRepository.Rows,
    settings: BetaPerformanceSettings,
    titles: BuiltInTitles,
    smartHighlights: List<SmartHighlightRow> = emptyList(),
    epochDay: Long = java.time.LocalDate.now().toEpochDay()
): List<NewLookRow> {
    fun builtIn(row: BuiltInRow, title: String, items: List<MetaPreview>): NewLookRow? {
        if (row.key in settings.hiddenBuiltInRows || items.size < MIN_BUILT_IN_ITEMS) return null
        // Built-in rows are highlighted unless the user made them plain (or grand); plain and grand rank rows drop
        // the numbers.
        val grand = row.key in settings.grandRows
        val highlighted = !grand && row.key !in settings.plainBuiltInRows
        return NewLookRow(
            key = row.rowKey,
            title = title,
            kind = when {
                grand -> RowKind.GRAND
                highlighted -> RowKind.HIGHLIGHT
                else -> RowKind.POSTER
            },
            items = items.mapIndexed { i, item -> builtInItem(row.rowKey, item, i, titles) },
            ranked = row.ranked && highlighted,
            builtIn = row
        )
    }

    val builtInRows = mapOf(
        BuiltInRow.TRENDING_WEEK to builtIn(BuiltInRow.TRENDING_WEEK, titles.trending, builtIns.trendingWeek),
        BuiltInRow.BECAUSE_YOU_WATCHED to builtIns.becauseYouWatched?.let {
            builtIn(BuiltInRow.BECAUSE_YOU_WATCHED, titles.becauseYouWatched.format(it.seedTitle), it.items)
        },
        BuiltInRow.FOR_YOU to builtIn(BuiltInRow.FOR_YOU, titles.forYou, builtIns.forYou),
        BuiltInRow.TOP_MOVIES_TODAY to builtIn(BuiltInRow.TOP_MOVIES_TODAY, titles.topMovies, builtIns.topMoviesToday),
        BuiltInRow.TOP_SHOWS_TODAY to builtIn(BuiltInRow.TOP_SHOWS_TODAY, titles.topShows, builtIns.topShowsToday)
    )

    // Catalog rows by the key the catalog order screen uses (addon, type and id; collections by their own key).
    val catalogs = presentation.filterNot { it.isWide }
    val orderKeys = catalogs.map { it.orderKey }
    val types = catalogs.associate { it.orderKey to it.apiType }
    // Continue Watching (with the upcoming episodes when they sit right after it) where the order puts it; upcoming
    // episodes elsewhere keep their own place.
    val cwIndex = presentation.indexOfFirst { it.key == MODERN_CONTINUE_WATCHING_ROW_KEY }
    val cwGroup = if (cwIndex < 0) emptyList() else presentation.drop(cwIndex).take(2).filterIndexed { i, row ->
        i == 0 || row.key == MODERN_UPCOMING_ROW_KEY
    }
    val wideRows = cwGroup.map { NewLookRow(it.key, it.title, RowKind.WIDE, it.items.list, source = it) }
    val merged = HomeRowOrder.merge(
        orderKeys, BuiltInRow.entries, settings.homeRowOrder, continueWatching = wideRows.isNotEmpty()
    ) { types[it] }

    // Built-ins that follow each catalog row (by its index among the catalogs); those before every catalog lead.
    val leading = ArrayList<NewLookRow>()
    val after = HashMap<Int, MutableList<NewLookRow>>()
    var catalogIndex = -1
    for (key in merged) {
        if (!HomeRowOrder.isBuiltIn(key)) {
            catalogIndex++
            continue
        }
        val rows = if (key == HomeRowOrder.CONTINUE_WATCHING_KEY) {
            wideRows
        } else {
            listOfNotNull(BuiltInRow.fromRowKey(key)?.let { builtInRows[it] })
        }
        if (rows.isEmpty()) continue
        if (catalogIndex < 0) leading += rows else after.getOrPut(catalogIndex) { ArrayList() } += rows
    }

    val out = ArrayList<NewLookRow>(presentation.size + builtInRows.size)
    catalogIndex = -1
    var leadingPlaced = false
    for (row in presentation) {
        if (row.isWide) {
            if (cwGroup.none { it === row }) out += NewLookRow(row.key, row.title, RowKind.WIDE, row.items.list, source = row)
            continue
        }
        if (!leadingPlaced) {
            out += leading
            leadingPlaced = true
        }
        catalogIndex++
        val folders = row.items.list.isNotEmpty() && row.items.list.all { it.payload is ModernPayload.CollectionFolder }
        val highlighted = row.key in settings.highlightedRows || row.orderKey in settings.highlightedRows
        val grand = !folders && (row.key in settings.grandRows || row.orderKey in settings.grandRows)
        out += if (folders) {
            NewLookRow(
                key = row.key,
                title = row.title,
                kind = if (highlighted) RowKind.HIGHLIGHT else RowKind.COLLECTION,
                items = row.items.list,
                source = row,
                collection = true,
                folderShape = (row.items.list.first().payload as ModernPayload.CollectionFolder).posterShape
            )
        } else {
            val kind = when {
                grand -> RowKind.GRAND
                highlighted -> RowKind.HIGHLIGHT
                else -> RowKind.POSTER
            }
            NewLookRow(row.key, row.title, kind, row.items.list, source = row)
        }
        after[catalogIndex]?.let { out += it }
    }
    // No catalogs yet: the built-ins and Continue Watching still show.
    if (!leadingPlaced) out += leading

    // Today's Smart Highlights, spread evenly: the user's grand rows count as highlights too, and stay put.
    val smartRows = smartRows(smartHighlights, settings, titles, epochDay)
    if (smartRows.isEmpty()) return out
    val slots = SmartHighlightPlanner.placements(out.map { it.kind == RowKind.GRAND }, smartRows.size)
    val withSmart = ArrayList<NewLookRow>(out.size + slots.size)
    var next = 0
    out.forEachIndexed { i, row ->
        while (next < slots.size && slots[next] == i) withSmart += smartRows[next++]
        withSmart += row
    }
    while (next < slots.size) withSmart += smartRows[next++]
    return withSmart
}

/**
 * Smart Highlights as Home rows, with the user's choices applied straight from the settings: blocked and snoozed
 * themes left out, removed titles taken out (a row left too short is dropped), a blocked AI-written name replaced by
 * the theme's own.
 */
private fun smartRows(
    smart: List<SmartHighlightRow>,
    settings: BetaPerformanceSettings,
    titles: BuiltInTitles,
    epochDay: Long
): List<NewLookRow> {
    if (!settings.smartHighlights) return emptyList()
    val prefix = BetaPerformanceSettings.SMART_NAME_PREFIX
    val shown = smart.mapNotNull { row ->
        if (row.themeId in settings.blockedHighlights) return@mapNotNull null
        if (SmartHighlightPlanner.isSnoozed(row.themeId, settings.snoozedHighlights, epochDay)) return@mapNotNull null
        val items = row.items.filter { "${row.themeId}|${it.id}" !in settings.highlightExclusions }
        if (items.size < SmartHighlightPlanner.MIN_ITEMS) return@mapNotNull null
        val nameBlocked = row.aiNamed && (prefix + HighlightNameFilter.normalise(row.title)) in settings.blockedHighlights
        val aiNamed = row.aiNamed && settings.smartHighlightAiNames && !nameBlocked
        val key = SMART_ROW_PREFIX + row.themeId
        NewLookRow(
            key = key,
            title = if (aiNamed) row.title else row.catalogueTitle,
            kind = RowKind.HIGHLIGHT,
            items = items.mapIndexed { i, item -> builtInItem(key, item, i, titles) },
            smart = SmartRowInfo(row.themeId, row.catalogueTitle, aiNamed)
        )
    }
    // Today's number of them for the Enrichment level; some as grand rows at Medium and Heavy. Trakt's trending row
    // leads as a grand row there, and the every-third rule counts the others only.
    var others = 0
    return shown.take(SmartHighlightPlanner.dailyCount(settings.enrichment, epochDay)).map { row ->
        val grand = if (row.smart?.themeId == TraktHighlightSource.SPOTLIGHT_ID) {
            settings.enrichment >= BetaPerformanceSettings.ENRICHMENT_MEDIUM
        } else {
            SmartHighlightPlanner.isGrand(settings.enrichment, others++)
        }
        if (grand) row.copy(kind = RowKind.GRAND) else row
    }
}

/** The built-in rows with [pattern] (Layout's custom poster URL) applied to their posters; unchanged when blank. */
internal fun BuiltInRowsRepository.Rows.withCustomPosterUrls(pattern: String): BuiltInRowsRepository.Rows {
    if (pattern.isBlank()) return this
    return copy(
        trendingWeek = trendingWeek.withCustomPosterUrls(pattern),
        topMoviesToday = topMoviesToday.withCustomPosterUrls(pattern),
        topShowsToday = topShowsToday.withCustomPosterUrls(pattern),
        becauseYouWatched = becauseYouWatched?.let { it.copy(items = it.items.withCustomPosterUrls(pattern)) },
        forYou = forYou.withCustomPosterUrls(pattern)
    )
}

/** Row key prefix of the Smart Highlights, so they can't clash with catalogs or built-in rows. */
internal const val SMART_ROW_PREFIX = "smart:"

/** A built-in row with fewer titles than this isn't worth a row. */
internal const val MIN_BUILT_IN_ITEMS = 3

private val HeroCarouselRow.isWide: Boolean
    get() = key == MODERN_CONTINUE_WATCHING_ROW_KEY || key == MODERN_UPCOMING_ROW_KEY

/** The catalog order screen's key for this row: addon, type and id for catalogs, the row key otherwise. */
private val HeroCarouselRow.orderKey: String
    get() = if (addonId != null && apiType != null && catalogId != null) {
        com.nuvio.tv.domain.model.catalogRowLegacyKey(addonId, apiType, catalogId)
    } else key

private fun builtInItem(rowKey: String, item: MetaPreview, index: Int, titles: BuiltInTitles): ModernCarouselItem {
    val isSeries = item.apiType.equals("series", true)
    return ModernCarouselItem(
        key = "${rowKey}_${item.id}_$index",
        title = item.name,
        subtitle = item.releaseInfo,
        imageUrl = item.background ?: item.poster,
        heroPreview = HeroPreview(
            title = item.name,
            logo = item.logo,
            description = item.description,
            contentTypeText = if (isSeries) titles.show else titles.movie,
            isSeries = isSeries,
            yearText = item.releaseInfo,
            imdbText = item.imdbRating?.let { String.format(java.util.Locale.US, "%.1f", it) },
            genres = item.genres.asStable(),
            poster = item.poster,
            backdrop = item.background,
            imageUrl = item.background ?: item.poster
        ),
        payload = ModernPayload.Catalog(
            focusKey = "$rowKey::${item.id}",
            itemId = item.id,
            itemType = item.apiType,
            addonBaseUrl = "",
            trailerTitle = item.name,
            trailerReleaseInfo = item.releaseInfo,
            trailerApiType = item.apiType
        ),
        metaPreview = item
    )
}
