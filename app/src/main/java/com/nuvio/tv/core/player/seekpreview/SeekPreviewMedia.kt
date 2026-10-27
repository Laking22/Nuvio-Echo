package com.nuvio.tv.core.player.seekpreview

import java.io.ByteArrayOutputStream
import java.io.IOException

/** Blocking byte-range access to one media file. */
internal interface RangeSource {
    /** Up to [length] bytes at [offset]; fewer only at the end of the file. */
    fun read(offset: Long, length: Int): ByteArray

    /** File length once a response has stated it, else -1. */
    val totalLength: Long
}

/** Bytes from one file position on, fetched further only as parsing needs them. */
internal class GrowingRead(private val source: RangeSource, private val origin: Long, first: Int, private val max: Int) {
    var bytes: ByteArray = source.read(origin, first)
        private set

    /** True once bytes [0, until) are here; false past the cap or the end of the file. */
    fun ensure(until: Long): Boolean {
        if (until <= bytes.size) return true
        if (until > max) return false
        val want = (until - bytes.size).coerceAtLeast(64 * 1024L).coerceAtMost((max - bytes.size).toLong()).toInt()
        val more = source.read(origin + bytes.size, want)
        if (more.isEmpty()) return false
        bytes += more
        return until <= bytes.size
    }
}

/** The file can't give thumbnails (container, codec or server); not worth retrying for this stream. */
internal open class UnsupportedMediaException(message: String) : IOException(message)

/** The server answered a range request with something other than the range. */
internal class RangesRefusedException(code: Int) : UnsupportedMediaException("server doesn't serve ranges ($code)")

internal enum class VideoCodec(val mime: String) {
    H264("video/avc"),
    HEVC("video/hevc"),
    AV1("video/av01"),
    VP9("video/x-vnd.on2.vp9"),
}

/** What the decoder needs to know about the video track. */
internal class VideoTrackInfo(
    val codec: VideoCodec,
    val width: Int,
    val height: Int,
    /** Display width / height from the container, or 0 when it doesn't say. */
    val displayAspect: Double,
    val bitDepth: Int,
    /** H.264/HEVC: bytes in each NAL length prefix. */
    val nalLengthSize: Int = 4,
    /** H.264/HEVC parameter sets as Annex-B, put in front of every keyframe. */
    val parameterSets: ByteArray = ByteArray(0),
    /** MediaFormat csd-0, csd-1, ... */
    val csd: List<ByteArray> = emptyList(),
    /** ISO/IEC 23091-2 transfer: 16 = PQ, 18 = HLG, 0 = not stated. */
    val transfer: Int = 0,
    /** Brightest the content gets in nits (MaxCLL or mastering peak), 0 when not stated. */
    val peakNits: Int = 0,
    /** Matroska track number (0 for MP4). */
    val trackNumber: Long = 0,
    /** Matroska header stripping: bytes removed from the front of every frame. */
    val strippedHeader: ByteArray = ByteArray(0),
) {
    /** This track with H.264/HEVC parameter sets found in the stream itself (its header had none). */
    fun withParameterSets(sets: ByteArray): VideoTrackInfo {
        val nals = AccessUnits.nalUnits(sets)
        val csd = if (codec == VideoCodec.H264) {
            // csd-0 the SPS, csd-1 the PPS, as MediaCodec wants them for H.264.
            listOf(nals.filter { it.type == 7 }, nals.filter { it.type == 8 })
                .map { group -> group.fold(ByteArray(0)) { acc, nal -> acc + AccessUnits.START_CODE + nal.bytes } }
                .filter { it.isNotEmpty() }
        } else {
            listOf(sets)
        }
        return VideoTrackInfo(
            codec, width, height, displayAspect, bitDepth, nalLengthSize, sets, csd, transfer, peakNits,
            trackNumber, strippedHeader,
        )
    }

    val needsParameterSets: Boolean
        get() = (codec == VideoCodec.H264 || codec == VideoCodec.HEVC) && parameterSets.isEmpty()
}

/** Keyframes in time order: Matroska cluster positions, or MP4 exact sample ranges. */
internal class KeyframeTable(
    val timeUs: LongArray,
    val offset: LongArray,
    /** Matroska CueRelativePosition, -1 when absent. */
    val relativePosition: LongArray,
    /** MP4 sample size, -1 for Matroska. */
    val size: IntArray,
) {
    val count: Int get() = timeUs.size

    /** The keyframe closest to [us], which is where a closest-keyframe seek lands. */
    fun nearest(us: Long): Int {
        if (count == 0) return -1
        var lo = 0
        var hi = count - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (timeUs[mid] < us) lo = mid + 1 else hi = mid
        }
        if (lo > 0 && us - timeUs[lo - 1] <= timeUs[lo] - us) return lo - 1
        return lo
    }
}

internal enum class Container { MATROSKA, MP4, HLS, DASH }

/** A Matroska file without Cues: where its clusters start, its length and duration. */
internal class CuelessLayout(val segmentDataStart: Long, val fileLength: Long, val durationUs: Long)

internal class MediaIndex(
    val container: Container,
    val video: VideoTrackInfo,
    val keyframes: KeyframeTable,
    /** Matroska TimestampScale in ns. */
    val timestampScaleNs: Long = 1_000_000L,
    /** HLS and DASH: the segments the keyframe table points into. */
    val segments: SegmentIndex? = null,
    /** Matroska without Cues: what keyframe positions are estimated from. */
    val cueless: CuelessLayout? = null,
    /** The container lists an audio track (any codec); null when only the video track was read. */
    val hasAudio: Boolean? = null,
) {
    /**
     * Recent keyframe size (the largest, decaying an eighth per keyframe), so a Matroska keyframe usually
     * comes in one request without over-reading. Keyframes may be read on several threads at once; a lost update
     * only costs one extra request.
     */
    @Volatile var keyframeBytesHint = 0
        private set

    fun noteKeyframeSize(bytes: Int) {
        keyframeBytesHint = maxOf(bytes, keyframeBytesHint - keyframeBytesHint / 8)
    }
}

/** Reads a file's index from its first bytes, whichever container it is. */
internal object MediaIndexReader {
    const val HEAD_BYTES = 256 * 1024

    fun read(source: RangeSource, fallbackDurationUs: Long = 0L, head: ByteArray = source.read(0, HEAD_BYTES)): MediaIndex {
        if (head.size < 16) throw UnsupportedMediaException("file too short")
        if (String(head, 0, 7, Charsets.ISO_8859_1) == "#EXTM3U") throw HlsPlaylistFound()
        if (looksLikeMpd(head)) throw DashManifestFound()
        return when {
            MatroskaIndexReader.looksLikeMatroska(head) -> MatroskaIndexReader.read(source, head, fallbackDurationUs)
            Mp4IndexReader.looksLikeMp4(head) -> Mp4IndexReader.read(source, head)
            else -> throw UnsupportedMediaException("container not MKV or MP4")
        }
    }

    /** The keyframe's sample as stored in the file, or null when it can't be found. */
    fun readKeyframe(index: MediaIndex, source: RangeSource, i: Int, maxBytes: Int): ByteArray? =
        when (index.container) {
            Container.MP4 -> {
                val size = index.keyframes.size[i]
                if (size <= 0 || size > maxBytes) null
                else source.read(index.keyframes.offset[i], size).takeIf { it.size == size }
            }
            Container.MATROSKA -> MatroskaIndexReader.readKeyframe(index, source, i, maxBytes)
            Container.HLS, Container.DASH -> SegmentedIndex.readKeyframe(index, i, maxBytes)
        }

    /** An XML document whose first element is `MPD` (DASH served without a telling extension or MIME type). */
    private fun looksLikeMpd(head: ByteArray): Boolean {
        val text = String(head, 0, minOf(head.size, 2048), Charsets.UTF_8).trimStart { it == '\uFEFF' || it.isWhitespace() }
        if (!text.startsWith("<")) return false
        return Regex("<(?:[A-Za-z_][\\w.-]*:)?MPD[\\s>]").containsMatchIn(text)
    }
}

/** Turns a stored keyframe into one decoder input buffer. */
internal object AccessUnits {
    val START_CODE = byteArrayOf(0, 0, 0, 1)

    class Nal(val type: Int, val bytes: ByteArray)

    /** The NAL units of an Annex-B buffer (H.264 or HEVC; the type is read both ways and sorted out by callers). */
    fun nalUnits(annexB: ByteArray, hevc: Boolean? = null): List<Nal> {
        val out = ArrayList<Nal>()
        var start = -1
        var i = 0
        fun emit(end: Int) {
            var e = end
            while (e > start && annexB[e - 1].toInt() == 0) e--
            if (start in 0 until e) {
                val first = annexB[start].toInt() and 0xFF
                val type = if (hevc == true) (first shr 1) and 0x3F else first and 0x1F
                out.add(Nal(type, annexB.copyOfRange(start, e)))
            }
        }
        while (i + 2 < annexB.size) {
            if (annexB[i].toInt() == 0 && annexB[i + 1].toInt() == 0 && annexB[i + 2].toInt() == 1) {
                if (start >= 0) emit(i)
                i += 3
                start = i
            } else {
                i++
            }
        }
        if (start >= 0) emit(annexB.size)
        return out
    }

    /** The parameter sets (H.264 SPS/PPS, HEVC VPS/SPS/PPS) carried inside an Annex-B keyframe, deduplicated. */
    fun parameterSets(track: VideoTrackInfo, annexB: ByteArray): ByteArray {
        val hevc = track.codec == VideoCodec.HEVC
        val wanted = if (hevc) setOf(32, 33, 34) else setOf(7, 8)
        val seen = ArrayList<ByteArray>()
        for (nal in nalUnits(annexB, hevc)) {
            if (nal.type in wanted && seen.none { it.contentEquals(nal.bytes) }) seen += nal.bytes
        }
        // VPS, SPS, PPS order, as decoders expect.
        val ordered = seen.sortedBy { if (hevc) (it[0].toInt() shr 1) and 0x3F else it[0].toInt() and 0x1F }
        return ordered.fold(ByteArray(0)) { acc, nal -> acc + START_CODE + nal }
    }

    fun build(track: VideoTrackInfo, stored: ByteArray): ByteArray? {
        val sample = if (track.strippedHeader.isEmpty()) stored else track.strippedHeader + stored
        return when (track.codec) {
            VideoCodec.H264, VideoCodec.HEVC -> toAnnexB(track, sample)
            VideoCodec.AV1, VideoCodec.VP9 -> sample
        }
    }

    private fun toAnnexB(track: VideoTrackInfo, sample: ByteArray): ByteArray? {
        val out = ByteArrayOutputStream(sample.size + track.parameterSets.size + 64)
        out.write(track.parameterSets)
        if (sample.size >= 4 && sample[0].toInt() == 0 && sample[1].toInt() == 0 &&
            (sample[2].toInt() == 1 || (sample[2].toInt() == 0 && sample[3].toInt() == 1))
        ) {
            // Already Annex-B (some muxers do this despite the spec).
            out.write(sample)
            return out.toByteArray()
        }
        val n = track.nalLengthSize
        val hevc = track.codec == VideoCodec.HEVC
        var p = 0
        var nals = 0
        while (p + n <= sample.size) {
            var len = 0L
            for (k in 0 until n) len = (len shl 8) or (sample[p + k].toLong() and 0xFF)
            p += n
            if (len <= 0 || p + len > sample.size) break
            val type = if (hevc) (sample[p].toInt() shr 1) and 0x3F else sample[p].toInt() and 0x1F
            // Dolby Vision's RPU (62) and enhancement layer (63) mean nothing to a plain HEVC decoder.
            if (!(hevc && (type == 62 || type == 63))) {
                out.write(START_CODE)
                out.write(sample, p, len.toInt())
                nals++
            }
            p += len.toInt()
        }
        return if (nals > 0) out.toByteArray() else null
    }
}

/** Decoder configuration records (avcC, hvcC, av1C) as the decoder wants them. */
internal object CodecConfig {
    private val START_CODE = byteArrayOf(0, 0, 0, 1)

    class Nal(val nalLengthSize: Int, val parameterSets: ByteArray, val csd: List<ByteArray>, val bitDepth: Int)

    fun avcC(cp: ByteArray): Nal {
        if (cp.size < 7) throw UnsupportedMediaException("short avcC")
        val lengthSize = (cp[4].toInt() and 0x03) + 1
        val sps = ByteArrayOutputStream()
        val pps = ByteArrayOutputStream()
        var bitDepth = 8
        var p = 5
        val spsCount = cp[p++].toInt() and 0x1F
        repeat(spsCount) { i ->
            val n = u16(cp, p)
            if (p + 2 + n > cp.size) throw UnsupportedMediaException("truncated avcC")
            if (i == 0) bitDepth = runCatching { avcBitDepth(cp, p + 2, n) }.getOrDefault(8)
            sps.write(START_CODE)
            sps.write(cp, p + 2, n)
            p += 2 + n
        }
        if (p < cp.size) {
            val ppsCount = cp[p++].toInt() and 0xFF
            repeat(ppsCount) {
                val n = u16(cp, p)
                if (p + 2 + n > cp.size) throw UnsupportedMediaException("truncated avcC")
                pps.write(START_CODE)
                pps.write(cp, p + 2, n)
                p += 2 + n
            }
        }
        val spsBytes = sps.toByteArray()
        val ppsBytes = pps.toByteArray()
        return Nal(lengthSize, spsBytes + ppsBytes, listOf(spsBytes, ppsBytes).filter { it.isNotEmpty() }, bitDepth)
    }

    fun hvcC(cp: ByteArray): Nal {
        if (cp.size < 23) throw UnsupportedMediaException("short hvcC")
        val bitDepth = (cp[17].toInt() and 0x07) + 8
        val lengthSize = (cp[21].toInt() and 0x03) + 1
        val out = ByteArrayOutputStream()
        var p = 23
        repeat(cp[22].toInt() and 0xFF) {
            if (p + 3 > cp.size) throw UnsupportedMediaException("truncated hvcC")
            val count = u16(cp, p + 1)
            p += 3
            repeat(count) {
                val n = u16(cp, p)
                if (p + 2 + n > cp.size) throw UnsupportedMediaException("truncated hvcC")
                out.write(START_CODE)
                out.write(cp, p + 2, n)
                p += 2 + n
            }
        }
        // Some remuxes leave the record empty and carry the sets in the stream; the engine finds them there.
        val sets = out.toByteArray()
        return Nal(lengthSize, sets, if (sets.isEmpty()) emptyList() else listOf(sets), bitDepth)
    }

    fun av1BitDepth(av1C: ByteArray): Int {
        if (av1C.size < 3) return 8
        val flags = av1C[2].toInt()
        return when {
            flags and 0x40 == 0 -> 8
            flags and 0x20 != 0 -> 12
            else -> 10
        }
    }

    /** bit_depth_luma from an H.264 SPS (with its NAL header byte); 8 below the High profiles. */
    private fun avcBitDepth(b: ByteArray, off: Int, len: Int): Int {
        val r = BitReader(unescape(b, off + 1, len - 1))
        val profile = r.bits(8)
        r.bits(16)  // constraint flags, level
        r.ue()      // seq_parameter_set_id
        if (profile !in setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)) return 8
        if (r.ue() == 3) r.bits(1)
        return r.ue() + 8
    }

    private fun unescape(b: ByteArray, off: Int, len: Int): ByteArray {
        val out = ByteArrayOutputStream(len)
        var zeros = 0
        for (i in off until off + len) {
            val v = b[i].toInt() and 0xFF
            if (zeros >= 2 && v == 3) {
                zeros = 0
                continue
            }
            out.write(v)
            zeros = if (v == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    private class BitReader(private val d: ByteArray) {
        private var pos = 0
        fun bits(n: Int): Int {
            var v = 0
            repeat(n) {
                if (pos ushr 3 >= d.size) throw UnsupportedMediaException("short SPS")
                v = (v shl 1) or ((d[pos ushr 3].toInt() shr (7 - (pos and 7))) and 1)
                pos++
            }
            return v
        }

        fun ue(): Int {
            var zeros = 0
            while (bits(1) == 0) if (++zeros > 31) throw UnsupportedMediaException("bad SPS")
            return (1 shl zeros) - 1 + if (zeros > 0) bits(zeros) else 0
        }
    }

    private fun u16(b: ByteArray, p: Int): Int = ((b[p].toInt() and 0xFF) shl 8) or (b[p + 1].toInt() and 0xFF)
}
