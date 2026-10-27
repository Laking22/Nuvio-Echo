package com.nuvio.tv.core.calendar

import android.content.Context
import android.util.Log
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.core.util.parseEpisodeReleaseLocalDate
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TmdbScheduleEpisode
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.repository.LibraryRepository
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Calendar: when the next episodes of the shows the active profile follows come out, and the "Remind me"
 * reminders that show on Home on the day.
 *
 * Followed = series in the library (watchlist, Trakt, saved lists) plus series watched in the last six months.
 * Each show is asked about once per 12 h: TMDB's /tv/{id} when the build has a key (one request carries status,
 * next and last episode, network, runtime and art), else the addons' meta, whose episode list has release dates.
 * Everything is cached per profile, so the screen opens on the last answers and updates as the new ones arrive.
 *
 * Nothing runs unless the Calendar is opened, or Home checks the few shows that have a reminder.
 */
@Singleton
class CalendarRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val tmdbApi: TmdbApi,
    private val tmdbService: TmdbService,
    private val tmdbSettingsDataStore: TmdbSettingsDataStore,
    private val metaRepository: MetaRepository,
    private val libraryRepository: LibraryRepository,
    private val watchProgressRepository: WatchProgressRepository,
    private val profileManager: ProfileManager
) {
    data class Snapshot(
        val shows: List<CalendarShow> = emptyList(),
        val followed: Int = 0,
        val reminders: Map<String, CalendarReminder> = emptyMap(),
        val refreshing: Boolean = false,
        /** False until the followed shows were listed once. */
        val loaded: Boolean = false
    )

    private val store = CalendarStore(context)
    private val lock = Mutex()
    private val refreshLock = Mutex()
    private var profileId = -1
    private val shows = LinkedHashMap<String, CalendarShow>()
    private val reminders = LinkedHashMap<String, CalendarReminder>()
    /** The followed shows' ids, in the order they were found. */
    private var followedIds: List<String> = emptyList()

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    private val hasTmdb: Boolean get() = com.nuvio.tv.core.apikeys.ApiKeys.tmdb.isNotBlank()

    /** Lists the followed shows (cached answers at once), then asks the sources about stale ones. */
    suspend fun refresh(force: Boolean = false) = withContext(Dispatchers.IO) {
        if (!refreshLock.tryLock()) return@withContext   // one refresh at a time; the running one will publish
        try {
            ensureProfile()
            val followed = followed()
            lock.withLock {
                followedIds = followed.map { it.id }
                for (f in followed) {
                    val cached = shows[f.id]
                    shows[f.id] = cached?.copy(
                        title = cached.title.ifBlank { f.title },
                        poster = cached.poster ?: f.poster,
                        backdrop = cached.backdrop ?: f.background,
                        addonBaseUrl = cached.addonBaseUrl ?: f.addonBaseUrl
                    ) ?: placeholder(f)
                }
            }
            publish(refreshing = true, loaded = true)

            val now = System.currentTimeMillis()
            val stale = followed.filter { f -> force || isStale(shows[f.id], now) }
            fetchAll(stale)
            lock.withLock { store.writeShows(profileId, shows.values) }
        } finally {
            publish(refreshing = false, loaded = true)
            refreshLock.unlock()
        }
    }

    /** Turns "Remind me" on or off for [showId]. Only shows with an announced next episode can be turned on. */
    suspend fun toggleReminder(showId: String) = withContext(Dispatchers.IO) {
        ensureProfile()
        lock.withLock {
            if (reminders.remove(showId) == null) {
                val show = shows[showId] ?: return@withLock
                if (show.upcoming(LocalDate.now()) == null) return@withLock
                reminders[showId] = CalendarReminder(showId, show.title, LocalDate.now(), notifiedThrough = null)
            }
            store.writeReminders(profileId, reminders.values)
        }
        publish()
    }

    /**
     * Reminded shows whose episode is out and wasn't shown yet. Shows whose answer is older than 12 h are asked
     * again first (only the reminded ones, so this stays a handful of requests).
     */
    suspend fun dueReminders(): List<CalendarDue> = withContext(Dispatchers.IO) {
        ensureProfile()
        val pending = lock.withLock { reminders.values.toList() }
        if (pending.isEmpty()) return@withContext emptyList()
        val now = System.currentTimeMillis()
        val stale = pending.mapNotNull { r ->
            val show = lock.withLock { shows[r.showId] }
            if (show != null && !isStale(show, now)) null else Followed(
                id = r.showId, title = show?.title ?: r.title, poster = show?.poster, background = show?.backdrop,
                addonBaseUrl = show?.addonBaseUrl, imdbId = r.showId.takeIf { it.startsWith("tt") }, tmdbId = show?.tmdbId
            )
        }
        if (stale.isNotEmpty()) {
            fetchAll(stale)
            lock.withLock { store.writeShows(profileId, shows.values) }
        }
        val today = LocalDate.now()
        lock.withLock {
            pending.mapNotNull { r ->
                val show = shows[r.showId] ?: return@mapNotNull null
                val episode = listOfNotNull(show.next, show.last)
                    .filter { ep ->
                        val date = ep.airDate ?: return@filter false
                        !date.isAfter(today) && !date.isBefore(r.since) &&
                            (r.notifiedThrough == null || date.isAfter(r.notifiedThrough))
                    }
                    .maxByOrNull { it.airDate!! }
                    ?: return@mapNotNull null
                CalendarDue(show, episode)
            }
        }
    }

    /**
     * Followed shows (without a reminder) whose next episode airs today, from the cache only: no requests, so
     * Home can mention them without the Calendar having to refresh everything.
     */
    suspend fun airingToday(): List<CalendarDue> = withContext(Dispatchers.IO) {
        ensureProfile()
        val today = LocalDate.now()
        lock.withLock {
            shows.values.mapNotNull { show ->
                if (show.id in reminders) return@mapNotNull null
                val next = show.next?.takeIf { it.airDate == today } ?: return@mapNotNull null
                CalendarDue(show, next)
            }
        }
    }

    /** These were shown on Home: each reminder stays on, for the show's next episode. */
    suspend fun markNotified(due: List<CalendarDue>) = withContext(Dispatchers.IO) {
        if (due.isEmpty()) return@withContext
        lock.withLock {
            for (d in due) {
                val r = reminders[d.show.id] ?: continue
                val date = d.episode.airDate ?: continue
                if (r.notifiedThrough == null || date.isAfter(r.notifiedThrough)) {
                    reminders[d.show.id] = r.copy(notifiedThrough = date)
                }
            }
            store.writeReminders(profileId, reminders.values)
        }
        publish()
    }

    private suspend fun ensureProfile() {
        val active = profileManager.activeProfileId.value
        lock.withLock {
            if (active == profileId) return
            profileId = active
            shows.clear()
            shows.putAll(store.readShows(active))
            reminders.clear()
            reminders.putAll(store.readReminders(active))
            followedIds = emptyList()
        }
        _state.value = Snapshot(reminders = lock.withLock { reminders.toMap() })
    }

    private suspend fun publish(refreshing: Boolean = _state.value.refreshing, loaded: Boolean = _state.value.loaded) {
        val snapshot = lock.withLock {
            Snapshot(
                shows = followedIds.mapNotNull { shows[it] },
                followed = followedIds.size,
                reminders = reminders.toMap(),
                refreshing = refreshing,
                loaded = loaded
            )
        }
        _state.value = snapshot
    }

    private class Followed(
        val id: String,
        val title: String,
        val poster: String?,
        val background: String?,
        val addonBaseUrl: String?,
        val imdbId: String?,
        val tmdbId: Int?
    )

    /** Series in the library plus series watched lately, library first, each once. */
    private suspend fun followed(): List<Followed> {
        val out = LinkedHashMap<String, Followed>()
        val library = withTimeoutOrNull(LIST_TIMEOUT_MS) { libraryRepository.libraryItems.first() }.orEmpty()
        for (entry in library) {
            if (!isSeries(entry.type)) continue
            val key = entry.imdbId?.takeIf { it.startsWith("tt") } ?: entry.id
            if (key in out) continue
            out[key] = Followed(
                id = key, title = entry.name, poster = entry.poster, background = entry.background,
                addonBaseUrl = entry.addonBaseUrl, imdbId = entry.imdbId, tmdbId = entry.tmdbId
            )
        }
        val cutoff = System.currentTimeMillis() - WATCHED_WITHIN_MS
        val progress = withTimeoutOrNull(LIST_TIMEOUT_MS) { watchProgressRepository.allProgress.first() }.orEmpty()
        for (p in progress.sortedByDescending { it.lastWatched }) {
            if (!isSeries(p.contentType) || p.lastWatched < cutoff) continue
            if (p.contentId in out || runCatching { watchProgressRepository.isDroppedShow(p.contentId) }.getOrDefault(false)) continue
            out[p.contentId] = Followed(
                id = p.contentId, title = p.name, poster = p.poster, background = p.backdrop,
                addonBaseUrl = p.addonBaseUrl, imdbId = p.contentId.takeIf { it.startsWith("tt") }, tmdbId = null
            )
        }
        return out.values.take(MAX_SHOWS)
    }

    private fun isSeries(type: String?) = type.equals("series", ignoreCase = true) || type.equals("tv", ignoreCase = true)

    private fun isStale(show: CalendarShow?, now: Long) = show == null || now - show.fetchedAt > STALE_MS

    private fun placeholder(f: Followed) = CalendarShow(
        id = f.id, title = f.title, poster = f.poster, backdrop = f.background, status = CalendarStatus.UNKNOWN,
        next = null, last = null, network = null, seasons = null, genre = null, addonBaseUrl = f.addonBaseUrl,
        tmdbId = f.tmdbId, fetchedAt = 0L
    )

    private suspend fun fetchAll(followed: List<Followed>) {
        if (followed.isEmpty()) return
        val started = System.currentTimeMillis()
        val gate = Semaphore(PARALLEL)
        var fromTmdb = 0
        var fromAddons = 0
        var failed = 0
        var lastPublish = 0L
        coroutineScope {
            followed.map { f ->
                async {
                    gate.withPermit {
                        val cached = lock.withLock { shows[f.id] }
                        val result = try {
                            fetchTmdb(f, cached)?.also { fromTmdb++ } ?: fetchAddons(f, cached)?.also { fromAddons++ }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            null
                        }
                        val now = System.currentTimeMillis()
                        val updated = result ?: run {
                            failed++
                            // Keep what was known and try again in an hour rather than at every opening.
                            (cached ?: placeholder(f)).copy(fetchedAt = now - STALE_MS + RETRY_MS)
                        }
                        lock.withLock { shows[f.id] = updated }
                        if (now - lastPublish > PUBLISH_EVERY_MS) {
                            lastPublish = now
                            publish()
                        }
                    }
                }
            }.awaitAll()
        }
        Log.i(
            TAG,
            "asked about ${followed.size} shows in ${(System.currentTimeMillis() - started) / 1000.0} s: " +
                "$fromTmdb from TMDB, $fromAddons from addons, $failed without an answer"
        )
    }

    private suspend fun fetchTmdb(f: Followed, cached: CalendarShow?): CalendarShow? {
        if (!hasTmdb) return null
        val tmdbId = cached?.tmdbId ?: f.tmdbId
            ?: tmdbService.ensureTmdbId(f.id, "tv", f.imdbId)?.toIntOrNull()
            ?: return null
        val language = tmdbSettingsDataStore.settings.value.language
        val body = tmdbApi.getTvSchedule(tmdbId, com.nuvio.tv.core.apikeys.ApiKeys.tmdb, language).body() ?: return null
        val today = LocalDate.now()
        val next = body.nextEpisodeToAir?.toEpisode()
        val last = body.lastEpisodeToAir?.toEpisode()
        // A next episode whose day has passed has aired; TMDB moves it to last_episode_to_air within a day.
        val upcoming = next?.takeIf { it.airDate == null || !it.airDate.isBefore(today) }
        val aired = if (next != null && upcoming == null) next else last
        return CalendarShow(
            id = f.id,
            title = body.name?.takeIf { it.isNotBlank() } ?: cached?.title ?: f.title,
            poster = body.posterPath?.let { "$IMAGE_BASE/w342$it" } ?: cached?.poster ?: f.poster,
            backdrop = (upcoming ?: aired)?.still
                ?: body.backdropPath?.let { "$IMAGE_BASE/w780$it" }
                ?: cached?.backdrop ?: f.background,
            status = CalendarStatus.from(body.status),
            next = upcoming,
            last = aired,
            network = body.networks?.firstOrNull { !it.name.isNullOrBlank() }?.name,
            seasons = body.numberOfSeasons,
            genre = body.genres?.firstOrNull()?.name,
            addonBaseUrl = cached?.addonBaseUrl ?: f.addonBaseUrl,
            tmdbId = tmdbId,
            fetchedAt = System.currentTimeMillis()
        ).let { show ->
            // TMDB's per-episode runtime is often missing; the show's usual one fills in.
            val runtime = body.episodeRunTime?.firstOrNull { it > 0 }
            if (runtime == null) show else show.copy(
                next = show.next?.let { if (it.runtimeMinutes == null) it.copy(runtimeMinutes = runtime) else it },
                last = show.last?.let { if (it.runtimeMinutes == null) it.copy(runtimeMinutes = runtime) else it }
            )
        }
    }

    private fun TmdbScheduleEpisode.toEpisode(): CalendarEpisode? {
        val season = seasonNumber ?: return null
        val number = episodeNumber ?: return null
        return CalendarEpisode(
            season = season,
            episode = number,
            name = name?.takeIf { it.isNotBlank() },
            airDate = airDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            overview = overview?.takeIf { it.isNotBlank() },
            runtimeMinutes = runtime?.takeIf { it > 0 },
            still = stillPath?.let { "$IMAGE_BASE/w780$it" },
            type = episodeType
        )
    }

    /** Without TMDB: the addons' meta for the show, whose episodes carry release dates. */
    private suspend fun fetchAddons(f: Followed, cached: CalendarShow?): CalendarShow? {
        val meta = withTimeoutOrNull(ADDON_TIMEOUT_MS) {
            metaRepository.getMetaFromAllAddons("series", f.id, f.addonBaseUrl)
                .first { it !is NetworkResult.Loading }
        }
        val data = (meta as? NetworkResult.Success<Meta>)?.data ?: return null
        val today = LocalDate.now()
        val episodes = data.videos.mapNotNull { v ->
            val season = v.season?.takeIf { it > 0 } ?: return@mapNotNull null
            val number = v.episode ?: return@mapNotNull null
            val date = parseEpisodeReleaseLocalDate(v.released) ?: return@mapNotNull null
            CalendarEpisode(
                season = season, episode = number, name = v.title.takeIf { it.isNotBlank() }, airDate = date,
                overview = v.overview?.takeIf { it.isNotBlank() }, runtimeMinutes = v.runtime, still = v.thumbnail,
                type = null
            )
        }
        val next = episodes.filter { !it.airDate!!.isBefore(today) }.minByOrNull { it.airDate!! }
        val last = episodes.filter { it.airDate!!.isBefore(today) }.maxByOrNull { it.airDate!! }
        return CalendarShow(
            id = f.id,
            title = data.name.ifBlank { cached?.title ?: f.title },
            poster = data.poster ?: cached?.poster ?: f.poster,
            backdrop = (next ?: last)?.still ?: data.background ?: cached?.backdrop ?: f.background,
            status = CalendarStatus.from(data.status),
            next = next,
            last = last,
            network = data.networks.firstOrNull()?.name,
            seasons = episodes.maxOfOrNull { it.season },
            genre = data.genres.firstOrNull(),
            addonBaseUrl = cached?.addonBaseUrl ?: f.addonBaseUrl,
            tmdbId = cached?.tmdbId,
            fetchedAt = System.currentTimeMillis()
        )
    }

    private companion object {
        const val TAG = "Calendar"
        const val IMAGE_BASE = "https://image.tmdb.org/t/p"
        const val STALE_MS = 12 * 60 * 60 * 1000L
        const val RETRY_MS = 60 * 60 * 1000L
        const val WATCHED_WITHIN_MS = 180L * 24 * 60 * 60 * 1000L
        const val MAX_SHOWS = 150
        const val PARALLEL = 4
        const val PUBLISH_EVERY_MS = 400L
        const val LIST_TIMEOUT_MS = 5_000L
        const val ADDON_TIMEOUT_MS = 15_000L
    }
}
