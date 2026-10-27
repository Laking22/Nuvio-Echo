package com.nuvio.tv.core.streams

import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamDebridCacheState

/** Resolution buckets shown in the player's quality menu, best first. */
enum class StreamQuality(val height: Int, val label: String) {
    P2160(2160, "4K"),
    P1440(1440, "1440p"),
    P1080(1080, "1080p"),
    P720(720, "720p"),
    P480(480, "480p"),
    P360(360, "360p"),
    P240(240, "240p"),
    P144(144, "144p");

    companion object {
        val menuOrder: List<StreamQuality> = entries.toList()

        /**
         * Bucket for a decoded video size. Uses the long edge as well as the short one so
         * cropped widescreen video (1920x800) still reads as 1080p; the long-edge steps sit near
         * each class's full width (1920, 1280...), so a 1720x720 scope encode stays 720p.
         */
        fun fromVideoSize(width: Int, height: Int): StreamQuality? {
            if (width <= 0 || height <= 0) return null
            val long = maxOf(width, height)
            val short = minOf(width, height)
            return when {
                long >= 3200 || short >= 2000 -> P2160
                long >= 2200 || short >= 1300 -> P1440
                long >= 1800 || short >= 1000 -> P1080
                long >= 1200 || short >= 700 -> P720
                long >= 700 || short >= 460 -> P480
                long >= 560 || short >= 340 -> P360
                long >= 380 || short >= 220 -> P240
                else -> P144
            }
        }
    }
}

/** Reads the resolution an addon advertises for a stream from its filename, name or description. */
object StreamQualityParser {

    /** After a bare resolution number: not followed by a size unit ("1080 MB", "2160MiB"). */
    private const val NOT_A_SIZE = "(?!\\s*[kmgt]i?b(?![a-z]))"

    /**
     * Structured fields (local scrapers, debrid parsing) first, then the text addons show. Cached per
     * stream object: the menu, the switch, the fallback and the audio ranking all re-read the same list.
     */
    fun parse(stream: Stream): StreamQuality? {
        val key = IdentityKey(stream)
        synchronized(cache) { cache[key]?.let { return it.quality } }
        val quality = cappedByName(
            parseText(stream.quality)
                ?: parseText(stream.clientResolve?.stream?.raw?.parsed?.resolution)
                ?: parse(
                    stream.behaviorHints?.filename,
                    stream.clientResolve?.filename,
                    stream.name,
                    stream.title,
                    stream.description
                ),
            stream.name
        )
        synchronized(cache) { cache[key] = Parsed(quality) }
        return quality
    }

    /**
     * A stated resolution ("1080p", "4K") in any of [texts] beats a loose word ("HD", "UHD") in an
     * earlier one: "Movie.1080p.UHD.BluRay" is a 1080p encode of a UHD disc, and a filename saying
     * only "HD" shouldn't outrank a name saying "1080p". Within each kind, the first text that has
     * one wins.
     */
    fun parse(vararg texts: String?): StreamQuality? =
        texts.firstNotNullOfOrNull { exact(it) } ?: texts.firstNotNullOfOrNull { loose(it) }

    /**
     * [stated], unless the stream's own [name] states a lower resolution: the name is the addon's label for this one
     * source, while a filename can be shared. PenguPlay gives every quality of an episode the same 1080p filename, so
     * its 360p source read as 1080p and auto-play started on it. Reading low is the safe side.
     */
    fun cappedByName(stated: StreamQuality?, name: String?): StreamQuality? {
        val named = exact(name) ?: return stated
        return if (stated != null && named.height < stated.height) named else stated
    }

    /** One text on its own: its stated resolution if it has one, else its loose word. */
    internal fun parseText(text: String?): StreamQuality? = exact(text) ?: loose(text)

    /** A resolution stated as a name ("1080p", "4K") or as a size ("1920x1080"), the better if both. */
    private fun exact(text: String?): StreamQuality? {
        val named = bestMatch(text, exactPattern)
        val sized = text?.let { dimensions.findAll(it) }
            ?.mapNotNull { StreamQuality.fromVideoSize(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
            ?.minByOrNull { it.ordinal }
        return listOfNotNull(named, sized).minByOrNull { it.ordinal }
    }

    /** "HD" or "SD" as a word, but not as part of an audio format ("DTS-HD MA", "TrueHD"). */
    private fun loose(text: String?): StreamQuality? = bestMatch(text?.replace(audioFormats, " "), loosePattern)

    // A picture size: 1920x1080, 3840 × 2160. Not inside a longer number.
    private val dimensions = Regex("(?<![0-9])([1-9]\\d{2,3})\\s?[x×]\\s?([1-9]\\d{2,3})(?![0-9])", RegexOption.IGNORE_CASE)
    private val audioFormats = Regex("dts[-. ]?hd(?:[-. ]?(?:ma|hra|x))?|true[-. ]?hd", RegexOption.IGNORE_CASE)

    // Tokens must stand alone ("4KHDHub" is not "4K", "x1080" is not 1080p); a frame rate or bit depth
    // may follow ("2160p60", "1080p10bit"). A bare number counts too, but not as a size ("1080 MB").
    // One capture group per bucket, best first, so one pass finds every token in a text.
    private val exactPattern = tokenRegex(
        StreamQuality.P2160 to "4320(?:p\\d{0,3}(?:bits?)?|$NOT_A_SIZE)|8k|2160(?:p\\d{0,3}(?:bits?)?|$NOT_A_SIZE)|4k",
        StreamQuality.P1440 to "1440(?:p\\d{0,3}(?:bits?)?|$NOT_A_SIZE)",
        StreamQuality.P1080 to "1080(?:[pi]\\d{0,3}(?:bits?)?|$NOT_A_SIZE)",
        StreamQuality.P720 to "720[pi]\\d{0,3}(?:bits?)?",
        StreamQuality.P480 to "576[pi]|540p|480[pi]",
        StreamQuality.P360 to "360p",
        StreamQuality.P240 to "240p",
        StreamQuality.P144 to "144p"
    )
    // "2K" in a release name is a 1080p-class picture (DCI 2K is 2048x1080), not 1440p.
    private val loosePattern = tokenRegex(
        StreamQuality.P2160 to "uhd",
        StreamQuality.P1440 to "qhd",
        StreamQuality.P1080 to "2k|fhd|full ?hd",
        StreamQuality.P720 to "hd",
        StreamQuality.P480 to "sd"
    )

    private class TokenPattern(val regex: Regex, val buckets: List<StreamQuality>)

    /** The best bucket any token of [pattern] in [text] names, or null. */
    private fun bestMatch(text: String?, pattern: TokenPattern): StreamQuality? {
        if (text.isNullOrBlank()) return null
        var best: StreamQuality? = null
        for (match in pattern.regex.findAll(text)) {
            val group = (1..pattern.buckets.size).first { match.groups[it] != null }
            val quality = pattern.buckets[group - 1]
            if (best == null || quality.ordinal < best.ordinal) best = quality
            if (best == StreamQuality.P2160) break
        }
        return best
    }

    private fun tokenRegex(vararg buckets: Pair<StreamQuality, String>): TokenPattern = TokenPattern(
        Regex(
            "(?<![a-z0-9])(?:" + buckets.joinToString("|") { "(${it.second})" } + ")(?![a-z0-9])",
            RegexOption.IGNORE_CASE
        ),
        buckets.map { it.first }
    )

    private class IdentityKey(val stream: Stream) {
        override fun equals(other: Any?): Boolean = other is IdentityKey && other.stream === stream
        override fun hashCode(): Int = System.identityHashCode(stream)
    }

    private class Parsed(val quality: StreamQuality?)

    /** Recently parsed streams (a few title's worth of source lists); the oldest drop out. */
    private val cache = object : LinkedHashMap<IdentityKey, Parsed>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<IdentityKey, Parsed>?): Boolean = size > 1024
    }
}

/**
 * A stream the player can switch to in place: a direct link or a cached debrid file. Torrents,
 * web links and YouTube trailers need their own flows, so the quality menu and the source
 * fallback leave them out.
 */
fun Stream.isInPlayerSwitchable(): Boolean {
    if (isExternal() || isTorrent() || youTubeIdToResolve() != null) return false
    when (debridCacheStatus?.state) {
        StreamDebridCacheState.CHECKING,
        StreamDebridCacheState.NOT_CACHED,
        StreamDebridCacheState.UNKNOWN -> return false
        StreamDebridCacheState.CACHED,
        null -> Unit
    }
    return !getStreamUrl().isNullOrBlank() || isDirectDebrid()
}

data class QualityOption(
    val quality: StreamQuality,
    val sourceCount: Int,
    val isCurrent: Boolean
) {
    val isAvailable: Boolean get() = sourceCount > 0 || isCurrent
}

object StreamQualityMenu {

    /**
     * The qualities that can be picked, best first: those some switchable source offers, plus the
     * one playing. Qualities no source offers aren't listed.
     */
    fun buildOptions(streams: List<Stream>, current: StreamQuality?): List<QualityOption> {
        val counts = IntArray(StreamQuality.entries.size)
        for (stream in streams) {
            if (!stream.isInPlayerSwitchable()) continue
            StreamQualityParser.parse(stream)?.let { counts[it.ordinal]++ }
        }
        return StreamQuality.menuOrder.mapNotNull { quality ->
            QualityOption(quality = quality, sourceCount = counts[quality.ordinal], isCurrent = quality == current)
                .takeIf { it.isAvailable }
        }
    }

    /** The qualities some switchable source offers (the one playing aside), best first. */
    fun availableQualities(streams: List<Stream>): List<StreamQuality> = buildOptions(streams, current = null).map { it.quality }

    /**
     * The source to play for [quality]: one from the addon already playing if it has that
     * quality (same source, different resolution), otherwise the first in addon order.
     */
    fun pickForQuality(
        streams: List<Stream>,
        quality: StreamQuality,
        preferredAddon: String?,
        isCurrentStream: (Stream) -> Boolean = { false }
    ): Stream? = rankForQuality(streams, quality, preferredAddon, isCurrentStream).firstOrNull()

    /** Every source with [quality], best first: the playing addon's, then the rest in addon order. */
    fun rankForQuality(
        streams: List<Stream>,
        quality: StreamQuality,
        preferredAddon: String?,
        isCurrentStream: (Stream) -> Boolean = { false }
    ): List<Stream> {
        val matches = streams.filter {
            it.isInPlayerSwitchable() && !isCurrentStream(it) && StreamQualityParser.parse(it) == quality
        }
        val (preferred, rest) = matches.partition { it.addonName == preferredAddon }
        return preferred + rest
    }
}

object StreamFallbackSelector {

    /**
     * Next source to try after the one from [failedAddon] failed: the addons after it in the
     * user's addon order (wrapping around), each offering its stream closest to [failedQuality].
     * The failed addon's other streams come last, since its server is the likeliest culprit.
     *
     * [streams] must already be in addon order (as the Streams screen and Sources panel list them).
     */
    fun next(
        streams: List<Stream>,
        failedAddon: String?,
        failedQuality: StreamQuality?,
        isTried: (Stream) -> Boolean
    ): Stream? {
        val candidates = streams.filter { it.isInPlayerSwitchable() && !isTried(it) }
        if (candidates.isEmpty()) return null
        val addonOrder = streams.map { it.addonName }.distinct()
        val failedIndex = addonOrder.indexOf(failedAddon)
        val rotation = if (failedIndex < 0) {
            addonOrder
        } else {
            addonOrder.drop(failedIndex + 1) + addonOrder.take(failedIndex) + addonOrder[failedIndex]
        }
        for (addon in rotation) {
            closestQuality(candidates.filter { it.addonName == addon }, failedQuality)?.let { return it }
        }
        return null
    }

    /** Same quality first, then the nearest step down, then up; unlabeled streams last. */
    internal fun closestQuality(streams: List<Stream>, target: StreamQuality?): Stream? {
        if (streams.isEmpty()) return null
        return streams.withIndex().minByOrNull { (index, stream) ->
            qualityDistance(StreamQualityParser.parse(stream), target) * 1_000 + index
        }?.value
    }

    internal fun qualityDistance(quality: StreamQuality?, target: StreamQuality?): Int {
        if (quality == null) return 100
        if (target == null) return 0
        val steps = quality.ordinal - target.ordinal // positive = lower resolution than target
        return if (steps >= 0) steps * 2 else -steps * 2 + 1
    }
}
