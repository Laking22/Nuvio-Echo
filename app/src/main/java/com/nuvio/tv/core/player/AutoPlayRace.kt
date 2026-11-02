package com.nuvio.tv.core.player

import android.os.SystemClock
import android.util.Log
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.data.local.StreamAutoPlaySource
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamDebridCacheState
import com.nuvio.tv.ui.screens.player.PlayerMediaSourceFactory
import com.nuvio.tv.ui.screens.player.PlayerPlaybackNetworking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

private const val TAG = "AutoPlayRace"

/**
 * How many of auto-play's matches race. A rule often matches 20 to 40 links; racing them all made dozens of downloads
 * share the TV's connection, so each (the winner too) arrived later.
 */
const val AUTO_PLAY_RACE_SIZE = 8
/**
 * Video a file has to deliver to win, enough to show it isn't answering then trickling. A file that ends sooner
 * doesn't count: no film is that small, but a debrid service's "not cached yet" clip is.
 */
private const val RACE_BYTES = 512L * 1024
/** An HLS link's first segment has to deliver as much, or all of itself (segments can be smaller). */
private const val RACE_SEGMENT_BYTES = 512L * 1024
/** The race gives up after this (none of the links delivered) and auto-play's first match plays. */
private const val RACE_TIMEOUT_MS = 8_000L
/** A switch's race gives up sooner: the picture is held still meanwhile, and the menu's own order still follows. */
private const val SWITCH_RACE_TIMEOUT_MS = 5_000L
/** Read from a file's start in a switch's race: enough to tell what it is, and its size from the range reply. */
private const val SWITCH_HEAD_BYTES = 64L * 1024
/** Past this far in, a switch's race checks the file where playback resumes rather than at its start. */
private const val SWITCH_FAR_FROM = 0.01
/** A remembered link that's slow to start has this long to deliver. */
private const val LINK_CHECK_MS = 4_000L
private const val PLAYLIST_MAX_BYTES = 512L * 1024
/** A remembered link to watch is forgotten if the player hasn't started it by then. */
private const val WATCH_KEPT_MS = 120_000L

/**
 * Auto-play's race (Race matching sources): auto-play's matches (up to [AUTO_PLAY_RACE_SIZE]) are fetched at once and
 * the first to return video plays, whatever its place in the rule's order. A link only wins once [RACE_BYTES] of real
 * media has arrived (recognised by its first bytes; for HLS, its playlist followed down to the first segment), so one
 * that answers at once but trickles data, or returns an error page, loses. The losers' downloads are cancelled; the
 * winner's connection stays in the player's pool, so its first read skips the handshake.
 *
 * A remembered link (Reuse last link) plays at once instead, with no fresh matches to race it against; the player
 * checks it if it's slow to start ([watchSlowStart]).
 */
object AutoPlayRace {

    private class Watch(val url: String, val at: Long)

    @Volatile private var watch: Watch? = null

    /** Asks the player that's about to start [url] (a remembered link) to check it if it's slow to start. */
    fun watchSlowStart(url: String) {
        watch = Watch(url, SystemClock.elapsedRealtime())
    }

    /** Whether [url] (the link the player is starting) was asked to be watched; answered once. */
    fun takeSlowStartWatch(url: String): Boolean {
        val w = watch ?: return false
        if (SystemClock.elapsedRealtime() - w.at > WATCH_KEPT_MS) {
            watch = null
            return false
        }
        val matches = w.url == url ||
            runCatching { PlayerMediaSourceFactory.normalizePlaybackRequest(w.url, emptyMap()).url == url }
                .getOrDefault(false)
        if (!matches) return false
        watch = null
        return true
    }

    /** Whether [stream] can race: a plain web link (not a torrent, a debrid file still to resolve, or YouTube). */
    fun canRace(stream: Stream): Boolean {
        if (stream.isTorrent() || stream.isDirectDebrid() || stream.isYouTube() || stream.isExternal()) return false
        val url = stream.getStreamUrl() ?: return false
        return url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)
    }

    /**
     * The links to race for auto-play's [pick]: the pick, then the rest of auto-play's own matches (the same mode,
     * rule, scope and addons) that can race, up to [AUTO_PLAY_RACE_SIZE]. Null when there's nothing to race it
     * against, or the pick is the remembered release for the next episode ([bingeGroup], kept to on purpose). A
     * link known not to be cached on the debrid service never joins: fetching it would start a download there.
     */
    fun fieldFor(
        pick: Stream,
        streams: List<Stream>,
        mode: StreamAutoPlayMode,
        regexPattern: String,
        preferences: Set<String>,
        source: StreamAutoPlaySource,
        installedAddonNames: Set<String>,
        selectedAddons: Set<String>,
        selectedPlugins: Set<String>,
        bingeGroup: String?
    ): List<Stream>? {
        if (!canRace(pick)) return null
        if (bingeGroup != null && pick.behaviorHints?.bingeGroup == bingeGroup) return null
        val matches = StreamAutoPlaySelector.raceCandidates(
            streams = streams,
            mode = mode,
            regexPattern = regexPattern,
            source = source,
            installedAddonNames = installedAddonNames,
            selectedAddons = selectedAddons,
            selectedPlugins = selectedPlugins,
            preferences = preferences
        )
        // Only alongside auto-play's own matches: a source-fallback pick (nothing matched) plays alone.
        if (pick !in matches) return null
        val field = listOf(pick) + matches.raceable()
            .filter { it.getStreamUrl() != pick.getStreamUrl() }
            .take(AUTO_PLAY_RACE_SIZE - 1)
        return field.takeIf { it.size > 1 }
    }

    /** The links to race for a switch: those of [ranked] (already the menu's own choice, best first) that can race. */
    fun switchField(ranked: List<Stream>): List<Stream> = ranked.raceable().take(AUTO_PLAY_RACE_SIZE)

    /** A switch's race: the link that delivered first (null if none did), and the links that failed before it. */
    class SwitchRace(val winner: Stream?, val failed: List<Stream>)

    /**
     * Races [candidates] for a switch made while watching (a quality or audio picked in the player). Each link has to
     * send video from about [fraction] of the way in, where playback resumes, since some serve a file's start at
     * once but stall on a part far into it; one that can't be read from there (no byte ranges) loses.
     */
    suspend fun pickForSwitch(candidates: List<Stream>, fraction: Double?): SwitchRace {
        val startedAt = SystemClock.elapsedRealtime()
        val failed = mutableListOf<Stream>()
        val winner = withContext(Dispatchers.IO) {
            withTimeoutOrNull(SWITCH_RACE_TIMEOUT_MS) {
                coroutineScope {
                    val runs = candidates.mapIndexed { index, stream ->
                        async {
                            val delivered = tryLink("switch #$index", stream, SystemClock.elapsedRealtime()) { url, headers ->
                                if (fraction == null || fraction < SWITCH_FAR_FROM) probe(url, headers) else probeFrom(url, headers, fraction)
                            }
                            if (delivered) index else null
                        }
                    }
                    var pending = runs
                    var found: Int? = null
                    while (found == null && pending.isNotEmpty()) {
                        val (done, index) = select<Pair<Deferred<Int?>, Int?>> {
                            pending.forEach { run -> run.onAwait { run to it } }
                        }
                        pending = pending - done
                        if (index == null) failed += candidates[runs.indexOf(done)]
                        found = index
                    }
                    runs.forEach { it.cancel() }
                    found
                }
            }
        }
        Log.i(
            TAG,
            "SWITCH_RACE: ${candidates.size} raced from ${fraction?.let { "${(it * 100).toInt()}%" } ?: "the start"}, " +
                (winner?.let { "won #$it ${candidates[it].raceLabel()}" } ?: "none delivered") +
                ", ${failed.size} failed, after ${SystemClock.elapsedRealtime() - startedAt}ms"
        )
        return SwitchRace(winner?.let(candidates::get), failed)
    }

    /** Those of [this] (matches) that can race, one per link. */
    private fun List<Stream>.raceable(): List<Stream> =
        filter { canRace(it) && it.debridCacheStatus?.state != StreamDebridCacheState.NOT_CACHED }
            .distinctBy { it.getStreamUrl() }

    /**
     * Auto-play's matches, checked as the addons' results come in rather than once auto-play has picked, so the race
     * is mostly run by then. The first [AUTO_PLAY_RACE_SIZE] matches to arrive are checked; a top match that arrives
     * later is checked when auto-play picks. The checks run in [scope] (the source search's) and stop with it; each
     * also ends at [RACE_BYTES] or [RACE_TIMEOUT_MS]. Used from the main thread.
     */
    class HeadStart(private val scope: CoroutineScope) {
        private val checks = LinkedHashMap<String, Deferred<Long?>>()

        /** Starts checking those of [matches] (auto-play's matches so far) not checked yet, up to the cap. */
        fun offer(matches: List<Stream>) {
            for (stream in matches.raceable()) {
                if (checks.size >= AUTO_PLAY_RACE_SIZE) return
                val url = stream.getStreamUrl() ?: continue
                if (url !in checks) checks[url] = scope.async(Dispatchers.IO) { timedCheck(stream, "ahead") }
            }
        }

        /** The check of [stream]'s link, if one was started; it may still be running. */
        internal fun checkOf(stream: Stream): Deferred<Long?>? = stream.getStreamUrl()?.let(checks::get)

        /** Stops the checks still running: auto-play has picked. */
        fun stop() {
            checks.values.forEach { it.cancel() }
        }
    }

    /**
     * Races [candidates] and returns the first to return video; auto-play's own pick (the first candidate) when none
     * does in time, which the player then tries as it always has. A link [headStart] has checked already keeps that
     * check, and when some are through, the fastest of them wins without waiting: timed from its own start, so a link
     * from an addon that answered first isn't favoured for that.
     */
    suspend fun pick(candidates: List<Stream>, headStart: HeadStart? = null): Stream {
        val first = candidates.first()
        if (candidates.size < 2) {
            headStart?.stop()
            return first
        }
        val startedAt = SystemClock.elapsedRealtime()
        val ahead = candidates.map { stream -> headStart?.checkOf(stream)?.takeUnless { it.isCancelled } }
        val through = ahead.withIndex()
            .mapNotNull { (index, check) -> if (check?.isCompleted == true) check.await()?.let { index to it } else null }
            .minByOrNull { (_, tookMs) -> tookMs }
        val winner = through?.first ?: withContext(Dispatchers.IO) {
            withTimeoutOrNull(RACE_TIMEOUT_MS) {
                coroutineScope {
                    val runs = candidates.mapIndexed { index, stream ->
                        val early = ahead[index]
                        async {
                            val tookMs = if (early != null) awaitCheck(early) else timedCheck(stream, "#$index")
                            if (tookMs != null) index else null
                        }
                    }
                    // The first to finish with video wins; a link that failed just drops out of the race.
                    var pending = runs
                    var found: Int? = null
                    while (found == null && pending.isNotEmpty()) {
                        val (done, index) = select<Pair<Deferred<Int?>, Int?>> {
                            pending.forEach { run -> run.onAwait { run to it } }
                        }
                        pending = pending - done
                        found = index
                    }
                    runs.forEach { it.cancel() }
                    found
                }
            }
        }
        headStart?.stop()
        Log.i(
            TAG,
            "AUTOPLAY_RACE: ${candidates.size} raced (${ahead.count { it != null }} checked ahead), " +
                (winner?.let { "won #$it ${candidates[it].raceLabel()}" } ?: "none delivered, playing #0") +
                (through?.let { (_, tookMs) -> ", already through in ${tookMs}ms" } ?: "") +
                " after ${SystemClock.elapsedRealtime() - startedAt}ms"
        )
        return winner?.let(candidates::get) ?: first
    }

    /** How long [stream]'s link took to deliver video, timed from now; null if it didn't within [RACE_TIMEOUT_MS]. */
    private suspend fun timedCheck(stream: Stream, label: String): Long? {
        val startedAt = SystemClock.elapsedRealtime()
        val delivered = withTimeoutOrNull(RACE_TIMEOUT_MS) { tryLink(label, stream, startedAt) } ?: false
        return if (delivered) SystemClock.elapsedRealtime() - startedAt else null
    }

    /** A head-start check's result; null if it was called off. */
    private suspend fun awaitCheck(check: Deferred<Long?>): Long? = try {
        check.await()
    } catch (e: CancellationException) {
        currentCoroutineContext().ensureActive()
        null
    }

    /** True when [stream] delivered video; logged either way (a link cancelled by the race's end isn't). */
    private suspend fun tryLink(
        label: String,
        stream: Stream,
        startedAt: Long,
        check: suspend (String, Map<String, String>) -> String? = ::probe
    ): Boolean {
        val url = stream.getStreamUrl()
        val result = runCatching { if (url == null) "no url" else check(url, stream.behaviorHints?.proxyHeaders?.request.orEmpty()) }
        result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        // Cut off by the race ending: its read failed for that reason, not the link's.
        if (!currentCoroutineContext().isActive) return false
        val failure = result.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message?.take(80)}" }
            ?: result.getOrNull()
        return if (failure != null) {
            Log.i(TAG, "lost $label ${stream.raceLabel()}: $failure")
            false
        } else {
            Log.i(TAG, "delivered $label ${stream.raceLabel()} in ${SystemClock.elapsedRealtime() - startedAt}ms")
            true
        }
    }

    /**
     * Whether [url] delivers video within [timeoutMs], as a link has to in the race: for a remembered link that's slow
     * to start, which can have died since.
     */
    suspend fun delivers(url: String, headers: Map<String, String>, timeoutMs: Long = LINK_CHECK_MS): Boolean {
        val startedAt = SystemClock.elapsedRealtime()
        // Wrapped, since a link that delivered gives null (no failure) and a timeout gives null too.
        val outcome = withContext(Dispatchers.IO) {
            withTimeoutOrNull(timeoutMs) {
                val why = runCatching { probe(url, headers) }
                    .getOrElse { if (it is CancellationException) throw it else "${it.javaClass.simpleName}: ${it.message?.take(80)}" }
                listOf(why)
            }
        }
        val failure = if (outcome == null) "nothing in ${timeoutMs}ms" else outcome.first()
        Log.i(TAG, "LINK_CHECK: ${failure ?: "delivered"} after ${SystemClock.elapsedRealtime() - startedAt}ms")
        return failure == null
    }

    /** Null when [rawUrl] delivered video, else why not. */
    private suspend fun probe(rawUrl: String, rawHeaders: Map<String, String>): String? {
        // Asked for exactly as the player will ask (credentials in the URL moved into a header, headers cleaned), so
        // a link never loses only for how the race asked.
        val request = PlayerMediaSourceFactory.normalizePlaybackRequest(rawUrl, PlayerMediaSourceFactory.sanitizeHeaders(rawHeaders))
        val url = request.url
        val headers = request.headers
        val client = PlayerPlaybackNetworking.createHttpClient(headers).newBuilder()
            .callTimeout(RACE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
        val head = read(client, url, headers, RACE_BYTES)
        if (head.failure != null) return head.failure
        val bytes = head.bytes
        if (bytes.looksLikePlaylist()) return probeHls(client, head.finalUrl, bytes, headers)
        if (bytes.looksLikeDash()) return null
        if (bytes.looksLikeText()) return "not video (${head.contentType ?: "text"})"
        if (!bytes.looksLikeMedia() && head.contentType?.startsWith("video/") != true &&
            head.contentType?.contains("octet-stream") != true
        ) {
            return "unrecognised data (${head.contentType ?: "no type"})"
        }
        return if (bytes.size >= RACE_BYTES) null
        else if (head.complete) "ended after ${bytes.size} bytes (a placeholder clip)"
        else "only ${bytes.size} bytes"
    }

    /**
     * Null when [rawUrl] delivers video from about [fraction] of the way in, else why not. Its start is read first, to
     * tell what it is and how big (the range reply gives the size), then [RACE_BYTES] from where that puts the spot.
     */
    private suspend fun probeFrom(rawUrl: String, rawHeaders: Map<String, String>, fraction: Double): String? {
        val request = PlayerMediaSourceFactory.normalizePlaybackRequest(rawUrl, PlayerMediaSourceFactory.sanitizeHeaders(rawHeaders))
        val url = request.url
        val headers = request.headers
        val client = PlayerPlaybackNetworking.createHttpClient(headers).newBuilder()
            .callTimeout(SWITCH_RACE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
        val head = read(client, url, headers, SWITCH_HEAD_BYTES)
        if (head.failure != null) return head.failure
        val bytes = head.bytes
        // An HLS link resumes by segment: its first one stands in for any.
        if (bytes.looksLikePlaylist()) return probeHls(client, head.finalUrl, bytes, headers)
        if (bytes.looksLikeDash()) return null
        if (bytes.looksLikeText()) return "not video (${head.contentType ?: "text"})"
        if (!bytes.looksLikeMedia() && head.contentType?.startsWith("video/") != true &&
            head.contentType?.contains("octet-stream") != true
        ) {
            return "unrecognised data (${head.contentType ?: "no type"})"
        }
        val total = head.total ?: return if (head.complete) "ended after ${bytes.size} bytes (a placeholder clip)"
            else "no byte ranges (can't resume mid-file)"
        if (total < RACE_BYTES * 4) return "only $total bytes in all (a placeholder clip)"
        val from = (total * fraction).toLong().coerceIn(0L, total - RACE_BYTES)
        val far = read(client, url, headers, RACE_BYTES, from)
        if (far.failure != null) return "mid-file: ${far.failure}"
        if (!far.partial) return "mid-file: the range was ignored"
        if (far.bytes.looksLikeText() && !far.bytes.looksLikeMedia()) return "mid-file: not video"
        return if (far.bytes.size >= RACE_BYTES || far.complete) null else "mid-file: only ${far.bytes.size} bytes"
    }

    /** An HLS link delivers once its first segment does: master playlist, media playlist, then the segment. */
    private suspend fun probeHls(
        client: OkHttpClient,
        url: String,
        playlist: ByteArray,
        headers: Map<String, String>
    ): String? {
        var text = String(playlist, Charsets.UTF_8)
        var base = url
        if (text.contains("#EXT-X-STREAM-INF")) {
            val variant = text.firstUri() ?: return "empty master playlist"
            base = resolve(base, variant) ?: return "bad variant url"
            val media = read(client, base, headers, PLAYLIST_MAX_BYTES)
            if (media.failure != null) return "variant: ${media.failure}"
            text = String(media.bytes, Charsets.UTF_8)
            base = media.finalUrl
        }
        val segment = text.firstUri()?.let { resolve(base, it) } ?: return "no segments"
        val data = read(client, segment, headers, RACE_SEGMENT_BYTES)
        if (data.failure != null) return "segment: ${data.failure}"
        // Segments are often encrypted or disguised, so anything that isn't an error page counts as video here.
        if (data.bytes.looksLikeText() && !data.bytes.looksLikeMedia()) return "segment is not video"
        return if (data.bytes.size >= RACE_SEGMENT_BYTES || data.complete) null else "segment only ${data.bytes.size} bytes"
    }

    private class Read(
        val bytes: ByteArray,
        val finalUrl: String,
        val contentType: String?,
        /** The whole body arrived (it was shorter than asked for). */
        val complete: Boolean,
        val failure: String?,
        /** The server sent the range asked for (206), so the file can be read from anywhere. */
        val partial: Boolean = false,
        /** The file's whole size, from the range reply. */
        val total: Long? = null
    )

    /**
     * Fetches up to [limit] bytes of [url] from byte [from], cancelling the download once they're in (or the race is
     * decided).
     */
    private suspend fun read(client: OkHttpClient, url: String, headers: Map<String, String>, limit: Long, from: Long = 0L): Read {
        val request = Request.Builder().url(url).apply {
            headers.forEach { (key, value) -> if (!key.equals("Range", ignoreCase = true)) header(key, value) }
            if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                header("User-Agent", PlayerMediaSourceFactory.DEFAULT_USER_AGENT)
            }
            header("Range", "bytes=$from-${from + limit - 1}")
        }.build()
        val call = client.newCall(request)
        return call.awaitCancellable { response ->
            if (!response.isSuccessful) {
                return@awaitCancellable Read(ByteArray(0), url, null, false, "HTTP ${response.code}")
            }
            val body = response.body ?: return@awaitCancellable Read(ByteArray(0), url, null, false, "empty body")
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            val source = body.byteStream()
            var complete = false
            while (out.size() < limit) {
                currentCoroutineContext().ensureActive()
                val n = source.read(buffer, 0, minOf(buffer.size.toLong(), limit - out.size()).toInt())
                if (n < 0) {
                    complete = true
                    break
                }
                out.write(buffer, 0, n)
            }
            Read(
                bytes = out.toByteArray(),
                finalUrl = response.request.url.toString(),
                contentType = body.contentType()?.toString(),
                complete = complete,
                failure = null,
                partial = response.code == 206,
                total = response.header("Content-Range")?.substringAfterLast('/')?.trim()?.toLongOrNull()
            )
        }
    }

    /** Runs the call; when the race is decided (this coroutine cancelled) the call is cancelled, mid-download too. */
    private suspend fun <T> Call.awaitCancellable(block: suspend (okhttp3.Response) -> T): T {
        // On cancelling, not on completion: a loser blocked reading a slow body must be cut off now.
        @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
        val stop = currentCoroutineContext().job.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cancel() }
        try {
            val response = suspendCancellableCoroutine<okhttp3.Response> { cont ->
                enqueue(object : okhttp3.Callback {
                    override fun onFailure(call: Call, e: java.io.IOException) {
                        if (cont.isActive) cont.resumeWith(Result.failure(e))
                    }

                    override fun onResponse(call: Call, response: okhttp3.Response) {
                        // Closed if the race ended in the meantime, so the connection goes back to the pool.
                        cont.resume(response) { _, unused, _ -> unused.close() }
                    }
                })
            }
            return response.use { block(it) }
        } finally {
            stop.dispose()
        }
    }

    private fun resolve(base: String, ref: String): String? =
        base.toHttpUrlOrNull()?.resolve(ref.trim())?.toString()

    private fun String.firstUri(): String? =
        lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }

    private fun Stream.raceLabel(): String =
        "${addonName}: ${(name ?: title).orEmpty().replace('\n', ' ').take(60)}"
}

private fun ByteArray.startsWithAscii(text: String, offset: Int = 0): Boolean =
    size >= offset + text.length && text.indices.all { this[offset + it] == text[it].code.toByte() }

private fun ByteArray.trimmedStart(): Int {
    var i = 0
    // A UTF-8 byte order mark, then whitespace.
    if (size >= 3 && this[0] == 0xEF.toByte() && this[1] == 0xBB.toByte() && this[2] == 0xBF.toByte()) i = 3
    while (i < size && (this[i] == ' '.code.toByte() || this[i] == '\n'.code.toByte() || this[i] == '\r'.code.toByte() || this[i] == '\t'.code.toByte())) i++
    return i
}

private fun ByteArray.looksLikePlaylist(): Boolean = startsWithAscii("#EXTM3U", trimmedStart())

private fun ByteArray.looksLikeDash(): Boolean {
    val start = String(this, 0, minOf(size, 512), Charsets.UTF_8)
    return start.contains("<MPD")
}

/** An error page or API reply: HTML, XML or JSON. */
private fun ByteArray.looksLikeText(): Boolean {
    val i = trimmedStart()
    if (i >= size) return true
    val c = this[i].toInt().toChar()
    return c == '<' || c == '{' || c == '['
}

/** The first bytes of a video container: Matroska/WebM, MP4/MOV, MPEG-TS, FLV, AVI or an ID3-tagged stream. */
private fun ByteArray.looksLikeMedia(): Boolean {
    if (size >= 4 && this[0] == 0x1A.toByte() && this[1] == 0x45.toByte() && this[2] == 0xDF.toByte() && this[3] == 0xA3.toByte()) return true
    if (listOf("ftyp", "moov", "mdat", "free", "wide", "skip", "styp", "moof", "sidx").any { startsWithAscii(it, 4) }) return true
    if (size > 188 && this[0] == 0x47.toByte() && this[188] == 0x47.toByte()) return true
    if (startsWithAscii("FLV") || startsWithAscii("ID3")) return true
    return startsWithAscii("RIFF") && startsWithAscii("AVI ", 8)
}
