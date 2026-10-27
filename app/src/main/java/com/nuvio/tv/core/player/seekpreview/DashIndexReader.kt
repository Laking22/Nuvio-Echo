package com.nuvio.tv.core.player.seekpreview

import okhttp3.HttpUrl
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/**
 * DASH (VOD): picks a trick-mode representation or the lightest video one, and treats each segment start as a
 * keyframe (DASH segments begin on one). Segments come from SegmentTemplate ($Number$ or $Time$, with or without
 * a SegmentTimeline), SegmentList or SegmentBase (its `sidx`), as fMP4 or MPEG-TS. Encrypted, live, multi-period
 * and WebM streams are refused.
 */
internal object DashIndexReader {
    private const val MIN_HEIGHT = 270
    private const val MAX_SEGMENTS = 100_000
    private const val TRICK_MODE = "http://dashif.org/guidelines/trickmode"
    private val TEMPLATE_IDENTIFIER = Regex("\\$(RepresentationID|Number|Time|Bandwidth)?(?:%0(\\d+)d)?\\$")
    private val DURATION = Regex(
        "P(?:([\\d.]+)Y)?(?:([\\d.]+)M)?(?:([\\d.]+)W)?(?:([\\d.]+)D)?(?:T(?:([\\d.]+)H)?(?:([\\d.]+)M)?(?:([\\d.]+)S)?)?",
    )

    private class Representation(
        val element: Element,
        val set: Element,
        val id: String,
        val bandwidth: Long,
        val width: Int,
        val height: Int,
        val codecs: String?,
        val mimeType: String,
        val trickMode: Boolean,
    )

    /** One kind of segment element (SegmentTemplate, SegmentList or SegmentBase) from the most specific level up. */
    private class Inherited(private val levels: List<Element>) {
        fun attr(name: String): String? = levels.firstNotNullOfOrNull { it.attr(name) }
        fun child(name: String): Element? = levels.firstNotNullOfOrNull { it.child(name) }
        fun children(name: String): List<Element> = levels.map { it.children(name) }.firstOrNull { it.isNotEmpty() }.orEmpty()
        val timescale: Long get() = attr("timescale")?.toLongOrNull()?.takeIf { it > 0 } ?: 1L
        val presentationTimeOffset: Long get() = attr("presentationTimeOffset")?.toLongOrNull() ?: 0L
        val startNumber: Long get() = attr("startNumber")?.toLongOrNull() ?: 1L
    }

    private class Segments(val list: List<MediaSegment>, val startsUs: LongArray, val init: MediaSegment?)

    fun read(fetcher: SegmentFetcher, url: HttpUrl, fallbackDurationUs: Long = 0L): MediaIndex {
        val (manifestUrl, text) = fetcher.manifest(url)
        val mpd = parse(text)
        if (mpd.name != "MPD") throw UnsupportedMediaException("not a DASH manifest")
        if (mpd.attr("type") == "dynamic") throw UnsupportedMediaException("live DASH")
        val videoPeriods = mpd.children("Period").filter { period -> period.children("AdaptationSet").any(::isVideo) }
        if (videoPeriods.isEmpty()) throw UnsupportedMediaException("no video adaptation set")
        if (videoPeriods.size > 1) throw UnsupportedMediaException("multi-period DASH")
        val period = videoPeriods[0]
        val periodStartUs = duration(period.attr("start")) ?: 0L
        val periodDurationUs = duration(period.attr("duration"))
            ?: duration(mpd.attr("mediaPresentationDuration"))?.minus(periodStartUs)
            ?: (fallbackDurationUs - periodStartUs).takeIf { fallbackDurationUs > 0 }
            ?: 0L

        val chosen = chooseRepresentation(period.children("AdaptationSet").filter(::isVideo))
        val base = listOf(mpd, period, chosen.set, chosen.element).fold(manifestUrl) { acc, level -> baseUrl(level, acc) }
        val levels = listOf(chosen.element, chosen.set, period)
        val segments = when {
            levels.any { it.child("SegmentTemplate") != null } ->
                templateSegments(chosen, Inherited(levels.mapNotNull { it.child("SegmentTemplate") }), base, periodStartUs, periodDurationUs)
            levels.any { it.child("SegmentList") != null } ->
                listSegments(Inherited(levels.mapNotNull { it.child("SegmentList") }), base, periodStartUs, periodDurationUs)
            levels.any { it.child("SegmentBase") != null } ->
                baseSegments(fetcher, Inherited(levels.mapNotNull { it.child("SegmentBase") }), base, periodStartUs)
            else -> throw UnsupportedMediaException("single-segment DASH without an index")
        }
        val isTs = chosen.mimeType == "video/mp2t"
        if (!isTs && segments.init == null) throw UnsupportedMediaException("fMP4 DASH without an init segment")
        return SegmentedIndex.build(
            Container.DASH, fetcher, segments.list, segments.startsUs, if (isTs) null else segments.init,
            chosen.width, chosen.height, chosen.codecs,
        )
    }

    private fun parse(text: String): Element {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        }
        return runCatching { factory.newDocumentBuilder().parse(InputSource(StringReader(text))).documentElement }
            .getOrNull() ?: throw UnsupportedMediaException("bad DASH manifest")
    }

    private fun isVideo(set: Element): Boolean {
        set.attr("contentType")?.let { return it == "video" }
        val mime = set.attr("mimeType") ?: set.children("Representation").firstNotNullOfOrNull { it.attr("mimeType") }
        if (mime != null) return mime.startsWith("video/")
        val codecs = set.attr("codecs") ?: set.children("Representation").firstNotNullOfOrNull { it.attr("codecs") }
        return codecs?.let(SegmentedIndex::hasVideoCodec) == true
    }

    /** A trick-mode representation, else the lightest at least [MIN_HEIGHT] tall; only unencrypted MP4 or TS ones. */
    private fun chooseRepresentation(sets: List<Element>): Representation {
        val all = sets.flatMap { set ->
            val trickMode = (set.children("EssentialProperty") + set.children("SupplementalProperty"))
                .any { it.attr("schemeIdUri") == TRICK_MODE }
            set.children("Representation").map { rep ->
                fun inherited(name: String) = rep.attr(name) ?: set.attr(name)
                Representation(
                    element = rep,
                    set = set,
                    id = rep.attr("id").orEmpty(),
                    bandwidth = rep.attr("bandwidth")?.toLongOrNull() ?: Long.MAX_VALUE,
                    width = inherited("width")?.toIntOrNull() ?: 0,
                    height = inherited("height")?.toIntOrNull() ?: 0,
                    codecs = inherited("codecs"),
                    mimeType = inherited("mimeType").orEmpty(),
                    trickMode = trickMode,
                )
            }
        }
        val clear = all.filter { it.set.child("ContentProtection") == null && it.element.child("ContentProtection") == null }
        if (clear.isEmpty()) throw UnsupportedMediaException(if (all.isEmpty()) "no video representation" else "encrypted DASH")
        val usable = clear.filter { it.mimeType.isEmpty() || it.mimeType == "video/mp4" || it.mimeType == "video/mp2t" }
        if (usable.isEmpty()) throw UnsupportedMediaException("DASH container ${clear.first().mimeType}")
        fun pick(list: List<Representation>): Representation? {
            val sorted = list.sortedBy { it.bandwidth }
            return sorted.firstOrNull { it.height >= MIN_HEIGHT } ?: sorted.lastOrNull()
        }
        return pick(usable.filter { it.trickMode }) ?: pick(usable)!!
    }

    private fun templateSegments(
        rep: Representation,
        template: Inherited,
        base: HttpUrl,
        periodStartUs: Long,
        periodDurationUs: Long,
    ): Segments {
        val media = template.attr("media") ?: throw UnsupportedMediaException("SegmentTemplate without media")
        val timescale = template.timescale
        val offset = template.presentationTimeOffset
        val times = segmentTimes(template, periodDurationUs)
        fun url(pattern: String, number: Long, time: Long): HttpUrl =
            base.resolve(expand(pattern, rep, number, time)) ?: throw UnsupportedMediaException("bad segment URI")
        val list = times.mapIndexed { i, t -> MediaSegment(url(media, template.startNumber + i, t), 0L, -1L) }
        val initElement = template.child("Initialization")
        val init = (template.attr("initialization") ?: initElement?.attr("sourceURL"))?.let { pattern ->
            val range = initElement?.attr("range")?.let(::byteRange)
            MediaSegment(url(pattern, template.startNumber, offset), range?.first ?: 0L, range?.second ?: -1L)
        }
        return Segments(list, startsUs(times, timescale, offset, periodStartUs), init)
    }

    private fun listSegments(segmentList: Inherited, base: HttpUrl, periodStartUs: Long, periodDurationUs: Long): Segments {
        val urls = segmentList.children("SegmentURL")
        val times = segmentTimes(segmentList, periodDurationUs, count = urls.size).take(urls.size)
        if (times.size < urls.size) throw UnsupportedMediaException("SegmentList without times")
        val list = urls.map { u ->
            val url = u.attr("media")?.let { base.resolve(it) ?: throw UnsupportedMediaException("bad segment URI") } ?: base
            val range = u.attr("mediaRange")?.let(::byteRange)
            MediaSegment(url, range?.first ?: 0L, range?.second ?: -1L)
        }
        val init = segmentList.child("Initialization")?.let { initSegment(it, base) }
        return Segments(list, startsUs(times, segmentList.timescale, segmentList.presentationTimeOffset, periodStartUs), init)
    }

    /** One file indexed by its `sidx`: each subsegment is a segment. */
    private fun baseSegments(fetcher: SegmentFetcher, segmentBase: Inherited, base: HttpUrl, periodStartUs: Long): Segments {
        val (indexStart, indexLength) = segmentBase.attr("indexRange")?.let(::byteRange)
            ?: throw UnsupportedMediaException("SegmentBase without indexRange")
        if (indexLength > 1024 * 1024) throw UnsupportedMediaException("sidx too large")
        val init = segmentBase.child("Initialization")?.let { initSegment(it, base) }
            ?: MediaSegment(base, 0L, indexStart).takeIf { indexStart > 0 }
        val sidx = fetcher.open(MediaSegment(base, indexStart, indexLength)).read(indexStart, indexLength.toInt())
        val subSegments = Mp4IndexReader.subSegments(sidx, indexStart)
        val shiftUs = segmentBase.presentationTimeOffset * 1_000_000L / segmentBase.timescale
        return Segments(
            subSegments.map { MediaSegment(base, it.offset, it.size) },
            LongArray(subSegments.size) { periodStartUs + subSegments[it].timeUs - shiftUs },
            init,
        )
    }

    private fun initSegment(initialization: Element, base: HttpUrl): MediaSegment {
        val url = initialization.attr("sourceURL")?.let { base.resolve(it) ?: throw UnsupportedMediaException("bad init URI") } ?: base
        val range = initialization.attr("range")?.let(::byteRange)
        return MediaSegment(url, range?.first ?: 0L, range?.second ?: -1L)
    }

    /**
     * Segment start times in the element's timescale: from its SegmentTimeline, else every `duration` for [count]
     * segments (a SegmentList's) or as many as fill the period.
     */
    private fun segmentTimes(element: Inherited, periodDurationUs: Long, count: Int = -1): List<Long> {
        val timescale = element.timescale
        val offset = element.presentationTimeOffset
        val periodEnd = offset + periodDurationUs * timescale / 1_000_000L
        val timeline = element.child("SegmentTimeline")
        val times = ArrayList<Long>()
        if (timeline != null) {
            val entries = timeline.children("S")
            var t = offset
            for ((i, s) in entries.withIndex()) {
                s.attr("t")?.toLongOrNull()?.let { t = it }
                val d = s.attr("d")?.toLongOrNull()?.takeIf { it > 0 } ?: throw UnsupportedMediaException("SegmentTimeline without d")
                var repeat = s.attr("r")?.toLongOrNull() ?: 0L
                if (repeat < 0) {
                    // Repeats until the next entry's start, or the end of the period.
                    val end = entries.getOrNull(i + 1)?.attr("t")?.toLongOrNull() ?: periodEnd
                    repeat = (end - t + d - 1) / d - 1
                }
                if (times.size + repeat >= MAX_SEGMENTS) throw UnsupportedMediaException("too many segments")
                for (k in 0..repeat) {
                    times += t
                    t += d
                }
            }
        } else {
            val d = element.attr("duration")?.toLongOrNull()?.takeIf { it > 0 } ?: return emptyList()
            val n = if (count >= 0) {
                count.toLong()
            } else {
                if (periodDurationUs <= 0) throw UnsupportedMediaException("DASH duration unknown")
                (periodEnd - offset + d - 1) / d
            }
            if (n >= MAX_SEGMENTS) throw UnsupportedMediaException("too many segments")
            for (k in 0 until n) times += offset + k * d
        }
        return times
    }

    private fun startsUs(times: List<Long>, timescale: Long, offset: Long, periodStartUs: Long): LongArray =
        LongArray(times.size) { periodStartUs + (times[it] - offset).coerceAtLeast(0L) * 1_000_000L / timescale }

    private fun expand(pattern: String, rep: Representation, number: Long, time: Long): String =
        TEMPLATE_IDENTIFIER.replace(pattern) { m ->
            val width = m.groupValues[2].toIntOrNull() ?: 0
            when (m.groupValues[1]) {
                "" -> "$"
                "RepresentationID" -> rep.id
                "Number" -> number.toString().padStart(width, '0')
                "Time" -> time.toString().padStart(width, '0')
                "Bandwidth" -> rep.bandwidth.toString().padStart(width, '0')
                else -> m.value
            }
        }

    /** "first-last" as (offset, length). */
    private fun byteRange(spec: String): Pair<Long, Long>? {
        val first = spec.substringBefore('-').trim().toLongOrNull() ?: return null
        val last = spec.substringAfter('-', "").trim().toLongOrNull() ?: return null
        return if (last >= first) first to last - first + 1 else null
    }

    private fun baseUrl(element: Element, parent: HttpUrl): HttpUrl {
        val text = element.child("BaseURL")?.textContent?.trim()?.takeIf { it.isNotEmpty() } ?: return parent
        return parent.resolve(text) ?: throw UnsupportedMediaException("bad BaseURL")
    }

    /** An ISO 8601 duration (PT1H2M3.5S) in microseconds. */
    private fun duration(text: String?): Long? {
        val m = DURATION.matchEntire(text?.trim() ?: return null) ?: return null
        val units = doubleArrayOf(365.0 * 86400, 30.0 * 86400, 7.0 * 86400, 86400.0, 3600.0, 60.0, 1.0)
        var seconds = 0.0
        for (i in units.indices) seconds += (m.groupValues[i + 1].toDoubleOrNull() ?: 0.0) * units[i]
        return (seconds * 1_000_000).toLong()
    }

    private val Element.name: String get() = localName ?: nodeName.substringAfter(':')

    private fun Element.children(name: String): List<Element> {
        val out = ArrayList<Element>()
        var node = firstChild
        while (node != null) {
            if (node is Element && node.name == name) out += node
            node = node.nextSibling
        }
        return out
    }

    private fun Element.child(name: String): Element? = children(name).firstOrNull()

    private fun Element.attr(name: String): String? = getAttribute(name).takeIf { it.isNotEmpty() }
}
