package com.nuvio.tv.ui.screens.genres

import com.nuvio.tv.core.browse.BrowseCursor
import com.nuvio.tv.core.browse.BrowseDetailsLoader
import com.nuvio.tv.core.browse.BrowseGenre
import com.nuvio.tv.core.browse.BrowsePage
import com.nuvio.tv.core.browse.BrowseRepository
import com.nuvio.tv.core.browse.BrowseRequest
import com.nuvio.tv.core.browse.BrowseSort
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/**
 * The second visit to Genres finds its first page in the repository's cache and applies it during
 * construction. That path used to read a property declared below `init` (still null) and crash.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GenresViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun genre(id: String) = BrowseGenre(
        id = id, labelRes = 0, movie = null, tv = null, cinemetaGenre = id, keywords = emptyList()
    )

    private fun meta(id: String) = MetaPreview(
        id = id, type = ContentType.MOVIE, name = id, poster = null, posterShape = PosterShape.POSTER,
        background = null, logo = null, description = null, releaseInfo = null, imdbRating = null, genres = emptyList()
    )

    @Test
    fun `constructing with a cached first page shows it at once`() {
        val page = BrowsePage(listOf(meta("a"), meta("b")), BrowseCursor(movie = 2, tv = null))
        val repository = mockk<BrowseRepository>(relaxed = true)
        every { repository.availableGenres(any()) } returns listOf(genre("action"), genre("comedy"), genre("drama"))
        every { repository.supportedSorts() } returns listOf(BrowseSort.POPULAR)
        every { repository.cachedPage(any(), any()) } answers {
            val request = firstArg<BrowseRequest>()
            if (request.genre?.id == "action") page else null
        }
        coEvery { repository.load(any(), any()) } returns page

        val viewModel = GenresViewModel(repository, mockk<BrowseDetailsLoader>(relaxed = true))

        val state = viewModel.uiState.value
        assertEquals(listOf("a", "b"), state.items.map { it.id })
        assertFalse(state.isLoading)
        assertEquals(true, state.canLoadMore)
    }

    @Test
    fun `constructing with nothing cached still works`() {
        val repository = mockk<BrowseRepository>(relaxed = true)
        every { repository.availableGenres(any()) } returns listOf(genre("action"))
        every { repository.cachedPage(any(), any()) } returns null

        val viewModel = GenresViewModel(repository, mockk<BrowseDetailsLoader>(relaxed = true))

        assertEquals(true, viewModel.uiState.value.isLoading)
    }
}
