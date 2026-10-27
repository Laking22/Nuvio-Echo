package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaCastMember
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** A title's people from TMDB, every one with a TMDB id so their filmography opens. */
data class TitleCredits(
    val cast: List<MetaCastMember> = emptyList(),
    /** [MetaCastMember.character] holds the job(s): "Director", "Writer, Producer"... */
    val crew: List<MetaCastMember> = emptyList()
)

/**
 * Full cast and crew for the new details page's Cast & Crew panel. Addon metadata usually lists the cast and one
 * lead credit; TMDB has the whole crew (shows: all seasons, via aggregate credits). Without a TMDB key, empty.
 */
@Singleton
class TitleCreditsRepository @Inject constructor(
    private val tmdbApi: TmdbApi,
    private val tmdbService: TmdbService
) {
    private val cache = HashMap<String, TitleCredits>()

    suspend fun credits(meta: Meta): TitleCredits = withContext(Dispatchers.IO) {
        synchronized(cache) { cache[meta.id] }?.let { return@withContext it }
        val key = com.nuvio.tv.core.apikeys.ApiKeys.tmdb
        if (key.isBlank()) return@withContext TitleCredits()
        val isTv = meta.type == ContentType.SERIES || meta.type == ContentType.TV ||
            meta.apiType.equals("series", true) || meta.apiType.equals("tv", true)
        try {
            val tmdbId = tmdbService.ensureTmdbId(meta.id, if (isTv) "tv" else "movie", meta.imdbId)?.toIntOrNull()
                ?: return@withContext TitleCredits()
            val credits = if (isTv) {
                val body = tmdbApi.getTvAggregateCredits(tmdbId, key).body() ?: return@withContext TitleCredits()
                TitleCredits(
                    cast = body.cast.orEmpty()
                        .sortedByDescending { it.totalEpisodeCount ?: 0 }
                        .mapNotNull { c ->
                            val id = c.id ?: return@mapNotNull null
                            val name = c.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                            MetaCastMember(name, c.roles?.firstOrNull()?.character?.takeIf { it.isNotBlank() }, photo(c.profilePath), id)
                        }
                        .take(MAX_CAST),
                    crew = orderCrew(body.crew.orEmpty().mapNotNull { c ->
                        val id = c.id ?: return@mapNotNull null
                        val name = c.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        CrewEntry(id, name, c.department, c.jobs.orEmpty().mapNotNull { it.job }, photo(c.profilePath), c.totalEpisodeCount ?: 0)
                    })
                )
            } else {
                val body = tmdbApi.getMovieCredits(tmdbId, key).body() ?: return@withContext TitleCredits()
                TitleCredits(
                    cast = body.cast.orEmpty().mapNotNull { c ->
                        val id = c.id ?: return@mapNotNull null
                        val name = c.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        MetaCastMember(name, c.character?.takeIf { it.isNotBlank() }, photo(c.profilePath), id)
                    }.take(MAX_CAST),
                    crew = orderCrew(body.crew.orEmpty().mapNotNull { c ->
                        val id = c.id ?: return@mapNotNull null
                        val name = c.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        CrewEntry(id, name, c.department, listOfNotNull(c.job), photo(c.profilePath), 0)
                    })
                )
            }
            synchronized(cache) { cache[meta.id] = credits }
            credits
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "credits failed: ${e.javaClass.simpleName}")
            TitleCredits()
        }
    }

    internal data class CrewEntry(
        val id: Int,
        val name: String,
        val department: String?,
        val jobs: List<String>,
        val photo: String?,
        val episodes: Int
    )

    internal companion object {
        private const val TAG = "TitleCredits"
        private const val MAX_CAST = 60
        private const val MAX_CREW = 60
        private val DEPARTMENTS = listOf(
            "Directing", "Writing", "Creator", "Production", "Sound", "Camera", "Editing",
            "Art", "Costume & Make-Up", "Visual Effects", "Lighting", "Crew"
        )

        private fun photo(path: String?): String? = path?.takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/w185$it" }

        /**
         * One entry per person (their jobs joined), the key departments first and, within one, the people on the
         * most episodes first; at most [MAX_CREW].
         */
        fun orderCrew(entries: List<CrewEntry>): List<MetaCastMember> {
            val byPerson = LinkedHashMap<Int, CrewEntry>()
            entries.forEach { e ->
                val existing = byPerson[e.id]
                byPerson[e.id] = if (existing == null) e else existing.copy(
                    jobs = (existing.jobs + e.jobs).distinct(),
                    episodes = maxOf(existing.episodes, e.episodes),
                    department = listOfNotNull(existing.department, e.department)
                        .minByOrNull { DEPARTMENTS.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } }
                )
            }
            return byPerson.values
                .sortedWith(
                    compareBy<CrewEntry> { DEPARTMENTS.indexOf(it.department).let { i -> if (i < 0) Int.MAX_VALUE else i } }
                        .thenByDescending { it.episodes }
                )
                .take(MAX_CREW)
                .map { MetaCastMember(it.name, it.jobs.take(3).joinToString(", ").ifBlank { it.department }, it.photo, it.id) }
        }
    }
}
