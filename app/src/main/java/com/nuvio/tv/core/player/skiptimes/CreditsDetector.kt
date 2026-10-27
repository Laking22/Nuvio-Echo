package com.nuvio.tv.core.player.skiptimes

import android.graphics.Bitmap
import com.nuvio.tv.data.repository.SkipInterval

/**
 * Finds end credits on black in keyframe pictures of a file's last minutes (the seek thumbnails), the way Intro
 * Skipper's black-frame pass does: a run of pictures that are almost all black and mostly lettered (a little
 * colourless bright text), long enough to be credits. Credits over the picture, or on a colour card, aren't found.
 */
internal object CreditsDetector {
    /** At or below this (0–255 luma) a pixel is black. */
    private const val BLACK_LUMA = 24
    /** At or above this a pixel is bright, as credit lettering is. */
    private const val BRIGHT_LUMA = 150
    /** Lettering's pixels are near grey: their channels differ by this at most. */
    private const val COLOURLESS_SPREAD = 48
    /**
     * A picture this black (share of pixels) is a black one. Intro Skipper's default is 85% of a full frame; shrunk to
     * a thumbnail, lettering blurs into grey, so a page dense with names reads a little less black.
     */
    private const val BLACK_SHARE = 0.80f
    /** A lettered picture's bright share: text, not a lit scene (nor a speck of light in a dark one). */
    private const val MIN_TEXT_SHARE = 0.003f
    private const val MAX_TEXT_SHARE = 0.15f
    /** Most of the bright pixels must be colourless for lettering. */
    private const val COLOURLESS_SHARE = 0.6f

    /** Where TV credits are looked for: the last 7.5 minutes (Intro Skipper's longest credits), every 15 s. */
    private const val TV_WINDOW_MS = 450_000L
    private const val TV_STEP_MS = 15_000L
    /** A movie's: its last 15 minutes, every 30 s. */
    private const val MOVIE_WINDOW_MS = 900_000L
    private const val MOVIE_STEP_MS = 30_000L
    /** The last picture looked at is this far from the end (the very last frames may not be keyframes). */
    private const val END_GUARD_MS = 3_000L
    /** Credits shorter than this are a title card or a gap between acts. */
    private const val MIN_CREDITS_MS = 20_000L
    /** Halvings that place each edge of a found run between two looks (15 s becomes about 4 s). */
    private const val REFINE_STEPS = 2
    /** What follows the credits for less than this (a studio logo) is part of them. */
    private const val SHORT_TAIL_MS = 15_000L

    /** How far before the end credits are looked for. */
    fun windowMs(movie: Boolean): Long = if (movie) MOVIE_WINDOW_MS else TV_WINDOW_MS

    class FrameStats(val black: Float, val bright: Float, val colourless: Float) {
        val isBlack: Boolean get() = black >= BLACK_SHARE
        val isLettered: Boolean
            get() = isBlack && bright in MIN_TEXT_SHARE..MAX_TEXT_SHARE && colourless >= COLOURLESS_SHARE

        override fun toString(): String =
            "black=%.2f bright=%.3f grey=%.2f".format(java.util.Locale.ROOT, black, bright, colourless)
    }

    fun stats(bitmap: Bitmap): FrameStats {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        return stats(pixels)
    }

    /** [pixels] as ARGB ints. */
    fun stats(pixels: IntArray): FrameStats {
        if (pixels.isEmpty()) return FrameStats(0f, 0f, 0f)
        var black = 0
        var bright = 0
        var colourless = 0
        for (c in pixels) {
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val luma = (r * 299 + g * 587 + b * 114) / 1000
            if (luma <= BLACK_LUMA) {
                black++
            } else if (luma >= BRIGHT_LUMA) {
                bright++
                if (maxOf(r, g, b) - minOf(r, g, b) <= COLOURLESS_SPREAD) colourless++
            }
        }
        val n = pixels.size.toFloat()
        return FrameStats(black / n, bright / n, if (bright == 0) 0f else colourless / bright.toFloat())
    }

    /**
     * The credits of a file [durationMs] long, or null. [grab] gives the picture's stats at a time (ms), null when
     * there's no picture; [log] hears each look, for tuning.
     */
    suspend fun find(
        durationMs: Long,
        movie: Boolean,
        grab: suspend (Long) -> FrameStats?,
        log: (String) -> Unit = {}
    ): SkipInterval? {
        if (durationMs <= 0) return null
        val window = windowMs(movie)
        val step = if (movie) MOVIE_STEP_MS else TV_STEP_MS
        val from = maxOf(durationMs / 2, durationMs - window)
        val last = durationMs - END_GUARD_MS
        if (last <= from) return null
        val times = ArrayList<Long>()
        var t = from
        while (t < last) {
            times += t
            t += step
        }
        times += last

        val looks = times.map { at -> at to grab(at).also { log("${at / 1000}s ${it ?: "no picture"}") } }

        // The first run of black pictures that's long enough and lettered on more than half of them (a black stretch
        // lettered on half or less is a gap between acts); a missing picture doesn't break it.
        var runStart = -1
        var runEnd = -1
        var i = 0
        while (i < looks.size && runStart < 0) {
            if (looks[i].second?.isBlack != true) {
                i++
                continue
            }
            var j = i
            var blacks = 0
            var lettered = 0
            var lastBlack = i
            while (j < looks.size) {
                val stats = looks[j].second
                if (stats != null) {
                    if (!stats.isBlack) break
                    blacks++
                    if (stats.isLettered) lettered++
                    lastBlack = j
                }
                j++
            }
            val spanMs = looks[lastBlack].first - looks[i].first + step
            if (blacks >= 2 && lettered * 2 > blacks && spanMs >= MIN_CREDITS_MS) {
                runStart = i
                runEnd = lastBlack
            }
            i = j
        }
        if (runStart < 0) return null

        suspend fun black(at: Long) = grab(at)?.isBlack == true

        // The start lies between the last picture before the run and its first.
        var before = looks.getOrNull(runStart - 1)?.first ?: (looks[runStart].first - step).coerceAtLeast(0L)
        var start = looks[runStart].first
        repeat(REFINE_STEPS) {
            val mid = (before + start) / 2
            if (black(mid)) start = mid else before = mid
        }
        // The end, between its last picture and the next; a run to the last look ends with the file.
        var end = durationMs
        if (runEnd < looks.lastIndex) {
            var inside = looks[runEnd].first
            var after = looks[runEnd + 1].first
            repeat(REFINE_STEPS) {
                val mid = (inside + after) / 2
                if (black(mid)) inside = mid else after = mid
            }
            end = after
        }
        if (durationMs - end <= SHORT_TAIL_MS) end = durationMs
        log("credits ${start / 1000}s to ${end / 1000}s")
        return SkipInterval(start / 1000.0, end / 1000.0, if (movie) "movie-credits" else "outro", "picture")
    }
}
