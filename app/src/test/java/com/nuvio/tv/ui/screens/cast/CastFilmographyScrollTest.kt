@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.nuvio.tv.ui.screens.cast

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PersonDetail
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.ui.components.posteroptions.PosterOptionsController
import com.nuvio.tv.ui.components.posteroptions.PosterOptionsState
import com.nuvio.tv.ui.theme.NuvioTheme
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

/** An actor's filmography scrolls as the D-pad moves along it. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36], qualifiers = "w960dp-h540dp-land-xhdpi", application = Application::class)
class CastFilmographyScrollTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun film(i: Int) = MetaPreview(
        id = "tt$i", type = ContentType.MOVIE, rawType = "movie", name = "Film $i", poster = null,
        posterShape = PosterShape.POSTER, background = null, logo = null, description = null,
        releaseInfo = "${2025 - i}", imdbRating = null, genres = emptyList()
    )

    @Test
    fun `moving right walks the whole filmography`() {
        val person = PersonDetail(
            tmdbId = 1, name = "Zendaya", biography = "Short bio.", birthday = null, deathday = null,
            placeOfBirth = null, profilePhoto = null, knownFor = "Acting",
            movieCredits = (1..30).map(::film), tvCredits = emptyList()
        )
        val posterOptions = mockk<PosterOptionsController>(relaxed = true)
        every { posterOptions.state } returns MutableStateFlow(PosterOptionsState())
        val viewModel = mockk<CastDetailViewModel>(relaxed = true)
        every { viewModel.uiState } returns MutableStateFlow(CastDetailUiState.Success(person))
        every { viewModel.watchedMovieIds } returns MutableStateFlow(emptySet())
        every { viewModel.watchedSeriesIds } returns MutableStateFlow(emptySet())
        every { viewModel.posterCardCornerRadiusDp } returns MutableStateFlow(12)
        every { viewModel.posterOptions } returns posterOptions
        every { viewModel.personName } returns "Zendaya"

        rule.setContent {
            NuvioTheme { CastDetailScreen(viewModel = viewModel, onBackPress = {}, onNavigateToDetail = { _, _, _ -> }) }
        }
        rule.mainClock.advanceTimeBy(2_000)
        rule.waitForIdle()
        repeat(14) {
            rule.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
            rule.mainClock.advanceTimeBy(600)
            rule.waitForIdle()
        }
        // Newest first: Film 1 is first, so fourteen moves land on Film 15, far past the first screen.
        rule.onAllNodesWithText("Film 15", useUnmergedTree = true)[0].assertIsDisplayed()
    }
}
