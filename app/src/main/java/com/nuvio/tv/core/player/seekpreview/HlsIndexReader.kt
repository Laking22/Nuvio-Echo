package com.nuvio.tv.core.player.seekpreview

import okhttp3.HttpUrl
import java.io.ByteArrayOutputStream

/**
 * HLS (VOD): picks the I-frame playlist or the lightest video rendition, and treats each segment start as
 * a keyframe (HLS segments begin on one). MPEG-TS and fMP4 segments; encrypted and live streams are refused.
 */
internal object HlsIndexReader {
    private const val MIN_HEIGHT = 270
    private val ATTRIBUTE = Regex("([A-Z0-9-]+)=(\"[^\"]*\"|[^,]*)")

    private class Rendition(val uri: String, val bandwidth: Long, val height: Int, val width: Int, val codecs: String?)

    fun read(fetcher: SegmentFetcher, url: HttpUrl): MediaIndex {
        var (base, text) = fetcher.manifest(url)
        if (!text.trimStart().startsWith("#EXTM3U")) throw UnsupportedMediaException("not an HLS playlist")
        var chosen: Rendition? = null
        if (text.contains("#EXT-X-STREAM-INF") || text.contains("#EXT-X-I-FRAME-STREAM-INF")) {
            chosen = chooseRendition(text) ?: throw UnsupportedMediaException("no video rendition")
            val next = base.resolve(chosen.uri) ?: throw UnsupportedMediaException("bad rendition URI")
            val media = fetcher.manifest(next)
            base = media.first
            text = media.second
        }
        val media = parseMedia(base, text)
        return SegmentedIndex.build(
            Container.HLS, fetcher, media.segments, media.startsUs, media.init,
            chosen?.width ?: 0, chosen?.height ?: 0, chosen?.codecs,
        )
    }

    private fun attributes(line: String): Map<String, String> =
        ATTRIBUTE.findAll(line.substringAfter(':')).associate { it.groupValues[1] to it.groupValues[2].trim('"') }

    /** The I-frame playlist, else the lightest rendition, that is at least [MIN_HEIGHT] tall. */
    private fun chooseRendition(master: String): Rendition? {
        val lines = master.lines().map { it.trim() }
        val iFrames = ArrayList<Rendition>()
        val renditions = ArrayList<Rendition>()
        for ((i, line) in lines.withIndex()) {
            when {
                line.startsWith("#EXT-X-I-FRAME-STREAM-INF:") -> rendition(attributes(line), attributes(line)["URI"])?.let { iFrames += it }
                line.startsWith("#EXT-X-STREAM-INF:") -> {
                    val uri = lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                    rendition(attributes(line), uri)?.let { renditions += it }
                }
            }
        }
        fun pick(list: List<Rendition>): Rendition? {
            val video = list.filter { r -> r.height > 0 || r.codecs?.let(SegmentedIndex::hasVideoCodec) == true }
            val sorted = video.sortedBy { it.bandwidth }
            return sorted.firstOrNull { it.height >= MIN_HEIGHT } ?: sorted.lastOrNull()
        }
        return pick(iFrames) ?: pick(renditions) ?: renditions.firstOrNull()
    }

    private fun rendition(attrs: Map<String, String>, uri: String?): Rendition? {
        if (uri.isNullOrBlank()) return null
        val resolution = attrs["RESOLUTION"]?.split('x')
        return Rendition(
            uri = uri,
            bandwidth = attrs["BANDWIDTH"]?.toLongOrNull() ?: Long.MAX_VALUE,
            width = resolution?.getOrNull(0)?.toIntOrNull() ?: 0,
            height = resolution?.getOrNull(1)?.toIntOrNull() ?: 0,
            codecs = attrs["CODECS"],
        )
    }

    private class Media(val segments: List<MediaSegment>, val startsUs: LongArray, val init: MediaSegment?)

    private fun parseMedia(base: HttpUrl, text: String): Media {
        val segments = ArrayList<MediaSegment>()
        val starts = ArrayList<Long>()
        var init: MediaSegment? = null
        var duration = 0.0
        var t = 0.0
        var rangeLength = -1L
        var rangeOffset = -1L
        val rangeEnds = HashMap<HttpUrl, Long>()
        var ended = false
        var vod = false
        for (raw in text.lines()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTINF:") -> duration = line.substringAfter(':').substringBefore(',').trim().toDoubleOrNull() ?: 0.0
                line.startsWith("#EXT-X-BYTERANGE:") -> {
                    val spec = line.substringAfter(':').trim()
                    rangeLength = spec.substringBefore('@').toLongOrNull() ?: -1L
                    rangeOffset = spec.substringAfter('@', "").toLongOrNull() ?: -1L
                }
                line.startsWith("#EXT-X-KEY:") -> {
                    val method = attributes(line)["METHOD"]
                    if (method != null && method != "NONE") throw UnsupportedMediaException("encrypted HLS")
                }
                line.startsWith("#EXT-X-MAP:") -> {
                    val attrs = attributes(line)
                    val uri = attrs["URI"]?.let { base.resolve(it) } ?: throw UnsupportedMediaException("bad EXT-X-MAP")
                    val range = attrs["BYTERANGE"]
                    init = MediaSegment(
                        uri,
                        range?.substringAfter('@', "")?.toLongOrNull() ?: 0L,
                        range?.substringBefore('@')?.toLongOrNull() ?: -1L,
                    )
                }
                line == "#EXT-X-ENDLIST" -> ended = true
                line.startsWith("#EXT-X-PLAYLIST-TYPE:") -> vod = line.substringAfter(':').trim() == "VOD"
                line.startsWith("#") -> Unit
                else -> {
                    val url = base.resolve(line) ?: throw UnsupportedMediaException("bad segment URI")
                    val segment = if (rangeLength > 0) {
                        val offset = if (rangeOffset >= 0) rangeOffset else rangeEnds[url] ?: 0L
                        rangeEnds[url] = offset + rangeLength
                        MediaSegment(url, offset, rangeLength)
                    } else {
                        MediaSegment(url, 0L, -1L)
                    }
                    segments += segment
                    starts += (t * 1_000_000).toLong()
                    t += duration
                    duration = 0.0
                    rangeLength = -1L
                    rangeOffset = -1L
                }
            }
        }
        if (!ended && !vod) throw UnsupportedMediaException("live HLS")
        return Media(segments, starts.toLongArray(), init)
    }
}

/** Just enough MPEG-TS to find the video stream and its first keyframe access unit. */
internal object TsDemux {
    private const val PACKET = 188
    private const val SCAN_LIMIT = 6 * 1024 * 1024

    /** The video codec named in the PMT (every HLS segment starts with PAT and PMT). */
    fun videoCodec(w: GrowingRead): VideoCodec? = scan(w, wantKeyframe = false)?.first

    /** The first H.264 IDR / HEVC IRAP access unit, as Annex-B. */
    fun firstKeyframe(w: GrowingRead): ByteArray? = scan(w, wantKeyframe = true)?.second

    private fun scan(w: GrowingRead, wantKeyframe: Boolean): Pair<VideoCodec, ByteArray>? {
        if (!w.ensure(PACKET * 2L)) return null
        var pos = (0 until PACKET).firstOrNull {
            w.bytes[it] == 0x47.toByte() && w.bytes[it + PACKET] == 0x47.toByte()
        } ?: return null
        var pmtPid = -1
        var videoPid = -1
        var codec: VideoCodec? = null
        var pes: ByteArrayOutputStream? = null
        while (pos < SCAN_LIMIT && w.ensure(pos + PACKET.toLong())) {
            val b = w.bytes
            if (b[pos] != 0x47.toByte()) return null
            val unitStart = b[pos + 1].toInt() and 0x40 != 0
            val pid = ((b[pos + 1].toInt() and 0x1F) shl 8) or (b[pos + 2].toInt() and 0xFF)
            val adaptation = (b[pos + 3].toInt() shr 4) and 0x3
            val end = pos + PACKET
            var p = pos + 4
            if (adaptation and 0x2 != 0) p += 1 + (b[p].toInt() and 0xFF)
            if (adaptation and 0x1 != 0 && p < end) {
                when {
                    pid == 0 && unitStart -> pmtPid = programMapPid(b, p, end)
                    pid == pmtPid && unitStart && videoPid < 0 -> {
                        val found = videoStream(b, p, end)
                        if (found != null) {
                            videoPid = found.first
                            codec = found.second
                            if (!wantKeyframe) return found.second to ByteArray(0)
                        }
                    }
                    pid == videoPid -> {
                        if (unitStart) {
                            val done = pes?.toByteArray()?.let(::pesPayload)
                            if (done != null && isKeyframe(done, codec!!)) return codec!! to done
                            pes = ByteArrayOutputStream()
                        }
                        pes?.write(b, p, end - p)
                    }
                }
            }
            pos += PACKET
        }
        // The segment (or I-frame byte range) ended: its last access unit is complete.
        val last = pes?.toByteArray()?.let(::pesPayload) ?: return null
        val c = codec ?: return null
        return if (isKeyframe(last, c)) c to last else null
    }

    /** The first program's PMT PID from a PAT section starting at [p]. */
    private fun programMapPid(b: ByteArray, p: Int, end: Int): Int {
        val t = p + 1 + (b[p].toInt() and 0xFF)
        if (t + 8 > end) return -1
        val sectionEnd = minOf(t + 3 + (((b[t + 1].toInt() and 0x0F) shl 8) or (b[t + 2].toInt() and 0xFF)) - 4, end)
        var e = t + 8
        while (e + 4 <= sectionEnd) {
            val program = ((b[e].toInt() and 0xFF) shl 8) or (b[e + 1].toInt() and 0xFF)
            if (program != 0) return ((b[e + 2].toInt() and 0x1F) shl 8) or (b[e + 3].toInt() and 0xFF)
            e += 4
        }
        return -1
    }

    /** The first H.264 (0x1B) or HEVC (0x24) stream of a PMT section starting at [p]. */
    private fun videoStream(b: ByteArray, p: Int, end: Int): Pair<Int, VideoCodec>? {
        val t = p + 1 + (b[p].toInt() and 0xFF)
        if (t + 12 > end) return null
        val sectionEnd = minOf(t + 3 + (((b[t + 1].toInt() and 0x0F) shl 8) or (b[t + 2].toInt() and 0xFF)) - 4, end)
        var e = t + 12 + (((b[t + 10].toInt() and 0x0F) shl 8) or (b[t + 11].toInt() and 0xFF))
        while (e + 5 <= sectionEnd) {
            val type = b[e].toInt() and 0xFF
            val pid = ((b[e + 1].toInt() and 0x1F) shl 8) or (b[e + 2].toInt() and 0xFF)
            when (type) {
                0x1B -> return pid to VideoCodec.H264
                0x24 -> return pid to VideoCodec.HEVC
            }
            e += 5 + (((b[e + 3].toInt() and 0x0F) shl 8) or (b[e + 4].toInt() and 0xFF))
        }
        return null
    }

    /** A PES packet's elementary data (after its header). */
    private fun pesPayload(pes: ByteArray): ByteArray? {
        if (pes.size < 9 || pes[0].toInt() != 0 || pes[1].toInt() != 0 || pes[2].toInt() != 1) return null
        val start = 9 + (pes[8].toInt() and 0xFF)
        return if (start < pes.size) pes.copyOfRange(start, pes.size) else null
    }

    private fun isKeyframe(annexB: ByteArray, codec: VideoCodec): Boolean {
        val hevc = codec == VideoCodec.HEVC
        return AccessUnits.nalUnits(annexB, hevc).any { if (hevc) it.type in 16..21 else it.type == 5 }
    }
}
