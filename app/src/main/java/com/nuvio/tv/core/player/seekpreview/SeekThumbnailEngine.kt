package com.nuvio.tv.core.player.seekpreview

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.util.Locale

/** The stream thumbnails are taken from. */
class SeekThumbnailSource private constructor(
    val url: String,
    val headers: Map<String, String>,
    /** The stream is HLS (by its URL or the player's MIME type). */
    val isHls: Boolean,
    /** The stream is DASH (by its URL or the player's MIME type). */
    val isDash: Boolean = false,
    /**
     * Another source of the same title, not the playing one (its quality, for logs). Its length has to match the
     * player's before its pictures are shown.
     */
    val alternate: String? = null,
) {
    /** Identifies the stream without holding its URL in logs. */
    val key: String = "${url.hashCode()}:${headers.hashCode()}:$isHls:$isDash:$alternate"

    /** The same link, whether it's the playing stream or another source. */
    val linkKey: String = "${url.hashCode()}:${headers.hashCode()}"

    /** This link as another source of the title, labelled [quality]. */
    fun asAlternate(quality: String): SeekThumbnailSource = SeekThumbnailSource(url, headers, isHls, isDash, quality)

    companion object {
        /** Null for streams thumbnails can't come from: torrents, live, local proxies and Smooth Streaming. */
        fun from(
            url: String?,
            headers: Map<String, String>,
            isTorrent: Boolean,
            isLive: Boolean,
            mimeType: String? = null,
            alternate: String? = null,
        ): SeekThumbnailSource? {
            if (url.isNullOrBlank() || isTorrent || isLive) return null
            val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase(Locale.ROOT)
            if (scheme != "http" && scheme != "https") return null
            val host = uri.host?.lowercase(Locale.ROOT) ?: return null
            if (host == "localhost" || host == "::1" || host.startsWith("127.")) return null
            val path = uri.path.orEmpty().lowercase(Locale.ROOT)
            val mime = mimeType.orEmpty().lowercase(Locale.ROOT)
            val dash = path.endsWith(".mpd") || "dash" in mime
            if (!dash && (path.endsWith(".ism") || path.contains(".ism/"))) return null
            val hls = !dash && (path.endsWith(".m3u8") || path.endsWith(".m3u") || "mpegurl" in mime)
            return SeekThumbnailSource(url, headers, hls, dash, alternate)
        }
    }
}

/** What seek thumbnails are doing, for the player's stream info. */
data class SeekPreviewStatus(
    /** The other source's label ("720p 1.1 GB"), or null for the playing file. */
    val source: String?,
    /** The server thumbnails come from. */
    val host: String?,
    /** That server is also the one playback streams from. */
    val sharesPlaybackServer: Boolean,
    val state: State,
    val frames: Int,
    val keptBytes: Long,
) {
    enum class State { WAITING, READING, READY, FILLING_NEAR, FILLING_5MIN, FILLING_1MIN, FILLING_10S, COMPLETE, UNAVAILABLE }
}

/** The sources to try, in order: each one that can't give thumbnails hands over to the next. */
private class SourceChain(val sources: List<SeekThumbnailSource>) {
    val key: String = sources.joinToString("|") { it.key }
}

/** A thumbnail and the keyframe time (ms) it shows. */
class SeekThumbnail(val bitmap: Bitmap, val timeMs: Long, val sourceKey: String)

/**
 * The filmstrip around a scrub position: [frames] are the positions [centerMs] + i × [stepMs] for i in -side..side,
 * each null until its thumbnail is in.
 */
class SeekStrip(val centerMs: Long, val stepMs: Long, val frames: List<SeekThumbnail?>, val sourceKey: String)

/**
 * Seek thumbnails for one player screen. Nothing happens until [prepare] or [request]: then one low-priority
 * thread reads the stream's keyframe index once and, while the user scrubs, fetches and decodes only the
 * keyframe nearest the latest position (older positions are skipped, never queued). The decoder is let go a
 * few seconds after scrubbing stops. Given several sources ([setSources]), it uses the first that works.
 *
 * Thumbnails are kept by time for the title ([titleKey]), whichever source made them, and on the device between
 * plays. A thumbnail already made within a few seconds of a position is shown for it: no download.
 */
class SeekThumbnailEngine(
    private val userAgent: String,
    /** Where thumbnails are kept per title between plays; null keeps them in memory only. */
    diskDir: java.io.File?,
    private val clientFactory: () -> OkHttpClient,
) : Closeable {
    private val thread = HandlerThread("SeekThumbs", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
    private val handler = Handler(thread.looper)
    private val lock = Any()

    private val _thumbnail = MutableStateFlow<SeekThumbnail?>(null)
    /** The latest thumbnail for the current stream (it may trail a fast scrub). */
    val thumbnail: StateFlow<SeekThumbnail?> = _thumbnail.asStateFlow()

    private val _strip = MutableStateFlow<SeekStrip?>(null)
    /** The filmstrip for the current stream while [stripSide] is above 0 (it may trail a fast scrub). */
    val strip: StateFlow<SeekStrip?> = _strip.asStateFlow()

    /** Frames shown either side of the scrub position (Settings → Filmstrip preview); 0 is the single thumbnail. */
    @Volatile var stripSide = 0

    private val _status = MutableStateFlow<SeekPreviewStatus?>(null)
    /** What the engine is doing, for the player's stream info; null before any stream. */
    val status: StateFlow<SeekPreviewStatus?> = _status.asStateFlow()

    private val _commitWaitMs = MutableStateFlow(MIN_COMMIT_WAIT_MS)
    /**
     * How long a released scrub should wait before seeking: enough for a thumbnail to arrive and be seen.
     * 0.9 s normally, longer (up to 2 s) on streams whose thumbnails come slowly.
     */
    val commitWaitMs: StateFlow<Long> = _commitWaitMs.asStateFlow()
    private var averageLatencyMs = 0.0

    private val _available = MutableStateFlow<Boolean?>(null)
    /** False once the current stream is known not to give thumbnails; null until its index is read. */
    val available: StateFlow<Boolean?> = _available.asStateFlow()

    @Volatile private var wanted: SourceChain? = null
    /** The link of the source being read (set on the thumbnail thread). */
    @Volatile private var activeKey: String? = null
    @Volatile private var closed = false
    @Volatile private var scrubbing = false

    // Requests, guarded by [lock].
    private var pendingTargetMs = -1L
    private var pendingDirection = 0
    private var draining = false

    // Worker thread only.
    private var chain: SourceChain? = null
    private var chainPosition = 0
    private var source: SeekThumbnailSource? = null
    @Volatile private var http: HttpRangeSource? = null
    @Volatile private var segments: SegmentFetcher? = null
    private var index: MediaIndex? = null
    private var unsupported = false
    private var failures = 0
    private var busySinceMs = 0L
    private var client: OkHttpClient? = null
    private val decoder = KeyframeDecoder()
    /** Decoded thumbnails by time (ms). */
    private val cache = object : LruCache<Long, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: Long, value: Bitmap): Int = value.allocationByteCount
    }
    private val releaseDecoder = Runnable { decoder.releaseCodec() }

    // Keyframe downloads run on a few threads, so a slow server's wait per request overlaps instead of adding up;
    // decoding stays on the thumbnail thread. [inflight] is managed by the thumbnail thread.
    private val fetchPool = java.util.concurrent.ThreadPoolExecutor(
        FETCH_THREADS, FETCH_THREADS, 30L, java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.LinkedBlockingQueue(),
    ) { task ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            task.run()
        }, "SeekThumbsFetch").apply { isDaemon = true }
    }.apply { allowCoreThreadTimeOut(true) }
    private val inflight = HashMap<Int, Fetch>()

    private class Fetch {
        @Volatile var started = false
        lateinit var future: java.util.concurrent.Future<ByteArray?>
    }

    // Background fill (worker thread only): every thumbnail made is kept small (JPEG) by time (ms), so it can be
    // shown again or stand in for a neighbour still coming. It belongs to the title, whichever source made it.
    private val store = java.util.TreeMap<Long, ByteArray>()
    private val disk = diskDir?.let { SeekPreviewDisk(it) }
    private var storeTitle: String? = null
    private var storeTitleSet = false
    /** The playing length the kept thumbnails belong to (one cut of the title); 0 when none. */
    private var storeLengthMs = 0L
    /** Sources of the current list found not to answer when it was first read; they're passed over. */
    private val deadLinks = HashSet<String>()
    private var probedChainKey: String? = null
    private var storeBytes = 0L
    private var producedCount = 0
    private var producedBytes = 0L
    private var fillWanted = false
    private var fillPass = 0
    private var fillCursor = 0L
    /** Where the current fill pass started (the playhead, on its grid). */
    private var fillAnchorMs = 0L
    private var fillMade = 0
    /**
     * The source in use is on the server playback streams from: its keyframes are read one at a time and the
     * background fill goes slowly, so it never competes with playback there.
     */
    private var sharesPlaybackServer = false
    private var fillDone = false
    /** The background fill was started for the current list: another source taking over picks it up again. */
    private var fillPrepared = false

    /** The player's length when a source was kept across a stream change; -1 when none is waiting to be checked. */
    private var lengthAtKeep = -1L
    private val fillQueue = ArrayDeque<Int>()
    private val fillStep = Runnable { fillOnce() }

    /**
     * The playing video's long edge in pixels (0: not known yet): another source wider than it would be no lighter.
     * The long edge, so a cropped 1280x548 film still matches a 1280x720 source.
     */
    @Volatile var playerVideoLongEdge = 0

    /**
     * The playing title (its source search key: a film, or one episode), for thumbnails kept between plays and
     * across sources; null keeps them for the current sources only.
     */
    @Volatile var titleKey: String? = null

    /** Where playback is, for the background fill to start around. */
    @Volatile var playerPositionMs = 0L

    val sourceKey: String? get() = wanted?.key

    /** Set while the player is rebuffering: thumbnail downloads hold off so they never compete with it. */
    @Volatile var playerBuffering = false

    /**
     * The player's duration, for files that don't state one. A change (another cut after a quality switch) is checked
     * at once: the kept thumbnails and the source in use have to match the new length.
     */
    @Volatile var playerDurationMs = 0L
        set(value) {
            val changed = field != value
            field = value
            if (changed && !closed) handler.post(::onLengthChanged)
        }

    /**
     * How much one stream may download for thumbnails (Settings → Playback → Seek preview data); taken when a
     * stream starts. Large files have large keyframes and use it up sooner; once it's gone, that stream shows none.
     */
    @Volatile var budgetBytes = DEFAULT_BUDGET_BYTES

    // Worker thread only: the scrub position served before the last, to measure the step between presses.
    private var previousTarget = -1L

    /**
     * Points the engine at [sources], tried in order (empty: none that can give thumbnails). Cheap when unchanged.
     */
    fun setSources(sources: List<SeekThumbnailSource>) {
        if (closed) return
        val next = SourceChain(sources).takeIf { sources.isNotEmpty() }
        if (next == null) {
            _available.value = false
            _status.value = null
        }
        if (next?.key == wanted?.key) return
        wanted = next
        Log.i(TAG, "sources: ${next?.sources?.joinToString { it.alternate ?: "playing" } ?: "none"}")
        synchronized(lock) { pendingTargetMs = -1L }
        _thumbnail.value = null
        _strip.value = null
        // Another source the new list still has carries on (see syncSource), so its reads aren't stopped.
        val keeps = activeKey?.let { key -> next?.sources?.any { it.linkKey == key && it.alternate != null } } == true
        if (!keeps) {
            http?.close()   // stops a read for the old stream; the worker swaps sources before its next step
            segments?.close()
            _available.value = if (next == null) false else null
        }
        // The old stream's decoder goes at once, so a new stream's player never waits for video memory.
        handler.post { if (next != null) syncSource() else decoder.releaseCodec() }
    }

    /** Reads the index ahead of the first scrub. */
    fun prepare() {
        if (closed || wanted == null) return
        handler.post {
            if (syncSource() != null && loadIndex() != null) {
                // Then fills in thumbnails across the video in the background, so scrubbing finds them made.
                fillPrepared = true
                fillWanted = true
                scheduleFill(FILL_START_MS)
                publishStatus()
            }
        }
    }

    /**
     * The controls came up at [positionMs]: the thumbnails the first presses will want are made now, so they show at
     * once. Skipped while scrubbing; the decoder goes again shortly after.
     */
    fun warm(positionMs: Long) {
        if (closed || wanted == null || _available.value == false || scrubbing) return
        handler.post {
            if (closed || scrubbing || syncSource() == null || loadIndex() == null) return@post
            val offsets = if (stripSide > 0) WARM_STRIP_OFFSETS else WARM_OFFSETS
            for (offset in offsets) {
                if (closed || scrubbing || hasPending()) break
                val target = positionMs + offset * PREFETCH_STEP_MS
                if (target >= 0) serve(target, publish = false)
            }
            if (!scrubbing) {
                handler.removeCallbacks(releaseDecoder)
                handler.postDelayed(releaseDecoder, DECODER_IDLE_MS)
            }
        }
    }

    /** The user is scrubbing at [positionMs]; [direction] is +1 forward, -1 back, 0 unknown. */
    fun request(positionMs: Long, direction: Int) {
        if (closed || wanted == null || _available.value == false) return
        scrubbing = true
        handler.removeCallbacks(releaseDecoder)
        synchronized(lock) {
            pendingTargetMs = positionMs.coerceAtLeast(0L)
            pendingDirection = direction
            if (draining) return
            draining = true
        }
        handler.post(::drain)
    }

    /**
     * The keyframe nearest [positionMs] of the current stream, for a player that's about to let its own picture
     * go (a quality or audio switch holds it on screen). Runs on the thumbnail thread; [onResult] gets null when
     * the stream can't give one. Unless a scrub is running, the decoder goes straight after, so it never shares
     * video memory with the next player.
     */
    fun grab(positionMs: Long, onResult: (Bitmap?) -> Unit) {
        if (closed || wanted == null || _available.value == false) {
            onResult(null)
            return
        }
        handler.post {
            val bitmap = if (closed) null else runCatching {
                val idx = if (syncSource() != null) loadIndex() else null
                val k = idx?.keyframes?.nearest(positionMs.coerceAtLeast(0L) * 1000L) ?: -1
                if (idx == null || k < 0) null else frameAtTime(keyTimeMs(idx, k)) ?: produce(idx, k)
            }.onFailure { Log.w(TAG, "grab failed", it) }.getOrNull()
            if (!scrubbing) {
                handler.removeCallbacks(releaseDecoder)
                decoder.releaseCodec()
            }
            onResult(bitmap)
        }
    }

    /** Scrubbing ended: the decoder is released if it doesn't resume soon. */
    fun stopScrubbing() {
        if (closed) return
        scrubbing = false
        synchronized(lock) { pendingTargetMs = -1L }
        handler.post { previousTarget = -1L }
        handler.removeCallbacks(releaseDecoder)
        handler.postDelayed(releaseDecoder, DECODER_IDLE_MS)
    }

    override fun close() {
        if (closed) return
        closed = true
        http?.close()
        segments?.close()
        handler.removeCallbacksAndMessages(null)
        fetchPool.shutdownNow()
        handler.post {
            decoder.close()
            cache.evictAll()
            store.clear()
            disk?.close()
            thread.quitSafely()
        }
    }

    private fun drain() {
        var lastTarget = -1L
        var lastDirection = 0
        while (!closed) {
            val target: Long
            val direction: Int
            synchronized(lock) {
                if (pendingTargetMs < 0) {
                    draining = false
                    target = -1L
                    direction = 0
                } else {
                    target = pendingTargetMs
                    direction = pendingDirection
                    pendingTargetMs = -1L
                }
            }
            if (target < 0) break
            if (lastTarget >= 0) previousTarget = lastTarget
            // What's already made shows at once; downloads only fill the gaps.
            ensureTitle()
            publishScrub(target, scrubStep(target))
            fetchAround(target, direction)
            serve(target, publish = true)
            lastTarget = target
            lastDirection = direction
        }
        if (!closed && scrubbing && lastTarget >= 0) {
            val step = scrubStep(lastTarget)
            val side = stripSide
            // The filmstrip's other frames: the next one in the scrub direction, the one behind, then further out.
            // Any new press stops this (serve() skips work while one is waiting).
            if (side > 0) {
                val ahead = if (lastDirection != 0) lastDirection else 1
                for (distance in 1..side) {
                    for (sign in intArrayOf(ahead, -ahead)) {
                        if (closed || !scrubbing || hasPending()) break
                        val target = lastTarget + sign * distance * step
                        if (target < 0) continue
                        serve(target, publish = false)
                        publishStrip(lastTarget, step)
                    }
                }
            }
            // The next presses' thumbnails beyond the strip, fetched ahead in the scrub direction at the step the
            // presses are actually moving, so they show at once.
            if (lastDirection != 0) {
                for (ahead in side + 1..side + PREFETCH_AHEAD) {
                    if (closed || !scrubbing || hasPending()) break
                    serve(lastTarget + lastDirection * step * ahead, publish = false)
                }
            }
        }
        previousTarget = lastTarget
    }

    /** The step between scrub presses, as measured from the last two; [PREFETCH_STEP_MS] until it's known. */
    private fun scrubStep(targetMs: Long): Long {
        val measured = if (previousTarget >= 0) kotlin.math.abs(targetMs - previousTarget) else 0L
        return if (measured in MIN_PREFETCH_STEP_MS..MAX_PREFETCH_STEP_MS) measured else PREFETCH_STEP_MS
    }

    /**
     * Makes the thumbnail for [targetMs] if there's none within a few seconds of it, and with [publish] shows it.
     * Without [publish] (the filmstrip's other frames, the next presses, warming) it gives way to a newer press.
     */
    private fun serve(targetMs: Long, publish: Boolean) {
        ensureTitle()
        val step = scrubStep(targetMs)
        if (slotFrame(targetMs, snapTolerance(step)) != null) {
            if (publish) publishScrub(targetMs, step)
            return
        }
        if (!publish && hasPending()) return
        if (syncSource() == null) return
        val idx = loadIndex() ?: return
        // Given up on: what's made still shows, nothing more is downloaded.
        if (unsupported) return
        val k = idx.keyframes.nearest(targetMs * 1000L)
        if (k < 0) return
        if (!hasMadeExact(keyTimeMs(idx, k))) {
            if (!publish && hasPending()) return
            if (!waitForPlayback()) return
            produce(idx, k) ?: return
        }
        if (publish) publishScrub(targetMs, step)
    }

    /**
     * Shows the scrub at [targetMs] from what's made: its own thumbnail, or meanwhile the nearest one (dimmed when
     * it's far off), and the filmstrip around it.
     */
    private fun publishScrub(targetMs: Long, stepMs: Long) {
        val key = wanted?.key ?: return
        val own = slotFrame(targetMs, snapTolerance(stepMs)) ?: madeNear(targetMs, STAND_IN_MAX_MS)
        own?.let { (at, bitmap) -> _thumbnail.value = SeekThumbnail(bitmap, at, key) }
        if (stripSide > 0) publishStrip(targetMs, stepMs)
    }

    /**
     * Starts downloading, all at once, the keyframes a scrub at [targetMs] will want: its own first, then the
     * filmstrip's (the next one ahead first) and the next presses'.
     */
    private fun fetchAround(targetMs: Long, direction: Int) {
        if (syncSource() == null) return
        val idx = loadIndex() ?: return
        if (unsupported) return
        val step = scrubStep(targetMs)
        val ahead = if (direction != 0) direction else 1
        val targets = ArrayList<Long>()
        targets += targetMs
        for (distance in 1..stripSide) {
            targets += targetMs + ahead * distance * step
            targets += targetMs - ahead * distance * step
        }
        if (direction != 0) for (n in stripSide + 1..stripSide + PREFETCH_AHEAD) targets += targetMs + direction * n * step
        val tolerance = snapTolerance(step)
        val ks = targets.filter { it >= 0 && slotFrame(it, tolerance) == null }
            .map { idx.keyframes.nearest(it * 1000L) }
            .filter { it >= 0 && !hasMadeExact(keyTimeMs(idx, it)) }
            .distinct()
        // On playback's own server only the one wanted now is fetched: the rest follow one at a time.
        fetchAhead(idx, if (sharesPlaybackServer) ks.take(1) else ks)
    }

    /**
     * Downloads keyframes [ks] on the fetch threads, in order. Queued downloads no longer wanted are dropped
     * (ones already running finish and are kept for later).
     */
    private fun fetchAhead(idx: MediaIndex, ks: List<Int>) {
        val reader = http ?: return
        val wanted = ks.filter { it >= 0 && !hasMadeExact(keyTimeMs(idx, it)) }
        val keep = wanted.toSet()
        inflight.entries.removeAll { (k, fetch) -> k !in keep && !fetch.started && fetch.future.cancel(false) }
        if (inflight.size > MAX_INFLIGHT) inflight.entries.removeAll { (k, fetch) -> k !in keep && fetch.future.isDone }
        for (k in wanted) {
            if (inflight.containsKey(k)) continue
            val fetch = Fetch()
            fetch.future = fetchPool.submit<ByteArray?> {
                fetch.started = true
                MediaIndexReader.readKeyframe(idx, reader, k, MAX_KEYFRAME_BYTES)
            }
            inflight[k] = fetch
        }
    }

    /** Keyframe [k]'s bytes from a download started earlier (waiting for it if it's still coming), or null. */
    private fun takeFetched(k: Int): ByteArray? {
        val fetch = inflight.remove(k) ?: return null
        return try {
            fetch.future.get(FETCH_WAIT_S, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: java.util.concurrent.CancellationException) {
            null
        } catch (e: java.util.concurrent.TimeoutException) {
            fetch.future.cancel(false)
            throw IOException("keyframe download timed out")
        } catch (e: java.util.concurrent.ExecutionException) {
            when (val cause = e.cause) {
                is IOException -> throw cause
                is RuntimeException -> throw cause
                is OutOfMemoryError -> throw cause
                else -> throw IOException(cause)
            }
        }
    }

    private fun keyTimeMs(idx: MediaIndex, k: Int): Long = idx.keyframes.timeUs[k] / 1000L

    /** How far a made thumbnail may be from a position and still be shown for it: half a press, 5 s at most. */
    private fun snapTolerance(stepMs: Long): Long = minOf(SNAP_MAX_MS, stepMs / 2)

    /** A thumbnail made at exactly [timeMs] (a keyframe's time), without decoding it. */
    private fun hasMadeExact(timeMs: Long): Boolean = cache.get(timeMs) != null || store.containsKey(timeMs)

    /** The thumbnail made at [timeMs]: from memory, else from the kept JPEG. */
    private fun frameAtTime(timeMs: Long): Bitmap? {
        cache.get(timeMs)?.let { return it }
        val jpeg = store[timeMs] ?: return null
        val bitmap = runCatching { BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) }.getOrNull() ?: return null
        cache.put(timeMs, bitmap)
        return bitmap
    }

    /** The time of the kept thumbnail nearest [timeMs], if it's within [toleranceMs]; nothing is decoded. */
    private fun madeNearTime(timeMs: Long, toleranceMs: Long): Long? =
        listOfNotNull(store.floorKey(timeMs), store.ceilingKey(timeMs))
            .filter { kotlin.math.abs(it - timeMs) <= toleranceMs }
            .minByOrNull { kotlin.math.abs(it - timeMs) }

    /** The kept thumbnail nearest [timeMs] within [toleranceMs], and its time. */
    private fun madeNear(timeMs: Long, toleranceMs: Long): Pair<Long, Bitmap>? =
        madeNearTime(timeMs, toleranceMs)?.let { at -> frameAtTime(at)?.let { at to it } }

    /**
     * The thumbnail to show for [timeMs]: one made within [toleranceMs], else the one made at the keyframe nearest
     * it (where keyframes are further apart than that).
     */
    private fun slotFrame(timeMs: Long, toleranceMs: Long): Pair<Long, Bitmap>? {
        madeNear(timeMs, toleranceMs)?.let { return it }
        val idx = index ?: return null
        val k = idx.keyframes.nearest(timeMs * 1000L)
        if (k < 0) return null
        val at = keyTimeMs(idx, k)
        return frameAtTime(at)?.let { at to it }
    }

    private fun keep(timeMs: Long, bitmap: Bitmap) {
        if (store.containsKey(timeMs) || storeBytes >= STORE_MAX_BYTES) return
        val out = ByteArrayOutputStream(16 * 1024)
        if (!runCatching { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out) }.getOrDefault(false)) return
        val jpeg = out.toByteArray()
        store[timeMs] = jpeg
        storeBytes += jpeg.size
        if (storeTitle != null) disk?.append(timeMs, jpeg)
    }

    /**
     * Keeps thumbnails for the playing title and cut: when either changes (a new title, or a switch to a release of
     * a different length), the old ones are let go and the new cut's kept thumbnails are loaded from the device. Until
     * the playing length is known none are kept, as they can't yet be matched to a cut. Without a title they last only
     * as long as the current sources.
     */
    private fun ensureTitle() {
        val title = titleKey
        val length = playerDurationMs
        val sameCut = storeLengthMs > 0 && length > 0 && kotlin.math.abs(length - storeLengthMs) <= ALTERNATE_LENGTH_SLACK_MS
        if (storeTitleSet && title == storeTitle && (title == null || sameCut)) return
        if (title != null && length <= 0) {
            // No length for a moment (a switch in progress): the same title keeps what it has.
            if (storeTitleSet && title == storeTitle) return
            if (store.isNotEmpty() || storeTitle != title) clearStore()
            storeTitle = title
            storeTitleSet = false
            return
        }
        clearStore()
        storeTitleSet = true
        storeTitle = title
        storeLengthMs = length
        if (title != null && disk != null) {
            val kept = disk.open(title, length, ALTERNATE_LENGTH_SLACK_MS, STORE_MAX_BYTES)
            kept.forEach { (time, jpeg) ->
                store[time] = jpeg
                storeBytes += jpeg.size
            }
            if (kept.isNotEmpty()) Log.i(TAG, "${kept.size} thumbnails kept from an earlier play (${storeBytes / 1024} KB)")
            disk.trim()
        }
        publishStatus()
    }

    /** The playing length changed: the kept thumbnails follow the cut, and a source kept across is checked now. */
    private fun onLengthChanged() {
        if (closed || playerDurationMs <= 0) return
        ensureTitle()
        if (chain != null && index != null && lengthAtKeep >= 0L) loadIndex()
    }

    private fun clearStore() {
        disk?.close()
        store.clear()
        storeBytes = 0L
        storeLengthMs = 0L
        cache.evictAll()
    }

    private fun fillPace(): Long = if (sharesPlaybackServer) FILL_SHARED_PACE_MS else FILL_PACE_MS

    private fun scheduleFill(delayMs: Long) {
        handler.removeCallbacks(fillStep)
        handler.postDelayed(fillStep, delayMs)
    }

    /**
     * One step of the background fill: one thumbnail, then the next step is scheduled. It waits while the user
     * scrubs (scrubbing comes first) or the player rebuffers, and stops for good on sources too heavy to sweep, on a
     * hardware decoder (playback needs it), and when it has used half the data allowed.
     */
    private fun fillOnce() {
        if (closed || !fillWanted) return
        if (scrubbing || hasPending() || playerBuffering) {
            scheduleFill(FILL_BUSY_MS)
            return
        }
        if (syncSource() == null) return
        val idx = loadIndex() ?: return
        if (unsupported) {
            fillWanted = false
            publishStatus()
            return
        }
        val reader = http ?: return
        val stop = when {
            decoder.isHardware -> "hardware decoder"
            producedCount >= 3 && producedBytes / producedCount > FILL_MAX_KEYFRAME_BYTES -> "keyframes too large"
            reader.bytesUsed + (segments?.bytesUsed ?: 0L) > budgetBytes / 2 -> "data limit"
            storeBytes >= STORE_MAX_BYTES -> "memory"
            else -> null
        }
        // Another server is downloaded from a few keyframes ahead; playback's own server one at a time, so the fill
        // never crowds playback.
        val ahead = if (sharesPlaybackServer) 1 else FETCH_THREADS
        if (stop == null) {
            while (fillQueue.size < ahead) {
                val next = nextFillKeyframe(idx)
                if (next < 0) break
                fillQueue.addLast(next)
            }
        }
        val k = if (stop == null) fillQueue.removeFirstOrNull() ?: -1 else -1
        if (k >= 0 && ahead > 1) fetchAhead(idx, listOf(k) + fillQueue)
        if (k < 0) {
            fillWanted = false
            Log.i(TAG, "background fill ${stop?.let { "stopped ($it)" } ?: "done"}: $fillMade frames, ${storeBytes / 1024} KB kept")
            fillDone = stop == null
            publishStatus()
            if (!scrubbing) {
                handler.removeCallbacks(releaseDecoder)
                handler.postDelayed(releaseDecoder, DECODER_IDLE_MS)
            }
            return
        }
        if (produce(idx, k, quiet = true) != null) {
            fillMade++
            if (fillMade % FILL_LOG_EVERY == 0) {
                Log.i(TAG, "background fill: $fillMade frames, ${store.size} kept (${storeBytes / 1024} KB), pass $fillPass")
            }
        }
        publishStatus()
        scheduleFill(fillPace())
    }

    /**
     * The next keyframe the fill should make, or -1 when it's done: first those around the playhead (further ahead
     * than behind: scrubbing forward is the more common), then the whole video every 5 minutes, every minute, and
     * every 10 seconds, each pass starting at the playhead and going forward before wrapping round. Spots already
     * covered are skipped.
     */
    private fun nextFillKeyframe(idx: MediaIndex): Int {
        val durationMs = idx.keyframes.timeUs[idx.keyframes.count - 1] / 1000L
        while (fillPass <= FILL_SPACINGS_MS.size) {
            if (fillPass == 0) {
                val around = playerPositionMs
                while (fillCursor < FILL_NEAR_OFFSETS.size) {
                    val target = around + FILL_NEAR_OFFSETS[fillCursor.toInt()] * PREFETCH_STEP_MS
                    fillCursor++
                    if (target < 0 || target > durationMs) continue
                    val k = fillKeyframe(idx, target, PREFETCH_STEP_MS / 4)
                    if (k >= 0) return k
                }
            } else {
                val spacing = FILL_SPACINGS_MS[fillPass - 1]
                if (fillCursor == 0L) fillAnchorMs = playerPositionMs.coerceIn(0L, durationMs) / spacing * spacing
                val slots = durationMs / spacing + 1
                while (fillCursor < slots) {
                    val target = (fillAnchorMs + fillCursor * spacing) % (slots * spacing)
                    fillCursor++
                    if (target > durationMs) continue
                    val k = fillKeyframe(idx, target, spacing / 4)
                    if (k >= 0) return k
                }
            }
            fillPass++
            fillCursor = 0L
        }
        return -1
    }

    /** The keyframe to make for [targetMs], or -1 when one is made within [toleranceMs] of it already. */
    private fun fillKeyframe(idx: MediaIndex, targetMs: Long, toleranceMs: Long): Int {
        if (madeNearTime(targetMs, toleranceMs) != null) return -1
        val k = idx.keyframes.nearest(targetMs * 1000L)
        return if (k >= 0 && !hasMadeExact(keyTimeMs(idx, k))) k else -1
    }

    /**
     * Publishes the filmstrip around [centerMs] from the thumbnails already made. Each tile shows only its own frame
     * (a plain tile until it's in); the middle one may borrow the nearest made frame meanwhile, unless another tile
     * already shows that frame.
     */
    private fun publishStrip(centerMs: Long, stepMs: Long) {
        val key = wanted?.key ?: return
        val side = stripSide
        val tolerance = snapTolerance(stepMs)
        val made = (-side..side).map { i ->
            val target = centerMs + i * stepMs
            if (target < 0) null else slotFrame(target, tolerance)
        }.toMutableList()
        // One frame in one tile: where keyframes are sparse, two tiles can land on the same one.
        val shown = HashSet<Long>()
        val order = listOf(side) + (1..side).flatMap { listOf(side + it, side - it) }
        for (i in order) made[i]?.let { (at, _) -> if (!shown.add(at)) made[i] = null }
        if (made[side] == null) made[side] = madeNear(centerMs, STAND_IN_MAX_MS)?.takeIf { it.first !in shown }
        val frames = made.map { it?.let { (at, bitmap) -> SeekThumbnail(bitmap, at, key) } }
        _strip.value = SeekStrip(centerMs, stepMs, frames, key)
    }

    private fun hasPending(): Boolean = synchronized(lock) { pendingTargetMs >= 0 }

    /** [src] is the source in use and its chain is still the wanted one (worker thread). */
    private fun isWanted(src: SeekThumbnailSource): Boolean = source === src && wanted?.key == chain?.key

    /**
     * Waits (up to [BUFFERING_WAIT_MS]) for the player to finish rebuffering. False when it didn't, or a newer
     * scrub position arrived: this one is skipped rather than fetched over a struggling stream.
     */
    private fun waitForPlayback(): Boolean {
        val deadline = SystemClock.elapsedRealtime() + BUFFERING_WAIT_MS
        while (playerBuffering) {
            if (closed || hasPending() || SystemClock.elapsedRealtime() > deadline) return false
            SystemClock.sleep(100)
        }
        return true
    }

    /** Swaps to the wanted streams when they changed; the source in use, or null when there's none to read. */
    private fun syncSource(): SeekThumbnailSource? {
        ensureTitle()
        val next = wanted
        if (next?.key == chain?.key) return source?.takeIf { http != null }
        // The stream changed (a quality switch), but the source in use (another one, or the one that was playing) is
        // on the new list as another source: it carries on, with what it has made, rather than starting again.
        val keep = source?.takeIf { index != null && !unsupported && http != null }
        val keptAt = if (keep == null || next == null) -1
        else next.sources.indexOfFirst { it.linkKey == keep.linkKey && it.alternate != null }
        if (next != null && keptAt >= 0) {
            chain = next
            chainPosition = keptAt
            source = next.sources[keptAt]
            lengthAtKeep = playerDurationMs
            val playingHost = next.sources.lastOrNull { it.alternate == null }?.url?.let(::hostOf)
            sharesPlaybackServer = hostOf(next.sources[keptAt].url) == playingHost
            _available.value = true
            publishStatus()
            decoder.releaseCodec()   // as on any stream change: the new player never waits for video memory
            Log.i(TAG, "keeping the ${keep?.alternate ?: "previously playing"} source (${store.size} frames) for the new stream")
            // Checked against the length now as well as when it changes: the new length may already be in.
            val kept = source
            val idx = index
            if (kept != null && idx != null && playerDurationMs > 0) lengthMismatch(kept, idx)?.let { markUnsupported(kept, it) }
            return source?.takeIf { http != null && !unsupported }
        }
        lengthAtKeep = -1L
        chain = next
        chainPosition = 0
        deadLinks.clear()
        probedChainKey = null
        fillPrepared = false
        // Without a title, thumbnails belong to the sources they came from.
        if (storeTitle == null) clearStore()
        fillWanted = false
        averageLatencyMs = 0.0
        _commitWaitMs.value = MIN_COMMIT_WAIT_MS
        open(next?.sources?.firstOrNull())
        return source?.takeIf { http != null }
    }

    /** Starts reading [next] afresh: nothing read from the previous source carries over. */
    private fun open(next: SeekThumbnailSource?) {
        inflight.values.forEach { it.future.cancel(false) }
        inflight.clear()
        fillQueue.clear()
        http?.close()
        http = null
        segments?.close()
        segments = null
        index = null
        unsupported = false
        failures = 0
        busySinceMs = 0L
        producedCount = 0
        producedBytes = 0L
        fillPass = 0
        fillCursor = 0L
        fillMade = 0
        decoder.releaseCodec()
        previousTarget = -1L
        source = next
        activeKey = next?.linkKey
        fillDone = false
        val playingHost = chain?.sources?.lastOrNull { it.alternate == null }?.url?.let(::hostOf)
        sharesPlaybackServer = next != null && (next.alternate == null || hostOf(next.url) == playingHost)
        // A source that failed hands the background fill over to the next one.
        if (fillWanted && next != null) scheduleFill(fillPace())
        publishStatus()
        if (next != null) {
            val c = client ?: clientFactory().also { client = it }
            http = runCatching { HttpRangeSource(c, next.url, next.headers, userAgent, budgetBytes) }.getOrNull()
            // Another source gets a few seconds to answer until its index is in: one that doesn't is passed over.
            if (next.alternate != null) http?.callTimeoutMs = ALTERNATE_ANSWER_MS
            if (http == null) markUnsupported(next, "not an HTTP URL")
        }
    }

    /** The index of the source in use, moving down the chain while sources fail to give one. */
    private fun loadIndex(): MediaIndex? {
        while (true) {
            index?.let { idx ->
                // A source kept across a stream change is checked against the new stream's length once it's known.
                val src = source
                if (lengthAtKeep < 0L || src == null || playerDurationMs <= 0 || playerDurationMs == lengthAtKeep) return idx
                lengthAtKeep = -1L
                val mismatch = lengthMismatch(src, idx) ?: return idx
                markUnsupported(src, mismatch)
                if (closed || unsupported) return null
                continue
            }
            probeChain()
            if (unsupported || closed) return null
            val before = source
            val idx = readIndex()
            // A source that failed for good hands over to the next one, which is read straight away.
            if (idx != null || closed || unsupported || source === before) return idx
        }
    }

    /**
     * The first time the list's index is wanted: asks its first few other sources, all at once, for one byte each
     * (a playlist just has to answer) and passes over those that don't answer in time, or files whose server refuses
     * range requests, so a dead server costs one short wait shared with the others instead of a timeout each.
     */
    private fun probeChain() {
        val current = chain ?: return
        if (probedChainKey == current.key) return
        probedChainKey = current.key
        val reader = http ?: return
        val probes = current.sources.drop(chainPosition).filter { it.alternate != null }.take(PROBE_COUNT)
        if (probes.size < 2) return
        val c = client ?: return
        val started = SystemClock.elapsedRealtime()
        val futures = probes.map { src ->
            src to fetchPool.submit<String?> {
                try {
                    val probe = HttpRangeSource(c, src.url, src.headers, userAgent, Long.MAX_VALUE)
                    probe.callTimeoutMs = ALTERNATE_ANSWER_MS
                    try {
                        if (probe.read(0, 1).isEmpty()) "empty" else null
                    } finally {
                        probe.close()
                    }
                } catch (e: HlsPlaylistFound) {
                    null   // it answered, with a playlist: read as HLS
                } catch (e: DashManifestFound) {
                    null
                } catch (e: RangesRefusedException) {
                    // A playlist needn't serve ranges: any answer means its server is there.
                    if (src.isHls || src.isDash) {
                        null
                    } else {
                        SeekPreviewHosts.noteRefused(src.url)
                        e.message
                    }
                } catch (e: Exception) {
                    SeekPreviewHosts.noteSilent(src.url)
                    e.javaClass.simpleName
                }
            }
        }
        val deadline = started + ALTERNATE_ANSWER_MS + 500L
        val failed = futures.mapNotNull { (src, future) ->
            val wait = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            val failure = try {
                future.get(wait, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
                future.cancel(true)
                "no answer"
            }
            failure?.let { src to it }
        }
        Log.i(TAG, "probed ${probes.size} sources in ${SystemClock.elapsedRealtime() - started}ms" +
            failed.joinToString("") { (src, why) -> "; ${src.alternate} failed ($why)" })
        failed.forEach { (src, _) ->
            deadLinks += src.linkKey
            SeekPreviewHosts.noteRejected(src.url)
        }
        val src = source ?: return
        if (src.linkKey in deadLinks && reader === http) markUnsupported(src, "didn't answer")
    }

    /**
     * Why another release of the title can't stand in for what's playing, or null when it can: only one as long as
     * what's playing (within a minute) shows the same scenes at the same times. Its last keyframe sits within
     * seconds of its end.
     */
    private fun lengthMismatch(src: SeekThumbnailSource, idx: MediaIndex): String? {
        val quality = src.alternate ?: return null
        val lengthMs = idx.keyframes.timeUs[idx.keyframes.count - 1] / 1000L
        val playingMs = playerDurationMs
        if (playingMs > 0L && kotlin.math.abs(lengthMs - playingMs) <= ALTERNATE_LENGTH_SLACK_MS) return null
        return "$quality source runs ${lengthMs / 1000}s, playing ${playingMs / 1000}s"
    }

    private fun readIndex(): MediaIndex? {
        val src = source ?: return null
        val reader = http ?: return null
        if (unsupported) return null
        val started = SystemClock.elapsedRealtime()
        // Another source has to state its own length: the player's is only a stand-in for the playing file's.
        val fallbackDurationUs = if (src.alternate != null) 0L else playerDurationMs * 1000L
        try {
            var idx = when {
                src.isHls -> HlsIndexReader.read(segmentFetcher(), reader.url)
                src.isDash -> DashIndexReader.read(segmentFetcher(), reader.url, fallbackDurationUs)
                else -> try {
                    MediaIndexReader.read(reader, fallbackDurationUs)
                } catch (found: HlsPlaylistFound) {
                    HlsIndexReader.read(segmentFetcher(), reader.url)
                } catch (found: DashManifestFound) {
                    DashIndexReader.read(segmentFetcher(), reader.url, fallbackDurationUs)
                }
            }
            if (idx.video.needsParameterSets) idx = withInBandParameterSets(idx, reader)
            if (!isWanted(src)) return null
            src.alternate?.let { quality ->
                SeekPreviewHosts.noteWorked(src.url)
                val playingEdge = playerVideoLongEdge
                if (playingEdge > 0 && maxOf(idx.video.width, idx.video.height) > playingEdge) {
                    markUnsupported(src, "$quality source is ${idx.video.width}x${idx.video.height}, larger than what's playing")
                    return null
                }
                // 10-bit video decodes only on the device's hardware decoder, which playback is using: another source is
                // meant to be light, so one like that is passed over.
                if (idx.video.bitDepth > 8) {
                    markUnsupported(src, "$quality source is ${idx.video.bitDepth}-bit (hardware decoding only)")
                    return null
                }
                lengthMismatch(src, idx)?.let {
                    markUnsupported(src, it)
                    return null
                }
            }
            index = idx
            reader.callTimeoutMs = 0L
            segments?.callTimeoutMs = 0L
            _available.value = true
            val v = idx.video
            Log.i(
                TAG,
                "index ${src.alternate?.let { "($it source) " }.orEmpty()}host=${hostOf(src.url)}" +
                    "${if (sharesPlaybackServer) " (playback's server)" else ""} ${idx.container} ${v.codec} ${v.width}x${v.height} ${v.bitDepth}-bit transfer=${v.transfer} " +
                    "keyframes=${idx.keyframes.count}${if (idx.cueless != null) " (no Cues: estimated)" else ""} " +
                    "bytes=${reader.bytesUsed + (segments?.bytesUsed ?: 0L)} " +
                    "ms=${SystemClock.elapsedRealtime() - started}",
            )
            publishStatus()
            return idx
        } catch (e: UnsupportedMediaException) {
            if (e is RangesRefusedException) SeekPreviewHosts.noteRefused(src.url)
            if (isWanted(src)) markUnsupported(src, e.message)
        } catch (e: IOException) {
            if (isWanted(src) && !closed) {
                failures++
                Log.w(TAG, "index read failed (${e.javaClass.simpleName}), attempt $failures")
                if (src.alternate != null) SeekPreviewHosts.noteSilent(src.url)
                // Another source is waiting: one that doesn't answer isn't retried.
                if (failures >= MAX_FAILURES || hasNextSource()) markUnsupported(src, "network")
            }
        } catch (e: RuntimeException) {
            if (isWanted(src)) markUnsupported(src, "bad index (${e.javaClass.simpleName})")
        }
        return null
    }

    /** The segment reader for the current stream (made when the stream turns out to be HLS or DASH). */
    private fun segmentFetcher(): SegmentFetcher {
        segments?.let { return it }
        val src = source ?: throw IOException("no stream")
        val c = client ?: clientFactory().also { client = it }
        return SegmentFetcher(c, src.headers, userAgent, budgetBytes).also {
            // Another source gets a few seconds to answer until its index is in, as for a file.
            if (src.alternate != null && index == null) it.callTimeoutMs = ALTERNATE_ANSWER_MS
            segments = it
        }
    }

    /** For a track whose header has no parameter sets: takes them from the first keyframes, where streams carry them. */
    private fun withInBandParameterSets(idx: MediaIndex, reader: RangeSource): MediaIndex {
        for (k in 0 until minOf(3, idx.keyframes.count)) {
            val stored = MediaIndexReader.readKeyframe(idx, reader, k, MAX_KEYFRAME_BYTES) ?: continue
            val unit = AccessUnits.build(idx.video, stored) ?: continue
            val sets = AccessUnits.parameterSets(idx.video, unit)
            if (sets.isNotEmpty()) {
                return MediaIndex(
                    idx.container, idx.video.withParameterSets(sets), idx.keyframes, idx.timestampScaleNs, idx.segments, idx.cueless,
                )
            }
        }
        throw UnsupportedMediaException("no parameter sets in header or stream")
    }

    /** Downloads and decodes keyframe [k]; [quiet] (the background fill) leaves it out of the log. */
    private fun produce(idx: MediaIndex, k: Int, quiet: Boolean = false): Bitmap? {
        val src = source ?: return null
        val reader = http ?: return null
        val started = SystemClock.elapsedRealtime()
        try {
            val stored = (takeFetched(k) ?: MediaIndexReader.readKeyframe(idx, reader, k, MAX_KEYFRAME_BYTES))
                ?: throw IOException("keyframe not found")
            val fetched = SystemClock.elapsedRealtime()
            val unit = AccessUnits.build(idx.video, stored) ?: throw IOException("empty keyframe")
            val bitmap = decoder.decode(idx.video, unit, THUMB_MAX_WIDTH, THUMB_MAX_HEIGHT)
            if (bitmap == null) {
                when {
                    decoder.noDecoder -> markUnsupported(src, "no decoder for ${idx.video.codec} ${idx.video.bitDepth}-bit")
                    decoder.busy -> noteBusy(src)
                    // Some keyframes don't decode on their own (an open GOP); once this source has given pictures,
                    // that one is just skipped rather than counted against the source.
                    producedCount > 0 -> Log.i(TAG, "keyframe at ${keyTimeMs(idx, k) / 1000}s didn't decode; skipped")
                    else -> noteFailure(src, "decode")
                }
                return null
            }
            failures = 0
            busySinceMs = 0L
            // The keyframe's time is read after its bytes: a file without Cues only learns it then.
            val timeMs = keyTimeMs(idx, k)
            cache.put(timeMs, bitmap)
            keep(timeMs, bitmap)
            producedCount++
            producedBytes += stored.size
            noteLatency(SystemClock.elapsedRealtime() - started)
            if (!quiet) Log.i(
                TAG,
                "frame at ${idx.keyframes.timeUs[k] / 1_000_000}s bytes=${stored.size} fetchMs=${fetched - started} " +
                    "decodeMs=${SystemClock.elapsedRealtime() - fetched} decoder=${decoder.codecName}",
            )
            return bitmap
        } catch (e: UnsupportedMediaException) {
            if (isWanted(src)) markUnsupported(src, e.message)
        } catch (e: IOException) {
            if (isWanted(src) && !closed) noteFailure(src, e.javaClass.simpleName)
        } catch (e: RuntimeException) {
            if (isWanted(src)) noteFailure(src, e.javaClass.simpleName)
        } catch (e: OutOfMemoryError) {
            cache.evictAll()
            decoder.releaseCodec()
            if (isWanted(src)) markUnsupported(src, "out of memory", lastResort = true)
        }
        return null
    }

    private fun noteLatency(ms: Long) {
        averageLatencyMs = if (averageLatencyMs == 0.0) ms.toDouble() else averageLatencyMs * 0.7 + ms * 0.3
        _commitWaitMs.value = (averageLatencyMs.toLong() + 700L).coerceIn(MIN_COMMIT_WAIT_MS, MAX_COMMIT_WAIT_MS)
    }

    /** The TV had no video memory for a decoder: wait it out, but not for more than [BUSY_GIVE_UP_MS]. */
    private fun noteBusy(src: SeekThumbnailSource) {
        val now = SystemClock.elapsedRealtime()
        if (busySinceMs == 0L) busySinceMs = now
        // The TV's video memory, not the source: another source would wait the same.
        if (now - busySinceMs > BUSY_GIVE_UP_MS) markUnsupported(src, "decoder busy for ${(now - busySinceMs) / 1000}s", lastResort = true)
    }

    private fun noteFailure(src: SeekThumbnailSource, reason: String) {
        failures++
        Log.w(TAG, "thumbnail failed ($reason), $failures in a row")
        if (failures >= MAX_FAILURES) markUnsupported(src, "repeated failures")
    }

    private fun publishStatus() {
        val src = source
        val state = when {
            src == null || unsupported -> SeekPreviewStatus.State.UNAVAILABLE
            index == null -> if (http == null) SeekPreviewStatus.State.WAITING else SeekPreviewStatus.State.READING
            fillDone -> SeekPreviewStatus.State.COMPLETE
            !fillWanted -> SeekPreviewStatus.State.READY
            else -> when (fillPass) {
                0 -> SeekPreviewStatus.State.FILLING_NEAR
                1 -> SeekPreviewStatus.State.FILLING_5MIN
                2 -> SeekPreviewStatus.State.FILLING_1MIN
                else -> SeekPreviewStatus.State.FILLING_10S
            }
        }
        _status.value = SeekPreviewStatus(
            source = src?.alternate,
            host = src?.url?.let(::hostOf),
            sharesPlaybackServer = sharesPlaybackServer,
            state = state,
            frames = store.size,
            keptBytes = storeBytes,
        )
    }

    private fun hostOf(url: String): String? = runCatching { Uri.parse(url).host }.getOrNull()

    private fun hasNextSource(): Boolean = chainPosition < (chain?.sources?.size ?: 0) - 1

    /**
     * [src] can't give thumbnails: the next source in the chain takes over, or, after the last one (or with
     * [lastResort]), the stream shows none.
     */
    private fun markUnsupported(src: SeekThumbnailSource, reason: String?, lastResort: Boolean = false) {
        if (unsupported) return
        if (!lastResort && src.alternate != null && isWanted(src)) SeekPreviewHosts.noteRejected(src.url)
        val current = chain
        if (!lastResort && current != null && isWanted(src) && hasNextSource()) {
            chainPosition++
            // Past those already found not to answer (the playing file is never one of them).
            while (current.sources[chainPosition].linkKey in deadLinks && hasNextSource()) chainPosition++
            val next = current.sources[chainPosition]
            Log.i(TAG, "no thumbnails from ${src.alternate ?: "playing"} source ($reason), trying ${next.alternate ?: "playing"}")
            // The fill may have stopped for that source (keyframes too large, a hardware decoder): the next one gets
            // its own go.
            if (fillPrepared) fillWanted = true
            open(next)
            return
        }
        unsupported = true
        decoder.releaseCodec()
        // Thumbnails already made keep showing (and scrubbing keeps waiting to commit); only with none is it off.
        if (isWanted(src) && store.isEmpty()) _available.value = false
        publishStatus()
        Log.i(TAG, "no more thumbnails from this stream ($reason); ${store.size} made ones still show")
    }

    private companion object {
        const val TAG = "SeekThumbs"
        const val THUMB_MAX_WIDTH = 384
        const val THUMB_MAX_HEIGHT = 216
        /** About 30 thumbnails: enough for a scrub back and forth, and a filmstrip either side. */
        const val CACHE_BYTES = 10 * 1024 * 1024
        const val MAX_KEYFRAME_BYTES = 12 * 1024 * 1024
        const val DEFAULT_BUDGET_BYTES = 800L * 1024 * 1024
        const val MAX_FAILURES = 3
        const val BUSY_GIVE_UP_MS = 10_000L
        const val BUFFERING_WAIT_MS = 4_000L
        const val MIN_COMMIT_WAIT_MS = 900L
        const val MAX_COMMIT_WAIT_MS = 2_000L
        const val DECODER_IDLE_MS = 2_000L
        const val PREFETCH_STEP_MS = 10_000L
        const val MIN_PREFETCH_STEP_MS = 1_000L
        const val MAX_PREFETCH_STEP_MS = 10L * 60_000L
        /** Thumbnails fetched ahead of the scrub: the next two presses. */
        const val PREFETCH_AHEAD = 2
        /** Keyframes downloaded at once. */
        const val FETCH_THREADS = 4
        /** Downloads kept waiting to be decoded before finished, unwanted ones are dropped. */
        const val MAX_INFLIGHT = 16
        /** Longest wait for a started download (the client gives up on a call after 20 s anyway). */
        const val FETCH_WAIT_S = 25L
        /** Warmed when the controls come up, in steps from the playhead: the first press forward, then back. */
        val WARM_OFFSETS = intArrayOf(1, -1)
        val WARM_STRIP_OFFSETS = intArrayOf(1, 0, 2, -1, 3)
        /** A made thumbnail stands in for one still coming when it's this close. */
        const val STAND_IN_MAX_MS = 5L * 60_000L
        /** Kept thumbnails (JPEG, ~10–20 KB each): room for every 10 s of a two-hour film. */
        const val STORE_MAX_BYTES = 14L * 1024 * 1024
        const val JPEG_QUALITY = 80
        const val FILL_START_MS = 500L
        /** A thumbnail made this close to a position is shown for it (at most half a press away). */
        const val SNAP_MAX_MS = 5_000L
        /** How long another source has to answer a request until its index is in. */
        const val ALTERNATE_ANSWER_MS = 4_000L
        /** Other sources asked at once, the first time a list is read. */
        const val PROBE_COUNT = 3
        /**
         * Between background thumbnails from another server: short, so a film's every-10-seconds pass takes minutes,
         * not a quarter of an hour. Decoding runs at background priority on its own thread.
         */
        const val FILL_PACE_MS = 100L
        /** The background fill logs a line every this many thumbnails. */
        const val FILL_LOG_EVERY = 50
        const val FILL_BUSY_MS = 1_500L
        /** Between background thumbnails read from playback's own server. */
        const val FILL_SHARED_PACE_MS = 1_000L
        /** Keyframes bigger than this on average (a 4K remux's are megabytes) aren't swept in the background. */
        const val FILL_MAX_KEYFRAME_BYTES = 300L * 1024
        /**
         * Thumbnails made around the playhead first, in steps of 10 s: 4 minutes ahead and 2 behind, alternating
         * while both last.
         */
        val FILL_NEAR_OFFSETS: IntArray = (1..24).flatMap { if (it <= 12) listOf(it, -it) else listOf(it) }.toIntArray()
        val FILL_SPACINGS_MS = longArrayOf(5L * 60_000L, 60_000L, 10_000L)
        /** How far another source's length may be from the playing one's. */
        const val ALTERNATE_LENGTH_SLACK_MS = 60_000L
    }
}
