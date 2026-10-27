package com.nuvio.tv.core.player

import android.os.SystemClock
import android.util.Log
import com.nuvio.tv.data.local.BetaPerformanceSettingsDataStore
import com.nuvio.tv.ui.screens.player.PlayerMediaSourceFactory
import com.nuvio.tv.ui.screens.player.PlayerPlaybackNetworking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "PlaybackPrewarm"
private const val RECENTLY_WARMED_MS = 60_000L
private const val FOCUS_DWELL_MS = 400L
private const val PREWARM_CALL_TIMEOUT_S = 6L

/**
 * Opens the connection to a stream's server before the player asks for it.
 *
 * The player's HTTP data source draws from [PlayerPlaybackNetworking]'s shared connection pool,
 * so a tiny ranged request made here (DNS, TLS handshake, addon redirect hops) leaves a warm,
 * already-redirected connection that the player's first read reuses. Started when a stream is
 * chosen, it overlaps with player start-up instead of happening after it.
 */
@Singleton
class PlaybackConnectionPrewarmer @Inject constructor(
    private val betaSettings: BetaPerformanceSettingsDataStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val recentlyWarmed = ConcurrentHashMap<String, Long>()
    private var pendingFocusPrewarm: Job? = null

    /** Warm [url] now (the stream was chosen, playback is about to start). */
    fun prewarm(url: String?, headers: Map<String, String>?) {
        if (!betaSettings.current.value.fastPlaybackStart) return
        val target = url?.takeIf { it.isHttpUrl() } ?: return
        val now = SystemClock.elapsedRealtime()
        val last = recentlyWarmed[target]
        if (last != null && now - last < RECENTLY_WARMED_MS) return
        if (recentlyWarmed.size > 64) recentlyWarmed.clear()
        recentlyWarmed[target] = now
        scope.launch { warm(target, headers.orEmpty()) }
    }

    /** Warm [url] once focus has rested on its row in the source list. */
    fun prewarmAfterDwell(url: String?, headers: Map<String, String>?) {
        pendingFocusPrewarm?.cancel()
        if (!betaSettings.current.value.fastPlaybackStart || url?.isHttpUrl() != true) return
        pendingFocusPrewarm = scope.launch {
            delay(FOCUS_DWELL_MS)
            prewarm(url, headers)
        }
    }

    private fun warm(url: String, headers: Map<String, String>) {
        val startedAt = SystemClock.elapsedRealtime()
        val host = url.toHttpUrlOrNull()?.host ?: "?"
        val result = runCatching {
            val client = PlayerPlaybackNetworking.createHttpClient(headers).newBuilder()
                .callTimeout(PREWARM_CALL_TIMEOUT_S, TimeUnit.SECONDS)
                .build()
            val request = Request.Builder().url(url).apply {
                headers.forEach { (key, value) ->
                    if (!key.equals("Range", ignoreCase = true)) header(key, value)
                }
                if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                    header("User-Agent", PlayerMediaSourceFactory.DEFAULT_USER_AGENT)
                }
                // One byte: enough to finish redirects and the handshake. Reading the whole
                // (one-byte) body lets the connection go back to the pool for the player.
                header("Range", "bytes=0-0")
            }.build()
            client.newCall(request).execute().use { response ->
                // Drain a short ranged body so the connection returns to the pool. A server
                // that ignores Range would start sending the whole file; that one is just closed.
                val body = response.body
                if (body != null && body.contentLength() in 0L..4_096L) body.bytes()
                "${response.code} -> ${response.request.url.host}"
            }
        }
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        result
            .onSuccess { Log.i(TAG, "warmed host=$host ($it) in ${elapsed}ms") }
            .onFailure { Log.i(TAG, "warm failed host=$host after ${elapsed}ms: ${it.javaClass.simpleName}") }
    }

    private fun String.isHttpUrl(): Boolean =
        startsWith("http://", ignoreCase = true) || startsWith("https://", ignoreCase = true)
}
