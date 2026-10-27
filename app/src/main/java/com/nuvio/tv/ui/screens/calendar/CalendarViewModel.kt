package com.nuvio.tv.ui.screens.calendar

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.calendar.CalendarEpisode
import com.nuvio.tv.core.calendar.CalendarRepository
import com.nuvio.tv.core.calendar.CalendarShow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import javax.inject.Inject

enum class CalendarGroup { TODAY, WEEK, LATER, NO_DATE }

/** One show in the Calendar: its next episode when one is announced, else the last one that aired. */
@Immutable
data class CalendarEntry(
    val show: CalendarShow,
    /** The episode the row is about: the next one, or for shows without a date the latest that aired. */
    val episode: CalendarEpisode?,
    /** The next episode's day; null when none is announced. */
    val date: LocalDate?,
    val daysAway: Long?,
    val group: CalendarGroup,
    val reminder: Boolean
)

@Immutable
data class CalendarUiState(
    /** Dated shows soonest first, then the ones without a date (returning before ended), each once. */
    val entries: List<CalendarEntry> = emptyList(),
    val byDate: Map<LocalDate, List<CalendarEntry>> = emptyMap(),
    val dated: Int = 0,
    val followed: Int = 0,
    val refreshing: Boolean = false,
    val loaded: Boolean = false,
    val today: LocalDate = LocalDate.now()
)

@HiltViewModel
class CalendarViewModel @Inject constructor(
    private val repository: CalendarRepository
) : ViewModel() {

    val uiState: StateFlow<CalendarUiState> = repository.state
        .map { build(it, LocalDate.now()) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, build(repository.state.value, LocalDate.now()))

    /** The show focus was last on, so coming back from its page lands on it again. */
    var lastFocusedId: String? = null

    init {
        viewModelScope.launch { repository.refresh() }
    }

    fun toggleReminder(showId: String) {
        viewModelScope.launch { repository.toggleReminder(showId) }
    }

    companion object {
        internal fun build(snapshot: CalendarRepository.Snapshot, today: LocalDate): CalendarUiState {
            val dated = ArrayList<CalendarEntry>()
            val undated = ArrayList<CalendarEntry>()
            for (show in snapshot.shows) {
                val reminder = show.id in snapshot.reminders
                val next = show.upcoming(today)
                val date = next?.airDate
                if (next != null && date != null) {
                    val days = ChronoUnit.DAYS.between(today, date)
                    val group = when {
                        days <= 0L -> CalendarGroup.TODAY
                        days < 7L -> CalendarGroup.WEEK
                        else -> CalendarGroup.LATER
                    }
                    dated += CalendarEntry(show, next, date, days, group, reminder)
                } else {
                    // Not asked yet: it may well turn out to have a date, or to have ended.
                    if (show.fetchedAt == 0L) continue
                    // Finished shows without a new episode have nothing left to wait for.
                    if (show.status.finished) continue
                    undated += CalendarEntry(show, show.last, null, null, CalendarGroup.NO_DATE, reminder)
                }
            }
            dated.sortWith(compareBy<CalendarEntry> { it.date }.thenBy { it.show.title.lowercase() })
            undated.sortWith(compareBy<CalendarEntry> { it.show.status.ordinal }.thenBy { it.show.title.lowercase() })
            return CalendarUiState(
                entries = dated + undated,
                byDate = dated.groupBy { it.date!! },
                dated = dated.size,
                followed = snapshot.followed,
                refreshing = snapshot.refreshing,
                loaded = snapshot.loaded,
                today = today
            )
        }
    }
}
