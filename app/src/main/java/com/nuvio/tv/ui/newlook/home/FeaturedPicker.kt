package com.nuvio.tv.ui.newlook.home

import android.content.Context
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import kotlin.random.Random

/**
 * Picks the hero carousel's titles so it doesn't keep opening on the same ones: titles featured lately (kept across
 * restarts) wait their turn while the pool has others, and movies and shows take turns.
 */
internal object FeaturedPicker {
    const val SLIDES = 6
    /** How many recently featured titles are remembered: six opens' worth. */
    const val HISTORY = 36

    /**
     * [SLIDES] titles from [pool], movies and shows taking turns (starting with either). Within each, titles not in
     * [recent] (newest first) come first, shuffled, then the ones featured longest ago; a turn goes to the other kind
     * when only it still has a title not featured lately.
     */
    fun pick(pool: List<MetaPreview>, recent: List<String>, random: Random = Random.Default): List<MetaPreview> {
        val recentRank = recent.withIndex().associate { it.value to it.index }
        fun fresh(item: MetaPreview) = item.id !in recentRank
        fun ordered(items: List<MetaPreview>) =
            items.filter(::fresh).shuffled(random) + items.filterNot(::fresh).sortedByDescending { recentRank.getValue(it.id) }
        val (showList, movieList) = pool.partition { it.isShow() }
        val shows = ArrayDeque(ordered(showList))
        val movies = ArrayDeque(ordered(movieList))
        var showsTurn = random.nextBoolean()
        return buildList {
            while (size < SLIDES && (shows.isNotEmpty() || movies.isNotEmpty())) {
                val mine = if (showsTurn) shows else movies
                val other = if (showsTurn) movies else shows
                val takeMine = mine.isNotEmpty() && (fresh(mine.first()) || other.isEmpty() || !fresh(other.first()))
                add(if (takeMine) mine.removeFirst() else other.removeFirst())
                showsTurn = !showsTurn
            }
        }
    }

    /** [recent] with [picked] put first, at most [HISTORY] long. */
    fun remember(recent: List<String>, picked: List<MetaPreview>): List<String> =
        (picked.map { it.id } + recent).distinct().take(HISTORY)

    private fun MetaPreview.isShow(): Boolean =
        type == ContentType.SERIES || apiType.equals("series", ignoreCase = true) || apiType.equals("tv", ignoreCase = true)

    private const val PREFS = "new_look_home"
    private const val RECENT_KEY = "featured_recent"

    fun loadRecent(context: Context): List<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(RECENT_KEY, null)
            ?.split('\n')?.filter { it.isNotBlank() }.orEmpty()

    fun saveRecent(context: Context, recent: List<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(RECENT_KEY, recent.joinToString("\n")).apply()
    }
}
