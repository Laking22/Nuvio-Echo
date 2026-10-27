package com.nuvio.tv.ui.screens.speedtest

import android.app.Activity
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.browse.BrowseMediaMode
import com.nuvio.tv.core.browse.BrowseRepository
import com.nuvio.tv.core.browse.BrowseRequest
import com.nuvio.tv.core.browse.BrowseSort
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.speedtest.FrameRecorder
import com.nuvio.tv.core.speedtest.FrameSample
import com.nuvio.tv.core.speedtest.FrameStats
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import com.nuvio.tv.domain.repository.StreamRepository
import com.nuvio.tv.ui.navigation.Screen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import kotlin.random.Random

enum class SpeedTestStatus { PENDING, RUNNING, DONE, FAILED }

enum class SpeedTestStepId(@StringRes val titleRes: Int) {
    ADDON_CATALOGS(R.string.speed_test_step_catalogs),
    ADDON_STREAMS(R.string.speed_test_step_streams),
    SEARCH_DATA(R.string.speed_test_step_search_data),
    GENRE_DATA(R.string.speed_test_step_genre_data),
    HOME_OPEN(R.string.speed_test_step_home_open),
    HOME_SCROLL(R.string.speed_test_step_home_scroll),
    SEARCH_UI(R.string.speed_test_step_search_ui),
    GENRES_UI(R.string.speed_test_step_genres_ui)
}

data class SpeedTestStep(
    val id: SpeedTestStepId,
    val status: SpeedTestStatus = SpeedTestStatus.PENDING,
    /** The step's main number, e.g. "1.4 s" or "6% janky". */
    val headline: String? = null,
    val details: List<String> = emptyList()
)

data class SpeedTestUiState(
    val running: Boolean = false,
    val finished: Boolean = false,
    val steps: List<SpeedTestStep> = SpeedTestStepId.entries.map { SpeedTestStep(it) },
    val totalMs: Long? = null
)

/** What the screen lends the test while it runs; dropped as soon as the run ends. */
class SpeedTestDriver(
    val activity: Activity,
    val navigate: (String) -> Unit,
    val returnToTest: () -> Unit
)

/**
 * Runs the speed test: times addon catalogs, a stream search (never playback), search and
 * genre queries, then drives Home, Search and Genres with remote key presses while recording
 * every frame. Lives only while the Speed Test screen is on the back stack.
 */
@HiltViewModel
class SpeedTestViewModel @Inject constructor(
    private val addonRepository: AddonRepository,
    private val catalogRepository: CatalogRepository,
    private val streamRepository: StreamRepository,
    private val browseRepository: BrowseRepository,
    private val tmdbApi: TmdbApi,
    private val tmdbService: TmdbService
) : ViewModel() {

    private val _uiState = MutableStateFlow(SpeedTestUiState())
    val uiState: StateFlow<SpeedTestUiState> = _uiState.asStateFlow()
    private var runJob: Job? = null

    fun start(driver: SpeedTestDriver) {
        if (runJob?.isActive == true) return
        _uiState.value = SpeedTestUiState(running = true)
        runJob = viewModelScope.launch {
            val started = SystemClock.elapsedRealtime()
            val recorder = FrameRecorder(driver.activity.window)
            try {
                runDataSteps()
                withContext(Dispatchers.Main) { recorder.start() }
                runUiSteps(driver, recorder)
            } finally {
                withContext(Dispatchers.Main) {
                    recorder.stop()
                    driver.returnToTest()
                }
                _uiState.update {
                    it.copy(running = false, finished = true, totalMs = SystemClock.elapsedRealtime() - started)
                }
                _uiState.value.steps.forEach { step ->
                    Log.i(TAG, "${step.id}: ${step.status} ${step.headline.orEmpty()} | ${step.details.joinToString(" | ")}")
                }
            }
        }
    }

    fun cancel() {
        runJob?.cancel()
    }

    // ── Data steps ──────────────────────────────────────────────────────────────────────────

    private suspend fun runDataSteps() {
        val addons = addonRepository.getInstalledAddons().first().filter { it.enabled }
        step(SpeedTestStepId.ADDON_CATALOGS) { addonCatalogs(addons) }
        step(SpeedTestStepId.ADDON_STREAMS) { addonStreams(addons) }
        step(SpeedTestStepId.SEARCH_DATA) { searchData(addons) }
        step(SpeedTestStepId.GENRE_DATA) { genreData() }
    }

    /** Every addon's first home catalog at once, as Home loads them. */
    private suspend fun addonCatalogs(addons: List<Addon>): StepOutcome = coroutineScope {
        val targets = addons.mapNotNull { addon ->
            addon.catalogs.firstOrNull { it.extraRequired.isEmpty() }?.let { addon to it }
        }
        if (targets.isEmpty()) return@coroutineScope StepOutcome.failed("No addon catalogs installed")
        val wallStart = SystemClock.elapsedRealtime()
        val timings = targets.map { (addon, catalog) ->
            async {
                val t0 = SystemClock.elapsedRealtime()
                val result = withTimeoutOrNull(ADDON_TIMEOUT_MS) {
                    catalogRepository.getCatalog(
                        addonBaseUrl = addon.baseUrl,
                        addonId = addon.id,
                        addonName = addon.displayName,
                        catalogId = catalog.id,
                        catalogName = catalog.name,
                        type = catalog.apiType
                    ).first { it !is NetworkResult.Loading }
                }
                Timing(addon.displayName, SystemClock.elapsedRealtime() - t0, result is NetworkResult.Success)
            }
        }.awaitAll()
        val wall = SystemClock.elapsedRealtime() - wallStart
        StepOutcome(
            headline = "${seconds(wall)} for ${timings.size} addons",
            details = timings.sortedBy { it.ms }.map { it.line() }
        )
    }

    /** A stream search for today's trending movie on every stream addon. Nothing is played. */
    private suspend fun addonStreams(addons: List<Addon>): StepOutcome = coroutineScope {
        val imdbId = trendingMovieImdbId() ?: FALLBACK_IMDB_IDS.random()
        val streamAddons = addons.filter { addon ->
            addon.resources.any { resource ->
                resource.name == "stream" &&
                    (resource.types.isEmpty() || "movie" in resource.types) &&
                    (resource.idPrefixes.isNullOrEmpty() || resource.idPrefixes.any { imdbId.startsWith(it) })
            }
        }
        if (streamAddons.isEmpty()) return@coroutineScope StepOutcome.failed("No stream addons installed")
        val wallStart = SystemClock.elapsedRealtime()
        val timings = streamAddons.map { addon ->
            async {
                val t0 = SystemClock.elapsedRealtime()
                val result = withTimeoutOrNull(ADDON_TIMEOUT_MS) {
                    streamRepository.getStreamsFromAddon(addon, "movie", imdbId)
                }
                val count = (result as? NetworkResult.Success)?.data?.size ?: 0
                Timing(addon.displayName, SystemClock.elapsedRealtime() - t0, result is NetworkResult.Success, count)
            }
        }.awaitAll()
        val wall = SystemClock.elapsedRealtime() - wallStart
        val first = timings.filter { it.ok && it.count > 0 }.minOfOrNull { it.ms }
        StepOutcome(
            headline = buildString {
                append(first?.let { "First sources in ${seconds(it)}" } ?: "No sources found")
                append(", all in ${seconds(wall)}")
            },
            details = listOf("Title: $imdbId (trending today)") + timings.sortedBy { it.ms }.map { it.line() }
        )
    }

    private suspend fun trendingMovieImdbId(): String? {
        if (com.nuvio.tv.core.apikeys.ApiKeys.tmdb.isBlank()) return null
        return withTimeoutOrNull(8_000) {
            val trending = tmdbApi.getTrending("movie", "day", tmdbService.apiKey()).body()?.results.orEmpty()
            trending.take(5).shuffled().firstNotNullOfOrNull { tmdbService.tmdbToImdb(it.id, "movie") }
        }
    }

    /** TMDB title ranking plus every addon search catalog, for a query unlikely to be cached. */
    private suspend fun searchData(addons: List<Addon>): StepOutcome = coroutineScope {
        val query = SEARCH_QUERIES.random()
        val tmdb = async {
            val t0 = SystemClock.elapsedRealtime()
            val results = runCatching { browseRepository.searchTitles(query) }.getOrNull()
            Timing("TMDB ranking", SystemClock.elapsedRealtime() - t0, results != null, results?.size ?: 0)
        }
        val searchCatalogs = addons.mapNotNull { addon ->
            addon.catalogs.firstOrNull { catalog -> catalog.extra.any { it.name == "search" } }?.let { addon to it }
        }
        val addonTimings = searchCatalogs.map { (addon, catalog) ->
            async {
                val t0 = SystemClock.elapsedRealtime()
                val result = withTimeoutOrNull(ADDON_TIMEOUT_MS) {
                    catalogRepository.getCatalog(
                        addonBaseUrl = addon.baseUrl,
                        addonId = addon.id,
                        addonName = addon.displayName,
                        catalogId = catalog.id,
                        catalogName = catalog.name,
                        type = catalog.apiType,
                        extraArgs = mapOf("search" to query)
                    ).first { it !is NetworkResult.Loading }
                }
                val count = (result as? NetworkResult.Success)?.data?.items?.size ?: 0
                Timing(addon.displayName, SystemClock.elapsedRealtime() - t0, result is NetworkResult.Success, count)
            }
        }
        val all = listOf(tmdb.await()) + addonTimings.awaitAll()
        StepOutcome(
            headline = "\"$query\": all results in ${seconds(all.maxOf { it.ms })}",
            details = all.sortedBy { it.ms }.map { it.line() }
        )
    }

    /** Three genre pages fetched past the cache, as a first visit to each would. */
    private suspend fun genreData(): StepOutcome {
        // Only genres that exist for each media type (Reality has no movies).
        val random = Random(SystemClock.elapsedRealtime())
        val available = browseRepository.availableGenres()
        val movieGenre = available.filter { it.movie != null }.random(random)
        val showGenre = available.filter { it.tv != null && it != movieGenre }.random(random)
        val anyGenre = available.filter { it != movieGenre && it != showGenre }.random(random)
        val requests = listOf(
            BrowseRequest(mode = BrowseMediaMode.MOVIES, genre = movieGenre, sort = BrowseSort.POPULAR),
            BrowseRequest(mode = BrowseMediaMode.SHOWS, genre = showGenre, sort = BrowseSort.LATEST),
            BrowseRequest(mode = BrowseMediaMode.ALL, genre = anyGenre, sort = BrowseSort.MOST_RATED)
        )
        val timings = requests.map { request ->
            val t0 = SystemClock.elapsedRealtime()
            val page = runCatching { browseRepository.loadFresh(request) }.getOrNull()
            Timing(
                "${request.genre?.id} · ${request.mode.name.lowercase()} · ${request.sort.name.lowercase()}",
                SystemClock.elapsedRealtime() - t0,
                page != null,
                page?.items?.size ?: 0
            )
        }
        return StepOutcome(
            headline = "Average ${seconds(timings.map { it.ms }.average().toLong())} per page",
            details = timings.map { it.line() }
        )
    }

    // ── UI steps ────────────────────────────────────────────────────────────────────────────

    private suspend fun runUiSteps(driver: SpeedTestDriver, recorder: FrameRecorder) {
        step(SpeedTestStepId.HOME_OPEN) {
            val opened = openScreen(driver, recorder, Screen.Home.route)
            delay(SETTLE_PAUSE_MS)
            opened
        }
        step(SpeedTestStepId.HOME_SCROLL) {
            val since = System.nanoTime()
            val presses = pressKeys(driver, List(HOME_SCROLL_STEPS) { KeyEvent.KEYCODE_DPAD_DOWN }, 350) +
                pressKeys(driver, List(HOME_SCROLL_STEPS) { KeyEvent.KEYCODE_DPAD_UP }, 350)
            recorder.awaitQuiet(since, quietMs = 800, timeoutMs = 10_000)
            interactionOutcome(recorder.framesSince(since), presses)
        }
        step(SpeedTestStepId.SEARCH_UI) {
            val opened = openScreen(driver, recorder, Screen.Search.route)
            delay(SETTLE_PAUSE_MS)
            val query = SEARCH_QUERIES.random()
            val since = System.nanoTime()
            val presses = pressKeys(driver, query.map(::keyCodeFor), 150)
            val lastKey = presses.last()
            // Results wait for a 300 ms pause in typing, then the network, so allow a long gap.
            val settled = recorder.awaitQuiet(lastKey, quietMs = 1_500, timeoutMs = 15_000)
            val interaction = interactionOutcome(recorder.framesSince(since), presses)
            StepOutcome(
                headline = "Results ${millis((settled - lastKey) / 1_000_000)} after typing",
                details = listOf("Typed \"$query\"", "Screen opened in ${opened.headline}") + interaction.details
            )
        }
        step(SpeedTestStepId.GENRES_UI) {
            val opened = openScreen(driver, recorder, Screen.Genres.route)
            delay(SETTLE_PAUSE_MS)
            val since = System.nanoTime()
            val presses = pressKeys(driver, List(GENRE_STEPS) { KeyEvent.KEYCODE_DPAD_DOWN }, 900)
            val settled = recorder.awaitQuiet(presses.last(), quietMs = 1_500, timeoutMs = 15_000)
            val interaction = interactionOutcome(recorder.framesSince(since), presses)
            StepOutcome(
                headline = "Last genre's titles in ${millis((settled - presses.last()) / 1_000_000)}",
                details = listOf("Screen opened in ${opened.headline}") + interaction.details
            )
        }
    }

    /** Navigates and waits for the screen to stop drawing; that wait is the open time. */
    private suspend fun openScreen(driver: SpeedTestDriver, recorder: FrameRecorder, route: String): StepOutcome {
        withContext(Dispatchers.Main) { driver.returnToTest() }
        delay(400)
        val t0 = System.nanoTime()
        withContext(Dispatchers.Main) { driver.navigate(route) }
        val settled = recorder.awaitQuiet(t0, quietMs = 1_000, timeoutMs = OPEN_TIMEOUT_MS)
        val frames = recorder.framesSince(t0)
        val stats = FrameStats.of(frames)
        val openMs = (settled - t0) / 1_000_000
        // A screen that never stops drawing (an animated hero, a trailer) has no settle point;
        // the first second of frames still shows how quickly it appeared.
        val firstFrameMs = frames.minOfOrNull { it.endNanos }?.let { (it - t0) / 1_000_000 }
        return StepOutcome(
            headline = if (openMs >= OPEN_TIMEOUT_MS - 100) "Kept animating" else millis(openMs),
            details = listOfNotNull(
                firstFrameMs?.let { "First frame after ${millis(it)}" },
                stats.line()
            )
        )
    }

    /** Presses each key [intervalMs] apart; returns when each was pressed (nanoTime). */
    private suspend fun pressKeys(driver: SpeedTestDriver, keys: List<Int>, intervalMs: Long): List<Long> =
        keys.map { keyCode ->
            val pressedAt = System.nanoTime()
            withContext(Dispatchers.Main) {
                val down = SystemClock.uptimeMillis()
                driver.activity.dispatchKeyEvent(KeyEvent(down, down, KeyEvent.ACTION_DOWN, keyCode, 0))
                driver.activity.dispatchKeyEvent(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyCode, 0))
            }
            delay(intervalMs)
            pressedAt
        }

    /** Frame smoothness, plus how long each key press took to show on screen. */
    private fun interactionOutcome(frames: List<FrameSample>, presses: List<Long>): StepOutcome {
        val stats = FrameStats.of(frames)
        val responses = presses.mapNotNull { pressedAt ->
            frames.filter { it.vsyncNanos >= pressedAt }.minByOrNull { it.vsyncNanos }
                ?.let { (it.endNanos - pressedAt) / 1_000_000.0 }
        }
        val avg = responses.takeIf { it.isNotEmpty() }?.average()
        return StepOutcome(
            headline = "${"%.1f".format(stats.jankyPercent)}% janky frames",
            details = listOfNotNull(
                stats.line(),
                avg?.let { "Key response: avg ${"%.0f".format(it)} ms, worst ${"%.0f".format(responses.max())} ms" }
            )
        )
    }

    private fun keyCodeFor(char: Char): Int = when (char) {
        ' ' -> KeyEvent.KEYCODE_SPACE
        in 'a'..'z' -> KeyEvent.KEYCODE_A + (char - 'a')
        in '0'..'9' -> KeyEvent.KEYCODE_0 + (char - '0')
        else -> KeyEvent.KEYCODE_SPACE
    }

    // ── Step bookkeeping ────────────────────────────────────────────────────────────────────

    private suspend fun step(id: SpeedTestStepId, block: suspend () -> StepOutcome) {
        updateStep(id) { it.copy(status = SpeedTestStatus.RUNNING) }
        val outcome = runCatching { block() }.getOrElse { error ->
            if (error is kotlinx.coroutines.CancellationException) throw error
            StepOutcome.failed(error.message ?: error.javaClass.simpleName)
        }
        updateStep(id) {
            it.copy(
                status = if (outcome.failed) SpeedTestStatus.FAILED else SpeedTestStatus.DONE,
                headline = outcome.headline,
                details = outcome.details
            )
        }
    }

    private fun updateStep(id: SpeedTestStepId, transform: (SpeedTestStep) -> SpeedTestStep) {
        _uiState.update { state -> state.copy(steps = state.steps.map { if (it.id == id) transform(it) else it }) }
    }

    private data class StepOutcome(
        val headline: String?,
        val details: List<String> = emptyList(),
        val failed: Boolean = false
    ) {
        companion object {
            fun failed(reason: String) = StepOutcome(headline = reason, failed = true)
        }
    }

    private data class Timing(val name: String, val ms: Long, val ok: Boolean, val count: Int = -1) {
        fun line(): String = buildString {
            append(name).append(": ")
            append(if (ok) millis(ms) else "failed after ${millis(ms)}")
            if (ok && count >= 0) append(" · ").append(count).append(" items")
        }
    }

    private companion object {
        const val TAG = "NuvioSpeedTest"
        const val ADDON_TIMEOUT_MS = 30_000L
        const val SETTLE_PAUSE_MS = 600L
        const val OPEN_TIMEOUT_MS = 12_000L
        const val HOME_SCROLL_STEPS = 8
        const val GENRE_STEPS = 5
        val FALLBACK_IMDB_IDS = listOf("tt15239678", "tt1375666", "tt0816692", "tt6718170")
        val SEARCH_QUERIES = listOf("interstellar", "the batman", "parasite", "arrival", "whiplash", "dune", "coco", "heat")

        fun millis(ms: Long): String = if (ms >= 1_000) seconds(ms) else "$ms ms"
        fun seconds(ms: Long): String = "%.2f s".format(ms / 1000.0)
        fun FrameStats.line(): String =
            "$frames frames · p50 ${"%.1f".format(p50Ms)} ms · p90 ${"%.1f".format(p90Ms)} ms · " +
                "worst ${"%.0f".format(maxMs)} ms · ${"%.1f".format(jankyPercent)}% janky · $stutters stutters"
    }
}
