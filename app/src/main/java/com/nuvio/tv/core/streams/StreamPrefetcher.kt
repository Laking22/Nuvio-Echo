package com.nuvio.tv.core.streams

import android.util.Log
import com.nuvio.tv.data.local.BetaPerformanceSettingsDataStore
import com.nuvio.tv.domain.model.enabledAddons
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.StreamRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

private const val TAG = "StreamPrefetcher"
/** How long focus rests on an item before its sources are fetched. */
const val PREFETCH_FOCUS_DWELL_MS = 800L
private const val FOCUS_DWELL_MS = PREFETCH_FOCUS_DWELL_MS

/**
 * Starts source searches before the Streams screen asks for them.
 *
 * Searches live in [StreamRepository]'s session cache, which runs each search to completion
 * independently of whoever is collecting it. Prefetching therefore only has to *start* the
 * session with the exact (type, videoId, season, episode) the Streams screen will request;
 * when the user presses Play, the screen attaches to the running (or finished) search.
 *
 * Prefetches are speculative: only one unclaimed prefetch runs at a time (a newer one cancels
 * it), and each one asks for a single movie or a single episode, never a season.
 */
@Singleton
class StreamPrefetcher @Inject constructor(
    private val streamRepository: StreamRepository,
    private val addonRepository: AddonRepository,
    private val betaSettings: BetaPerformanceSettingsDataStore,
    @Named("addonStreams") private val streamsHttpClient: OkHttpClient
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pendingFocusPrefetch: Job? = null

    /** A title was opened: search for what its Play button will launch. */
    fun prefetchOnOpen(type: String, videoId: String, season: Int? = null, episode: Int? = null) {
        if (!betaSettings.current.value.prefetchOnOpen) return
        startSpeculativeSearch(type, videoId, season, episode)
    }

    /**
     * For items that go straight to the Streams screen when clicked (Continue Watching, episode
     * rows): start the search once focus has rested on the item, so D-pad scrolling past a row
     * doesn't search every card it crosses.
     */
    fun prefetchAfterDwell(
        type: String,
        videoId: String,
        season: Int? = null,
        episode: Int? = null,
        dwellMs: Long = FOCUS_DWELL_MS
    ) {
        pendingFocusPrefetch?.cancel()
        if (!betaSettings.current.value.prefetchOnFocus) return
        pendingFocusPrefetch = scope.launch {
            delay(dwellMs)
            if (!betaSettings.current.value.prefetchOnFocus) return@launch
            startSpeculativeSearch(type, videoId, season, episode)
        }
    }

    /**
     * A regular row's poster opened out on Home (Fetch on expansion): search for what its Play would launch once
     * [dwellMs] has passed without focus moving on. Shares [prefetchAfterDwell]'s slot, so only one waits at a time.
     */
    fun prefetchOnExpansion(type: String, videoId: String, season: Int?, episode: Int?, dwellMs: Long) {
        pendingFocusPrefetch?.cancel()
        if (!betaSettings.current.value.fetchOnExpansion) return
        pendingFocusPrefetch = scope.launch {
            if (dwellMs > 0) delay(dwellMs)
            if (!betaSettings.current.value.fetchOnExpansion) return@launch
            startSpeculativeSearch(type, videoId, season, episode)
        }
    }

    private fun startSpeculativeSearch(type: String, videoId: String, season: Int?, episode: Int?) {
        if (type.isBlank() || videoId.isBlank()) return
        val key = "${type.lowercase()}|$videoId|${season ?: ""}|${episode ?: ""}"
        Log.i(TAG, "prefetch $key")
        scope.launch {
            runCatching {
                // The first emission means the session exists and its job has started.
                streamRepository.getStreamsFromAllAddons(
                    type = type,
                    videoId = videoId,
                    season = season,
                    episode = episode,
                    speculative = true
                ).first()
            }.onFailure { Log.w(TAG, "prefetch failed $key: ${it.message}") }
        }
    }

    /**
     * Opens pooled TLS connections to every stream addon host so the first search after launch
     * skips DNS + handshake. Connections stay warm in the stream client's pool for 5 minutes.
     * Follows the addon list (often empty until account sync lands) and the setting.
     */
    fun warmUpAddonHosts() {
        scope.launch {
            val origins = addonRepository.getInstalledAddons().map { addons ->
                addons.enabledAddons()
                    .filter { addon -> addon.resources.any { it.name == "stream" } }
                    .mapNotNull { addon ->
                        addon.baseUrl.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}:${it.port}/" }
                    }
                    .toSet()
            }
            val enabled = betaSettings.settings.map { it.warmUpAddonConnections }
            combine(origins, enabled) { hosts, on -> if (on) hosts else emptySet() }
                .distinctUntilChanged()
                .catch { Log.w(TAG, "addon list unavailable for warm-up: ${it.message}") }
                .collect { hosts -> warm(hosts) }
        }
    }

    private suspend fun warm(origins: Set<String>) = coroutineScope {
        if (origins.isEmpty()) return@coroutineScope
        val client = streamsHttpClient.newBuilder()
            .callTimeout(3, TimeUnit.SECONDS)
            .cache(null)
            .build()
        origins.map { origin ->
            async {
                withTimeoutOrNull(3_500L) {
                    runCatching {
                        client.newCall(Request.Builder().url(origin).head().build()).execute().close()
                    }
                }
            }
        }.awaitAll()
        Log.i(TAG, "warmed ${origins.size} addon host(s)")
    }
}
