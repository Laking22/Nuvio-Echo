package com.nuvio.tv.core.home.smart

import com.nuvio.tv.core.trakt.TraktPublicListSourceResolver
import com.nuvio.tv.data.remote.dto.trakt.TraktListSummaryDto
import com.nuvio.tv.data.repository.TraktAuthService
import com.nuvio.tv.domain.model.MetaPreview
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Smart Highlights from Trakt, which needs only the app's client id (no sign-in):
 * - the day's popular and trending public lists, as themes named after the list (people's own titles, so the row
 *   names change without Gemini), and
 * - [SPOTLIGHT_ID], what's trending on Trakt now, which leads the day's highlights as a grand row.
 * Their titles go through the same filters as the TMDB themes (watched and rated ones out, no title twice).
 */
@Singleton
class TraktHighlightSource @Inject constructor(
    private val resolver: TraktPublicListSourceResolver,
    private val traktAuthService: TraktAuthService
) {
    /** Lists fetched for a day, so Home's refreshes that day don't ask Trakt again. */
    private var cachedDay: Long? = null
    private var cachedThemes: List<HighlightTheme> = emptyList()

    val available: Boolean get() = traktAuthService.hasRequiredCredentials()

    /** Today's list themes, best liked first; empty when Trakt can't be reached (tried again on the next refresh). */
    suspend fun listThemes(epochDay: Long): List<HighlightTheme> {
        synchronized(this) { if (cachedDay == epochDay) return cachedThemes }
        val lists = resolver.prominentListSummaries(LIST_CANDIDATES) ?: return emptyList()
        val themes = lists
            .filter { (list, likes) -> isUsable(list, likes) }
            .sortedByDescending { it.second }
            .take(MAX_LIST_THEMES)
            .mapNotNull { (list, _) -> list.toTheme() }
        synchronized(this) {
            cachedDay = epochDay
            cachedThemes = themes
        }
        return themes
    }

    /** Today's spotlight theme: trending on Trakt. */
    val spotlight: HighlightTheme = HighlightTheme(
        id = SPOTLIGHT_ID,
        title = "Trending This Week",
        definition = "Movies and shows that the most people are watching right now.",
        movie = null,
        tv = null
    )

    /** The titles behind a Trakt theme, or null when Trakt couldn't be reached. */
    suspend fun items(theme: HighlightTheme): List<MetaPreview>? = when {
        theme.id == SPOTLIGHT_ID -> resolver.trendingBackdropPreviews(ITEMS_PER_ROW)
        theme.traktListId != null -> resolver.listBackdropPreviews(theme.traktListId, ITEMS_PER_ROW)
        else -> null
    }

    private fun TraktListSummaryDto.toTheme(): HighlightTheme? {
        val id = ids?.trakt ?: return null
        val title = name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return HighlightTheme(
            id = LIST_PREFIX + id,
            title = title,
            definition = description?.trim()?.takeIf { it.isNotEmpty() }?.take(DEFINITION_LENGTH) ?: title,
            movie = null,
            tv = null,
            traktListId = id
        )
    }

    internal companion object {
        /** Theme id of the spotlight row. */
        const val SPOTLIGHT_ID = "trakt_trending"
        /** Theme ids of list rows: this plus the list's Trakt id, so a blocked list stays blocked. */
        const val LIST_PREFIX = "trakt_list_"
        private const val LIST_CANDIDATES = 40
        /** List themes offered a day; the planner orders them with the rest. */
        private const val MAX_LIST_THEMES = 10
        private const val ITEMS_PER_ROW = 40
        private const val DEFINITION_LENGTH = 200
        /** Lists shorter than this rarely fill a row once watched titles are out; longer ones are dumps. */
        private const val MIN_LIST_ITEMS = 15
        private const val MAX_LIST_ITEMS = 500
        private const val MIN_LIKES = 5
        /** Lists people keep for themselves rather than as a theme. */
        private val PERSONAL = Regex("""\b(watchlist|watched|my |to watch|seen|favou?rites?|collection|plex|library|20\d\d watched)\b""", RegexOption.IGNORE_CASE)

        /** A public list that reads as a theme: a sensible size, liked by people, and a name the filter accepts. */
        fun isUsable(list: TraktListSummaryDto, likes: Int): Boolean {
            val name = list.name?.trim().orEmpty()
            if (list.privacy != null && !list.privacy.equals("public", ignoreCase = true)) return false
            val count = list.itemCount ?: return false
            if (count !in MIN_LIST_ITEMS..MAX_LIST_ITEMS) return false
            if (likes < MIN_LIKES) return false
            if (PERSONAL.containsMatchIn("$name ")) return false
            return HighlightNameFilter.isAcceptable(name)
        }
    }
}
