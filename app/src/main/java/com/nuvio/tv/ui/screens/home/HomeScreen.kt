package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.ui.theme.NuvioTheme

import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import android.util.Log
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Divider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.domain.model.LibraryListTab
import com.nuvio.tv.domain.model.localizedMembershipTitle
import com.nuvio.tv.domain.model.LibrarySourceMode
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.newlook.NewLookOption
import com.nuvio.tv.ui.newlook.NewLookOptionsMenu
import com.nuvio.tv.ui.components.ErrorState
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.LocalStartupLoadingState
import com.nuvio.tv.ui.components.LocalStartupSplashEnabled
import com.nuvio.tv.ui.components.shouldShowHomeStartupLoader
import com.nuvio.tv.ui.components.NuvioDialog
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.core.tracking.LOCAL_LIBRARY_LIST_KEY
import com.nuvio.tv.core.tracking.supportsMembershipFor
import com.nuvio.tv.data.local.StartupAuthNotice
import com.nuvio.tv.ui.components.posteroptions.TrackingRemovalConfirmationDialog
import kotlinx.coroutines.delay

private data class HomePosterOptionsTarget(
    val item: MetaPreview,
    val addonBaseUrl: String
)

private const val HOME_STABLE_GATE_TIMEOUT_MS = 5_000L

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel(),
    onNavigateToDetail: (String, String, String) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit = { item ->
        onNavigateToDetail(
            when (item) {
                is ContinueWatchingItem.InProgress -> item.progress.contentId
                is ContinueWatchingItem.NextUp -> item.info.contentId
            },
            when (item) {
                is ContinueWatchingItem.InProgress -> item.progress.contentType
                is ContinueWatchingItem.NextUp -> item.info.contentType
            },
            ""
        )
    },
    onContinueWatchingStartFromBeginning: (ContinueWatchingItem) -> Unit = onContinueWatchingClick,
    onContinueWatchingPlayManually: (ContinueWatchingItem) -> Unit = onContinueWatchingClick,
    onNavigateToFolderDetail: (String, String) -> Unit = { _, _ -> },
    /** New look's Watch Now: the title's page, straight into playback. */
    onWatchNow: (String, String, String) -> Unit = onNavigateToDetail
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Home was the only major screen without a lifecycle observer, so nothing ever told it to
    // look at its catalogs again.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.beginShuffleHomeVisit()
                viewModel.refreshHomeCatalogsIfStale()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val modernPresentation by viewModel.modernHomePresentation.collectAsStateWithLifecycle()
    val initialCwResolved by viewModel.initialCwResolved.collectAsStateWithLifecycle()
    val effectiveAutoplayEnabled by viewModel.effectiveAutoplayEnabled.collectAsStateWithLifecycle(
        initialValue = false
    )
    val hasCatalogContent = uiState.catalogRows.any { it.items.isNotEmpty() }
    val hasCollectionContent = uiState.homeRows.any { it is HomeRow.CollectionRow }
    val hasHeroContent = uiState.heroItems.isNotEmpty()
    val modernPresentationReady =
        modernPresentation.rows.list.isNotEmpty() ||
            (uiState.heroSectionEnabled && hasHeroContent && !hasCatalogContent && !hasCollectionContent)
    var showHomeContentWithAnimation by rememberSaveable { mutableStateOf(false) }
    var hasShownInitialHomeContent by rememberSaveable { mutableStateOf(false) }
    // Once we've shown stable home content, never go back to loading gate.
    var homeStableGateReleased by rememberSaveable { mutableStateOf(false) }
    // Home is built (hidden, under the splash) once the gate releases, and shown once its rows stop moving, so it
    // never opens on rows that shuffle a second later as built-in rows and later catalogs arrive.
    var homeSettled by rememberSaveable { mutableStateOf(false) }
    // Track that catalog loading has started at least once (isLoading went true→false).
    var catalogLoadingStarted by rememberSaveable { mutableStateOf(false) }
    var posterOptionsTarget by remember { mutableStateOf<HomePosterOptionsTarget?>(null) }

    // Notify ViewModel of locale changes after activity recreation
    LaunchedEffect(Unit) {
        viewModel.notifyLocaleChanged()
    }

    val movieWatchedStatus = uiState.movieWatchedStatus
    // Use a stable lambda whose identity NEVER changes. The lambda captures
    // movieWatchedStatus via rememberUpdatedState so it always reads the latest
    // value without forcing downstream recomposition from lambda identity change.
    val latestMovieWatchedStatus = androidx.compose.runtime.rememberUpdatedState(movieWatchedStatus)
    val isCatalogItemWatched: (MetaPreview) -> Boolean = remember {
        { item: MetaPreview -> latestMovieWatchedStatus.value[homeItemStatusKey(item.id, item.apiType)] == true }
    }
    val onCatalogItemLongPress: (MetaPreview, String) -> Unit = remember {
        { item, addonBaseUrl ->
            posterOptionsTarget = HomePosterOptionsTarget(item, addonBaseUrl)
            viewModel.refreshPosterLibraryStatus(item)
        }
    }

    val onNavigateToDetailStable = remember(onNavigateToDetail) { onNavigateToDetail }
    val onContinueWatchingClickStable = remember(onContinueWatchingClick) { onContinueWatchingClick }
    val onContinueWatchingStartFromBeginningStable = remember(onContinueWatchingStartFromBeginning) { onContinueWatchingStartFromBeginning }
    val onContinueWatchingPlayManuallyStable = remember(onContinueWatchingPlayManually) { onContinueWatchingPlayManually }
    val onNavigateToFolderDetailStable = remember(onNavigateToFolderDetail) { onNavigateToFolderDetail }

    LaunchedEffect(
        uiState.isLoading,
        hasCatalogContent,
        hasCollectionContent,
        hasHeroContent,
        initialCwResolved,
        modernPresentationReady
    ) {
        // Track that addons are known (even if isLoading flipped too fast to catch).
        if (uiState.installedAddonsCount > 0) {
            catalogLoadingStarted = true
        }
        // Wait until catalog loading has completed with content AND the CW
        // pipeline has completed its first emission.
        if (!homeStableGateReleased &&
            catalogLoadingStarted &&
            !uiState.isLoading &&
            initialCwResolved &&
            modernPresentationReady &&
            // When addons are installed, require at least one catalog row.
            (hasCatalogContent || uiState.installedAddonsCount == 0)
        ) {
            Log.d("HomeGate", "RELEASE: catalogs=$hasCatalogContent cwResolved=$initialCwResolved cwItems=${uiState.continueWatchingItems.size} addons=${uiState.installedAddonsCount}")
            homeStableGateReleased = true
        }
    }

    LaunchedEffect(Unit) {
        // Safety timeout — if catalogs and CW haven't loaded within this
        // window, show whatever is available.  Covers edge cases like
        // clean cache (addons loading from remote sync) and users with
        // no addons at all.
        delay(HOME_STABLE_GATE_TIMEOUT_MS)
        if (!homeStableGateReleased) {
            Log.d("HomeGate", "RELEASE timeout: isLoading=${uiState.isLoading} cwResolved=$initialCwResolved catalogs=$hasCatalogContent cwItems=${uiState.continueWatchingItems.size}")
            homeStableGateReleased = true
        }
    }

    val noAddonsError = stringResource(R.string.home_error_no_addons)
    val noCatalogAddonsError = stringResource(R.string.home_error_no_catalog_addons)
    val hasAnyContent = uiState.catalogRows.isNotEmpty() ||
        (uiState.continueWatchingEnabled && uiState.continueWatchingItems.isNotEmpty()) ||
        uiState.heroItems.isNotEmpty() ||
        hasCollectionContent
    val showStartupLoader = when {
        !uiState.layoutPreferencesReady -> true
        uiState.isLoading && !hasAnyContent -> true
        uiState.error == noAddonsError && uiState.catalogRows.isEmpty() -> !homeStableGateReleased
        uiState.error == noCatalogAddonsError && uiState.catalogRows.isEmpty() && !hasCollectionContent && !hasHeroContent -> !homeStableGateReleased
        uiState.error != null && uiState.catalogRows.isEmpty() -> false
        !uiState.isLoading && !hasAnyContent -> !homeStableGateReleased
        else -> !homeStableGateReleased || !modernPresentationReady || !showHomeContentWithAnimation || !homeSettled
    }

    // Reports the home screen as fully drawn once it leaves the loading state so startup timing is measurable and post-launch work can be deferred.
    ReportDrawnWhen { !showStartupLoader }

    val startupLoadingState = LocalStartupLoadingState.current
    val showHomeLoader = shouldShowHomeStartupLoader(
        loading = showStartupLoader,
        sharedSplashEnabled = LocalStartupSplashEnabled.current,
        startupComplete = startupLoadingState?.complete != false
    )
    LaunchedEffect(showStartupLoader, startupLoadingState) {
        if (!showStartupLoader) {
            startupLoadingState?.complete = true
        }
    }

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        when {
            !uiState.layoutPreferencesReady -> {
                Unit
            }

            uiState.isLoading && !hasAnyContent -> {
                Unit
            }

            uiState.error == noAddonsError && uiState.catalogRows.isEmpty() -> {
                if (!homeStableGateReleased) {
                    Unit
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.home_no_addons),
                            style = MaterialTheme.typography.bodyLarge,
                            color = NuvioTheme.colors.TextSecondary
                        )
                    }
                }
            }

            uiState.error == noCatalogAddonsError && uiState.catalogRows.isEmpty() && !hasCollectionContent && !hasHeroContent -> {
                if (!homeStableGateReleased) {
                    Unit
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.home_no_catalog_addons),
                            style = MaterialTheme.typography.bodyLarge,
                            color = NuvioTheme.colors.TextSecondary
                        )
                    }
                }
            }

            uiState.error != null && uiState.catalogRows.isEmpty() -> {
                ErrorState(
                    message = uiState.error ?: stringResource(R.string.error_generic),
                    onRetry = { viewModel.onEvent(HomeEvent.OnRetry) }
                )
            }

            !uiState.isLoading && !hasAnyContent -> {
                // Don't show "no catalogs" until the stable gate has released —
                // addons may still be loading from remote after a cache clear.
                if (!homeStableGateReleased) {
                    Unit
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.web_no_catalogs),
                            style = MaterialTheme.typography.bodyLarge,
                            color = NuvioTheme.colors.TextSecondary
                        )
                    }
                }
            }

            else -> {
                // On first launch, wait for stable content before revealing home.
                // Once released, never go back to loading (homeStableGateReleased is rememberSaveable).
                if (!homeStableGateReleased) {
                    Unit
                } else if (!modernPresentationReady) {
                    Unit
                } else {
                    // Flip showHomeContentWithAnimation on the next frame so
                    // AnimatedVisibility can run its enter transition.
                    LaunchedEffect(Unit) {
                        if (!showHomeContentWithAnimation) {
                            kotlinx.coroutines.yield()
                            showHomeContentWithAnimation = true
                        }
                    }
                    LaunchedEffect(showHomeContentWithAnimation) {
                        if (showHomeContentWithAnimation) {
                            hasShownInitialHomeContent = true
                        }
                    }
                    // Keep loading visible during the single-frame gap before animation starts.
                    if (!showHomeContentWithAnimation) {
                        Unit
                    }
                    AnimatedVisibility(
                        visible = showHomeContentWithAnimation,
                        enter = if (hasShownInitialHomeContent) {
                            EnterTransition.None
                        } else {
                            fadeIn(animationSpec = tween(320)) +
                                slideInVertically(
                                    initialOffsetY = { it / 24 },
                                    animationSpec = tween(320)
                                )
                        }
                    ) {
                        val settledAlpha by animateFloatAsState(
                            if (homeSettled) 1f else 0f, tween(240), label = "homeSettled"
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer { alpha = settledAlpha }
                                // Keys wait too: nothing moves on a Home that can't be seen yet (Back still works).
                                .onPreviewKeyEvent { !homeSettled && it.key != Key.Back }
                        ) {
                        com.nuvio.tv.ui.newlook.home.NewLookHome(
                            viewModel = viewModel,
                            uiState = uiState,
                            presentation = modernPresentation,
                            onNavigateToDetail = onNavigateToDetailStable,
                            onWatchNow = onWatchNow,
                            onContinueWatchingClick = onContinueWatchingClickStable,
                            onContinueWatchingStartFromBeginning = onContinueWatchingStartFromBeginningStable,
                            onContinueWatchingPlayManually = onContinueWatchingPlayManuallyStable,
                            showContinueWatchingManualPlayOption = effectiveAutoplayEnabled,
                            onNavigateToFolderDetail = onNavigateToFolderDetailStable,
                            isCatalogItemWatched = isCatalogItemWatched,
                            onCatalogItemLongPress = onCatalogItemLongPress,
                            onSettled = { homeSettled = true }
                        )
                        }
                    }
                }
            }
        }

        if (showHomeLoader) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                LoadingIndicator()
            }
        }

        val startupAuthNotice = uiState.startupAuthNotice
        if (startupAuthNotice != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = NuvioTheme.spacing.xl)
                    .background(
                        color = Color(0xFF5A1C1C),
                        shape = RoundedCornerShape(10.dp)
                    )
                    .padding(horizontal = 18.dp, vertical = 10.dp)
            ) {
                Text(
                    text = when (startupAuthNotice) {
                        StartupAuthNotice.NUVIO -> stringResource(R.string.auth_notice_nuvio_logged_out)
                        StartupAuthNotice.TRAKT -> stringResource(R.string.auth_notice_trakt_logged_out)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextPrimary
                )
            }
        }
    }

    val selectedPoster = posterOptionsTarget
    if (selectedPoster != null) {
        val item = selectedPoster.item
        val statusKey = homeItemStatusKey(item.id, item.apiType)
        val isMovie = item.apiType.equals("movie", ignoreCase = true)
        val isSeries = item.apiType.equals("series", ignoreCase = true) ||
            item.apiType.equals("tv", ignoreCase = true) ||
            item.apiType.equals("anime", ignoreCase = true)
        HomePosterOptionsDialog(
            item = item,
            addonBaseUrl = selectedPoster.addonBaseUrl,
            showManageLists = uiState.librarySourceMode != LibrarySourceMode.LOCAL,
            isMovie = isMovie,
            isSeries = isSeries,
            isWatched = movieWatchedStatus[statusKey] == true,
            isWatchedPending = statusKey in uiState.movieWatchedPending,
            onDismiss = { posterOptionsTarget = null },
            onDetails = {
                onNavigateToDetail(item.id, item.apiType, selectedPoster.addonBaseUrl)
                posterOptionsTarget = null
            },
            onManageLists = {
                viewModel.openPosterListPicker(item, selectedPoster.addonBaseUrl)
                posterOptionsTarget = null
            },
            onToggleWatched = {
                if (isMovie) {
                    viewModel.togglePosterMovieWatched(item)
                } else {
                    viewModel.togglePosterSeriesWatched(item)
                }
                posterOptionsTarget = null
            }
        )
    }

    if (uiState.showPosterListPicker) {
        val localTab = LibraryListTab(
            key = LOCAL_LIBRARY_LIST_KEY,
            title = stringResource(R.string.trakt_library_source_nuvio),
            type = LibraryListTab.Type.WATCHLIST
        )
        val contentType = uiState.posterListPickerContentType.orEmpty()
        val destinationTabs = uiState.libraryListTabs.filter { tab ->
            tab.supportsMembershipFor(contentType)
        }
        HomeLibraryListPickerDialog(
            title = uiState.posterListPickerTitle ?: stringResource(R.string.detail_lists_fallback),
            tabs = listOf(localTab) + destinationTabs,
            membership = uiState.posterListPickerMembership,
            isPending = uiState.posterListPickerPending,
            error = uiState.posterListPickerError,
            onToggle = { key -> viewModel.togglePosterListPickerMembership(key) },
            onSave = { viewModel.savePosterListPickerMembership() },
            onDismiss = { viewModel.dismissPosterListPicker() }
        )
    }

    if (uiState.posterListPickerRemovalConfirmations.isNotEmpty()) {
        TrackingRemovalConfirmationDialog(
            itemTitle = uiState.posterListPickerTitle.orEmpty(),
            confirmations = uiState.posterListPickerRemovalConfirmations,
            isPending = uiState.posterListPickerPending,
            onConfirm = viewModel::confirmPosterListPickerRemoval,
            onDismiss = viewModel::cancelPosterListPickerRemoval
        )
    }
}

/**
 * Holding a title on Home: the new look's glass menu. Its bin works as the details page's Bin button does: into the
 * starred bin, or out of the one bin holding it; "Bins…" lists every bin, with a new one at the end.
 */
@Composable
private fun HomePosterOptionsDialog(
    item: MetaPreview,
    addonBaseUrl: String?,
    showManageLists: Boolean,
    isMovie: Boolean,
    isSeries: Boolean,
    isWatched: Boolean,
    isWatchedPending: Boolean,
    onDismiss: () -> Unit,
    onDetails: () -> Unit,
    onManageLists: () -> Unit,
    onToggleWatched: () -> Unit,
    binsViewModel: HomePosterBinsViewModel = hiltViewModel()
) {
    val binsState by binsViewModel.state.collectAsStateWithLifecycle()
    val bins = binsViewModel.bins(binsState)
    val holding = binsViewModel.holding(binsState, item)
    var listingBins by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    if (naming) {
        val blank = stringResource(R.string.bins_name_blank)
        val taken = stringResource(R.string.bins_name_taken)
        com.nuvio.tv.ui.screens.library.BinNameDialog(
            title = stringResource(R.string.bins_name_title),
            initial = "",
            confirmLabel = stringResource(R.string.bins_create_button),
            onConfirm = { name -> binsViewModel.createAndAdd(name, item, addonBaseUrl, blank) { taken.format(it) } },
            onDismiss = { naming = false; onDismiss() }
        )
        return
    }
    fun setInBin(binId: String, add: Boolean) {
        binsViewModel.setInBin(binId, item, addonBaseUrl, add)
        onDismiss()
    }
    val starred = bins.firstOrNull { it.starred }
    val binOption = when {
        holding.size == 1 -> NewLookOption(stringResource(R.string.home_poster_remove_from_bin, holding.single().name)) {
            setInBin(holding.single().id, add = false)
        }
        holding.isEmpty() && starred != null -> NewLookOption(stringResource(R.string.home_poster_add_to_bin, starred.name)) {
            setInBin(starred.id, add = true)
        }
        else -> null
    }
    val binList = bins.map { bin ->
        NewLookOption(if (bin.starred) "★  ${bin.name}" else bin.name, checked = bin in holding) {
            setInBin(bin.id, add = bin !in holding)
        }
    } + NewLookOption("+  " + stringResource(R.string.nd_bin_new)) { naming = true }
    val mainOptions = listOfNotNull(
        NewLookOption(stringResource(R.string.cw_action_go_to_details), onClick = onDetails),
        binOption,
        NewLookOption(
            if (holding.size > 1) stringResource(R.string.home_poster_in_bins, holding.size)
            else stringResource(R.string.home_poster_choose_bin)
        ) { listingBins = true },
        if (isMovie || isSeries) {
            NewLookOption(
                stringResource(if (isWatched) R.string.hero_mark_unwatched else R.string.hero_mark_watched),
                enabled = !isWatchedPending,
                onClick = onToggleWatched
            )
        } else {
            null
        },
        if (showManageLists) NewLookOption(stringResource(R.string.library_manage_lists), onClick = onManageLists) else null
    )
    // One menu, its options swapped for the list of bins, so it doesn't close and reopen in between.
    NewLookOptionsMenu(
        title = if (listingBins) stringResource(R.string.nd_bin_picker_title) else item.name,
        subtitle = if (listingBins) item.name else stringResource(R.string.home_poster_dialog_subtitle),
        image = if (listingBins) null else item.background ?: item.poster,
        options = if (listingBins) binList else mainOptions,
        // Back from the list of bins goes back to the menu.
        onDismiss = { if (listingBins) listingBins = false else onDismiss() },
        initialIndex = if (listingBins) bins.indexOfFirst { it.starred }.coerceAtLeast(0) else 0,
        contentKey = listingBins
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun HomeLibraryListPickerDialog(
    title: String,
    tabs: List<LibraryListTab>,
    membership: Map<String, Boolean>,
    isPending: Boolean,
    error: String?,
    onToggle: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    val primaryFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        primaryFocusRequester.requestFocus()
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = title,
        subtitle = stringResource(R.string.detail_lists_subtitle),
        width = 500.dp
    ) {
        if (!error.isNullOrBlank()) {
            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFFFB6B6)
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 300.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(tabs, key = { it.key }) { tab ->
                val selected = membership[tab.key] == true
                val titleText = if (selected) "\u2713 ${tab.localizedMembershipTitle()}" else tab.localizedMembershipTitle()
                Button(
                    onClick = { onToggle(tab.key) },
                    enabled = !isPending,
                    modifier = if (tab.key == tabs.firstOrNull()?.key) {
                        Modifier
                            .fillMaxWidth()
                            .focusRequester(primaryFocusRequester)
                    } else {
                        Modifier.fillMaxWidth()
                    },
                    colors = ButtonDefaults.colors(
                        containerColor = if (selected) NuvioTheme.colors.FocusBackground else NuvioTheme.colors.BackgroundCard,
                        contentColor = NuvioTheme.colors.TextPrimary
                    )
                ) {
                    Text(
                        text = titleText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Divider(color = NuvioTheme.colors.Border, thickness = NuvioTheme.spacing.hairline)

        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Button(
                onClick = onSave,
                enabled = !isPending,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundCard,
                    contentColor = NuvioTheme.colors.TextPrimary
                )
            ) {
                Text(if (isPending) stringResource(R.string.action_saving) else stringResource(R.string.action_save))
            }
        }
    }
}
