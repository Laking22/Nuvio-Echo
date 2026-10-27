package com.nuvio.tv.ui.screens.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.local.ReleaseReminder
import com.nuvio.tv.data.local.ReleaseRemindersStore
import com.nuvio.tv.data.repository.TitleCredits
import com.nuvio.tv.data.repository.TitleCreditsRepository
import com.nuvio.tv.data.local.TitleRating
import com.nuvio.tv.data.local.TitleRatings
import com.nuvio.tv.data.local.TitleRatingsStore
import com.nuvio.tv.data.repository.TitleReviewsRepository
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.TraktCommentReview
import com.nuvio.tv.domain.model.Video
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Reviews for a title or an episode, as the new details page shows them. */
data class ReviewsState(
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val items: List<TraktCommentReview> = emptyList()
)

/**
 * What the new details page adds to [MetaDetailsViewModel]: the thumbs (stored per profile in [TitleRatingsStore],
 * read by Home's personal rows and Smart Highlights) and reviews ([TitleReviewsRepository]).
 */
@HiltViewModel
class NewDetailsViewModel @Inject constructor(
    private val ratingsStore: TitleRatingsStore,
    private val reviewsRepository: TitleReviewsRepository,
    private val creditsRepository: TitleCreditsRepository,
    private val remindersStore: ReleaseRemindersStore,
    savedStateHandle: SavedStateHandle
) : ViewModel() {
    private val itemId: String? = savedStateHandle["itemId"]
    private val itemType: String? = savedStateHandle["itemType"]

    val ratings: StateFlow<TitleRatings> = ratingsStore.ratings
        .stateIn(viewModelScope, SharingStarted.Eagerly, TitleRatings())

    private val _titleReviews = MutableStateFlow(ReviewsState())
    val titleReviews: StateFlow<ReviewsState> = _titleReviews.asStateFlow()

    private val _episodeReviews = MutableStateFlow<Map<Pair<Int, Int>, ReviewsState>>(emptyMap())
    val episodeReviews: StateFlow<Map<Pair<Int, Int>, ReviewsState>> = _episodeReviews.asStateFlow()

    private var reviewsFor: String? = null

    /** TMDB's full cast and crew, for the Cast & Crew panel; empty until loaded (or without a TMDB key). */
    private val _credits = MutableStateFlow(TitleCredits())
    val credits: StateFlow<TitleCredits> = _credits.asStateFlow()

    /** "Remind me" entries of the active profile, by [ReleaseReminder.key]. */
    val reminders: StateFlow<Map<String, ReleaseReminder>> = remindersStore.reminders
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Starts loading the title's reviews and credits once per title. */
    fun bind(meta: Meta) {
        if (reviewsFor == meta.id) return
        reviewsFor = meta.id
        _titleReviews.value = ReviewsState(loading = true)
        viewModelScope.launch {
            val items = runCatching { reviewsRepository.titleReviews(meta, itemId, itemType) }.getOrDefault(emptyList())
            _titleReviews.value = ReviewsState(loading = false, loaded = true, items = items)
        }
        viewModelScope.launch {
            _credits.value = runCatching { creditsRepository.credits(meta) }.getOrDefault(TitleCredits())
        }
    }

    fun toggleReminder(reminder: ReleaseReminder) {
        viewModelScope.launch { remindersStore.toggle(reminder) }
    }

    fun loadEpisodeReviews(meta: Meta, video: Video) {
        val key = (video.season ?: return) to (video.episode ?: return)
        val existing = _episodeReviews.value[key]
        if (existing != null && (existing.loading || existing.loaded)) return
        _episodeReviews.update { it + (key to ReviewsState(loading = true)) }
        viewModelScope.launch {
            val items = runCatching { reviewsRepository.episodeReviews(meta, itemId, itemType, video) }
                .getOrDefault(emptyList())
            _episodeReviews.update { it + (key to ReviewsState(loading = false, loaded = true, items = items)) }
        }
    }

    /** Sets the title's thumbs; choosing the current one again clears it. */
    fun toggleTitleRating(meta: Meta, rating: TitleRating) {
        val next = if (ratings.value.title(meta.id) == rating) null else rating
        viewModelScope.launch { ratingsStore.rateTitle(meta.id, meta.apiType, meta.name, next) }
    }

    fun toggleEpisodeRating(meta: Meta, video: Video, rating: TitleRating) {
        val season = video.season ?: return
        val episode = video.episode ?: return
        val current = ratings.value.episodesOf(meta.id)[season to episode]
        val next = if (current == rating) null else rating
        viewModelScope.launch { ratingsStore.rateEpisode(meta.id, meta.apiType, meta.name, season, episode, next) }
    }
}
