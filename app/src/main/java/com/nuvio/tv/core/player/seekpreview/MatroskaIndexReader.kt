package com.nuvio.tv.core.player.seekpreview

/** One EBML element header: its id (with the length marker), data size (-1 = unknown) and header length. */
internal class EbmlHeader(val id: Long, val size: Long, val headerLength: Int)

internal object Ebml {
    /** The element header at [p], or null when the buffer ends inside it. */
    fun header(b: ByteArray, p: Int, end: Int = b.size): EbmlHeader? {
        if (p >= end) return null
        val first = b[p].toInt() and 0xFF
        if (first == 0) throw UnsupportedMediaException("bad EBML id")
        val idLength = Integer.numberOfLeadingZeros(first) - 23
        if (idLength > 4) throw UnsupportedMediaException("bad EBML id")
        if (p + idLength >= end) return null
        var id = 0L
        for (i in 0 until idLength) id = (id shl 8) or (b[p + i].toLong() and 0xFF)
        val sizeAt = p + idLength
        val size = vint(b, sizeAt, end) ?: return null
        return EbmlHeader(id, size.first, idLength + size.second)
    }

    /** A size-style vint (marker removed) and its length; value -1 for the all-ones "unknown" size. */
    fun vint(b: ByteArray, p: Int, end: Int = b.size): Pair<Long, Int>? {
        if (p >= end) return null
        val first = b[p].toInt() and 0xFF
        if (first == 0) throw UnsupportedMediaException("bad EBML size")
        val length = Integer.numberOfLeadingZeros(first) - 23
        if (p + length > end) return null
        val mask = 0xFF shr length
        var value = (first and mask).toLong()
        var allOnes = (first and mask) == mask
        for (i in 1 until length) {
            val v = b[p + i].toInt() and 0xFF
            value = (value shl 8) or v.toLong()
            if (v != 0xFF) allOnes = false
        }
        return (if (allOnes) -1L else value) to length
    }

    class Child(val id: Long, val start: Int, val dataStart: Int, val dataEnd: Int)

    /** Complete children of b[start, end); stops at the first one that runs past [end]. */
    fun children(b: ByteArray, start: Int, end: Int): List<Child> {
        val out = ArrayList<Child>()
        var p = start
        while (p < end) {
            val h = header(b, p, end) ?: break
            val data = p + h.headerLength
            val dataEnd = if (h.size < 0) end.toLong() else data + h.size
            if (dataEnd > end) break
            out.add(Child(h.id, p, data, dataEnd.toInt()))
            p = dataEnd.toInt()
        }
        return out
    }

    fun uint(b: ByteArray, a: Int, e: Int): Long {
        var v = 0L
        for (i in a until minOf(e, a + 8)) v = (v shl 8) or (b[i].toLong() and 0xFF)
        return v
    }

    fun float(b: ByteArray, a: Int, e: Int): Double = when (e - a) {
        4 -> java.lang.Float.intBitsToFloat(uint(b, a, e).toInt()).toDouble()
        8 -> java.lang.Double.longBitsToDouble(uint(b, a, e))
        else -> 0.0
    }

    fun ascii(b: ByteArray, a: Int, e: Int): String = String(b, a, e - a, Charsets.ISO_8859_1).trimEnd('\u0000')
}

/** Matroska/WebM: the video track from Tracks and keyframe positions from Cues, read in bounded ranges. */
internal object MatroskaIndexReader {
    private const val ID_EBML = 0x1A45DFA3L
    private const val ID_DOCTYPE = 0x4282L
    private const val ID_SEGMENT = 0x18538067L
    private const val ID_SEEKHEAD = 0x114D9B74L
    private const val ID_SEEK = 0x4DBBL
    private const val ID_SEEK_ID = 0x53ABL
    private const val ID_SEEK_POSITION = 0x53ACL
    private const val ID_INFO = 0x1549A966L
    private const val ID_TIMESTAMP_SCALE = 0x2AD7B1L
    private const val ID_TRACKS = 0x1654AE6BL
    private const val ID_TRACK_ENTRY = 0xAEL
    private const val ID_TRACK_NUMBER = 0xD7L
    private const val ID_TRACK_TYPE = 0x83L
    private const val TRACK_TYPE_AUDIO = 2L
    private const val ID_CODEC_ID = 0x86L
    private const val ID_CODEC_PRIVATE = 0x63A2L
    private const val ID_CONTENT_ENCODINGS = 0x6D80L
    private const val ID_CONTENT_ENCODING = 0x6240L
    private const val ID_CONTENT_COMPRESSION = 0x5034L
    private const val ID_CONTENT_COMP_ALGO = 0x4254L
    private const val ID_CONTENT_COMP_SETTINGS = 0x4255L
    private const val ID_CONTENT_ENCRYPTION = 0x5035L
    private const val ID_VIDEO = 0xE0L
    private const val ID_PIXEL_WIDTH = 0xB0L
    private const val ID_PIXEL_HEIGHT = 0xBAL
    private const val ID_DISPLAY_WIDTH = 0x54B0L
    private const val ID_DISPLAY_HEIGHT = 0x54BAL
    private const val ID_DISPLAY_UNIT = 0x54B2L
    private const val ID_COLOUR = 0x55B0L
    private const val ID_TRANSFER = 0x55BAL
    private const val ID_MAX_CLL = 0x55BCL
    private const val ID_MASTERING = 0x55D0L
    private const val ID_LUMINANCE_MAX = 0x55D9L
    private const val ID_BLOCK_ADDITION_MAPPING = 0x41E4L
    private const val ID_BLOCK_ADD_ID_TYPE = 0x41E7L
    private const val ID_BLOCK_ADD_ID_EXTRA = 0x41EDL
    private const val ID_CUES = 0x1C53BB6BL
    private const val ID_CUE_POINT = 0xBBL
    private const val ID_CUE_TIME = 0xB3L
    private const val ID_CUE_TRACK_POSITIONS = 0xB7L
    private const val ID_CUE_TRACK = 0xF7L
    private const val ID_CUE_CLUSTER_POSITION = 0xF1L
    private const val ID_CUE_RELATIVE_POSITION = 0xF0L
    private const val ID_CLUSTER = 0x1F43B675L
    private const val ID_CLUSTER_TIMESTAMP = 0xE7L
    private const val ID_CRC32 = 0xBFL
    private const val ID_DURATION = 0x4489L
    private const val ID_SIMPLE_BLOCK = 0xA3L
    private const val ID_BLOCK_GROUP = 0xA0L
    private const val ID_BLOCK = 0xA1L
    private const val ID_REFERENCE_BLOCK = 0xFBL
    private const val ID_CHAPTERS = 0x1043A770L
    private const val ID_EDITION_ENTRY = 0x45B9L
    private const val ID_EDITION_FLAG_HIDDEN = 0x45BDL
    private const val ID_EDITION_FLAG_DEFAULT = 0x45DBL
    private const val ID_CHAPTER_ATOM = 0xB6L
    private const val ID_CHAPTER_TIME_START = 0x91L
    private const val ID_CHAPTER_TIME_END = 0x92L
    private const val ID_CHAPTER_FLAG_HIDDEN = 0x98L
    private const val ID_CHAPTER_FLAG_ENABLED = 0x4598L
    private const val ID_CHAPTER_SEGMENT_UID = 0x6E67L
    private const val ID_CHAPTER_DISPLAY = 0x80L
    private const val ID_CHAP_STRING = 0x85L

    private const val MAX_META_BYTES = 4 * 1024 * 1024
    private const val MAX_CUES_BYTES = 12 * 1024 * 1024
    private const val MAX_CHAPTERS_BYTES = 512 * 1024
    private const val FIRST_WINDOW = 192 * 1024
    private const val SCAN_WINDOW = 512 * 1024
    private const val CUELESS_WINDOW = 384 * 1024
    // Live-muxed files can have a cluster every 0.1 s; the byte cap is what really bounds a walk.
    private const val MAX_WALK_CLUSTERS = 2000
    /** Without Cues, thumbnails sit on 10 s slots, each landing on a keyframe within this of its slot. */
    const val CUELESS_SLOT_US = 10_000_000L
    private const val CUELESS_TOLERANCE_US = 15_000_000L

    fun looksLikeMatroska(head: ByteArray): Boolean = head.size >= 4 && Ebml.uint(head, 0, 4) == ID_EBML

    fun read(source: RangeSource, head: ByteArray, fallbackDurationUs: Long = 0L): MediaIndex {
        val ebml = Ebml.header(head, 0) ?: throw UnsupportedMediaException("short EBML header")
        val ebmlEnd = ebml.headerLength + ebml.size.toInt()
        if (ebml.size < 0 || ebmlEnd > head.size) throw UnsupportedMediaException("bad EBML header")
        Ebml.children(head, ebml.headerLength, ebmlEnd).firstOrNull { it.id == ID_DOCTYPE }?.let {
            val docType = Ebml.ascii(head, it.dataStart, it.dataEnd)
            if (docType != "matroska" && docType != "webm") throw UnsupportedMediaException("doctype $docType")
        }
        val segment = Ebml.header(head, ebmlEnd) ?: throw UnsupportedMediaException("short header")
        if (segment.id != ID_SEGMENT) throw UnsupportedMediaException("no Segment")
        val segmentData = ebmlEnd + segment.headerLength

        val positions = HashMap<Long, Long>()
        var info: ByteArray? = null
        var tracks: ByteArray? = null
        var cues: ByteArray? = null
        val moreSeekHeads = ArrayList<Long>()
        for (el in Ebml.children(head, segmentData, head.size)) {
            when (el.id) {
                ID_SEEKHEAD -> readSeekHead(head, el.dataStart, el.dataEnd, segmentData.toLong(), positions, moreSeekHeads)
                ID_INFO -> info = head.copyOfRange(el.dataStart, el.dataEnd)
                ID_TRACKS -> tracks = head.copyOfRange(el.dataStart, el.dataEnd)
                ID_CUES -> cues = head.copyOfRange(el.dataStart, el.dataEnd)
                ID_CLUSTER -> break
            }
        }
        // A SeekHead can point at a second one (mkvmerge writes one at the end).
        for (pos in moreSeekHeads.take(2)) {
            if (ID_INFO in positions && ID_TRACKS in positions && ID_CUES in positions) break
            val body = runCatching { fetchElement(source, pos, ID_SEEKHEAD, 64 * 1024) }.getOrNull() ?: continue
            readSeekHead(body, 0, body.size, segmentData.toLong(), positions, null)
        }
        if (info == null) info = positions[ID_INFO]?.let { fetchElement(source, it, ID_INFO, MAX_META_BYTES) }
        if (tracks == null) tracks = positions[ID_TRACKS]?.let { fetchElement(source, it, ID_TRACKS, MAX_META_BYTES) }
        if (tracks == null) throw UnsupportedMediaException("no Tracks")
        val video = videoTrack(tracks)
        val hasAudio = Ebml.children(tracks, 0, tracks.size).any { entry ->
            entry.id == ID_TRACK_ENTRY && Ebml.children(tracks, entry.dataStart, entry.dataEnd)
                .any { it.id == ID_TRACK_TYPE && Ebml.uint(tracks, it.dataStart, it.dataEnd) == TRACK_TYPE_AUDIO }
        }
        var timestampScale = 1_000_000L
        var durationTicks = 0.0
        info?.let { body ->
            for (el in Ebml.children(body, 0, body.size)) {
                if (el.id == ID_TIMESTAMP_SCALE) {
                    timestampScale = Ebml.uint(body, el.dataStart, el.dataEnd).takeIf { v -> v > 0 } ?: timestampScale
                } else if (el.id == ID_DURATION) {
                    durationTicks = Ebml.float(body, el.dataStart, el.dataEnd)
                }
            }
        }
        if (cues == null) cues = positions[ID_CUES]?.let { fetchElement(source, it, ID_CUES, MAX_CUES_BYTES) }
        if (cues == null) {
            val durationUs = (durationTicks * timestampScale / 1000.0).toLong().takeIf { it > 0 } ?: fallbackDurationUs
            return cuelessIndex(source, video, segmentData.toLong(), timestampScale, durationUs, hasAudio)
        }
        val keyframes = cueTable(cues, segmentData.toLong(), timestampScale, video.trackNumber)
        if (keyframes.count < 2) throw UnsupportedMediaException("too few cues")
        return MediaIndex(Container.MATROSKA, video, keyframes, timestampScale, hasAudio = hasAudio)
    }

    /**
     * The chapters of the default edition (else the first shown one), top level only: from the Chapters element in
     * the first bytes, or where a SeekHead puts it. Needs no decodable video track, unlike [read].
     */
    fun readChapters(source: RangeSource, head: ByteArray): List<MediaChapter> {
        val ebml = Ebml.header(head, 0) ?: return emptyList()
        val ebmlEnd = ebml.headerLength + ebml.size.toInt()
        if (ebml.size < 0 || ebmlEnd > head.size) return emptyList()
        val segment = Ebml.header(head, ebmlEnd) ?: return emptyList()
        if (segment.id != ID_SEGMENT) return emptyList()
        val segmentData = ebmlEnd + segment.headerLength

        val positions = HashMap<Long, Long>()
        val moreSeekHeads = ArrayList<Long>()
        var chapters: ByteArray? = null
        for (el in Ebml.children(head, segmentData, head.size)) {
            when (el.id) {
                ID_SEEKHEAD -> readSeekHead(head, el.dataStart, el.dataEnd, segmentData.toLong(), positions, moreSeekHeads)
                ID_CHAPTERS -> chapters = head.copyOfRange(el.dataStart, el.dataEnd)
                ID_CLUSTER -> break
            }
        }
        if (chapters == null && ID_CHAPTERS !in positions) {
            for (pos in moreSeekHeads.take(2)) {
                val body = runCatching { fetchElement(source, pos, ID_SEEKHEAD, 64 * 1024) }.getOrNull() ?: continue
                readSeekHead(body, 0, body.size, segmentData.toLong(), positions, null)
                if (ID_CHAPTERS in positions) break
            }
        }
        val body = chapters
            ?: positions[ID_CHAPTERS]?.let { fetchElement(source, it, ID_CHAPTERS, MAX_CHAPTERS_BYTES) }
            ?: return emptyList()
        return chapterList(body)
    }

    private fun chapterList(b: ByteArray): List<MediaChapter> {
        fun flagSet(parent: Ebml.Child, id: Long) = Ebml.children(b, parent.dataStart, parent.dataEnd)
            .any { it.id == id && Ebml.uint(b, it.dataStart, it.dataEnd) == 1L }
        val editions = Ebml.children(b, 0, b.size).filter { it.id == ID_EDITION_ENTRY }
        val edition = editions.firstOrNull { flagSet(it, ID_EDITION_FLAG_DEFAULT) }
            ?: editions.firstOrNull { !flagSet(it, ID_EDITION_FLAG_HIDDEN) }
            ?: return emptyList()
        val out = ArrayList<MediaChapter>()
        for (atom in Ebml.children(b, edition.dataStart, edition.dataEnd)) {
            if (atom.id != ID_CHAPTER_ATOM) continue
            var startNs = -1L
            var endNs = -1L
            var shown = true
            var title: String? = null
            for (f in Ebml.children(b, atom.dataStart, atom.dataEnd)) {
                when (f.id) {
                    ID_CHAPTER_TIME_START -> startNs = Ebml.uint(b, f.dataStart, f.dataEnd)
                    ID_CHAPTER_TIME_END -> endNs = Ebml.uint(b, f.dataStart, f.dataEnd)
                    ID_CHAPTER_FLAG_HIDDEN -> if (Ebml.uint(b, f.dataStart, f.dataEnd) == 1L) shown = false
                    ID_CHAPTER_FLAG_ENABLED -> if (Ebml.uint(b, f.dataStart, f.dataEnd) == 0L) shown = false
                    // A chapter played from another file has no place on this one's timeline.
                    ID_CHAPTER_SEGMENT_UID -> shown = false
                    ID_CHAPTER_DISPLAY -> if (title == null) {
                        title = Ebml.children(b, f.dataStart, f.dataEnd).firstOrNull { it.id == ID_CHAP_STRING }
                            ?.let { String(b, it.dataStart, it.dataEnd - it.dataStart, Charsets.UTF_8).trimEnd('\u0000') }
                    }
                }
            }
            if (startNs >= 0 && shown) {
                out.add(MediaChapter(startNs / 1_000_000L, endNs.takeIf { it > startNs }?.div(1_000_000L), title.orEmpty().trim()))
            }
        }
        return out.sortedBy { it.startMs }
    }

    /** A file without Cues: 10 s slots placed by estimate once read (see [readCuelessKeyframe]). */
    private fun cuelessIndex(
        source: RangeSource,
        video: VideoTrackInfo,
        segmentData: Long,
        scaleNs: Long,
        durationUs: Long,
        hasAudio: Boolean
    ): MediaIndex {
        val length = source.totalLength
        if (durationUs <= 0 || length <= segmentData) throw UnsupportedMediaException("no Cues, and no length to estimate from")
        val slots = (durationUs / CUELESS_SLOT_US + 1).toInt().coerceIn(2, 6000)
        val table = KeyframeTable(
            timeUs = LongArray(slots) { it * CUELESS_SLOT_US },
            offset = LongArray(slots) { -1L },
            relativePosition = LongArray(slots) { -1L },
            size = IntArray(slots) { -1 },
        )
        return MediaIndex(
            Container.MATROSKA, video, table, scaleNs,
            cueless = CuelessLayout(segmentData, length, durationUs),
            hasAudio = hasAudio
        )
    }

    private fun readSeekHead(
        b: ByteArray, start: Int, end: Int, segmentData: Long,
        positions: MutableMap<Long, Long>, moreSeekHeads: MutableList<Long>?,
    ) {
        for (seek in Ebml.children(b, start, end)) {
            if (seek.id != ID_SEEK) continue
            var id = -1L
            var pos = -1L
            for (f in Ebml.children(b, seek.dataStart, seek.dataEnd)) {
                if (f.id == ID_SEEK_ID) id = Ebml.uint(b, f.dataStart, f.dataEnd)
                else if (f.id == ID_SEEK_POSITION) pos = Ebml.uint(b, f.dataStart, f.dataEnd)
            }
            if (id < 0 || pos < 0) continue
            if (id == ID_SEEKHEAD) moreSeekHeads?.add(segmentData + pos)
            else if (id !in positions) positions[id] = segmentData + pos
        }
    }

    /** The element at [pos] (which must have [expectedId]): its body only. One request when it fits the window. */
    private fun fetchElement(source: RangeSource, pos: Long, expectedId: Long, cap: Int): ByteArray {
        val first = source.read(pos, minOf(cap + 16, FIRST_WINDOW))
        val h = Ebml.header(first, 0) ?: throw UnsupportedMediaException("short element")
        if (h.id != expectedId) throw UnsupportedMediaException("SeekHead points at the wrong element")
        if (h.size <= 0 || h.size > cap) throw UnsupportedMediaException("element too large")
        val end = h.headerLength + h.size.toInt()
        if (end <= first.size) return first.copyOfRange(h.headerLength, end)
        val rest = source.read(pos + first.size, end - first.size)
        val body = first.copyOfRange(h.headerLength, first.size) + rest
        if (body.size.toLong() != h.size) throw UnsupportedMediaException("truncated element")
        return body
    }

    private fun videoTrack(t: ByteArray): VideoTrackInfo {
        for (entry in Ebml.children(t, 0, t.size)) {
            if (entry.id != ID_TRACK_ENTRY) continue
            var number = 0L
            var type = 0L
            var codecId = ""
            var codecPrivate: ByteArray? = null
            var width = 0
            var height = 0
            var displayWidth = 0L
            var displayHeight = 0L
            var displayUnit = 0L
            var transfer = 0
            var peak = 0
            var dvProfile = -1
            var stripped = ByteArray(0)
            var unsupportedEncoding = false
            for (f in Ebml.children(t, entry.dataStart, entry.dataEnd)) {
                when (f.id) {
                    ID_TRACK_NUMBER -> number = Ebml.uint(t, f.dataStart, f.dataEnd)
                    ID_TRACK_TYPE -> type = Ebml.uint(t, f.dataStart, f.dataEnd)
                    ID_CODEC_ID -> codecId = Ebml.ascii(t, f.dataStart, f.dataEnd)
                    ID_CODEC_PRIVATE -> codecPrivate = t.copyOfRange(f.dataStart, f.dataEnd)
                    ID_CONTENT_ENCODINGS -> {
                        val encoding = contentEncoding(t, f.dataStart, f.dataEnd)
                        if (encoding == null) unsupportedEncoding = true else stripped = encoding
                    }
                    ID_BLOCK_ADDITION_MAPPING -> {
                        var addType = 0L
                        var extra: ByteArray? = null
                        for (m in Ebml.children(t, f.dataStart, f.dataEnd)) {
                            if (m.id == ID_BLOCK_ADD_ID_TYPE) addType = Ebml.uint(t, m.dataStart, m.dataEnd)
                            else if (m.id == ID_BLOCK_ADD_ID_EXTRA) extra = t.copyOfRange(m.dataStart, m.dataEnd)
                        }
                        // 'dvcC' / 'dvvC': DOVIDecoderConfigurationRecord
                        if ((addType == 0x64766343L || addType == 0x64767643L) && extra != null && extra.size >= 5) {
                            dvProfile = (extra[2].toInt() and 0xFF) shr 1
                        }
                    }
                    ID_VIDEO -> for (v in Ebml.children(t, f.dataStart, f.dataEnd)) {
                        when (v.id) {
                            ID_PIXEL_WIDTH -> width = Ebml.uint(t, v.dataStart, v.dataEnd).toInt()
                            ID_PIXEL_HEIGHT -> height = Ebml.uint(t, v.dataStart, v.dataEnd).toInt()
                            ID_DISPLAY_WIDTH -> displayWidth = Ebml.uint(t, v.dataStart, v.dataEnd)
                            ID_DISPLAY_HEIGHT -> displayHeight = Ebml.uint(t, v.dataStart, v.dataEnd)
                            ID_DISPLAY_UNIT -> displayUnit = Ebml.uint(t, v.dataStart, v.dataEnd)
                            ID_COLOUR -> for (c in Ebml.children(t, v.dataStart, v.dataEnd)) {
                                when (c.id) {
                                    ID_TRANSFER -> transfer = Ebml.uint(t, c.dataStart, c.dataEnd).toInt()
                                    ID_MAX_CLL -> peak = maxOf(peak, Ebml.uint(t, c.dataStart, c.dataEnd).toInt())
                                    ID_MASTERING -> Ebml.children(t, c.dataStart, c.dataEnd)
                                        .firstOrNull { it.id == ID_LUMINANCE_MAX }
                                        ?.let { if (peak == 0) peak = Ebml.float(t, it.dataStart, it.dataEnd).toInt() }
                                }
                            }
                        }
                    }
                }
            }
            if (type != 1L) continue
            if (unsupportedEncoding) throw UnsupportedMediaException("compressed or encrypted video")
            // Profile 5 is IPT colour that only a Dolby Vision decoder can show.
            if (dvProfile == 5) throw UnsupportedMediaException("Dolby Vision profile 5")
            val aspect = if (displayWidth > 0 && displayHeight > 0 && (displayUnit == 0L || displayUnit == 3L)) {
                displayWidth.toDouble() / displayHeight
            } else 0.0
            return when {
                codecId.startsWith("V_MPEG4/ISO/AVC") || codecId.startsWith("V_MPEGH/ISO/HEVC") -> {
                    val cp = codecPrivate ?: throw UnsupportedMediaException("no CodecPrivate")
                    val hevc = codecId.startsWith("V_MPEGH")
                    val config = if (hevc) CodecConfig.hvcC(cp) else CodecConfig.avcC(cp)
                    VideoTrackInfo(
                        codec = if (hevc) VideoCodec.HEVC else VideoCodec.H264,
                        width = width, height = height, displayAspect = aspect, bitDepth = config.bitDepth,
                        nalLengthSize = config.nalLengthSize, parameterSets = config.parameterSets, csd = config.csd,
                        transfer = transfer, peakNits = peak, trackNumber = number, strippedHeader = stripped,
                    )
                }
                codecId == "V_AV1" -> VideoTrackInfo(
                    codec = VideoCodec.AV1, width = width, height = height, displayAspect = aspect,
                    bitDepth = codecPrivate?.let(CodecConfig::av1BitDepth) ?: 8,
                    csd = listOfNotNull(codecPrivate), transfer = transfer, peakNits = peak,
                    trackNumber = number, strippedHeader = stripped,
                )
                codecId == "V_VP9" -> VideoTrackInfo(
                    codec = VideoCodec.VP9, width = width, height = height, displayAspect = aspect,
                    bitDepth = 8, transfer = transfer, peakNits = peak, trackNumber = number, strippedHeader = stripped,
                )
                else -> throw UnsupportedMediaException("video codec $codecId")
            }
        }
        throw UnsupportedMediaException("no video track")
    }

    /** Header-stripping bytes (empty when none), or null for zlib/encryption, which aren't supported. */
    private fun contentEncoding(t: ByteArray, start: Int, end: Int): ByteArray? {
        var stripped = ByteArray(0)
        for (encoding in Ebml.children(t, start, end)) {
            if (encoding.id != ID_CONTENT_ENCODING) continue
            for (c in Ebml.children(t, encoding.dataStart, encoding.dataEnd)) {
                if (c.id == ID_CONTENT_ENCRYPTION) return null
                if (c.id != ID_CONTENT_COMPRESSION) continue
                var algo = 0L
                var settings = ByteArray(0)
                for (s in Ebml.children(t, c.dataStart, c.dataEnd)) {
                    if (s.id == ID_CONTENT_COMP_ALGO) algo = Ebml.uint(t, s.dataStart, s.dataEnd)
                    else if (s.id == ID_CONTENT_COMP_SETTINGS) settings = t.copyOfRange(s.dataStart, s.dataEnd)
                }
                if (algo != 3L) return null
                stripped = settings
            }
        }
        return stripped
    }

    private fun cueTable(c: ByteArray, segmentData: Long, scaleNs: Long, track: Long): KeyframeTable {
        val times = ArrayList<Long>()
        val clusters = ArrayList<Long>()
        val relatives = ArrayList<Long>()
        for (point in Ebml.children(c, 0, c.size)) {
            if (point.id != ID_CUE_POINT) continue
            var time = -1L
            var cluster = -1L
            var relative = -1L
            for (f in Ebml.children(c, point.dataStart, point.dataEnd)) {
                if (f.id == ID_CUE_TIME) {
                    time = Ebml.uint(c, f.dataStart, f.dataEnd)
                } else if (f.id == ID_CUE_TRACK_POSITIONS && cluster < 0) {
                    var cueTrack = -1L
                    var position = -1L
                    var rel = -1L
                    for (g in Ebml.children(c, f.dataStart, f.dataEnd)) {
                        when (g.id) {
                            ID_CUE_TRACK -> cueTrack = Ebml.uint(c, g.dataStart, g.dataEnd)
                            ID_CUE_CLUSTER_POSITION -> position = Ebml.uint(c, g.dataStart, g.dataEnd)
                            ID_CUE_RELATIVE_POSITION -> rel = Ebml.uint(c, g.dataStart, g.dataEnd)
                        }
                    }
                    if (cueTrack == track && position >= 0) {
                        cluster = position
                        relative = rel
                    }
                }
            }
            if (time >= 0 && cluster >= 0) {
                times.add(time * scaleNs / 1000L)
                clusters.add(segmentData + cluster)
                relatives.add(relative)
            }
        }
        val order = times.indices.sortedBy { times[it] }
        return KeyframeTable(
            timeUs = LongArray(order.size) { times[order[it]] },
            offset = LongArray(order.size) { clusters[order[it]] },
            relativePosition = LongArray(order.size) { relatives[order[it]] },
            size = IntArray(order.size) { -1 },
        )
    }

    /** The cued keyframe's frame bytes: straight to the block when the cue says where it is, else a scan. */
    fun readKeyframe(index: MediaIndex, source: RangeSource, i: Int, maxBytes: Int): ByteArray? {
        if (index.cueless != null) return readCuelessKeyframe(index, source, i, maxBytes)
        val relative = index.keyframes.relativePosition[i]
        val wantUs = index.keyframes.timeUs[i]
        val expected = index.keyframeBytesHint.takeIf { it > 0 }?.let { it + it / 4 + 16 * 1024 } ?: FIRST_WINDOW
        val first = if (relative >= 0) (relative + 64 + expected).coerceAtMost(maxBytes.toLong()).toInt() else SCAN_WINDOW
        val w = GrowingRead(source, index.keyframes.offset[i], minOf(first, maxBytes), maxBytes)
        val cluster = Ebml.header(w.bytes, 0) ?: return null
        if (cluster.id != ID_CLUSTER) return null
        if (relative >= 0) {
            val at = cluster.headerLength + relative
            if (w.ensure(at + 16)) blockAt(index, w, at.toInt())?.let {
                index.noteKeyframeSize(it.size)
                return it
            }
        }
        // No usable relative position: walk the cluster for the video keyframe at the cue's time.
        return walkClusters(index, w, 0L, wantUs)?.second?.also { index.noteKeyframeSize(it.size) }
    }

    /**
     * No Cues: estimates the byte offset of slot [i] from the file's length and duration, finds the next
     * cluster from there and takes its first video keyframe, correcting the estimate up to twice. The slot's
     * time becomes the keyframe's real time.
     */
    private fun readCuelessKeyframe(index: MediaIndex, source: RangeSource, i: Int, maxBytes: Int): ByteArray? {
        val layout = index.cueless ?: return null
        val slotUs = i * CUELESS_SLOT_US
        val span = (layout.fileLength - layout.segmentDataStart).toDouble()
        var offset = layout.segmentDataStart + (slotUs.toDouble() / layout.durationUs * span).toLong()
        val bytesPerSecond = span / (layout.durationUs / 1_000_000.0)
        repeat(3) { attempt ->
            offset = offset.coerceIn(layout.segmentDataStart, layout.fileLength - 1)
            val w = GrowingRead(source, offset, CUELESS_WINDOW, maxBytes)
            val found = findCluster(w, layout, index.timestampScaleNs)?.let { walkClusters(index, w, it, wantUs = null) }
            if (found == null) {
                // Past the last keyframe (the end of the file): look again 4 s earlier, then 12 s more.
                offset -= (bytesPerSecond * if (attempt == 0) 4 else 12).toLong()
                return@repeat
            }
            val (timeUs, frame) = found
            val error = slotUs - timeUs
            if (kotlin.math.abs(error) <= CUELESS_TOLERANCE_US || attempt == 2) {
                index.keyframes.timeUs[i] = timeUs
                index.noteKeyframeSize(frame.size)
                return frame
            }
            offset += (error.toDouble() / layout.durationUs * span).toLong()
        }
        return null
    }

    /** Position in [w] of the first real cluster (its ID, a sane size and a Timestamp first), or null. */
    private fun findCluster(w: GrowingRead, layout: CuelessLayout, scaleNs: Long): Long? {
        val maxTimestamp = (layout.durationUs + 60_000_000L) * 1000L / scaleNs
        var p = 0
        while (w.ensure(p + 32L)) {
            val b = w.bytes
            val limit = b.size - 32
            while (p < limit) {
                if (b[p] == 0x1F.toByte() && b[p + 1] == 0x43.toByte() && b[p + 2] == 0xB6.toByte() &&
                    b[p + 3] == 0x75.toByte() && plausibleCluster(b, p, maxTimestamp)
                ) return p.toLong()
                p++
            }
            if (!w.ensure(b.size + 256 * 1024L)) return null
        }
        return null
    }

    private fun plausibleCluster(b: ByteArray, at: Int, maxTimestamp: Long): Boolean {
        val h = runCatching { Ebml.header(b, at) }.getOrNull() ?: return false
        if (h.size > 512L * 1024 * 1024) return false
        var p = at + h.headerLength
        repeat(2) {
            val child = runCatching { Ebml.header(b, p) }.getOrNull() ?: return false
            when (child.id) {
                ID_CRC32 -> p += child.headerLength + child.size.toInt()
                ID_CLUSTER_TIMESTAMP -> {
                    if (child.size !in 1..8 || p + child.headerLength + child.size > b.size) return false
                    val ts = Ebml.uint(b, p + child.headerLength, p + child.headerLength + child.size.toInt())
                    return ts <= maxTimestamp
                }
                else -> return false
            }
        }
        return false
    }

    /**
     * Walks clusters from the one at [start], element by element. With [wantUs], returns the first cluster's
     * video keyframe at that time (else its first keyframe); without, the first video keyframe found.
     */
    private fun walkClusters(index: MediaIndex, w: GrowingRead, start: Long, wantUs: Long?): Pair<Long, ByteArray>? {
        var clusterTimestamp = 0L
        var clusters = 0
        var fallback: Pair<Long, ByteArray>? = null
        var p = start
        while (w.ensure(p + 12)) {
            val h = Ebml.header(w.bytes, p.toInt()) ?: break
            if (h.id == ID_CLUSTER) {
                if (wantUs != null && clusters > 0) break
                if (++clusters > MAX_WALK_CLUSTERS) break
                clusterTimestamp = 0L
                p += h.headerLength
                continue
            }
            if (h.size < 0) break
            val dataStart = p + h.headerLength
            val dataEnd = dataStart + h.size
            when (h.id) {
                ID_CLUSTER_TIMESTAMP -> {
                    if (!w.ensure(dataEnd)) break
                    clusterTimestamp = Ebml.uint(w.bytes, dataStart.toInt(), dataEnd.toInt())
                }
                ID_SIMPLE_BLOCK, ID_BLOCK_GROUP -> {
                    if (!w.ensure(dataEnd)) break
                    val block = if (h.id == ID_BLOCK_GROUP) {
                        Ebml.children(w.bytes, dataStart.toInt(), dataEnd.toInt()).firstOrNull { it.id == ID_BLOCK }?.dataStart
                    } else dataStart.toInt()
                    val frame = if (block != null) blockAt(index, w, p.toInt()) else null
                    if (frame != null && block != null) {
                        val b = w.bytes
                        val track = Ebml.vint(b, block) ?: break
                        val relTime = ((b[block + track.second].toInt() shl 8) or
                            (b[block + track.second + 1].toInt() and 0xFF)).toShort().toLong()
                        val us = (clusterTimestamp + relTime) * index.timestampScaleNs / 1000L
                        if (wantUs == null || kotlin.math.abs(us - wantUs) <= 1_000L) return us to frame
                        if (fallback == null) fallback = us to frame
                    }
                }
            }
            p = dataEnd
        }
        return fallback
    }

    /** The frame in the SimpleBlock or BlockGroup at [at] when it's an unlaced keyframe of the video track. */
    private fun blockAt(index: MediaIndex, w: GrowingRead, at: Int): ByteArray? {
        val h = Ebml.header(w.bytes, at) ?: return null
        if (h.size <= 0 || (h.id != ID_SIMPLE_BLOCK && h.id != ID_BLOCK_GROUP)) return null
        val dataStart = at + h.headerLength
        val dataEnd = dataStart + h.size
        if (!w.ensure(dataEnd)) return null
        val b = w.bytes
        var blockStart = dataStart
        var blockEnd = dataEnd.toInt()
        var keyByGroup = false
        if (h.id == ID_BLOCK_GROUP) {
            blockStart = -1
            var referenced = false
            for (c in Ebml.children(b, dataStart, dataEnd.toInt())) {
                if (c.id == ID_BLOCK) {
                    blockStart = c.dataStart
                    blockEnd = c.dataEnd
                } else if (c.id == ID_REFERENCE_BLOCK) {
                    referenced = true
                }
            }
            if (blockStart < 0) return null
            keyByGroup = !referenced
        }
        val track = Ebml.vint(b, blockStart) ?: return null
        if (track.first != index.video.trackNumber) return null
        val flagsAt = blockStart + track.second + 2
        if (flagsAt >= blockEnd) return null
        val flags = b[flagsAt].toInt() and 0xFF
        if (flags and 0x06 != 0) return null   // laced
        val key = if (h.id == ID_SIMPLE_BLOCK) flags and 0x80 != 0 else keyByGroup
        if (!key) return null
        return b.copyOfRange(flagsAt + 1, blockEnd)
    }
}
