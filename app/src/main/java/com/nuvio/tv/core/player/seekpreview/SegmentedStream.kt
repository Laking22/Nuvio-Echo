package com.nuvio.tv.core.player.seekpreview

import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/** One HLS or DASH segment as bytes of a URL; [length] -1 for the whole file. */
internal class MediaSegment(val url: HttpUrl, val offset: Long, val length: Long)

/** What a thumbnail needs from a segmented stream: its segments (keyframe table offsets index them). */
internal class SegmentIndex(
    val fetcher: SegmentFetcher,
    val segments: List<MediaSegment>,
    /** fMP4 segments: the video track's id; -1 for MPEG-TS segments. */
    val fmp4TrackId: Long,
)

/** Manifests and segments of one HLS or DASH stream through playback's client, against one download budget. */
internal class SegmentFetcher(
    private val client: OkHttpClient,
    private val headers: Map<String, String>,
    private val userAgent: String,
    private val budgetBytes: Long,
) : Closeable {
    private val used = AtomicLong()
    // Segment reads in progress: keyframes are read on several threads at once, and closing stops them all.
    private val sources = java.util.concurrent.ConcurrentHashMap.newKeySet<HttpRangeSource>()
    @Volatile private var call: Call? = null
    @Volatile private var closed = false

    val bytesUsed: Long get() = used.get()

    /** Longest any one request may take, start to finish (0: the client's own timeouts); segment reads too. */
    @Volatile var callTimeoutMs = 0L

    /** A playlist's or MPD's text and its final URL (after redirects; relative URIs resolve against it). */
    fun manifest(url: HttpUrl): Pair<HttpUrl, String> {
        if (closed) throw IOException("closed")
        val request = Request.Builder().url(url).apply {
            headers.forEach { (name, value) -> if (name.isNotBlank() && !name.equals("Range", true)) header(name, value) }
            if (headers.keys.none { it.equals("User-Agent", true) }) header("User-Agent", userAgent)
        }.build()
        val c = client.newCall(request)
        callTimeoutMs.takeIf { it > 0 }?.let { c.timeout().timeout(it, java.util.concurrent.TimeUnit.MILLISECONDS) }
        call = c
        if (closed) c.cancel()
        c.execute().use { response ->
            if (!response.isSuccessful) throw IOException("manifest ${response.code}")
            val body = response.body ?: throw IOException("no body")
            val bytes = body.byteStream().use { input ->
                val out = ByteArrayOutputStream()
                val chunk = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    out.write(chunk, 0, n)
                    if (out.size() > MAX_MANIFEST_BYTES) throw UnsupportedMediaException("manifest too large")
                }
                out.toByteArray()
            }
            if (used.addAndGet(bytes.size.toLong()) > budgetBytes) throw UnsupportedMediaException("budget used up")
            return response.request.url to String(bytes, Charsets.UTF_8)
        }
    }

    /**
     * Byte reads of [segment]'s URL (ranges, or the start of the file where a server ignores Range). Other reads
     * carry on; [release] it when done.
     */
    fun open(segment: MediaSegment): RangeSource {
        if (closed) throw IOException("closed")
        val source = HttpRangeSource(client, segment.url, headers, userAgent, budgetBytes, used, plainPrefix = true)
        source.callTimeoutMs = callTimeoutMs
        sources += source
        if (closed) source.close()
        return source
    }

    /** A read from [open] is done. */
    fun release(source: RangeSource) {
        (source as? HttpRangeSource)?.let { sources -= it }
    }

    override fun close() {
        closed = true
        call?.cancel()
        sources.forEach { it.close() }
        sources.clear()
    }

    private companion object {
        const val MAX_MANIFEST_BYTES = 4 * 1024 * 1024
    }
}

/**
 * HLS and DASH share this: every segment starts on a keyframe, so segment starts make the keyframe table, and a
 * thumbnail is the first frame of a segment (MPEG-TS, or fMP4 with an init segment).
 */
internal object SegmentedIndex {
    private val VIDEO_CODECS = setOf("avc1", "avc3", "hvc1", "hev1", "dvh1", "dvhe", "av01", "vp09")

    fun build(
        container: Container,
        fetcher: SegmentFetcher,
        segments: List<MediaSegment>,
        startsUs: LongArray,
        init: MediaSegment?,
        width: Int,
        height: Int,
        codecs: String?,
    ): MediaIndex {
        if (segments.size < 2) throw UnsupportedMediaException("too few segments")
        val track: VideoTrackInfo
        var trackId = -1L
        if (init != null) {
            val bytes = GrowingRead(fetcher.open(init), init.offset, initLength(init), 1024 * 1024).bytes
            val (t, id) = Mp4IndexReader.initTrack(bytes)
            track = t
            trackId = id
        } else {
            val first = segments[0]
            val w = GrowingRead(fetcher.open(first), first.offset, firstWindow(first, 64 * 1024), segmentCap(first, 2 * 1024 * 1024))
            val codec = TsDemux.videoCodec(w) ?: throw UnsupportedMediaException("no H.264/HEVC video in segments")
            track = VideoTrackInfo(
                codec = codec,
                width = width,
                height = height,
                displayAspect = 0.0,
                bitDepth = bitDepthFromCodecs(codecs, codec),
            )
        }
        val keyframes = KeyframeTable(
            timeUs = startsUs.copyOf(segments.size),
            offset = LongArray(segments.size) { it.toLong() },
            relativePosition = LongArray(segments.size) { -1L },
            size = IntArray(segments.size) { -1 },
        )
        return MediaIndex(container, track, keyframes, segments = SegmentIndex(fetcher, segments, trackId))
    }

    /** The keyframe at the start of segment [i]: Annex-B for MPEG-TS, a length-prefixed sample for fMP4. */
    fun readKeyframe(index: MediaIndex, i: Int, maxBytes: Int): ByteArray? {
        val segmented = index.segments ?: return null
        val segment = segmented.segments[index.keyframes.offset[i].toInt()]
        val hint = index.keyframeBytesHint.takeIf { it > 0 }?.let { it + it / 3 + 32 * 1024 } ?: (192 * 1024)
        val source = segmented.fetcher.open(segment)
        try {
            val w = GrowingRead(source, segment.offset, firstWindow(segment, hint), segmentCap(segment, maxBytes))
            val frame = if (segmented.fmp4TrackId >= 0) {
                Mp4IndexReader.firstFragmentSample(w, segmented.fmp4TrackId)
            } else {
                TsDemux.firstKeyframe(w)
            }
            return frame?.also { index.noteKeyframeSize(it.size) }
        } finally {
            segmented.fetcher.release(source)
        }
    }

    fun hasVideoCodec(codecs: String): Boolean = codecs.split(',').any { it.trim().take(4) in VIDEO_CODECS }

    private fun firstWindow(segment: MediaSegment, want: Int): Int =
        if (segment.length > 0) minOf(segment.length, want.toLong()).toInt() else want

    private fun segmentCap(segment: MediaSegment, cap: Int): Int =
        if (segment.length > 0) minOf(segment.length, cap.toLong()).toInt() else cap

    private fun initLength(init: MediaSegment): Int = if (init.length > 0) init.length.toInt().coerceAtMost(1024 * 1024) else 256 * 1024

    /** From the CODECS attribute: HEVC profile 2 and H.264 High 10 are 10-bit. Unknown HEVC counts as 10-bit so a hardware decoder is used. */
    private fun bitDepthFromCodecs(codecs: String?, codec: VideoCodec): Int {
        val entry = codecs?.split(',')?.map { it.trim() }?.firstOrNull { hasVideoCodec(it) }
        return when (codec) {
            VideoCodec.HEVC -> {
                val profile = entry?.split('.')?.getOrNull(1)?.trimStart('A', 'B', 'C')?.toIntOrNull()
                if (profile == 1) 8 else 10
            }
            VideoCodec.H264 -> if (entry?.split('.')?.getOrNull(1)?.take(2)?.toIntOrNull(16) == 110) 10 else 8
            else -> 8
        }
    }
}
