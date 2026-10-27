package com.nuvio.tv.core.calendar

import com.nuvio.tv.ui.screens.calendar.CalendarGroup
import com.nuvio.tv.ui.screens.calendar.CalendarViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class CalendarTest {
    private val today = LocalDate.of(2026, 10, 12)

    private fun ep(season: Int, episode: Int, date: LocalDate?, type: String? = null) =
        CalendarEpisode(season, episode, null, date, null, null, null, type)

    private fun show(
        id: String,
        status: CalendarStatus = CalendarStatus.RETURNING,
        next: CalendarEpisode? = null,
        last: CalendarEpisode? = null,
        fetchedAt: Long = 1L
    ) = CalendarShow(id, id, null, null, status, next, last, null, null, null, null, null, fetchedAt)

    @Test
    fun `status words from TMDB and Cinemeta`() {
        assertEquals(CalendarStatus.RETURNING, CalendarStatus.from("Returning Series"))
        assertEquals(CalendarStatus.RETURNING, CalendarStatus.from("Continuing"))
        assertEquals(CalendarStatus.IN_PRODUCTION, CalendarStatus.from("In Production"))
        assertEquals(CalendarStatus.IN_PRODUCTION, CalendarStatus.from("Planned"))
        assertEquals(CalendarStatus.ENDED, CalendarStatus.from("Ended"))
        assertEquals(CalendarStatus.CANCELED, CalendarStatus.from("Canceled"))
        assertEquals(CalendarStatus.CANCELED, CalendarStatus.from("Cancelled"))
        assertEquals(CalendarStatus.UNKNOWN, CalendarStatus.from(null))
    }

    @Test
    fun `markers`() {
        assertEquals(CalendarMarker.SERIES_PREMIERE, ep(1, 1, today).marker)
        assertEquals(CalendarMarker.SEASON_PREMIERE, ep(3, 1, today).marker)
        assertEquals(CalendarMarker.FINALE, ep(3, 8, today, "finale").marker)
        assertEquals(CalendarMarker.MID_SEASON, ep(3, 4, today, "mid_season").marker)
        assertEquals(CalendarMarker.NONE, ep(3, 4, today, "standard").marker)
    }

    @Test
    fun `next episode in the past is not upcoming`() {
        assertNull(show("a", next = ep(1, 2, today.minusDays(1))).upcoming(today))
        assertEquals(2, show("a", next = ep(1, 2, today)).upcoming(today)?.episode)
    }

    @Test
    fun `dated shows soonest first, then undated by status, finished and unasked ones hidden`() {
        val snapshot = CalendarRepository.Snapshot(
            shows = listOf(
                show("later", next = ep(2, 1, today.plusDays(20))),
                show("ended", status = CalendarStatus.ENDED, last = ep(5, 10, today.minusDays(300))),
                show("unknown", status = CalendarStatus.UNKNOWN),
                show("today", next = ep(1, 5, today)),
                show("production", status = CalendarStatus.IN_PRODUCTION),
                show("unasked", fetchedAt = 0L),
                show("week", next = ep(4, 3, today.plusDays(6))),
                show("returning", status = CalendarStatus.RETURNING),
                // A canceled show that still has one more episode coming keeps its date.
                show("canceled-dated", status = CalendarStatus.CANCELED, next = ep(2, 9, today.plusDays(7)))
            ),
            followed = 9,
            reminders = mapOf("week" to CalendarReminder("week", "week", today, null)),
            loaded = true
        )
        val state = CalendarViewModel.build(snapshot, today)
        assertEquals(
            listOf("today", "week", "canceled-dated", "later", "returning", "production", "unknown"),
            state.entries.map { it.show.id }
        )
        assertEquals(
            listOf(
                CalendarGroup.TODAY, CalendarGroup.WEEK, CalendarGroup.LATER, CalendarGroup.LATER,
                CalendarGroup.NO_DATE, CalendarGroup.NO_DATE, CalendarGroup.NO_DATE
            ),
            state.entries.map { it.group }
        )
        assertEquals(4, state.dated)
        assertEquals(listOf(true), state.entries.filter { it.reminder }.map { it.show.id == "week" })
        assertEquals(listOf("week"), state.byDate[today.plusDays(6)]?.map { it.show.id })
    }
}
