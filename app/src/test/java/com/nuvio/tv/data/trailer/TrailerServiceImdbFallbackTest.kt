package com.nuvio.tv.data.trailer

import android.util.Log
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TmdbVideoResult
import com.nuvio.tv.data.remote.api.TmdbVideosResponse
import com.nuvio.tv.data.remote.api.TrailerApi
import com.nuvio.tv.domain.model.TmdbSettings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import retrofit2.Response

/** When YouTube refuses (or has nothing), the title's trailer comes from IMDb. */
class TrailerServiceImdbFallbackTest {

    private val tmdbApi = mockk<TmdbApi>()
    private val extractor = mockk<InAppYouTubeExtractor>()
    private val tmdbService = mockk<TmdbService>()
    private val imdb = mockk<ImdbTrailerSource>()
    private val service = TrailerService(
        trailerApi = mockk<TrailerApi>(),
        tmdbApi = tmdbApi,
        inAppYouTubeExtractor = extractor,
        tmdbSettingsDataStore = mockk<TmdbSettingsDataStore> {
            every { settings } returns MutableStateFlow(TmdbSettings(language = "en", useTrailers = true))
        },
        tmdbService = tmdbService,
        maxTrailerVideoHeight = { 1080 },
        imdbTrailerSource = imdb
    )

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0
        every { tmdbService.apiKey() } returns "tmdb-key"
        coEvery { tmdbService.tmdbToImdb(123, "movie") } returns "tt0000123"
        coEvery { tmdbApi.getMovieVideos(any(), any(), any()) } returns Response.success(
            TmdbVideosResponse(
                id = 123,
                results = listOf(TmdbVideoResult(iso6391 = "en", name = "Trailer", key = "abcdefghijk", site = "YouTube", type = "Trailer", official = true))
            )
        )
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `plays IMDb's trailer when YouTube refuses`() = runTest {
        every { extractor.isRateLimited() } returns true
        coEvery { extractor.extractPlaybackSource(any(), any()) } returns null
        coEvery { imdb.trailer("tt0000123", 1080) } returns TrailerPlaybackSource(videoUrl = "https://imdb/trailer.mp4")

        val source = service.getTrailerPlaybackSource(title = "Film", year = "2024", tmdbId = "123", type = "movie")

        assertEquals("https://imdb/trailer.mp4", source?.videoUrl)
    }

    @Test
    fun `keeps YouTube's trailer when it plays`() = runTest {
        every { extractor.isRateLimited() } returns false
        coEvery { extractor.extractPlaybackSource(any(), any()) } returns TrailerPlaybackSource(videoUrl = "https://youtube/video")

        val source = service.getTrailerPlaybackSource(title = "Film", year = "2024", tmdbId = "123", type = "movie")

        assertEquals("https://youtube/video", source?.videoUrl)
        coVerify(exactly = 0) { imdb.trailer(any(), any()) }
    }
}
