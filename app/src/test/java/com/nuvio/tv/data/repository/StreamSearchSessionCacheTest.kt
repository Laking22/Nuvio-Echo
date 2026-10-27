package com.nuvio.tv.data.repository

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.AddonStreams
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class StreamSearchSessionCacheTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cache = StreamSearchSessionCache(scope = scope)

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun key(videoId: String) = StreamSearchRequestKey(
        profileId = 1,
        type = "series",
        videoId = videoId,
        season = 1,
        episode = null,
        sourceConfiguration = "config"
    )

    /** A search that never finishes on its own; [cancelled] completes when it is cancelled. */
    private class EndlessSearch {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val flow: Flow<NetworkResult<List<AddonStreams>>> = flow {
            started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                cancelled.complete(Unit)
            }
        }
    }

    private suspend fun prefetch(videoId: String, search: EndlessSearch) {
        cache.observe(key(videoId), forceRefresh = false, speculative = true) { search.flow }.first()
        withTimeout(1_000) { search.started.await() }
    }

    @Test
    fun `a newer prefetch cancels the previous unclaimed one`() = runBlocking {
        val episode1 = EndlessSearch()
        val episode2 = EndlessSearch()

        prefetch("tt1:1:1", episode1)
        prefetch("tt1:1:2", episode2)

        withTimeout(1_000) { episode1.cancelled.await() }
        assertFalse(episode2.cancelled.isCompleted)
    }

    @Test
    fun `a prefetch the streams screen opened keeps running`() = runBlocking {
        val episode1 = EndlessSearch()
        val episode2 = EndlessSearch()
        prefetch("tt1:1:1", episode1)

        val screenAttached = CompletableDeferred<Unit>()
        val screen = launch {
            cache.observe(key("tt1:1:1"), forceRefresh = false) { error("search should be reused") }
                .collect { screenAttached.complete(Unit) }
        }
        withTimeout(1_000) { screenAttached.await() }

        prefetch("tt1:1:2", episode2)
        delay(200)

        assertFalse(episode1.cancelled.isCompleted)
        screen.cancel()
    }

    @Test
    fun `screen searches never cancel prefetches`() = runBlocking {
        val prefetched = EndlessSearch()
        val opened = EndlessSearch()
        prefetch("tt1:1:1", prefetched)

        cache.observe(key("tt2:1:1"), forceRefresh = false) { opened.flow }.first()
        withTimeout(1_000) { opened.started.await() }
        delay(200)

        assertFalse(prefetched.cancelled.isCompleted)
    }

    @Test
    fun `finished prefetch results survive later prefetches`() = runBlocking {
        val result = NetworkResult.Success(listOf(AddonStreams("Addon", null, emptyList())))
        var searches = 0
        val finishing: () -> Flow<NetworkResult<List<AddonStreams>>> = {
            searches += 1
            flow { emit(result) }
        }
        val firstKey = key("tt1:1:1")
        cache.observe(firstKey, forceRefresh = false, speculative = true, producer = finishing)
            .first { it is NetworkResult.Success }

        prefetch("tt1:1:2", EndlessSearch())

        val reopened = cache.observe(firstKey, forceRefresh = false, producer = finishing)
            .first { it is NetworkResult.Success }
        assertEquals(result, reopened)
        assertEquals(1, searches)
    }
}
