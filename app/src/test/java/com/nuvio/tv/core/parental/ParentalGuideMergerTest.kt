package com.nuvio.tv.core.parental

import com.nuvio.tv.data.remote.api.AniListMedia
import com.nuvio.tv.data.remote.api.AniListTag
import com.nuvio.tv.data.remote.api.ImdbApiParentsGuideCategory
import com.nuvio.tv.data.remote.api.ImdbApiSeverityBreakdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParentalGuideMergerTest {

    private fun imdb(category: String, vararg votes: Pair<String, Int>) = ImdbApiParentsGuideCategory(
        category = category,
        severityBreakdowns = votes.map { ImdbApiSeverityBreakdown(it.first, it.second) }
    )

    @Test
    fun `imdb severity wins over weaker sources for the same category`() {
        val imdb = ParentalGuideMerger.fromImdb(listOf(imdb("VIOLENCE", "none" to 20, "severe" to 900)))
        val tmdb = ParentalGuideMerger.fromTmdb(listOf("Violence"), emptyList())
        val summary = ParentalGuideMerger.merge("PG-13", listOf(tmdb, imdb))
        assertEquals(listOf(ParentalCategory.VIOLENCE to ParentalLevel.SEVERE), summary.categories)
        assertEquals("PG-13", summary.rating)
    }

    @Test
    fun `a well-voted imdb none blocks keyword guesses but a thin one does not`() {
        val solidNone = ParentalGuideMerger.fromImdb(listOf(imdb("SEXUAL_CONTENT", "none" to 1065, "mild" to 161)))
        val thinNone = ParentalGuideMerger.fromImdb(listOf(imdb("SEXUAL_CONTENT", "none" to 3)))
        val tmdb = ParentalGuideMerger.fromTmdb(emptyList(), listOf("nudity"))
        assertTrue(ParentalGuideMerger.merge(null, listOf(solidNone, tmdb)).categories.isEmpty())
        assertEquals(
            listOf(ParentalCategory.NUDITY to ParentalLevel.PRESENT),
            ParentalGuideMerger.merge(null, listOf(thinNone, tmdb)).categories
        )
    }

    @Test
    fun `anilist fills anime categories and notes, skipping spoiler and weak tags`() {
        val media = AniListMedia(
            id = 1,
            genres = listOf("Action", "Horror"),
            tags = listOf(
                AniListTag("Gore", rank = 85),
                AniListTag("Nudity", rank = 62),
                AniListTag("Suicide", rank = 70, isMediaSpoiler = true),
                AniListTag("Drugs", rank = 20)
            )
        )
        val report = ParentalGuideMerger.fromAniList(media)
        assertEquals(ParentalLevel.SEVERE, report.levels[ParentalCategory.VIOLENCE])
        assertEquals(ParentalLevel.MODERATE, report.levels[ParentalCategory.NUDITY])
        assertEquals(ParentalLevel.MODERATE, report.levels[ParentalCategory.FRIGHTENING])
        assertNull(report.levels[ParentalCategory.ALCOHOL])
        assertEquals(setOf(ContentNote.GORE), report.notes)
    }

    @Test
    fun `tmdb descriptors and keywords map to categories and notes`() {
        val report = ParentalGuideMerger.fromTmdb(
            descriptors = listOf("Threat", "Injury", "Sexuality"),
            keywords = listOf("Suicide", "drug use", "heist")
        )
        assertEquals(
            setOf(ParentalCategory.FRIGHTENING, ParentalCategory.VIOLENCE, ParentalCategory.NUDITY, ParentalCategory.ALCOHOL),
            report.levels.keys
        )
        assertEquals(setOf(ContentNote.SUICIDE), report.notes)
    }

    @Test
    fun `rating alone is still a guide and nothing at all is empty`() {
        val ratingOnly = ParentalGuideMerger.merge("TV-MA", emptyList())
        assertFalse(ratingOnly.isEmpty)
        assertTrue(ratingOnly.categories.isEmpty())
        assertTrue(ParentalGuideMerger.merge(" ", emptyList()).isEmpty)
    }

    @Test
    fun `categories are ordered most severe first`() {
        val imdb = ParentalGuideMerger.fromImdb(
            listOf(
                imdb("PROFANITY", "mild" to 400),
                imdb("VIOLENCE", "severe" to 400),
                imdb("FRIGHTENING_INTENSE_SCENES", "moderate" to 400)
            )
        )
        val summary = ParentalGuideMerger.merge(null, listOf(imdb, ParentalGuideMerger.fromTmdb(emptyList(), listOf("nudity"))))
        assertEquals(
            listOf(ParentalCategory.VIOLENCE, ParentalCategory.FRIGHTENING, ParentalCategory.PROFANITY, ParentalCategory.NUDITY),
            summary.categories.map { it.first }
        )
    }
}
