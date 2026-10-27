package com.nuvio.tv.core.home.smart

import android.content.Context

/**
 * Which themes and titles Smart Highlights showed on recent days, per profile, so the next days bring new ones: a
 * theme shown lately sinks in the day's order ([SmartHighlightPlanner.recencyPenalty]) and titles shown lately go last
 * in their rows. Entries are "id@epochDay"; ones older than [KEEP_DAYS] are dropped.
 */
internal class HighlightHistory(context: Context) {
    private val prefs = context.getSharedPreferences("smart_highlight_history", Context.MODE_PRIVATE)

    /** The day each theme was last shown. */
    fun themes(profileId: Int): Map<String, Long> = read(themesKey(profileId))

    /** Titles shown on the [TITLE_DAYS] days before [today] (today's own don't count, so the rows hold all day). */
    fun recentTitles(profileId: Int, today: Long): Set<String> =
        read(titlesKey(profileId)).filterValues { it in (today - TITLE_DAYS) until today }.keys

    fun record(profileId: Int, today: Long, themeIds: Collection<String>, titleIds: Collection<String>) {
        prefs.edit()
            .putStringSet(themesKey(profileId), merge(read(themesKey(profileId)), themeIds, today))
            .putStringSet(titlesKey(profileId), merge(read(titlesKey(profileId)), titleIds, today))
            .apply()
    }

    private fun merge(kept: Map<String, Long>, ids: Collection<String>, today: Long): Set<String> {
        val all = HashMap(kept)
        ids.forEach { all[it] = today }
        return all.filterValues { today - it <= KEEP_DAYS }.map { (id, day) -> "$id@$day" }.toSet()
    }

    private fun read(key: String): Map<String, Long> {
        val out = HashMap<String, Long>()
        prefs.getStringSet(key, null).orEmpty().forEach { entry ->
            val id = entry.substringBeforeLast('@')
            val day = entry.substringAfterLast('@', "").toLongOrNull() ?: return@forEach
            if (day > (out[id] ?: Long.MIN_VALUE)) out[id] = day
        }
        return out
    }

    private fun themesKey(profileId: Int) = "themes_$profileId"
    private fun titlesKey(profileId: Int) = "titles_$profileId"

    private companion object {
        const val KEEP_DAYS = 7L
        const val TITLE_DAYS = 3L
    }
}
