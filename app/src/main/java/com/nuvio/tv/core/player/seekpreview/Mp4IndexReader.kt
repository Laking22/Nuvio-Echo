package com.nuvio.tv.core.player.seekpreview

/**
 * Progressive MP4/MOV: reads `moov` (front or back) and keeps each sync sample's exact byte range and time.
 * Fragmented MP4 isn't supported.
 */
internal object Mp4IndexReader {
    private const val MAX_MOOV = 40 * 1024 * 1024
    /** Above this, only the video track is read out of `moov` (files with many audio tracks run to 20 MB). */
    private const val SLIM_ABOVE = 3 * 1024 * 1024
    private const val TRAK_PEEK = 4096
    private val TOP_LEVEL = setOf("ftyp", "moov", "free", "skip", "wide", "mdat", "pdin", "uuid", "styp")

    fun looksLikeMp4(head: ByteArray): Boolean = head.size >= 8 && type(head, 4) in TOP_LEVEL

    private class Box(val type: String, val dataStart: Int, val end: Int)

    private fun u32(b: ByteArray, p: Int): Long =
        ((b[p].toLong() and 0xFF) shl 24) or ((b[p + 1].toLong() and 0xFF) shl 16) or
            ((b[p + 2].toLong() and 0xFF) shl 8) or (b[p + 3].toLong() and 0xFF)

    private fun u64(b: ByteArray, p: Int): Long = (u32(b, p) shl 32) or u32(b, p + 4)
    private fun u16(b: ByteArray, p: Int): Int = ((b[p].toInt() and 0xFF) shl 8) or (b[p + 1].toInt() and 0xFF)
    private fun type(b: ByteArray, p: Int) = String(b, p, 4, Charsets.ISO_8859_1)

    private fun boxes(b: ByteArray, start: Int, end: Int): List<Box> {
        val out = ArrayList<Box>()
        var p = start
        while (p + 8 <= end) {
            var size = u32(b, p)
            var header = 8
            if (size == 1L) {
                if (p + 16 > end) break
                size = u64(b, p + 8)
                header = 16
            } else if (size == 0L) {
                size = (end - p).toLong()
            }
            if (size < header || p + size > end) break
            out.add(Box(type(b, p + 4), p + header, (p + size).toInt()))
            p += size.toInt()
        }
        return out
    }

    private fun child(b: ByteArray, parent: Box, t: String): Box? = boxes(b, parent.dataStart, parent.end).firstOrNull { it.type == t }

    fun read(source: RangeSource, head: ByteArray, slimAbove: Int = SLIM_ABOVE): MediaIndex {
        val moov = locateMoov(source, head, slimAbove)
        val root = Box("moov", 0, moov.size)
        if (child(moov, root, "mvex") != null) throw UnsupportedMediaException("fragmented MP4")
        val traks = boxes(moov, 0, moov.size).filter { it.type == "trak" }
        // A slimmed moov is the video trak alone (it parses as a moov with one track, and no mvhd): audio unknown.
        val hasAudio = if (child(moov, root, "mvhd") == null) null else traks.any { trak ->
            child(moov, trak, "mdia")?.let { mdia -> child(moov, mdia, "hdlr") }
                ?.let { type(moov, it.dataStart + 8) == "soun" } == true
        }
        for (trak in traks) {
            val mdia = child(moov, trak, "mdia") ?: continue
            val hdlr = child(moov, mdia, "hdlr") ?: continue
            if (type(moov, hdlr.dataStart + 8) != "vide") continue
            val mdhd = child(moov, mdia, "mdhd") ?: continue
            val timescale = u32(moov, mdhd.dataStart + if (moov[mdhd.dataStart].toInt() == 1) 20 else 12)
            if (timescale <= 0) continue
            val stbl = child(moov, mdia, "minf")?.let { child(moov, it, "stbl") } ?: continue
            val track = sampleEntry(moov, stbl) ?: continue
            val shift = child(moov, trak, "edts")?.let { firstEditMediaTime(moov, it) } ?: 0L
            val keyframes = syncSamples(moov, stbl, timescale, shift)
            if (keyframes.count < 2) throw UnsupportedMediaException("too few sync samples")
            return MediaIndex(Container.MP4, track, keyframes, hasAudio = hasAudio)
        }
        throw UnsupportedMediaException("no supported video track")
    }

    /** Walks top-level boxes with header-only reads until `moov`, then reads it whole. */
    private fun locateMoov(source: RangeSource, head: ByteArray, slimAbove: Int): ByteArray {
        val (start, end) = moovBounds(source, head)
        val bodyLength = (end - start).toInt()
        val body = when {
            end <= head.size -> head.copyOfRange(start.toInt(), end.toInt())
            bodyLength > slimAbove -> return videoTrakOnly(source, start, end)
            else -> source.read(start, bodyLength)
        }
        if (body.size != bodyLength) throw UnsupportedMediaException("truncated moov")
        return body
    }

    /** Walks top-level boxes with header-only reads until `moov`: where its body starts and ends in the file. */
    private fun moovBounds(source: RangeSource, head: ByteArray): Pair<Long, Long> {
        var pos = 0L
        repeat(32) {
            val header = if (pos + 16 <= head.size) head.copyOfRange(pos.toInt(), pos.toInt() + 16) else source.read(pos, 16)
            if (header.size < 8) throw UnsupportedMediaException("moov not found")
            var size = u32(header, 0)
            var headerLength = 8
            if (size == 1L) {
                if (header.size < 16) throw UnsupportedMediaException("moov not found")
                size = u64(header, 8)
                headerLength = 16
            } else if (size == 0L) {
                val total = source.totalLength
                if (total <= 0) throw UnsupportedMediaException("box to end of file")
                size = total - pos
            }
            when (val t = type(header, 4)) {
                "moov" -> {
                    if (size > MAX_MOOV) throw UnsupportedMediaException("moov too large")
                    return (pos + headerLength) to (pos + size)
                }
                "moof" -> throw UnsupportedMediaException("fragmented MP4")
                else -> if (t.any { it.code < 32 || it.code > 126 }) throw UnsupportedMediaException("not an MP4 box")
            }
            if (size < headerLength) throw UnsupportedMediaException("bad box size")
            pos += size
            val total = source.totalLength
            if (total in 1..pos) throw UnsupportedMediaException("moov not found")
        }
        throw UnsupportedMediaException("moov not found")
    }

    /**
     * Nero chapters (`moov/udta/chpl`), which have no end of their own; none when the file keeps none, or keeps
     * them as a QuickTime text track. A large `moov` is stepped through for `udta` alone.
     */
    fun readChapters(source: RangeSource, head: ByteArray): List<MediaChapter> {
        val (start, end) = moovBounds(source, head)
        val length = (end - start).toInt()
        val udta = if (end <= head.size || length <= SLIM_ABOVE) {
            val moov = if (end <= head.size) head.copyOfRange(start.toInt(), end.toInt()) else source.read(start, length)
            if (moov.size != length) throw UnsupportedMediaException("truncated moov")
            child(moov, Box("moov", 0, moov.size), "udta")?.let { moov.copyOfRange(it.dataStart, it.end) }
        } else {
            moovChildBody(source, start, end, "udta")
        } ?: return emptyList()
        val chpl = child(udta, Box("udta", 0, udta.size), "chpl") ?: return emptyList()
        return chplChapters(udta, chpl.dataStart, chpl.end)
    }

    /** The body of `moov`'s first [boxType] child, found one small read per child. */
    private fun moovChildBody(source: RangeSource, start: Long, end: Long, boxType: String): ByteArray? {
        var pos = start
        repeat(64) {
            if (pos + 8 > end) return null
            val peek = source.read(pos, 16)
            if (peek.size < 8) return null
            var size = u32(peek, 0)
            var headerLength = 8
            if (size == 1L && peek.size >= 16) {
                size = u64(peek, 8)
                headerLength = 16
            }
            if (size < headerLength || pos + size > end) return null
            if (type(peek, 4) == boxType) {
                if (size > SLIM_ABOVE) return null
                val body = source.read(pos + headerLength, (size - headerLength).toInt())
                return body.takeIf { it.size.toLong() == size - headerLength }
            }
            pos += size
        }
        return null
    }

    /** `chpl`: version, flags (and 4 more bytes from version 1), a count, then each start (100 ns) and title. */
    private fun chplChapters(b: ByteArray, start: Int, end: Int): List<MediaChapter> {
        if (end - start < 5) return emptyList()
        var p = start + 4 + if (b[start].toInt() != 0) 4 else 0
        if (p >= end) return emptyList()
        val count = b[p].toInt() and 0xFF
        p++
        val out = ArrayList<MediaChapter>()
        for (i in 0 until count) {
            if (p + 9 > end) break
            val start100ns = u64(b, p)
            val titleLength = b[p + 8].toInt() and 0xFF
            p += 9
            if (p + titleLength > end) break
            val title = String(b, p, titleLength, Charsets.UTF_8).trim()
            p += titleLength
            if (start100ns >= 0) out.add(MediaChapter(start100ns / 10_000L, null, title))
        }
        return out.sortedBy { it.startMs }
    }

    /**
     * Steps through `moov`'s children one small read each and returns just the video `trak` box, which
     * parses like a `moov` holding only that track.
     */
    private fun videoTrakOnly(source: RangeSource, start: Long, end: Long): ByteArray {
        var pos = start
        repeat(64) {
            if (pos + 8 > end) throw UnsupportedMediaException("no video track")
            val peek = source.read(pos, TRAK_PEEK)
            if (peek.size < 8) throw UnsupportedMediaException("truncated moov")
            var size = u32(peek, 0)
            if (size == 1L && peek.size >= 16) size = u64(peek, 8)
            if (size < 8 || pos + size > end) throw UnsupportedMediaException("bad box in moov")
            when (type(peek, 4)) {
                "mvex" -> throw UnsupportedMediaException("fragmented MP4")
                "trak" -> if (isVideoTrak(peek)) {
                    if (size > MAX_MOOV) throw UnsupportedMediaException("video track too large")
                    val trak = source.read(pos, size.toInt())
                    if (trak.size.toLong() != size) throw UnsupportedMediaException("truncated video track")
                    return trak
                }
            }
            pos += size
        }
        throw UnsupportedMediaException("no video track")
    }

    /** True when the start of a `trak` holds a `hdlr` of type `vide` (it sits a few hundred bytes in). */
    private fun isVideoTrak(peek: ByteArray): Boolean {
        for (i in 4..peek.size - 16) {
            if (peek[i] == 'h'.code.toByte() && type(peek, i) == "hdlr") return type(peek, i + 12) == "vide"
        }
        return false
    }

    /** An fMP4 init segment (HLS EXT-X-MAP, DASH Initialization): the video track and its track_ID. */
    fun initTrack(init: ByteArray): Pair<VideoTrackInfo, Long> {
        val moov = boxes(init, 0, init.size).firstOrNull { it.type == "moov" }
            ?: throw UnsupportedMediaException("init segment without moov")
        for (trak in boxes(init, moov.dataStart, moov.end).filter { it.type == "trak" }) {
            val mdia = child(init, trak, "mdia") ?: continue
            val hdlr = child(init, mdia, "hdlr") ?: continue
            if (type(init, hdlr.dataStart + 8) != "vide") continue
            val stbl = child(init, mdia, "minf")?.let { child(init, it, "stbl") } ?: continue
            val track = sampleEntry(init, stbl) ?: continue
            val tkhd = child(init, trak, "tkhd") ?: continue
            val trackId = u32(init, tkhd.dataStart + if (init[tkhd.dataStart].toInt() == 1) 20 else 12)
            return track to trackId
        }
        throw UnsupportedMediaException("no video track in init segment")
    }

    /**
     * The first sample of [trackId] in an fMP4 media segment (styp/sidx/moof/mdat), read through [w].
     * HLS and DASH fMP4 segments start on a keyframe.
     */
    fun firstFragmentSample(w: GrowingRead, trackId: Long): ByteArray? {
        var pos = 0L
        repeat(16) {
            if (!w.ensure(pos + 16)) return null
            var size = u32(w.bytes, pos.toInt())
            if (size == 1L) size = u64(w.bytes, pos.toInt() + 8)
            if (size < 8) return null
            if (type(w.bytes, pos.toInt() + 4) == "moof") {
                if (!w.ensure(pos + size)) return null
                return sampleFromMoof(w, pos, size, trackId)
            }
            pos += size
        }
        return null
    }

    /** One entry of a `sidx` box: where a DASH subsegment starts (in time and in the file) and its size. */
    class SubSegment(val timeUs: Long, val offset: Long, val size: Long)

    /** The subsegments a top-level `sidx` box lists; [b] is the box from its header on, read at file offset [boxStart]. */
    fun subSegments(b: ByteArray, boxStart: Long): List<SubSegment> {
        if (b.size < 32 || type(b, 4) != "sidx") throw UnsupportedMediaException("no sidx at the index range")
        var size = u32(b, 0)
        var p = 8
        if (size == 1L) { size = u64(b, 8); p = 16 }
        val version = b[p].toInt()
        p += 8  // version, flags, reference_ID
        val timescale = u32(b, p)
        p += 4
        val earliest: Long
        val firstOffset: Long
        if (version == 0) {
            earliest = u32(b, p); firstOffset = u32(b, p + 4); p += 8
        } else {
            earliest = u64(b, p); firstOffset = u64(b, p + 8); p += 16
        }
        val count = u16(b, p + 2)
        p += 4
        if (timescale <= 0 || p + 12L * count > b.size) throw UnsupportedMediaException("short sidx")
        // Offsets count from the first byte after the sidx box.
        var offset = boxStart + size + firstOffset
        var t = earliest
        val out = ArrayList<SubSegment>(count)
        repeat(count) {
            val reference = u32(b, p)
            if (reference ushr 31 == 1L) throw UnsupportedMediaException("hierarchical sidx")
            val length = reference and 0x7FFFFFFF
            out.add(SubSegment(t * 1_000_000L / timescale, offset, length))
            offset += length
            t += u32(b, p + 4)
            p += 12
        }
        return out
    }

    private fun sampleFromMoof(w: GrowingRead, moofStart: Long, moofSize: Long, trackId: Long): ByteArray? {
        val b = w.bytes
        val moof = Box("moof", moofStart.toInt() + 8, (moofStart + moofSize).toInt())
        for (traf in boxes(b, moof.dataStart, moof.end).filter { it.type == "traf" }) {
            val tfhd = child(b, traf, "tfhd") ?: continue
            val tfhdFlags = u32(b, tfhd.dataStart) and 0xFFFFFF
            if (u32(b, tfhd.dataStart + 4) != trackId) continue
            var p = tfhd.dataStart + 8
            var base = moofStart
            if (tfhdFlags and 0x1L != 0L) { base = u64(b, p); p += 8 }
            if (tfhdFlags and 0x2L != 0L) p += 4
            if (tfhdFlags and 0x8L != 0L) p += 4
            val defaultSize = if (tfhdFlags and 0x10L != 0L) u32(b, p) else -1L
            val trun = child(b, traf, "trun") ?: return null
            val trunFlags = u32(b, trun.dataStart) and 0xFFFFFF
            if (u32(b, trun.dataStart + 4) < 1) return null
            var q = trun.dataStart + 8
            var dataOffset = 0L
            if (trunFlags and 0x1L != 0L) { dataOffset = u32(b, q).toInt().toLong(); q += 4 }
            if (trunFlags and 0x4L != 0L) q += 4
            if (trunFlags and 0x100L != 0L) q += 4
            val size = if (trunFlags and 0x200L != 0L) u32(b, q) else defaultSize
            if (size <= 0 || size > 16L * 1024 * 1024) return null
            val start = base + dataOffset
            if (start < 0 || !w.ensure(start + size)) return null
            return w.bytes.copyOfRange(start.toInt(), (start + size).toInt())
        }
        return null
    }

    private fun sampleEntry(b: ByteArray, stbl: Box): VideoTrackInfo? {
        val stsd = child(b, stbl, "stsd") ?: return null
        val entry = boxes(b, stsd.dataStart + 8, stsd.end).firstOrNull() ?: return null
        val codec = when (entry.type) {
            "avc1", "avc3" -> VideoCodec.H264
            "hvc1", "hev1", "dvh1", "dvhe" -> VideoCodec.HEVC
            "av01" -> VideoCodec.AV1
            "vp09" -> VideoCodec.VP9
            else -> throw UnsupportedMediaException("video codec ${entry.type}")
        }
        // VisualSampleEntry: width at 24, height at 26, child boxes after 78 bytes.
        val width = u16(b, entry.dataStart + 24)
        val height = u16(b, entry.dataStart + 26)
        var nal: CodecConfig.Nal? = null
        var av1c: ByteArray? = null
        var vpBitDepth = 8
        var aspect = 0.0
        var transfer = 0
        var peak = 0
        var dvProfile = -1
        for (c in boxes(b, entry.dataStart + 78, entry.end)) {
            when (c.type) {
                "avcC" -> if (codec == VideoCodec.H264) nal = CodecConfig.avcC(b.copyOfRange(c.dataStart, c.end))
                "hvcC" -> if (codec == VideoCodec.HEVC) nal = CodecConfig.hvcC(b.copyOfRange(c.dataStart, c.end))
                "av1C" -> av1c = b.copyOfRange(c.dataStart, c.end)
                "vpcC" -> if (c.end - c.dataStart >= 7) vpBitDepth = (b[c.dataStart + 6].toInt() and 0xFF) shr 4
                "pasp" -> {
                    val h = u32(b, c.dataStart)
                    val v = u32(b, c.dataStart + 4)
                    if (h > 0 && v > 0 && width > 0 && height > 0) aspect = width.toDouble() * h / (height.toDouble() * v)
                }
                "colr" -> if (c.end - c.dataStart >= 10 && type(b, c.dataStart) == "nclx") transfer = u16(b, c.dataStart + 6)
                "clli" -> if (c.end - c.dataStart >= 2) peak = maxOf(peak, u16(b, c.dataStart))
                "dvcC", "dvvC" -> if (c.end - c.dataStart >= 5) dvProfile = (b[c.dataStart + 2].toInt() and 0xFF) shr 1
            }
        }
        if (dvProfile == 5) throw UnsupportedMediaException("Dolby Vision profile 5")
        return when (codec) {
            VideoCodec.H264, VideoCodec.HEVC -> {
                val config = nal ?: return null
                VideoTrackInfo(
                    codec, width, height, aspect, config.bitDepth, config.nalLengthSize, config.parameterSets,
                    config.csd, transfer, peak,
                )
            }
            VideoCodec.AV1 -> VideoTrackInfo(
                codec, width, height, aspect, av1c?.let(CodecConfig::av1BitDepth) ?: 8,
                csd = listOfNotNull(av1c), transfer = transfer, peakNits = peak,
            )
            VideoCodec.VP9 -> VideoTrackInfo(
                codec, width, height, aspect, vpBitDepth.takeIf { it in 8..12 } ?: 8, transfer = transfer, peakNits = peak,
            )
        }
    }

    private fun firstEditMediaTime(b: ByteArray, edts: Box): Long {
        val elst = child(b, edts, "elst") ?: return 0L
        if (u32(b, elst.dataStart + 4) < 1) return 0L
        val p = elst.dataStart + 8
        val mediaTime = if (b[elst.dataStart].toInt() == 1) u64(b, p + 8) else u32(b, p + 4).toInt().toLong()
        return if (mediaTime > 0) mediaTime else 0L
    }

    /** One pass over every sample, keeping only the sync samples (so a long film needs no per-sample arrays). */
    private fun syncSamples(b: ByteArray, stbl: Box, timescale: Long, shift: Long): KeyframeTable {
        val stsz = child(b, stbl, "stsz")
        val stz2 = if (stsz == null) child(b, stbl, "stz2") else null
        val sampleCount: Int
        val sampleSize: (Int) -> Long
        when {
            stsz != null -> {
                val fixed = u32(b, stsz.dataStart + 4)
                sampleCount = u32(b, stsz.dataStart + 8).toInt()
                if (fixed == 0L && stsz.dataStart + 12 + 4L * sampleCount > stsz.end) throw UnsupportedMediaException("short stsz")
                sampleSize = { i -> if (fixed != 0L) fixed else u32(b, stsz.dataStart + 12 + 4 * i) }
            }
            stz2 != null -> {
                val field = b[stz2.dataStart + 7].toInt() and 0xFF
                sampleCount = u32(b, stz2.dataStart + 8).toInt()
                val base = stz2.dataStart + 12
                sampleSize = { i ->
                    when (field) {
                        4 -> ((b[base + i / 2].toInt() shr (if (i % 2 == 0) 4 else 0)) and 0x0F).toLong()
                        8 -> (b[base + i].toLong() and 0xFF)
                        else -> u16(b, base + 2 * i).toLong()
                    }
                }
            }
            else -> throw UnsupportedMediaException("no stsz")
        }
        val stss = child(b, stbl, "stss")
        val syncCount = stss?.let { u32(b, it.dataStart + 4).toInt() } ?: sampleCount
        // Without stss every sample is a sync sample; only about one a second is kept.
        fun syncAt(k: Int): Int = if (stss != null) u32(b, stss.dataStart + 8 + 4 * k).toInt() - 1 else k

        val stco = child(b, stbl, "stco")
        val co64 = child(b, stbl, "co64")
        val chunkCount = when {
            stco != null -> u32(b, stco.dataStart + 4).toInt()
            co64 != null -> u32(b, co64.dataStart + 4).toInt()
            else -> throw UnsupportedMediaException("no stco")
        }
        fun chunkOffset(c: Int): Long = if (stco != null) u32(b, stco.dataStart + 8 + 4 * c) else u64(b, co64!!.dataStart + 8 + 8 * c)
        val stsc = child(b, stbl, "stsc") ?: throw UnsupportedMediaException("no stsc")
        val stscCount = u32(b, stsc.dataStart + 4).toInt()
        fun stscFirst(e: Int) = u32(b, stsc.dataStart + 8 + 12 * e).toInt() - 1
        fun stscPerChunk(e: Int) = u32(b, stsc.dataStart + 12 + 12 * e).toInt()
        val stts = child(b, stbl, "stts") ?: throw UnsupportedMediaException("no stts")
        val sttsCount = u32(b, stts.dataStart + 4).toInt()
        val ctts = child(b, stbl, "ctts")
        val cttsCount = ctts?.let { u32(b, it.dataStart + 4).toInt() } ?: 0
        val cttsSigned = ctts != null && b[ctts.dataStart].toInt() == 1

        val times = ArrayList<Long>()
        val offsets = ArrayList<Long>()
        val sizes = ArrayList<Int>()
        var nextSyncIndex = 0
        var nextSync = if (syncCount > 0) syncAt(0) else Int.MAX_VALUE
        var lastKeptUs = -1_000_000L

        var sample = 0
        var stscEntry = 0
        var sttsEntry = 0
        var sttsLeft = if (sttsCount > 0) u32(b, stts.dataStart + 8).toInt() else 0
        var dts = 0L
        var cttsEntry = 0
        var cttsLeft = if (cttsCount > 0) u32(b, ctts!!.dataStart + 8).toInt() else 0
        chunks@ for (chunk in 0 until chunkCount) {
            while (stscEntry + 1 < stscCount && stscFirst(stscEntry + 1) <= chunk) stscEntry++
            var offset = chunkOffset(chunk)
            repeat(stscPerChunk(stscEntry)) {
                if (sample >= sampleCount || nextSyncIndex >= syncCount) break@chunks
                val size = sampleSize(sample)
                if (sample == nextSync) {
                    val cto = if (ctts != null && cttsEntry < cttsCount) {
                        val raw = u32(b, ctts.dataStart + 12 + 8 * cttsEntry)
                        if (cttsSigned) raw.toInt().toLong() else raw
                    } else 0L
                    val us = ((dts + cto - shift).coerceAtLeast(0L)) * 1_000_000L / timescale
                    if (stss != null || us - lastKeptUs >= 1_000_000L) {
                        times.add(us)
                        offsets.add(offset)
                        sizes.add(size.toInt())
                        lastKeptUs = us
                    }
                    nextSyncIndex++
                    nextSync = if (nextSyncIndex < syncCount) syncAt(nextSyncIndex) else Int.MAX_VALUE
                }
                offset += size
                sample++
                // Advance decode time and composition offset to the next sample.
                if (sttsEntry < sttsCount) {
                    dts += u32(b, stts.dataStart + 12 + 8 * sttsEntry)
                    if (--sttsLeft <= 0 && ++sttsEntry < sttsCount) sttsLeft = u32(b, stts.dataStart + 8 + 8 * sttsEntry).toInt()
                }
                if (ctts != null && cttsEntry < cttsCount && --cttsLeft <= 0 && ++cttsEntry < cttsCount) {
                    cttsLeft = u32(b, ctts.dataStart + 8 + 8 * cttsEntry).toInt()
                }
            }
        }
        val order = times.indices.sortedBy { times[it] }
        return KeyframeTable(
            timeUs = LongArray(order.size) { times[order[it]] },
            offset = LongArray(order.size) { offsets[order[it]] },
            relativePosition = LongArray(order.size) { -1L },
            size = IntArray(order.size) { sizes[order[it]] },
        )
    }
}
