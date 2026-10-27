package com.nuvio.tv.core.calendar

import androidx.compose.runtime.Immutable
import java.time.LocalDate

/** Where a show stands. The order is the order of the shows without a date in the Calendar. */
enum class CalendarStatus {
    RETURNING,
    IN_PRODUCTION,
    UNKNOWN,
    ENDED,
    CANCELED;

    val finished: Boolean get() = this == ENDED || this == CANCELED

    companion object {
        /** TMDB ("Returning Series", "In Production", "Planned", "Ended", "Canceled") or Cinemeta ("Continuing"). */
        fun from(raw: String?): CalendarStatus {
            val s = raw?.trim()?.lowercase() ?: return UNKNOWN
            return when {
                s.startsWith("cancel") -> CANCELED
                s.startsWith("ended") -> ENDED
                s.startsWith("returning") || s.startsWith("continuing") -> RETURNING
                s.startsWith("in production") || s.startsWith("planned") || s.startsWith("pilot") -> IN_PRODUCTION
                else -> UNKNOWN
            }
        }
    }
}

/** What kind of episode it is, from TMDB's episode_type (and the episode number for premieres). */
enum class CalendarMarker { NONE, SERIES_PREMIERE, SEASON_PREMIERE, FINALE, MID_SEASON }

@Immutable
data class CalendarEpisode(
    val season: Int,
    val episode: Int,
    val name: String?,
    /** The calendar date it airs (TMDB air_date, or a release date in the viewer's zone). */
    val airDate: LocalDate?,
    val overview: String?,
    val runtimeMinutes: Int?,
    /** A still of the episode, when the source has one. */
    val still: String?,
    val type: String?
) {
    val key: String get() = "S${season}E$episode"

    val marker: CalendarMarker
        get() = when {
            type.equals("finale", ignoreCase = true) -> CalendarMarker.FINALE
            type.equals("mid_season", ignoreCase = true) -> CalendarMarker.MID_SEASON
            episode == 1 && season == 1 -> CalendarMarker.SERIES_PREMIERE
            episode == 1 && season > 1 -> CalendarMarker.SEASON_PREMIERE
            else -> CalendarMarker.NONE
        }
}

/** A followed show and what the schedule sources said about it. */
@Immutable
data class CalendarShow(
    /** The id the rest of the app opens the show by (usually its IMDb id). */
    val id: String,
    val title: String,
    val poster: String?,
    /** Landscape art for the big panel: the next episode's still, else the show's backdrop. */
    val backdrop: String?,
    val status: CalendarStatus,
    /** The next episode with a date today or later; null when none is announced. */
    val next: CalendarEpisode?,
    /** The latest episode that already aired. */
    val last: CalendarEpisode?,
    val network: String?,
    val seasons: Int?,
    val genre: String?,
    val addonBaseUrl: String?,
    /** TMDB's id for the show, once looked up (saves the IMDb → TMDB request on every refresh). */
    val tmdbId: Int?,
    /** When the sources were last asked, epoch ms; 0 = never (only what the library knew). */
    val fetchedAt: Long
) {
    /** The next episode, if it airs today or later. */
    fun upcoming(today: LocalDate): CalendarEpisode? = next?.takeIf { ep -> ep.airDate?.let { !it.isBefore(today) } == true }
}

/** "Remind me" for a show. It fires once per episode, for episodes that air after it was turned on. */
@Immutable
data class CalendarReminder(
    val showId: String,
    val title: String,
    /** The day it was turned on; episodes that aired before don't count. */
    val since: LocalDate,
    /** Episodes up to this air date were already shown on Home; null = none yet. */
    val notifiedThrough: LocalDate?
)

/** A reminder whose episode is out and hasn't been shown yet. */
@Immutable
data class CalendarDue(
    val show: CalendarShow,
    val episode: CalendarEpisode
)
