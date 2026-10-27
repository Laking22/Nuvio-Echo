@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.nuvio.tv.ui.newlook.preview

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.IntOffset
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.intercept.Interceptor
import coil3.request.ImageResult
import coil3.request.SuccessResult
import coil3.svg.SvgDecoder
import com.nuvio.tv.DrawerItem
import com.nuvio.tv.DrawerItemIcon
import com.nuvio.tv.LocalContentFocusRequester
import com.nuvio.tv.R
import com.nuvio.tv.core.home.BuiltInRowsRepository
import com.nuvio.tv.core.home.FeaturedTag
import com.nuvio.tv.core.home.NewLookDetails
import com.nuvio.tv.data.local.BetaPerformanceSettings
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.ui.navigation.Screen
import com.nuvio.tv.ui.newlook.AvatarX
import com.nuvio.tv.ui.newlook.AvatarY
import com.nuvio.tv.ui.newlook.GlassPill
import com.nuvio.tv.ui.newlook.GlassTabBar
import com.nuvio.tv.ui.newlook.LocalNewLookChrome
import com.nuvio.tv.ui.newlook.NewLookChrome
import com.nuvio.tv.ui.newlook.ProfileBubble
import com.nuvio.tv.ui.newlook.SidebarColumn
import com.nuvio.tv.ui.newlook.home.NewLookHomeContent
import com.nuvio.tv.ui.newlook.home.NewLookHomeMemory
import com.nuvio.tv.ui.newlook.home.NewLookHomeSource
import com.nuvio.tv.ui.screens.home.ContinueWatchingItem
import com.nuvio.tv.ui.screens.home.HeroCarouselRow
import com.nuvio.tv.ui.screens.home.HeroPreview
import com.nuvio.tv.ui.screens.home.HomeEvent
import com.nuvio.tv.ui.screens.home.HomeUiState
import com.nuvio.tv.ui.screens.home.MODERN_CONTINUE_WATCHING_ROW_KEY
import com.nuvio.tv.ui.screens.home.ModernCarouselItem
import com.nuvio.tv.ui.screens.home.ModernHomePresentationState
import com.nuvio.tv.ui.screens.home.ModernPayload
import com.nuvio.tv.ui.theme.NuvioLayout
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.asStable
import com.nuvio.tv.ui.util.rememberDrawerItemFocusRequesters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

/**
 * The new look's layout preview: the app's own Home ([NewLookHomeContent]), glass menu ([GlassPill], [GlassTabBar])
 * and avatar ([ProfileBubble]) rendered at 960 × 540 dp, 2 px per dp (a 1080p TV's scale), driven by real
 * D-pad key events, with a picture saved after each step to app/build/newlook-preview/. It's the same code as the
 * APK, so a layout or focus bug shows up here too (and a crash fails the test).
 *
 * Not the real thing: the artwork is generated (no TMDB or addons in a test), trailers don't play, and the menu is
 * placed the way NewLookScaffold places it rather than by the scaffold itself (which needs the app's navigation).
 *
 * Run: ./gradlew :app:testFullDebugUnitTest --tests '*NewLookPreviewTest*'
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36], qualifiers = "w960dp-h540dp-land-xhdpi", application = Application::class)
class NewLookPreviewTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val out = File("build/newlook-preview").apply { mkdirs() }

    @Test
    fun `sidebar layout - walk through Home`() {
        show(menuOnTop = false, hideMenu = false)
        settle(2500)
        shot("01-landing-watch-now")
        // Home opens on the window's Watch Now (bottom-left of the window, at 2 px per dp).
        assertTrue("landing focus ${focusedBounds()}", focusedBounds().let { it.top > 600 && it.bottom < 896 && it.left < 300 })
        key(KeyEvent.KEYCODE_DPAD_RIGHT); shot("02-more-info")
        key(KeyEvent.KEYCODE_DPAD_RIGHT); shot("03-next-title")
        key(KeyEvent.KEYCODE_DPAD_DOWN, settleMs = 1200); shot("04-continue-watching")
        key(KeyEvent.KEYCODE_DPAD_RIGHT); shot("05-continue-watching-next-card")
        key(KeyEvent.KEYCODE_DPAD_DOWN, settleMs = 1500); shot("06-trending-this-week")
        key(KeyEvent.KEYCODE_DPAD_DOWN, settleMs = 1500); shot("07-because-you-watched")
        key(KeyEvent.KEYCODE_DPAD_DOWN, settleMs = 400); shot("08-poster-row-just-focused")
        settle(1200); shot("09-poster-opened")
        key(KeyEvent.KEYCODE_DPAD_RIGHT, settleMs = 1500); key(KeyEvent.KEYCODE_DPAD_RIGHT, settleMs = 1500)
        shot("10-poster-row-two-along")
        key(KeyEvent.KEYCODE_DPAD_DOWN, settleMs = 1500); shot("11-top-20-movies")
        key(KeyEvent.KEYCODE_DPAD_RIGHT, settleMs = 1500); key(KeyEvent.KEYCODE_DPAD_RIGHT, settleMs = 1500)
        shot("12-top-20-third")
        key(KeyEvent.KEYCODE_DPAD_DOWN, settleMs = 1500); shot("13-collection")
        key(KeyEvent.KEYCODE_DPAD_DOWN, settleMs = 1500); shot("14-series-row")
        settle(1200); shot("15-series-poster-opened")
        key(KeyEvent.KEYCODE_DPAD_DOWN, settleMs = 1500); shot("16-top-20-shows")
        key(KeyEvent.KEYCODE_DPAD_LEFT, settleMs = 1000); shot("17-menu")
        key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_DOWN, settleMs = 1000); shot("18-menu-moved-down")
        key(KeyEvent.KEYCODE_DPAD_RIGHT, settleMs = 1000); shot("19-back-to-the-row")
        key(KeyEvent.KEYCODE_BACK, settleMs = 1500); shot("20-back-goes-to-the-window")
    }

    @Test
    fun `smart highlight row`() {
        val butler = com.nuvio.tv.core.home.smart.SmartHighlightRow(
            themeId = "butler_did_it", title = "The Butler Did It", catalogueTitle = "The Butler Did It", aiNamed = false,
            items = names("smart", "Knives Out", "Glass Onion", "Wake Up Dead Man", "Murder on the Orient Express",
                "Death on the Nile", "See How They Run", "Only Murders in the Building", "Poker Face")
        )
        show(menuOnTop = false, hideMenu = false, smart = listOf(butler))
        settle(2500)
        // Smart Highlights are spread through Home (SmartHighlightPlanner.placements): go down until it shows.
        fun shown() = rule.onAllNodes(androidx.compose.ui.test.hasText("The Butler Did It"), useUnmergedTree = true)
            .fetchSemanticsNodes().isNotEmpty()
        var downs = 0
        while (!shown() && downs < 14) {
            key(KeyEvent.KEYCODE_DPAD_DOWN, settleMs = 1500)
            downs++
        }
        shot("25-smart-highlight")
        assertTrue("smart highlight title shown", shown())
        key(KeyEvent.KEYCODE_DPAD_RIGHT, settleMs = 1500); shot("26-smart-highlight-second")
    }

    @Test
    fun `grand row`() {
        show(menuOnTop = false, hideMenu = false, grandRows = setOf("trending_week"))
        settle(2500)
        key(KeyEvent.KEYCODE_DPAD_DOWN, settleMs = 1500)
        key(KeyEvent.KEYCODE_DPAD_DOWN, settleMs = 1500); shot("31-grand-row")
        val card = focusedBounds()
        // As big as the hero window (830 × 430 dp at 2 px per dp).
        assertEquals(1660f, card.width, 4f)
        assertEquals(860f, card.height, 4f)
        key(KeyEvent.KEYCODE_DPAD_RIGHT, settleMs = 600); shot("32-grand-more-info")
        assertEquals("Right lights More Info first, focus stays on the card", card, focusedBounds())
        key(KeyEvent.KEYCODE_DPAD_RIGHT, settleMs = 1500); shot("33-grand-next-card")
        assertEquals("then moves to the next card, in the same place", card.top, focusedBounds().top, 4f)
        key(KeyEvent.KEYCODE_DPAD_DOWN, settleMs = 1500); shot("34-after-grand-row")
    }

    @Test
    fun `menu round trip keeps the row`() {
        show(menuOnTop = false, hideMenu = false)
        settle(2500)
        key(KeyEvent.KEYCODE_DPAD_DOWN, settleMs = 1500); key(KeyEvent.KEYCODE_DPAD_DOWN, settleMs = 1500)
        shot("41-trending-before-menu")
        val before = focusedBounds()
        key(KeyEvent.KEYCODE_DPAD_LEFT, settleMs = 1000); shot("42-menu")
        assertTrue("menu focus ${focusedBounds()}", focusedBounds().right < 200)
        key(KeyEvent.KEYCODE_DPAD_RIGHT, settleMs = 1500); shot("43-back-from-menu")
        // Right from the menu goes back to the same card (it used to land on the row above).
        assertEquals(before, focusedBounds())
    }

    @Test
    fun `search screen`() {
        // The keyboard search's own view model, fed by a stand-in browse source; the addon search behind it is a
        // stand-in view model whose query and results follow what the screen sends.
        val trending = names("trend", "Weapons", "The Pitt", "Alien: Earth", "Superman", "Wednesday", "F1", "Peacemaker",
            "The Long Walk", "Task", "The Paper", "Sinners", "Materialists", "Nobody 2", "Eddington", "Slow Horses")
        val duneTitles = listOf(
            meta("tt-d1", "Dune: Part Two", year = "2024", rating = 8.5f, genres = listOf("Sci-Fi", "Adventure")),
            meta("tt-d2", "Dune", year = "2021", rating = 8.0f, genres = listOf("Sci-Fi", "Adventure")),
            meta("tt-d3", "Dune: Prophecy", series = true, year = "2024", rating = 7.1f, genres = listOf("Sci-Fi", "Drama")),
            meta("tt-d4", "Dune", year = "1984", rating = 6.3f, genres = listOf("Sci-Fi", "Adventure")),
            meta("tt-d5", "Jodorowsky's Dune", year = "2013", rating = 8.0f, genres = listOf("Documentary"))
        )
        val desert = names("kw", "Mad Max: Fury Road", "Lawrence of Arabia", "The Martian", "Furiosa", "Star Wars",
            "Arrakis Rising", "Blade Runner 2049", "Nausicaa", "Tenet", "Interstellar")
        val repository = io.mockk.mockk<com.nuvio.tv.core.browse.BrowseRepository>(relaxed = true)
        io.mockk.every { repository.hasTmdb } returns true
        io.mockk.every { repository.supportsKeywords() } returns true
        io.mockk.every { repository.cachedPage(any(), any()) } answers {
            val request = firstArg<com.nuvio.tv.core.browse.BrowseRequest>()
            com.nuvio.tv.core.browse.BrowsePage(
                if (request.keyword != null) desert else trending,
                com.nuvio.tv.core.browse.BrowseCursor(null, null)
            )
        }
        io.mockk.coEvery { repository.searchKeywords(any(), any()) } returns listOf(
            com.nuvio.tv.core.browse.BrowseKeyword("desert planet", tmdbId = 1),
            com.nuvio.tv.core.browse.BrowseKeyword("sandworm", tmdbId = 2),
            com.nuvio.tv.core.browse.BrowseKeyword("based on novel", tmdbId = 3),
            com.nuvio.tv.core.browse.BrowseKeyword("chosen one", tmdbId = 4)
        )
        io.mockk.coEvery { repository.searchTitles(any()) } returns duneTitles
        val details = io.mockk.mockk<com.nuvio.tv.core.browse.BrowseDetailsLoader>(relaxed = true)
        io.mockk.every { details.cached(any()) } returns null
        io.mockk.every { details.preview(any()) } answers { firstArg<MetaPreview>().let { detailsOf(it, complete = false) } }
        io.mockk.coEvery { details.load(any()) } answers { detailsOf(firstArg(), complete = true) }
        val keyboardViewModel = com.nuvio.tv.ui.screens.search.KeyboardSearchViewModel(repository, details)

        val searchState = MutableStateFlow(com.nuvio.tv.ui.screens.search.SearchUiState(recentSearches = listOf("severance", "the bear")))
        val searchViewModel = io.mockk.mockk<com.nuvio.tv.ui.screens.search.SearchViewModel>(relaxed = true)
        io.mockk.every { searchViewModel.uiState } returns searchState
        io.mockk.every { searchViewModel.watchedMovieIds } returns MutableStateFlow(setOf("tt-d2"))
        io.mockk.every { searchViewModel.watchedSeriesIds } returns MutableStateFlow(emptySet())
        io.mockk.every { searchViewModel.onEvent(any()) } answers {
            when (val event = firstArg<com.nuvio.tv.ui.screens.search.SearchEvent>()) {
                is com.nuvio.tv.ui.screens.search.SearchEvent.QueryChanged -> {
                    val q = event.query.trim().lowercase()
                    val rows = if (q.length >= 2) listOf(
                        com.nuvio.tv.domain.model.CatalogRow(
                            addonId = "cinemeta", addonName = "Cinemeta", addonBaseUrl = "", catalogId = "search",
                            catalogName = "Search", type = ContentType.MOVIE,
                            items = duneTitles.filter { it.name.lowercase().contains(q) }
                        )
                    ) else emptyList()
                    searchState.value = searchState.value.copy(query = event.query, submittedQuery = event.query, catalogRows = rows)
                }
                else -> Unit
            }
        }

        show(menuOnTop = false, hideMenu = false, route = Screen.Search.route) {
            com.nuvio.tv.ui.screens.search.KeyboardSearchScreen(
                searchViewModel = searchViewModel,
                viewModel = keyboardViewModel,
                onNavigateToDetail = { _, _, _ -> }
            )
        }
        settle(2500)
        shot("51-search-opens")
        // "dune" on the on-screen keyboard (six keys a row: d is row 1 column 4, u row 4 column 3, n row 3 column 2).
        repeat(3) { key(KeyEvent.KEYCODE_DPAD_RIGHT, settleMs = 300) }
        key(KeyEvent.KEYCODE_DPAD_CENTER, settleMs = 300)
        repeat(3) { key(KeyEvent.KEYCODE_DPAD_DOWN, settleMs = 300) }
        key(KeyEvent.KEYCODE_DPAD_LEFT, settleMs = 300); key(KeyEvent.KEYCODE_DPAD_CENTER, settleMs = 300)
        key(KeyEvent.KEYCODE_DPAD_UP, settleMs = 300); key(KeyEvent.KEYCODE_DPAD_LEFT, settleMs = 300)
        key(KeyEvent.KEYCODE_DPAD_CENTER, settleMs = 300)
        repeat(2) { key(KeyEvent.KEYCODE_DPAD_UP, settleMs = 300) }
        repeat(3) { key(KeyEvent.KEYCODE_DPAD_RIGHT, settleMs = 300) }
        key(KeyEvent.KEYCODE_DPAD_CENTER, settleMs = 1500)
        shot("52-search-typed-dune")
        assertEquals("dune", searchState.value.query)
        key(KeyEvent.KEYCODE_DPAD_RIGHT, settleMs = 300); key(KeyEvent.KEYCODE_DPAD_RIGHT, settleMs = 1500)
        shot("53-search-results-grid")
        key(KeyEvent.KEYCODE_DPAD_RIGHT, settleMs = 1500); shot("54-search-next-result")
        key(KeyEvent.KEYCODE_BACK, settleMs = 1200); shot("55-search-back-to-keyboard")
        repeat(7) { key(KeyEvent.KEYCODE_DPAD_DOWN, settleMs = 300) }
        settle(600); shot("56-search-keyword-focused")
        key(KeyEvent.KEYCODE_DPAD_CENTER, settleMs = 1500); shot("57-search-keyword-picked")
    }

    @Test
    fun `library bins`() {
        // The app's own Library over a real bins view model, with the bins kept in memory.
        fun titles(prefix: String, vararg names: String) = names(prefix, *names).mapIndexed { i, m ->
            com.nuvio.tv.core.bins.BinEntry(
                id = m.id, type = m.rawType, name = m.name, poster = m.poster, background = m.background,
                releaseInfo = m.releaseInfo, addedAt = 1_000L + i, order = i
            )
        }
        val now = System.currentTimeMillis()
        val bins = FakeBins(
            com.nuvio.tv.core.bins.BinsState(
                bins = listOf(
                    com.nuvio.tv.core.bins.Bin("horror", "Horror Binge", starred = true, order = 0, createdAt = 1, viewedAt = now - 3_600_000,
                        entries = titles("hb", "Sinners", "Weapons", "Talk to Me", "Hereditary", "The Substance", "Nosferatu", "Longlegs", "Barbarian")),
                    com.nuvio.tv.core.bins.Bin("scifi", "Sci-Fi Comfort", order = 1, createdAt = 2, viewedAt = now - 2 * 86_400_000L,
                        entries = titles("sf", "Dune: Part Two", "Arrival", "Interstellar", "Severance", "Silo", "Andor")),
                    com.nuvio.tv.core.bins.Bin("prestige", "Prestige TV", order = 2, createdAt = 3, viewedAt = now - 5 * 86_400_000L,
                        entries = titles("pt", "Succession", "The Bear", "Shogun", "Slow Horses", "The Pitt")),
                    com.nuvio.tv.core.bins.Bin("date", "Date Night", order = 3, createdAt = 4, viewedAt = now - 9 * 86_400_000L,
                        entries = titles("dn", "Past Lives", "Anora", "La La Land", "Challengers")),
                    com.nuvio.tv.core.bins.Bin("oscar", "Oscar Watchlist", order = 4, createdAt = 5, viewedAt = now - 13 * 86_400_000L,
                        entries = titles("os", "Oppenheimer", "The Brutalist", "Conclave")),
                    com.nuvio.tv.core.bins.Bin("rewatch", "Rewatch Someday", order = 5, createdAt = 6, viewedAt = now - 30 * 86_400_000L,
                        entries = titles("rw", "Arcane", "Dark", "Fallout", "The Last of Us", "Wednesday"))
                ),
                migrated = true
            )
        )
        val layout = io.mockk.mockk<com.nuvio.tv.data.local.LayoutPreferenceDataStore>(relaxed = true)
        io.mockk.every { layout.posterCardWidthDp } returns kotlinx.coroutines.flow.flowOf(126)
        io.mockk.every { layout.posterCardCornerRadiusDp } returns kotlinx.coroutines.flow.flowOf(12)
        io.mockk.every { layout.customPosterUrlPattern } returns kotlinx.coroutines.flow.flowOf("")
        io.mockk.every { layout.customPosterEnabledScreens } returns kotlinx.coroutines.flow.flowOf(com.nuvio.tv.core.poster.CustomPosterScreen.ALL)
        val progress = io.mockk.mockk<com.nuvio.tv.domain.repository.WatchProgressRepository>(relaxed = true)
        io.mockk.every { progress.observeWatchedMovieIds() } returns kotlinx.coroutines.flow.flowOf(setOf("hb:3"))
        val seriesWatched = io.mockk.mockk<com.nuvio.tv.data.local.WatchedSeriesStateHolder>(relaxed = true)
        io.mockk.every { seriesWatched.fullyWatchedSeriesIds } returns MutableStateFlow(emptySet())
        val viewModel = com.nuvio.tv.ui.screens.library.BinsViewModel(
            context = rule.activity.applicationContext,
            repository = bins,
            layoutPreferences = layout,
            watchProgressRepository = progress,
            watchedSeriesStateHolder = seriesWatched,
            metaRepository = io.mockk.mockk(relaxed = true)
        )
        val cloud = io.mockk.mockk<com.nuvio.tv.ui.screens.library.CloudLibraryViewModel>(relaxed = true)
        io.mockk.every { cloud.uiState } returns MutableStateFlow(com.nuvio.tv.ui.screens.library.CloudUiState())
        var opened: String? = null

        show(menuOnTop = false, hideMenu = false, route = Screen.Library.route) {
            com.nuvio.tv.ui.screens.library.LibraryScreen(
                onNavigateToDetail = { id, _, _ -> opened = id },
                binsViewModel = viewModel,
                cloudViewModel = cloud
            )
        }
        // The bins' backdrops cycle forever; the clock moves only when the test says so.
        rule.mainClock.autoAdvance = false
        settle(2500)
        shot("61-library-bins")
        assertFocused("Horror Binge")

        // Up from a bin reaches its star; OK stars it (only one bin is starred).
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertFocused("Sci-Fi Comfort")
        key(KeyEvent.KEYCODE_DPAD_UP)
        assertFocused("star:Sci-Fi Comfort")
        shot("62-library-star-focused")
        key(KeyEvent.KEYCODE_DPAD_CENTER, settleMs = 1200)
        assertEquals(listOf("scifi"), bins.flow.value.bins.filter { it.starred }.map { it.id })
        shot("63-library-starred")
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        assertFocused("Sci-Fi Comfort")

        // The pills: Custom order opens up to show its name; the pills after it slide over.
        key(KeyEvent.KEYCODE_DPAD_UP); key(KeyEvent.KEYCODE_DPAD_UP)
        repeat(4) { key(KeyEvent.KEYCODE_DPAD_RIGHT, settleMs = 500) }
        settle(600)
        rule.onAllNodesWithText("Create bin", useUnmergedTree = true).fetchSemanticsNodes().let { assertTrue("Create bin label shows", it.isNotEmpty()) }
        shot("64-library-create-focused")
        key(KeyEvent.KEYCODE_DPAD_LEFT, settleMs = 900)
        rule.onAllNodesWithText("Custom order", useUnmergedTree = true).fetchSemanticsNodes().let { assertTrue("Custom order label shows", it.isNotEmpty()) }
        shot("65-library-custom-order-focused")

        // Custom order: pick a bin, pick another, and they swap.
        key(KeyEvent.KEYCODE_DPAD_CENTER, settleMs = 1200)
        key(KeyEvent.KEYCODE_DPAD_DOWN, settleMs = 900)
        val first = focusedBinName()
        key(KeyEvent.KEYCODE_DPAD_CENTER, settleMs = 800)
        shot("66-library-picked")
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        val second = focusedBinName()
        key(KeyEvent.KEYCODE_DPAD_CENTER, settleMs = 1500)
        shot("67-library-swapped")
        val order = com.nuvio.tv.core.bins.BinsReducer.sortedBins(bins.flow.value.bins, com.nuvio.tv.core.bins.BinSort.CUSTOM).map { it.name }
        assertTrue("$first and $second swapped in $order", order.indexOf(first) == order.indexOf(second) + 1)
        assertFocused(first)
        key(KeyEvent.KEYCODE_BACK, settleMs = 1200)

        // Open a bin, then come back to it.
        key(KeyEvent.KEYCODE_DPAD_CENTER, settleMs = 1800)
        shot("68-library-open-bin")
        key(KeyEvent.KEYCODE_DPAD_RIGHT, settleMs = 900); key(KeyEvent.KEYCODE_DPAD_DOWN, settleMs = 1200)
        shot("69-library-bin-scrolled")
        key(KeyEvent.KEYCODE_BACK, settleMs = 1500)
        assertFocused(first)

        // (Create bin's dialog is checked on a device: a focused text field's cursor never lets this test idle.)
        assertTrue(opened == null)
    }

    private fun assertFocused(description: String) {
        val nodes = rule.onAllNodes(androidx.compose.ui.test.isFocused(), useUnmergedTree = true).fetchSemanticsNodes()
        val labels = nodes.mapNotNull { it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription)?.firstOrNull() }
        assertTrue("expected focus on $description, focused: $labels", description in labels)
    }

    private fun focusedBinName(): String {
        val nodes = rule.onAllNodes(androidx.compose.ui.test.isFocused(), useUnmergedTree = true).fetchSemanticsNodes()
        return nodes.firstNotNullOf { it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription)?.firstOrNull() }
    }

    private class FakeBins(initial: com.nuvio.tv.core.bins.BinsState) : com.nuvio.tv.core.bins.BinsRepository {
        val flow = MutableStateFlow(initial)
        override val state = flow
        private fun edit(change: (com.nuvio.tv.core.bins.BinsState) -> com.nuvio.tv.core.bins.BinsState) { flow.value = change(flow.value) }
        private val r = com.nuvio.tv.core.bins.BinsReducer
        override suspend fun createBin(name: String): com.nuvio.tv.core.bins.BinNameResult {
            val clean = com.nuvio.tv.core.bins.cleanBinName(name)
            if (clean.isEmpty()) return com.nuvio.tv.core.bins.BinNameResult.Blank
            if (flow.value.bins.any { it.name.equals(clean, true) }) return com.nuvio.tv.core.bins.BinNameResult.Duplicate
            val id = "new-${flow.value.bins.size}"
            edit { r.create(it, id, clean, System.currentTimeMillis()) }
            return com.nuvio.tv.core.bins.BinNameResult.Ok(id)
        }
        override suspend fun renameBin(binId: String, name: String) = com.nuvio.tv.core.bins.BinNameResult.Ok(binId).also { edit { s -> r.rename(s, binId, name) } }
        override suspend fun deleteBin(binId: String) = edit { r.delete(it, binId) }
        override suspend fun toggleStar(binId: String) = edit { r.toggleStar(it, binId) }
        override suspend fun setSort(sort: com.nuvio.tv.core.bins.BinSort) = edit { r.setSort(it, sort) }
        override suspend fun useDisplayedBinOrder(orderedBinIds: List<String>) = edit { r.useDisplayedBinOrder(it, orderedBinIds) }
        override suspend fun swapBins(a: String, b: String) = edit { r.swapBins(it, a, b) }
        override suspend fun useDisplayedEntryOrder(binId: String, orderedKeys: List<String>) = edit { r.useDisplayedEntryOrder(it, binId, orderedKeys) }
        override suspend fun swapEntries(binId: String, a: String, b: String) = edit { r.swapEntries(it, binId, a, b) }
        override suspend fun markBinViewed(binId: String) = edit { r.markBinViewed(it, binId, System.currentTimeMillis()) }
        override suspend fun markEntryViewed(binId: String, key: String) = edit { r.markEntryViewed(it, binId, key, System.currentTimeMillis()) }
        override suspend fun add(binId: String, item: com.nuvio.tv.domain.model.LibraryEntryInput) =
            edit { r.addEntry(it, binId, with(com.nuvio.tv.core.bins.BinEntry(item.itemId, item.itemType, item.title)) { this }, System.currentTimeMillis()) }
        override suspend fun remove(binId: String, itemId: String, itemType: String) =
            edit { r.removeEntry(it, binId, com.nuvio.tv.core.bins.binKey(itemId, itemType)) }
    }

    private fun detailsOf(item: MetaPreview, complete: Boolean) = com.nuvio.tv.core.browse.BrowseItemDetails(
        id = item.id, title = item.name, poster = item.poster, logo = item.logo, year = item.releaseInfo,
        runtime = if (complete) "2h 46m" else null, ageRating = if (complete) "PG-13" else null, rating = item.imdbRating,
        genres = item.genres, overview = item.description, cast = if (complete) listOf("Timothée Chalamet", "Zendaya", "Rebecca Ferguson") else emptyList(),
        isComplete = complete
    )

    @Test
    fun `menu on top layout`() {
        show(menuOnTop = true, hideMenu = false)
        settle(2500)
        shot("21-top-menu-landing")
        key(KeyEvent.KEYCODE_DPAD_UP); shot("22-top-menu-focused")
        key(KeyEvent.KEYCODE_DPAD_RIGHT); key(KeyEvent.KEYCODE_DPAD_RIGHT); shot("23-top-menu-moved")
        key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_DOWN, settleMs = 1500); shot("24-top-menu-first-row")
        // Once on a TV: the avatar stayed at the top-left as the bar scrolled away, over "Continue Watching".
        val avatar = rule.onNodeWithTag("avatar", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val title = rule.onAllNodesWithText("Continue Watching", useUnmergedTree = true).fetchSemanticsNodes().first().boundsInRoot
        assertTrue("avatar $avatar overlaps the row title $title", !avatar.overlaps(title))
        key(KeyEvent.KEYCODE_DPAD_DOWN, settleMs = 1500); shot("24b-top-menu-second-row")
    }

    @Test
    fun `hidden menu layout`() {
        show(menuOnTop = false, hideMenu = true)
        settle(2500)
        shot("31-hidden-menu-landing")
        key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_DOWN, settleMs = 1500); shot("32-hidden-menu-rows")
        key(KeyEvent.KEYCODE_DPAD_LEFT, settleMs = 1000); shot("33-hidden-menu-opened")
    }

    @Test
    fun `menu on top and hidden until selected`() {
        show(menuOnTop = true, hideMenu = true)
        settle(2500)
        shot("25-top-hidden-landing")
        key(KeyEvent.KEYCODE_DPAD_UP, settleMs = 1200)
        val bar = focusedBounds()
        val picture = shot("26-top-hidden-bar-focused")
        // Once on a TV: the bar took focus but a dark strip covered it (the page's clip had moved down).
        // The focused tab's lens is light glass, so the middle of the focused tab must be well above the page's
        // near-black.
        val lum = luminance(picture, bar.center.x.toInt(), bar.center.y.toInt())
        assertTrue("focused tab is hidden (luminance $lum at ${bar.center})", lum > 60)
        assertTrue("the bar sits on screen", bar.top >= 0f)
        key(KeyEvent.KEYCODE_DPAD_RIGHT); shot("27-top-hidden-bar-moved")
        key(KeyEvent.KEYCODE_DPAD_DOWN, settleMs = 1200); shot("28-top-hidden-back-to-hero")
    }

    private fun luminance(bitmap: Bitmap, x: Int, y: Int): Int {
        var total = 0
        var n = 0
        for (dy in -4..4) for (dx in -4..4) {
            val p = bitmap.getPixel((x + dx).coerceIn(0, bitmap.width - 1), (y + dy).coerceIn(0, bitmap.height - 1))
            total += (android.graphics.Color.red(p) * 3 + android.graphics.Color.green(p) * 6 + android.graphics.Color.blue(p)) / 10
            n++
        }
        return total / n
    }

    // ---- Driving ----

    /** One remote button press, delivered to whatever has focus, as the TV delivers it. */
    private fun key(code: Int, settleMs: Long = 900) {
        if (code == KeyEvent.KEYCODE_BACK) {
            // Home's Back handling is a BackHandler, which listens on the activity's back dispatcher.
            rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        } else {
            rule.onRoot().performKeyInput { pressKey(Key(code)) }
        }
        settle(settleMs)
    }

    /** Where the focused element is on screen, in px. */
    private fun focusedBounds(): androidx.compose.ui.geometry.Rect =
        rule.onAllNodes(androidx.compose.ui.test.isFocused(), useUnmergedTree = true).fetchSemanticsNodes().single().boundsInRoot

    /** Lets time pass in small steps so effects, animations and image loads all run. */
    private fun settle(ms: Long) {
        var left = ms
        while (left > 0) {
            rule.mainClock.advanceTimeBy(50)
            // Android's own clock too, so ViewModel delays (search debounce) run.
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50))
            rule.waitForIdle()
            left -= 50
        }
    }

    private fun shot(name: String): Bitmap {
        settle(100)
        // Coil's image fade runs on real time and only moves on when drawn: draw once to start any fades, let them
        // finish in real time, then draw the picture that's kept.
        fun draw(): Bitmap {
            var bitmap: Bitmap? = null
            rule.runOnUiThread {
                val view = rule.activity.window.decorView
                bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
            }
            return bitmap!!
        }
        draw()
        Thread.sleep(350)
        settle(50)
        val bitmap: Bitmap? = draw()
        File(out, "$name.png").outputStream().use { bitmap!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bitmap!!
    }

    // ---- The screen ----

    /** Home in the scaffold's arrangement; returns the menu's Home icon's focus, to move into the menu. */
    /** Home (or [screen], with [route] selected in the menu) in the scaffold's arrangement. */
    private fun show(
        menuOnTop: Boolean,
        hideMenu: Boolean,
        route: String = Screen.Home.route,
        smart: List<com.nuvio.tv.core.home.smart.SmartHighlightRow> = emptyList(),
        grandRows: Set<String> = emptySet(),
        screen: (@Composable () -> Unit)? = null
    ): FocusRequester {
        val context = rule.activity.applicationContext
        SingletonImageLoader.setUnsafe(
            ImageLoader.Builder(context)
                .coroutineContext(Dispatchers.Unconfined)
                .components {
                    add(PreviewArt)
                    add(SvgDecoder.Factory())
                }
                .build()
        )
        val source = PreviewSource(smart)
        var pillFocus: FocusRequester? = null
        // On the TV, Home composes in a window that already has focus; here the window gets it first, then Home.
        var windowReady by mutableStateOf(false)
        var inputMode: androidx.compose.ui.input.InputModeManager? = null
        rule.setContent {
            inputMode = androidx.compose.ui.platform.LocalInputModeManager.current
            NuvioTheme {
                val calendarIcon = ImageVector.vectorResource(R.drawable.ic_nav_calendar)
                val items = remember {
                    listOf(
                        DrawerItem(Screen.Home.route, "Home", icon = Icons.Default.Home),
                        DrawerItem(Screen.Search.route, "Search", iconRes = R.raw.sidebar_search),
                        DrawerItem(Screen.Genres.route, "Discover", icon = Icons.Default.GridView),
                        DrawerItem(Screen.Calendar.route, "Calendar", icon = calendarIcon),
                        DrawerItem(Screen.Library.route, "Library", iconRes = R.raw.sidebar_library),
                        DrawerItem(Screen.Settings.route, "Settings", iconRes = R.raw.sidebar_settings)
                    )
                }
                val itemFocus = rememberDrawerItemFocusRequesters(items)
                pillFocus = itemFocus.getValue(Screen.Home.route)
                val drawerIcon: @Composable (DrawerItem, Color, Modifier) -> Unit = { item, tint, modifier ->
                    DrawerItemIcon(iconRes = item.iconRes, icon = item.icon, tint = tint, modifier = modifier)
                }
                var pillFocused by remember { mutableStateOf(false) }
                var focusedIndex by remember { mutableIntStateOf(-1) }
                val state = remember { NewLookChrome.State() }
                val inset = NuvioLayout.tokens.sidebarContentOffset
                val barItems = remember(items) {
                    items.filter { it.route == Screen.Search.route } +
                        items.filter { it.route != Screen.Search.route && it.route != Screen.Settings.route } +
                        items.filter { it.route == Screen.Settings.route }
                }
                val contentFocus = remember { FocusRequester() }
                val pageScroll = remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
                val chrome = remember {
                    NewLookChrome(
                        menuOnTop = menuOnTop,
                        hideMenu = hideMenu,
                        contentInset = inset,
                        menuFocusedState = state,
                        focusTopBarAction = { runCatching { itemFocus.getValue(Screen.Home.route).requestFocus() } },
                        topBarContent = { onDown, modifier ->
                            GlassTabBar(
                                items = barItems,
                                selectedRoute = route,
                                itemFocus = itemFocus,
                                onFocusChanged = { state.bar = it },
                                onClick = {},
                                onLeftFromFirst = {},
                                onDown = onDown,
                                drawerIcon = drawerIcon,
                                modifier = modifier
                            )
                        },
                        pageScroll = pageScroll
                    )
                }
                val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
                Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(start = inset)
                            // As NewLookScaffold: Left from a screen's left edge moves into the sidebar.
                            .onKeyEvent { event ->
                                if (!menuOnTop && !pillFocused && event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft) {
                                    if (!focusManager.moveFocus(FocusDirection.Left)) runCatching { itemFocus.getValue(Screen.Home.route).requestFocus() }
                                    true
                                } else false
                            }
                    ) {
                        // Something focusable so the Compose host view can take the window's focus before Home is there;
                        // Home's own first-focus code then moves focus off it.
                        Box(modifier = Modifier.focusable(enabled = !windowReady))
                        if (windowReady) CompositionLocalProvider(
                            LocalNewLookChrome provides chrome,
                            LocalContentFocusRequester provides contentFocus
                        ) {
                            if (screen != null) screen() else NewLookHomeContent(
                                source = source,
                                settings = BetaPerformanceSettings(
                                    menuOnTop = menuOnTop, hideMenuUntilSelected = hideMenu, grandRows = grandRows
                                ),
                                uiState = HomeUiState(heroItems = featured, heroSectionEnabled = true),
                                presentation = ModernHomePresentationState(rows = presentationRows().asStable()),
                                onNavigateToDetail = { _, _, _ -> },
                                onWatchNow = { _, _, _ -> },
                                onContinueWatchingClick = {},
                                onContinueWatchingStartFromBeginning = {},
                                onContinueWatchingPlayManually = {},
                                showContinueWatchingManualPlayOption = false,
                                onNavigateToFolderDetail = { _, _ -> },
                                isCatalogItemWatched = { it.id == "poster:movie:2" },
                                onCatalogItemLongPress = { _, _ -> }
                            )
                        }
                    }
                    // As NewLookScaffold places them.
                    if (!menuOnTop) {
                        // Always there (so it can take focus), shown as the scaffold shows it.
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .width(SidebarColumn)
                                .graphicsLayer { alpha = if (!hideMenu || pillFocused) 1f else 0f },
                            contentAlignment = Alignment.Center
                        ) {
                            GlassPill(
                                items = items,
                                selectedRoute = route,
                                focused = pillFocused,
                                focusedIndex = focusedIndex,
                                itemFocus = itemFocus,
                                onFocusChanged = { pillFocused = it; state.pill = it },
                                onItemFocused = { focusedIndex = it },
                                onClick = {},
                                onUpFromFirst = {},
                                onRight = { runCatching { contentFocus.requestFocus() } },
                                drawerIcon = drawerIcon
                            )
                        }
                    }
                    // The avatar is Home's alone.
                    if (route == Screen.Home.route && (!hideMenu || pillFocused)) {
                        ProfileBubble(
                            name = "Alex",
                            colorHex = "#1E88E5",
                            avatarImageUrl = null,
                            focusRequester = remember { FocusRequester() },
                            canFocus = false,
                            onFocusChanged = {},
                            onClick = {},
                            onBackToMenu = {},
                            modifier = Modifier
                                .offset { IntOffset(AvatarX.roundToPx(), AvatarY.roundToPx()) }
                                // As the scaffold: with the menu on top and showing, it scrolls with the page.
                                .graphicsLayer { if (menuOnTop && !hideMenu) translationY = -pageScroll.floatValue }
                                .testTag("avatar")
                        )
                    }
                }
            }
        }
        // A test window never gets window focus by itself; a TV's does, and Compose needs it to hold focus and
        // take key events.
        rule.runOnUiThread {
            val decor = rule.activity.window.decorView
            decor.dispatchWindowFocusChanged(true)
            val content = rule.activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)
            // The Compose host view inside the ComposeView takes focus, as on the TV.
            val host = (content as android.view.ViewGroup).getChildAt(0)
            host.requestFocus()
            // A TV driven by a remote is never in touch mode, and buttons only take focus outside it; a test window
            // starts in touch mode.
            inputMode!!.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard)
        }
        rule.waitForIdle()
        windowReady = true
        return pillFocus!!
    }

    // ---- Sample data ----

    private fun meta(
        id: String,
        name: String,
        series: Boolean = false,
        year: String = "2025",
        rating: Float? = 7.8f,
        genres: List<String> = listOf("Drama", "Thriller"),
        logo: Boolean = false,
        description: String? = SYNOPSIS
    ) = MetaPreview(
        id = id,
        type = if (series) ContentType.SERIES else ContentType.MOVIE,
        rawType = if (series) "series" else "movie",
        name = name,
        poster = art("poster", id, name),
        posterShape = PosterShape.POSTER,
        background = art("backdrop", id, name),
        logo = if (logo) art("logo", id, name) else null,
        description = description,
        releaseInfo = year,
        imdbRating = rating,
        genres = genres
    )

    private val featured = listOf(
        meta("tt1", "Severance", series = true, year = "2022", rating = 8.7f, genres = listOf("Mystery", "Sci-Fi"), logo = true),
        meta("tt2", "Dune: Part Two", year = "2024", rating = 8.5f, genres = listOf("Sci-Fi", "Adventure"), logo = true),
        meta("tt3", "The Bear", series = true, year = "2022", rating = 8.5f, genres = listOf("Comedy", "Drama"), logo = true),
        meta("tt4", "Sinners", year = "2025", rating = 7.7f, genres = listOf("Horror", "Thriller"), logo = true),
        meta("tt5", "Andor", series = true, year = "2022", rating = 8.5f, genres = listOf("Sci-Fi", "Action"), logo = true),
        meta("tt6", "One Battle After Another", year = "2025", rating = 8.1f, genres = listOf("Action", "Comedy"), logo = true)
    )

    private fun names(prefix: String, vararg titles: String) =
        titles.mapIndexed { i, t -> meta("$prefix:${i + 1}", t, series = prefix.contains("show"), logo = i % 2 == 0) }

    private val builtIns = BuiltInRowsRepository.Rows(
        trendingWeek = names("trend", "Weapons", "The Pitt", "Alien: Earth", "Superman", "Wednesday", "F1", "Peacemaker", "The Long Walk"),
        topMoviesToday = names("topmovie", "The Conjuring: Last Rites", "Weapons", "F1", "Superman", "Nobody 2", "Freakier Friday", "The Naked Gun", "Materialists", "Jurassic World Rebirth", "Ballerina", "Thunderbolts*"),
        topShowsToday = names("topshow", "Wednesday", "Alien: Earth", "The Paper", "Task", "Peacemaker", "Platonic", "The Gilded Age", "Slow Horses", "Dexter: Resurrection"),
        becauseYouWatched = BuiltInRowsRepository.BecauseYouWatched(
            "Severance", names("byw", "Dark", "Mr. Robot", "Black Mirror", "Silo", "Devs", "Westworld", "Fringe")
        ),
        forYou = emptyList()
    )

    private fun posterItem(m: MetaPreview) = ModernCarouselItem(
        key = m.id, title = m.name, subtitle = m.releaseInfo, imageUrl = m.poster,
        heroPreview = HeroPreview(
            title = m.name, logo = m.logo, description = m.description, contentTypeText = null,
            isSeries = m.type == ContentType.SERIES, yearText = m.releaseInfo, imdbText = m.imdbRating?.toString(),
            genres = m.genres.asStable(), poster = m.poster, backdrop = m.background, imageUrl = m.poster
        ),
        payload = ModernPayload.Catalog(m.id, m.id, m.rawType, "", m.name, m.releaseInfo, m.rawType),
        metaPreview = m
    )

    private fun catalogRow(addon: String, type: String, id: String, title: String, vararg titles: String) = HeroCarouselRow(
        key = "row_${addon}_${type}_$id", title = title, globalRowIndex = 0,
        items = titles.mapIndexed { i, t -> posterItem(meta("poster:$type:${i + 1}", t, series = type == "series")) }.asStable(),
        catalogId = id, addonId = addon, apiType = type
    )

    private fun cwItem(id: String, name: String, episode: String?, percent: Float): ModernCarouselItem {
        val progress = WatchProgress(
            contentId = id, contentType = if (episode != null) "series" else "movie", name = name,
            poster = null, backdrop = art("backdrop", id, name), logo = null, videoId = id,
            season = if (episode != null) 2 else null, episode = if (episode != null) 4 else null, episodeTitle = null,
            position = (percent * 3_000_000).toLong(), duration = 3_000_000L, lastWatched = 0L
        )
        val item = ContinueWatchingItem.InProgress(progress = progress, episodeThumbnail = art("thumb", id, name))
        return ModernCarouselItem(
            key = "cw_$id", title = name, subtitle = episode, imageUrl = progress.backdrop,
            heroPreview = HeroPreview(
                title = name, logo = null, description = null, contentTypeText = null, yearText = null, imdbText = null,
                secondaryHighlightText = "${((1 - percent) * 50).toInt()}m left",
                genres = emptyList<String>().asStable(), poster = null, backdrop = progress.backdrop, imageUrl = progress.backdrop
            ),
            payload = ModernPayload.ContinueWatching(item)
        )
    }

    private fun presentationRows(): List<HeroCarouselRow> = listOf(
        HeroCarouselRow(
            key = MODERN_CONTINUE_WATCHING_ROW_KEY, title = "Continue Watching", globalRowIndex = 0,
            items = listOf(
                cwItem("cw1", "Severance", "S2 E4", 0.42f),
                cwItem("cw2", "The Bear", "S3 E2", 0.7f),
                cwItem("cw3", "Dune: Part Two", null, 0.25f),
                cwItem("cw4", "Slow Horses", "S4 E1", 0.1f)
            ).asStable()
        ),
        catalogRow("cinemeta", "movie", "top", "Popular - Movie",
            "Weapons", "Sinners", "F1", "Superman", "Materialists", "The Long Walk", "Nobody 2", "Eddington", "Bugonia", "Frankenstein"),
        HeroCarouselRow(
            key = "collection_studios", title = "Streaming Services", globalRowIndex = 0,
            items = listOf("Netflix", "Prime Video", "Disney+", "Max", "Apple TV+", "Hulu").mapIndexed { i, name ->
                ModernCarouselItem(
                    key = "folder$i", title = name, subtitle = null, imageUrl = art("folder", "f$i", name),
                    heroPreview = HeroPreview(
                        title = name, logo = null, description = null, contentTypeText = null, yearText = null, imdbText = null,
                        genres = emptyList<String>().asStable(), poster = null, backdrop = art("backdrop", "f$i", name), imageUrl = null
                    ),
                    payload = ModernPayload.CollectionFolder(
                        focusKey = "f$i", collectionId = "studios", collectionTitle = "Streaming Services", folderId = "f$i",
                        posterShape = PosterShape.LANDSCAPE, focusGlowEnabled = false, focusGifEnabled = false,
                        focusGifUrl = null, heroBackdropUrl = null, heroVideoUrl = null, titleLogoUrl = null
                    )
                )
            }.asStable()
        ),
        catalogRow("cinemeta", "series", "top", "Popular - Series",
            "Wednesday", "The Pitt", "Alien: Earth", "Task", "The Paper", "Peacemaker", "Dept. Q", "Andor", "Slow Horses")
    )

    /** Stands in for the ViewModel and the TMDB lookups; every title gets a tag, runtime and genres. */
    private inner class PreviewSource(
        smart: List<com.nuvio.tv.core.home.smart.SmartHighlightRow> = emptyList()
    ) : NewLookHomeSource {
        override val smartHighlights = MutableStateFlow(smart)
        override val memory = NewLookHomeMemory().apply { featuredOrder = featured.map { it.id } }
        override val scrollToTop = MutableStateFlow(0)
        override val trailerUrls: Map<String, String> = emptyMap()
        override val trailerAudioUrls: Map<String, String> = emptyMap()
        override val builtInRows = MutableStateFlow(builtIns)
        override suspend fun refreshBuiltIns() = Unit
        override fun cachedDetails(id: String): NewLookDetails = detailsFor(id)
        override suspend fun details(item: MetaPreview, withTag: Boolean, topTen: Set<String>) = detailsFor(item.id)
        override fun requestTrailer(itemId: String, title: String, releaseInfo: String?, apiType: String, surfaceHeightPx: Int?) = Unit
        override fun requestLazyCatalogLoad(catalogKey: String) = Unit
        override fun onEvent(event: HomeEvent) = Unit

        private fun detailsFor(id: String) = NewLookDetails(
            genres = listOf("Drama", "Thriller"),
            runtimeMinutes = 100 + abs(id.hashCode()) % 60,
            tag = FeaturedTag.entries[abs(id.hashCode()) % FeaturedTag.entries.size],
            tagSeason = 3
        )
    }

    private companion object {
        const val SYNOPSIS = "A team's careful plan comes apart in one long night, and the people they trusted most " +
            "turn out to have been planning something of their own all along."

        fun art(kind: String, id: String, title: String) =
            "https://preview.nuvio/$kind/${java.net.URLEncoder.encode(id, "UTF-8")}/${java.net.URLEncoder.encode(title, "UTF-8")}"
    }
}

/** Draws stand-in artwork for preview URLs: a coloured gradient with the title, or a logo of just the title. */
private object PreviewArt : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val url = chain.request.data as? String ?: return chain.proceed()
        if (!url.startsWith("https://preview.nuvio/")) return chain.proceed()
        val parts = url.removePrefix("https://preview.nuvio/").split('/')
        val kind = parts[0]
        val id = java.net.URLDecoder.decode(parts[1], "UTF-8")
        val title = java.net.URLDecoder.decode(parts[2], "UTF-8")
        return SuccessResult(image = draw(kind, id, title).asImage(), request = chain.request, dataSource = DataSource.MEMORY)
    }

    private fun draw(kind: String, id: String, title: String): Bitmap {
        val (w, h) = when (kind) {
            "poster" -> 342 to 513
            "logo" -> 600 to 200
            "folder" -> 640 to 360
            else -> 1280 to 720
        }
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val bold = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        if (kind == "logo") {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                typeface = bold
                textSize = fitText(title.uppercase(), w * 0.95f, 120f, bold)
                setShadowLayer(8f, 0f, 3f, 0x99000000.toInt())
            }
            canvas.drawText(title.uppercase(), 8f, h * 0.7f, paint)
            return bitmap
        }
        val hue = (abs(title.hashCode()) % 360).toFloat()
        val a = android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.65f, 0.55f))
        val b = android.graphics.Color.HSVToColor(floatArrayOf((hue + 50) % 360, 0.75f, 0.18f))
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), a, b, Shader.TileMode.CLAMP)
        })
        // A few soft shapes so it reads as artwork rather than a flat swatch.
        val shape = Paint(Paint.ANTI_ALIAS_FLAG)
        repeat(4) { i ->
            shape.color = android.graphics.Color.HSVToColor(40 + i * 15, floatArrayOf((hue + i * 70) % 360, 0.5f, 0.9f))
            val r = h * (0.18f + 0.09f * ((abs(id.hashCode()) shr i) % 4))
            canvas.drawCircle(w * (0.2f + 0.22f * i), h * (0.3f + 0.15f * (i % 3)), r, shape)
        }
        if (kind == "poster") {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                typeface = bold
                textSize = fitText(title, w * 0.88f, 44f, bold)
                setShadowLayer(6f, 0f, 2f, 0xAA000000.toInt())
            }
            canvas.drawText(title, w * 0.06f, h * 0.9f, paint)
        }
        return bitmap
    }

    private fun fitText(text: String, width: Float, max: Float, typeface: Typeface): Float {
        val paint = Paint().apply { this.typeface = typeface; textSize = max }
        val measured = paint.measureText(text)
        return if (measured <= width) max else max * width / measured
    }
}
