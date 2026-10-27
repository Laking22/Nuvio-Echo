package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** The thumbs on the details page: liked, loved (two thumbs) or not for me. */
enum class TitleRating(val key: String) {
    LIKE("like"),
    LOVE("love"),
    DISLIKE("dislike");

    companion object {
        fun of(key: String?): TitleRating? = entries.firstOrNull { it.key == key }
    }
}

data class RatedTitle(
    val contentId: String,
    val contentType: String,
    val title: String,
    val rating: TitleRating,
    val ratedAt: Long
)

data class RatedEpisode(
    val contentId: String,
    val contentType: String,
    /** The show's name, so a show whose episodes were rated can seed recommendations. */
    val title: String,
    val season: Int,
    val episode: Int,
    val rating: TitleRating,
    val ratedAt: Long
)

/** One profile's ratings. Titles are keyed by content id, episodes by [episodeKey]. */
data class TitleRatings(
    val titles: Map<String, RatedTitle> = emptyMap(),
    val episodes: Map<String, RatedEpisode> = emptyMap()
) {
    fun title(contentId: String): TitleRating? = titles[contentId]?.rating

    fun episodesOf(contentId: String): Map<Pair<Int, Int>, TitleRating> =
        episodes.values.filter { it.contentId == contentId }.associate { (it.season to it.episode) to it.rating }

    /** Changes whenever a rating does, for the rows that are rebuilt only when their inputs change. */
    val signature: String
        get() = (titles.values.map { "${it.contentId}=${it.rating.key}" } +
            episodes.values.map { "${episodeKey(it.contentId, it.season, it.episode)}=${it.rating.key}" })
            .sorted().joinToString(",")

    companion object {
        fun episodeKey(contentId: String, season: Int, episode: Int): String = "$season:$episode:$contentId"
    }
}

/**
 * Stores the details page's thumbs per profile. They feed For You, Because You Watched and Smart Highlights
 * (see [com.nuvio.tv.core.home.Taste]); they stay on this device.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class TitleRatingsStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    val ratings: Flow<TitleRatings> = profileManager.activeProfileId.flatMapLatest(::observe)

    fun observe(profileId: Int): Flow<TitleRatings> = factory.get(profileId, FEATURE).data.map(::readTitleRatings)

    suspend fun current(): TitleRatings = observe(profileManager.activeProfileId.value).first()

    /** Sets [rating] on a title, or clears it with null. */
    suspend fun rateTitle(contentId: String, contentType: String, title: String, rating: TitleRating?) {
        if (contentId.isBlank()) return
        factory.get(profileManager.activeProfileId.value, FEATURE).edit { prefs ->
            val key = stringPreferencesKey(TITLE_PREFIX + contentId)
            if (rating == null) prefs.remove(key)
            else prefs[key] = encode(rating, System.currentTimeMillis(), contentType, title)
        }
    }

    suspend fun rateEpisode(
        contentId: String,
        contentType: String,
        title: String,
        season: Int,
        episode: Int,
        rating: TitleRating?
    ) {
        if (contentId.isBlank()) return
        factory.get(profileManager.activeProfileId.value, FEATURE).edit { prefs ->
            val key = stringPreferencesKey(EPISODE_PREFIX + TitleRatings.episodeKey(contentId, season, episode))
            if (rating == null) prefs.remove(key)
            else prefs[key] = encode(rating, System.currentTimeMillis(), contentType, title)
        }
    }

    internal companion object {
        const val FEATURE = "title_ratings"
        const val TITLE_PREFIX = "t:"
        const val EPISODE_PREFIX = "e:"
        private const val SEP = '\t'

        fun encode(rating: TitleRating, at: Long, type: String, title: String): String =
            listOf(rating.key, at.toString(), type, title.replace(SEP, ' ')).joinToString(SEP.toString())
    }
}

/** Reads the stored entries; anything that doesn't parse is skipped. */
internal fun readTitleRatings(preferences: Preferences): TitleRatings {
    val titles = HashMap<String, RatedTitle>()
    val episodes = HashMap<String, RatedEpisode>()
    for ((key, value) in preferences.asMap()) {
        val raw = value as? String ?: continue
        val parts = raw.split('\t', limit = 4)
        val rating = TitleRating.of(parts.getOrNull(0)) ?: continue
        val at = parts.getOrNull(1)?.toLongOrNull() ?: 0L
        val type = parts.getOrNull(2).orEmpty()
        val title = parts.getOrNull(3).orEmpty()
        val name = key.name
        when {
            name.startsWith(TitleRatingsStore.TITLE_PREFIX) -> {
                val id = name.removePrefix(TitleRatingsStore.TITLE_PREFIX).takeIf { it.isNotBlank() } ?: continue
                titles[id] = RatedTitle(id, type, title, rating, at)
            }
            name.startsWith(TitleRatingsStore.EPISODE_PREFIX) -> {
                val rest = name.removePrefix(TitleRatingsStore.EPISODE_PREFIX)
                val season = rest.substringBefore(':').toIntOrNull() ?: continue
                val afterSeason = rest.substringAfter(':')
                val episode = afterSeason.substringBefore(':').toIntOrNull() ?: continue
                val id = afterSeason.substringAfter(':', "").takeIf { it.isNotBlank() } ?: continue
                episodes[TitleRatings.episodeKey(id, season, episode)] =
                    RatedEpisode(id, type, title, season, episode, rating, at)
            }
        }
    }
    return TitleRatings(titles, episodes)
}
