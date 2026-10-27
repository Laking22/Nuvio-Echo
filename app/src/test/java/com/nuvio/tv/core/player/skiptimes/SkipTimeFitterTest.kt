package com.nuvio.tv.core.player.skiptimes

import com.nuvio.tv.core.player.seekpreview.MediaChapter
import com.nuvio.tv.data.repository.SkipInterval
import com.nuvio.tv.data.repository.SkipSegments
import com.nuvio.tv.data.repository.SkipTimes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SkipTimeFitterTest {

    private val duration = 3_180_000L

    private fun chapters(vararg c: Pair<Long, String>) = c.map { MediaChapter(it.first, null, it.second) }

    private fun combine(
        times: SkipTimes,
        chapters: List<MediaChapter>? = null,
        keyframesMs: LongArray? = null,
        credits: SkipInterval? = null,
        chapterNames: Boolean = true,
        fit: Boolean = false,
        movie: Boolean = false
    ) = SkipTimeFitter.combine(times, duration, movie, chapters, keyframesMs, credits, chapterNames, fit)

    @Test
    fun `named chapters give the opening and ending, a preview none`() {
        val list = SkipTimeFitter.fromChapters(
            chapters(0L to "Prologue", 95_000L to "Opening", 185_000L to "Part A", 3_000_000L to "Ending", 3_090_000L to "Preview"),
            duration,
            movie = false
        )
        assertEquals(listOf("intro" to (95.0 to 185.0), "outro" to (3000.0 to 3090.0)), list.map { it.type to (it.startTime to it.endTime) })
    }

    @Test
    fun `an anime's OP wins over the cold open its release calls Intro`() {
        // Bocchi the Rock E4 as released: "Intro" is the cold open before the OP.
        val list = SkipTimeFitter.fromChapters(
            chapters(0L to "Intro", 105_000L to "OP", 195_000L to "Episode", 1_325_000L to "ED", 1_415_000L to "Preview"),
            1_440_000L,
            movie = false
        )
        assertEquals(listOf("intro" to (105.0 to 195.0), "outro" to (1325.0 to 1415.0)), list.map { it.type to (it.startTime to it.endTime) })
        // Alone, "Intro" is the title sequence.
        val western = SkipTimeFitter.fromChapters(chapters(0L to "Cold Open", 240_000L to "Intro", 290_000L to "Act One"), duration, movie = false)
        assertEquals(listOf(240.0 to 290.0), western.map { it.startTime to it.endTime })
    }

    @Test
    fun `opening credits are the intro, and a movie only takes its credits`() {
        val tv = SkipTimeFitter.fromChapters(chapters(0L to "Cold Open", 300_000L to "Opening Credits", 360_000L to "Act 1"), duration, movie = false)
        assertEquals(listOf("intro"), tv.map { it.type })
        val movie = SkipTimeFitter.fromChapters(chapters(0L to "Opening", 60_000L to "Chapter 2", 2_700_000L to "End Credits"), duration, movie = true)
        assertEquals(listOf("movie-credits"), movie.map { it.type })
    }

    @Test
    fun `a chapter too long to be an intro is left alone`() {
        assertEquals(emptyList<SkipInterval>(), SkipTimeFitter.fromChapters(chapters(0L to "Opening", 600_000L to "Part B"), duration, movie = false))
    }

    @Test
    fun `nothing from the file keeps the databases' times`() {
        val intro = SkipInterval(461.0, 584.0, "intro", "introdb")
        val outro = SkipInterval(3091.0, 3165.0, "outro", "introdb")
        val times = SkipTimes(listOf(intro, outro))
        assertEquals(listOf(intro, outro), combine(times, chapterNames = false))
    }

    @Test
    fun `named chapters win over the databases`() {
        val times = SkipTimes(listOf(SkipInterval(461.0, 584.0, "intro", "introdb")))
        val list = combine(times, chapters(0L to "Cold Open", 454_000L to "Intro", 572_000L to "Episode"))
        assertEquals(listOf(SkipInterval(454.0, 572.0, "intro", "chapters")), list)
    }

    @Test
    fun `fit picks the database whose edges are on the file's cuts, and snaps onto them`() {
        val theIntroDb = SkipInterval(454.2, 572.6, "intro", "theintrodb")
        val introDb = SkipInterval(461.0, 584.0, "intro", "introdb")
        val times = SkipTimes(listOf(theIntroDb), candidates = listOf(theIntroDb, introDb))
        // Unnamed chapters at IntroDB's edges: IntroDB's times are this release's.
        val marks = chapters(0L to "Chapter 1", 461_400L to "Chapter 2", 583_000L to "Chapter 3")
        val list = combine(times, marks, fit = true)
        assertEquals(listOf(SkipInterval(461.4, 583.0, "intro", "introdb")), list)
    }

    @Test
    fun `fit prefers the database whose both edges can snap onto cuts`() {
        // Game of Thrones S1E1 (PenguPlay 4K): unnamed chapters at 7:18.438 and 8:58.538.
        val theIntroDb = SkipInterval(435.7, 534.9, "intro", "theintrodb")
        val introDb = SkipInterval(437.0, 531.0, "intro", "introdb")
        val times = SkipTimes(listOf(theIntroDb), candidates = listOf(theIntroDb, introDb))
        val marks = chapters(0L to "00:00:00.000", 8_008L to "00:00:08.008", 438_438L to "00:07:18.438", 538_538L to "00:08:58.538", 1_096_512L to "00:18:16.512")
        val intro = combine(times, marks, fit = true).single()
        assertEquals("theintrodb", intro.provider)
        assertEquals(438.438, intro.startTime, 0.001)
        assertEquals(538.538, intro.endTime, 0.001)
    }

    @Test
    fun `fit moves an intro's end back onto the keyframe before it`() {
        val intro = SkipInterval(30.0, 90.0, "intro", "theintrodb")
        val list = combine(SkipTimes(listOf(intro)), keyframesMs = longArrayOf(0, 44_000, 88_400, 92_000), fit = true)
        assertEquals(88.4, list.single().endTime, 0.001)
        // Without Fit nothing moves.
        assertEquals(90.0, combine(SkipTimes(listOf(intro)), keyframesMs = longArrayOf(0, 88_400)).single().endTime, 0.001)
    }

    @Test
    fun `credits in the picture set a nearby ending's start, or stand alone`() {
        val found = SkipInterval(3_102.0, 3_180.0, "outro", "picture")
        val db = SkipInterval(3_091.0, 3_165.0, "outro", "introdb")
        val merged = combine(SkipTimes(listOf(db)), credits = found).single()
        assertEquals(3_102.0, merged.startTime, 0.001)
        assertEquals("introdb", merged.provider)
        assertEquals(found, combine(SkipTimes.NONE, credits = found).single())
        // A database far off is kept; one that confirmed no outro keeps the pictures out.
        val far = SkipInterval(2_900.0, 2_990.0, "outro", "introdb")
        assertEquals(far, combine(SkipTimes(listOf(far)), credits = found).single())
        assertNull(combine(SkipTimes(emptyList(), confirmedAbsent = setOf(SkipSegments.ENDING)), credits = found).singleOrNull())
    }

    @Test
    fun `a movie's credits still stop at its post-credits scene`() {
        val credits = SkipInterval(2_800.0, 3_180.0, "movie-credits", "theintrodb")
        val scene = SkipInterval(3_100.0, 3_170.0, "post-credits", "introdb")
        val list = combine(SkipTimes(listOf(credits, scene), candidates = listOf(credits, scene)), fit = true, movie = true)
        assertEquals(listOf(SkipInterval(2_800.0, 3_100.0, "movie-credits", "theintrodb"), scene), list)
    }
}
