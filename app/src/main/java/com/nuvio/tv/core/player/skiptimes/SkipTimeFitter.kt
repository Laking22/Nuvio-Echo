package com.nuvio.tv.core.player.skiptimes

import com.nuvio.tv.core.player.seekpreview.MediaChapter
import com.nuvio.tv.data.repository.SkipInterval
import com.nuvio.tv.data.repository.SkipSegments
import com.nuvio.tv.data.repository.SkipTimes
import com.nuvio.tv.data.repository.creditsBeforeScene
import kotlin.math.abs

/**
 * Turns the databases' skip times into ones for the file that plays, from what that file says about itself: its
 * chapter names (exact for this release), its chapter starts and keyframes (where its cuts really are) and credits
 * found in its pictures ([CreditsDetector]). The chapter-name patterns follow Intro Skipper's (GPL-3.0, as this
 * app is), with Western "Main Titles" added.
 */
internal object SkipTimeFitter {
    private val PREVIEW = Regex(
        """(^|\s)(Preview|PV|Sneak\s?Peek|Coming\s?(Up|Soon)|Next\s+(time|on|episode)|Extra|Teaser|Trailer)(?![\s:]+End)(\s|:|$)""",
        RegexOption.IGNORE_CASE
    )
    /** The title sequence by name; wins over a plain "Intro", which in anime releases is the cold open before the OP. */
    private val OPENING = Regex(
        """(^|\s)(OP|Opening|Main\s+Titles?|Title\s+Sequence)(?![\s:]+End)(\s|:|$)""",
        RegexOption.IGNORE_CASE
    )
    private val INTRO = Regex("""(^|\s)(Intro|Introduction)(?![\s:]+End)(\s|:|$)""", RegexOption.IGNORE_CASE)
    private val RECAP = Regex(
        """(^|\s)(Re?cap|Sum{1,2}ary|Prev(ious(ly)?)?|(Last|Earlier)(\s\w+)?|Catch[ -]up)(?![\s:]+End)(\s|:|$)""",
        RegexOption.IGNORE_CASE
    )
    private val CREDITS = Regex("""(^|\s)(Credits?|ED|Ending|Outro)(?![\s:]+End)(\s|:|$)""", RegexOption.IGNORE_CASE)

    /** Databases whose edges are this close agree, and nothing is judged between them. */
    private const val AGREE_S = 3.0
    /** Databases' totals of edge distances closer by this pick the closer one. */
    private const val CLOSER_BY_S = 1.0
    /** An edge moves onto a chapter start this close. */
    private const val CHAPTER_SNAP_S = 5.0
    /**
     * An intro's or recap's end moves back onto a keyframe this close: skipping seeks to the next keyframe, which
     * from just past a cut can be a whole group of pictures into the episode.
     */
    private const val KEYFRAME_SNAP_S = 3.0
    /** Credits found in the pictures this close to a database's take their start from the pictures. */
    private const val CREDITS_AGREE_S = 60.0

    private val KINDS = listOf(SkipSegments.OPENING, SkipSegments.RECAP, SkipSegments.ENDING, SkipSegments.MOVIE_CREDITS)

    /**
     * The skip times for a file [durationMs] long. With nothing from the file (no [chapters], no [credits]) it's the
     * databases' best of each, unchanged. [chapterNames] lets named chapters set segments; [fit] judges between
     * databases that disagree and moves edges onto the file's chapter starts and keyframes.
     */
    fun combine(
        times: SkipTimes,
        durationMs: Long,
        movie: Boolean,
        chapters: List<MediaChapter>?,
        keyframesMs: LongArray?,
        credits: SkipInterval?,
        chapterNames: Boolean,
        fit: Boolean
    ): List<SkipInterval> {
        val named = if (chapterNames && chapters != null) fromChapters(chapters, durationMs, movie) else emptyList()
        val marks = chapters?.let(::chapterMarks) ?: DoubleArray(0)
        val keyframes = keyframesMs.takeIf { fit }
        val out = ArrayList<SkipInterval>()
        for (kind in KINDS) {
            val ofKind = { interval: SkipInterval -> SkipSegments.category(interval.type) == kind }
            var chosen = named.firstOrNull(ofKind)
            if (chosen == null) {
                chosen = if (fit) pick(times.candidates.filter(ofKind), marks) else times.intervals.firstOrNull(ofKind)
                if (chosen != null && fit) chosen = snap(chosen, kind, marks, keyframes, durationMs)
            }
            if (kind == SkipSegments.ENDING || kind == SkipSegments.MOVIE_CREDITS) {
                chosen = withCredits(chosen, credits?.takeIf(ofKind), kind in times.confirmedAbsent, durationMs)
            }
            chosen?.let(out::add)
        }
        // What has no kind (a movie's post-credits scene) passes through.
        val scenes = times.intervals.filter { SkipSegments.category(it.type) == null }
        return (out.creditsBeforeScene(scenes.minByOrNull { it.startTime }) + scenes).sortedBy { it.startTime }
    }

    /**
     * Segments the file's chapter names give, the first of each kind (an opening by name before a plain "Intro"); a
     * movie's chapters only give its credits.
     */
    fun fromChapters(chapters: List<MediaChapter>, durationMs: Long, movie: Boolean): List<SkipInterval> {
        if (chapters.isEmpty() || durationMs <= 0) return emptyList()
        val found = LinkedHashMap<String, SkipInterval>()
        val namedOpening = !movie && chapters.any { OPENING.containsMatchIn(it.title) && !PREVIEW.containsMatchIn(it.title) }
        chapters.forEachIndexed { i, chapter ->
            val title = chapter.title
            if (title.isBlank() || PREVIEW.containsMatchIn(title)) return@forEachIndexed
            val type = when {
                movie -> if (CREDITS.containsMatchIn(title)) "movie-credits" else null
                OPENING.containsMatchIn(title) -> "intro"
                INTRO.containsMatchIn(title) -> if (namedOpening) null else "intro"
                RECAP.containsMatchIn(title) -> "recap"
                CREDITS.containsMatchIn(title) -> "outro"
                else -> null
            } ?: return@forEachIndexed
            val kind = SkipSegments.category(type) ?: return@forEachIndexed
            if (kind in found) return@forEachIndexed
            val endMs = (chapter.endMs ?: chapters.getOrNull(i + 1)?.startMs ?: durationMs).coerceAtMost(durationMs)
            val interval = SkipInterval(chapter.startMs / 1000.0, endMs / 1000.0, type, "chapters")
            if (lengthFits(interval, kind) && SkipSegments.plausible(interval, durationMs)) found[kind] = interval
        }
        return found.values.toList()
    }

    private fun lengthFits(interval: SkipInterval, kind: String): Boolean {
        val length = interval.endTime - interval.startTime
        return when (kind) {
            SkipSegments.OPENING -> length in 10.0..180.0
            SkipSegments.RECAP -> length in 5.0..240.0
            SkipSegments.ENDING -> length in 10.0..600.0
            else -> length in 10.0..1200.0
        }
    }

    /** Chapter starts (s), the file's start left out: every cut a chapter marks. */
    private fun chapterMarks(chapters: List<MediaChapter>): DoubleArray =
        chapters.map { it.startMs / 1000.0 }.filter { it > 0.5 }.distinct().sorted().toDoubleArray()

    /**
     * Of the databases' times for one segment (highest priority first), the one with more edges close enough to snap
     * onto the file's chapter starts, then the closer one; the first when they agree or tie.
     */
    private fun pick(candidates: List<SkipInterval>, marks: DoubleArray): SkipInterval? {
        val first = candidates.firstOrNull() ?: return null
        if (candidates.size < 2 || marks.isEmpty()) return first
        val agree = candidates.all { abs(it.startTime - first.startTime) <= AGREE_S && abs(it.endTime - first.endTime) <= AGREE_S }
        if (agree) return first
        fun nearMarks(interval: SkipInterval): List<Double> = listOf(interval.startTime, interval.endTime)
            .mapNotNull { edge -> nearest(marks, edge)?.let { abs(it - edge) }?.takeIf { it <= CHAPTER_SNAP_S } }
        var best = first
        var bestNear = nearMarks(first)
        for (candidate in candidates.drop(1)) {
            val near = nearMarks(candidate)
            val better = near.size > bestNear.size ||
                (near.size == bestNear.size && near.isNotEmpty() && near.sum() < bestNear.sum() - CLOSER_BY_S)
            if (better) {
                best = candidate
                bestNear = near
            }
        }
        return best
    }

    /** Edges onto a chapter start close by; else an intro's or recap's end back onto the keyframe at or just before it. */
    private fun snap(interval: SkipInterval, kind: String, marks: DoubleArray, keyframesMs: LongArray?, durationMs: Long): SkipInterval {
        val length = if (durationMs > 0) durationMs / 1000.0 else Double.MAX_VALUE
        fun onChapter(edge: Double): Double? = nearest(marks, edge)?.takeIf { abs(it - edge) <= CHAPTER_SNAP_S }
        val start = onChapter(interval.startTime) ?: interval.startTime
        var end = interval.endTime
        if (end.isFinite() && end < length) {
            val chapterEnd = onChapter(end)
            end = when {
                chapterEnd != null -> chapterEnd
                (kind == SkipSegments.OPENING || kind == SkipSegments.RECAP) && keyframesMs != null ->
                    keyframeAtOrBefore(keyframesMs, end)?.takeIf { end - it <= KEYFRAME_SNAP_S } ?: end
                else -> end
            }
        }
        if (end <= start) return interval
        return if (start == interval.startTime && end == interval.endTime) interval else interval.copy(startTime = start, endTime = end)
    }

    /**
     * The database's ending, its start set by the credits found in the pictures when the two are close; the pictures'
     * credits alone when no database has an ending, unless one confirmed the episode has none ([confirmedNone]).
     * Named chapters win over both.
     */
    private fun withCredits(db: SkipInterval?, found: SkipInterval?, confirmedNone: Boolean, durationMs: Long): SkipInterval? {
        if (found == null || db?.provider == "chapters") return db
        if (db == null) return found.takeUnless { confirmedNone }
        if (abs(found.startTime - db.startTime) > CREDITS_AGREE_S) return db
        val fileEnd = if (durationMs > 0) durationMs / 1000.0 else Double.MAX_VALUE
        // The database knows how the credits end, unless it runs past this file or the pictures agree anyway.
        val end = if (db.endTime > fileEnd || abs(found.endTime - db.endTime) <= CREDITS_AGREE_S) found.endTime else db.endTime
        return db.copy(startTime = found.startTime, endTime = maxOf(end, found.startTime + 1.0))
    }

    private fun nearest(sorted: DoubleArray, value: Double): Double? {
        if (sorted.isEmpty()) return null
        var lo = 0
        var hi = sorted.size - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (sorted[mid] < value) lo = mid + 1 else hi = mid
        }
        val after = sorted[lo]
        val before = if (lo > 0) sorted[lo - 1] else after
        return if (abs(before - value) <= abs(after - value)) before else after
    }

    private fun keyframeAtOrBefore(keyframesMs: LongArray, seconds: Double): Double? {
        val target = (seconds * 1000).toLong()
        var lo = 0
        var hi = keyframesMs.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (keyframesMs[mid] <= target) {
                found = mid
                lo = mid + 1
            } else hi = mid - 1
        }
        return if (found < 0) null else keyframesMs[found] / 1000.0
    }
}
