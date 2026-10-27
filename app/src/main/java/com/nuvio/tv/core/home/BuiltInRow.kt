package com.nuvio.tv.core.home

import androidx.annotation.StringRes
import com.nuvio.tv.R

/** Nuvio's own highlighted rows on the new look's Home, not tied to any addon. */
enum class BuiltInRow(val key: String, @StringRes val labelRes: Int, val ranked: Boolean = false) {
    TRENDING_WEEK("trending_week", R.string.home_row_trending_week),
    BECAUSE_YOU_WATCHED("because_you_watched", R.string.home_row_because_you_watched_setting),
    FOR_YOU("for_you", R.string.home_row_for_you),
    TOP_MOVIES_TODAY("top_movies_today", R.string.home_row_top_movies_today, ranked = true),
    TOP_SHOWS_TODAY("top_shows_today", R.string.home_row_top_shows_today, ranked = true);

    /** Row key on Home, so it can't clash with an addon catalog's key. */
    val rowKey: String get() = HomeRowOrder.BUILT_IN_PREFIX + key

    companion object {
        fun fromRowKey(rowKey: String): BuiltInRow? = entries.firstOrNull { it.rowKey == rowKey }
    }
}
