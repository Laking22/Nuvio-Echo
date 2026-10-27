package com.nuvio.tv.ui.screens.detail

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.data.local.ReleaseReminder
import com.nuvio.tv.data.local.ReleaseRemindersStore
import com.nuvio.tv.data.local.readReleaseReminders
import com.nuvio.tv.data.repository.TitleCredits
import com.nuvio.tv.data.repository.TitleCreditsRepository
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaCastMember
import com.nuvio.tv.domain.model.MetaTrailer
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class NewDetailsLogicTest {

    private fun meta(
        type: ContentType = ContentType.MOVIE,
        released: String? = null,
        releaseInfo: String? = null,
        castMembers: List<MetaCastMember> = emptyList()
    ) = Meta(
        id = "tt1", type = type, name = "Title", poster = null, posterShape = PosterShape.POSTER, background = null,
        logo = null, description = null, releaseInfo = releaseInfo, imdbRating = null, genres = emptyList(),
        runtime = null, director = emptyList(), cast = emptyList(), castMembers = castMembers, videos = emptyList(),
        country = null, awards = null, language = null, links = emptyList(), released = released
    )

    private fun video(released: String?, season: Int = 2, episode: Int = 5) = Video(
        id = "tt1:$season:$episode", title = "Ep", released = released, thumbnail = null, season = season,
        episode = episode, overview = null
    )

    @Test
    fun `reminders read back by key, bad entries skipped`() {
        val movie = ReleaseReminder("tt9", "movie", "Film", null, null, null, LocalDate.of(2027, 3, 4))
        val ep = ReleaseReminder("tmdb:5", "series", "Show", 2, 5, "Finale", LocalDate.of(2026, 11, 1))
        val prefs = mutablePreferencesOf(
            stringPreferencesKey(movie.key) to ReleaseRemindersStore.encode(movie),
            stringPreferencesKey(ep.key) to ReleaseRemindersStore.encode(ep),
            stringPreferencesKey("m:bad") to "not-a-date\tmovie\tX\t"
        )
        val read = readReleaseReminders(prefs)
        assertEquals(setOf(movie.key, ep.key), read.keys)
        assertEquals(movie, read[movie.key])
        assertEquals(ep, read[ep.key])
    }

    @Test
    fun `only episodes that haven't aired get a reminder`() {
        val future = LocalDate.now().plusDays(10)
        assertNotNull(upcomingReminder(meta(ContentType.SERIES), video(future.toString())))
        assertEquals(future, upcomingReminder(meta(ContentType.SERIES), video("${future}T12:00:00Z"))?.releaseDate)
        assertNull(upcomingReminder(meta(ContentType.SERIES), video(LocalDate.now().minusDays(3).toString())))
        assertNull(upcomingReminder(meta(ContentType.SERIES), video(null)))
    }

    @Test
    fun `an unreleased movie gets a reminder, a year alone reminds on its first day`() {
        val future = LocalDate.now().plusMonths(2)
        assertEquals(future, upcomingReminder(meta(released = future.toString()))?.releaseDate)
        assertNull(upcomingReminder(meta(released = "2019-11-27")))
        val nextYear = LocalDate.now().year + 1
        assertEquals(LocalDate.of(nextYear, 1, 1), upcomingReminder(meta(releaseInfo = "$nextYear"))?.releaseDate)
        assertNull(upcomingReminder(meta(releaseInfo = "2019")))
    }

    @Test
    fun `crew is one entry per person, key departments first`() {
        val crew = TitleCreditsRepository.orderCrew(listOf(
            TitleCreditsRepository.CrewEntry(1, "Composer", "Sound", listOf("Original Music Composer"), null, 0),
            TitleCreditsRepository.CrewEntry(2, "Rian", "Writing", listOf("Writer"), null, 0),
            TitleCreditsRepository.CrewEntry(2, "Rian", "Directing", listOf("Director"), null, 0),
            TitleCreditsRepository.CrewEntry(3, "Prod", "Production", listOf("Producer"), null, 0)
        ))
        assertEquals(listOf("Rian", "Prod", "Composer"), crew.map { it.name })
        assertEquals("Writer, Director", crew.first().character)
        assertEquals(2, crew.first().tmdbId)
    }

    @Test
    fun `addon cast gets TMDB ids by name and TMDB crew follows the lead credits`() {
        val m = meta(castMembers = listOf(
            MetaCastMember("Daniel Craig", "Benoit Blanc"),
            MetaCastMember("Rian Johnson", "Director")
        ))
        val credits = TitleCredits(
            cast = listOf(MetaCastMember("Daniel Craig", "Benoit Blanc", "p.jpg", 8784)),
            crew = listOf(
                MetaCastMember("Rian Johnson", "Director, Writer", null, 67367),
                MetaCastMember("Nathan Johnson", "Original Music Composer", null, 59357)
            )
        )
        val (cast, crew) = mergeCredits(m, credits)
        assertEquals(8784, cast.single().tmdbId)
        assertEquals("p.jpg", cast.single().photo)
        assertEquals(listOf("Rian Johnson", "Nathan Johnson"), crew.map { it.name })
        assertEquals(67367, crew.first().tmdbId)
    }
    @Test
    fun `playable trailers put trailers first, drop duplicates and blanks, and keep bare ids`() {
        val m = meta(type = ContentType.SERIES).copy(
            trailers = listOf(
                MetaTrailer(type = "Teaser", name = "Teaser", ytId = "aaaaaaaaaaa"),
                MetaTrailer(type = "Featurette", name = "Behind", ytId = "bbbbbbbbbbb"),
                MetaTrailer(type = "Trailer", name = "Trailer 1", ytId = "ccccccccccc"),
                MetaTrailer(type = "Trailer", name = "Dup", ytId = "ccccccccccc"),
                MetaTrailer(type = "Trailer", name = "No id", ytId = " ")
            ),
            trailerYtIds = listOf("ccccccccccc", "ddddddddddd")
        )
        val list = m.playableTrailers()
        assertEquals(listOf("ccccccccccc", "ddddddddddd", "aaaaaaaaaaa", "bbbbbbbbbbb"), list.map { it.ytId })
        assertEquals("Trailer 1", list.first().name)
    }
}
