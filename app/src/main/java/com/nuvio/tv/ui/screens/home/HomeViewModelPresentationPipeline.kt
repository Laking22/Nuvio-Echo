package com.nuvio.tv.ui.screens.home

import android.util.Log
import kotlinx.coroutines.CancellationException
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.build.AppFeaturePolicy
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.poster.withCustomPosterUrls
import com.nuvio.tv.core.tmdb.TmdbEnrichment
import com.nuvio.tv.domain.model.HomeImdbRatingsVisibility
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.TmdbSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

private const val TMDB_HERO_ENRICHMENT_CONCURRENCY = 4

private data class CoreLayoutPrefs(
    val heroCatalogKeys: List<String>,
    val heroSectionEnabled: Boolean,
    val catalogTypeSuffixEnabled: Boolean,
    val hideUnreleasedContent: Boolean,
    val showFullReleaseDate: Boolean
)

private data class LayoutUiPrefs(
    val heroCatalogKeys: List<String>,
    val heroSectionEnabled: Boolean,
    val catalogTypeSuffixEnabled: Boolean,
    val hideUnreleasedContent: Boolean,
    val showFullReleaseDate: Boolean,
    val modernLandscapePostersEnabled: Boolean,
    val homeImdbRatingsVisibility: HomeImdbRatingsVisibility,
    val trailerMuted: Boolean,
    val seeAll: SeeAllPrefs = SeeAllPrefs()
)

/** Catalog See All's poster cards, which it reads from this ViewModel. */
private data class SeeAllPrefs(
    val posterLabelsEnabled: Boolean = true,
    val catalogAddonNameEnabled: Boolean = true,
    val posterCardWidthDp: Int = 126,
    val posterCardCornerRadiusDp: Int = 12
)

@OptIn(FlowPreview::class)
internal fun HomeViewModel.observeLayoutPreferencesPipeline() {
    val coreLayoutPrefsFlow = combine(
        layoutPreferenceDataStore.heroCatalogSelections,
        layoutPreferenceDataStore.heroSectionEnabled,
        layoutPreferenceDataStore.catalogTypeSuffixEnabled,
        layoutPreferenceDataStore.hideUnreleasedContent,
        layoutPreferenceDataStore.showFullReleaseDate
    ) { heroCatalogKeys, heroSectionEnabled, catalogTypeSuffixEnabled, hideUnreleasedContent, showFullReleaseDate ->
        CoreLayoutPrefs(
            heroCatalogKeys = heroCatalogKeys,
            heroSectionEnabled = heroSectionEnabled,
            catalogTypeSuffixEnabled = catalogTypeSuffixEnabled,
            hideUnreleasedContent = hideUnreleasedContent,
            showFullReleaseDate = showFullReleaseDate
        )
    }

    val seeAllPrefsFlow = combine(
        layoutPreferenceDataStore.posterLabelsEnabled,
        layoutPreferenceDataStore.catalogAddonNameEnabled,
        layoutPreferenceDataStore.posterCardWidthDp,
        layoutPreferenceDataStore.posterCardCornerRadiusDp
    ) { posterLabelsEnabled, catalogAddonNameEnabled, posterCardWidthDp, posterCardCornerRadiusDp ->
        SeeAllPrefs(
            posterLabelsEnabled = posterLabelsEnabled,
            catalogAddonNameEnabled = catalogAddonNameEnabled,
            posterCardWidthDp = posterCardWidthDp,
            posterCardCornerRadiusDp = posterCardCornerRadiusDp
        )
    }

    viewModelScope.launch {
        combine(
            coreLayoutPrefsFlow,
            layoutPreferenceDataStore.modernLandscapePostersEnabled,
            layoutPreferenceDataStore.homeImdbRatingsVisibility,
            layoutPreferenceDataStore.focusedPosterBackdropTrailerMuted,
            seeAllPrefsFlow
        ) { corePrefs, landscapePosters, homeImdbRatingsVisibility, trailerMuted, seeAllPrefs ->
            LayoutUiPrefs(
                heroCatalogKeys = corePrefs.heroCatalogKeys,
                heroSectionEnabled = corePrefs.heroSectionEnabled,
                catalogTypeSuffixEnabled = corePrefs.catalogTypeSuffixEnabled,
                hideUnreleasedContent = corePrefs.hideUnreleasedContent,
                showFullReleaseDate = corePrefs.showFullReleaseDate,
                modernLandscapePostersEnabled = landscapePosters,
                homeImdbRatingsVisibility = homeImdbRatingsVisibility,
                trailerMuted = trailerMuted,
                seeAll = seeAllPrefs
            )
        }
            .distinctUntilChanged()
            .debounce(300)
            .collectLatest { prefs ->
                val previousState = _uiState.value
                val heroKeysChanged = currentHeroCatalogKeys != prefs.heroCatalogKeys
                val shouldRefreshCatalogPresentation =
                    heroKeysChanged ||
                        previousState.heroSectionEnabled != prefs.heroSectionEnabled ||
                        previousState.hideUnreleasedContent != prefs.hideUnreleasedContent ||
                        previousState.homeImdbRatingsVisibility != prefs.homeImdbRatingsVisibility
                currentHeroCatalogKeys = prefs.heroCatalogKeys
                _uiState.update {
                    it.copy(
                        layoutPreferencesReady = true,
                        heroCatalogKeys = prefs.heroCatalogKeys,
                        heroSectionEnabled = prefs.heroSectionEnabled,
                        catalogTypeSuffixEnabled = prefs.catalogTypeSuffixEnabled,
                        hideUnreleasedContent = prefs.hideUnreleasedContent,
                        showFullReleaseDate = prefs.showFullReleaseDate,
                        modernLandscapePostersEnabled = prefs.modernLandscapePostersEnabled,
                        homeImdbRatingsVisibility = prefs.homeImdbRatingsVisibility,
                        focusedPosterBackdropTrailerMuted = prefs.trailerMuted,
                        posterLabelsEnabled = prefs.seeAll.posterLabelsEnabled,
                        catalogAddonNameEnabled = prefs.seeAll.catalogAddonNameEnabled,
                        posterCardWidthDp = prefs.seeAll.posterCardWidthDp,
                        posterCardCornerRadiusDp = prefs.seeAll.posterCardCornerRadiusDp
                    )
                }
                if (shouldRefreshCatalogPresentation) {
                    // When hero catalog keys change, load any hero catalogs
                    // not yet in catalogsMap (e.g., after startup race or
                    // when user changes hero selection in settings).
                    if (heroKeysChanged && prefs.heroCatalogKeys.isNotEmpty()) {
                        loadHeroCatalogsPipeline()
                    } else {
                        scheduleUpdateCatalogRows()
                    }
                }
            }
    }
}

@OptIn(FlowPreview::class)
internal fun HomeViewModel.observeModernHomePresentationPipeline() {
    viewModelScope.launch {
        combine(uiState, _currentLocaleTag) { state, localeTag ->
                ModernHomePresentationInput(
                    homeRows = state.homeRows,
                    catalogRows = state.catalogRows,
                    continueWatchingItems = if (state.continueWatchingEnabled)
                        state.continueWatchingItems.withCustomPosterUrls(
                            com.nuvio.tv.core.poster.patternForScreen(state.customPosterUrlPattern, com.nuvio.tv.core.poster.CustomPosterScreen.CONTINUE_WATCHING, state.customPosterEnabledScreens)
                        )
                    else emptyList(),
                    upcomingItems = if (state.continueWatchingEnabled)
                        state.upcomingItems.withCustomPosterUrls(
                            com.nuvio.tv.core.poster.patternForScreen(state.customPosterUrlPattern, com.nuvio.tv.core.poster.CustomPosterScreen.CONTINUE_WATCHING, state.customPosterEnabledScreens)
                        )
                    else emptyList(),
                    useLandscapePosters = state.modernLandscapePostersEnabled,
                    showCatalogTypeSuffix = state.catalogTypeSuffixEnabled,
                    showFullReleaseDate = state.showFullReleaseDate,
                    showImdbRatings = state.homeImdbRatingsVisibility.showRatings,
                    localeTag = localeTag
                )
            }
            // Compare by row structure only (keys + item counts), not by
            // item content.  TMDB/meta enrichment changes item fields but
            // not the row structure — the hero section reads enriched data
            // via lastEnrichedPreview instead.
            .distinctUntilChanged { old, new ->
                old.homeRows === new.homeRows
                    && old.continueWatchingItems == new.continueWatchingItems
                    && old.upcomingItems == new.upcomingItems
                    && old.useLandscapePosters == new.useLandscapePosters
                    && old.showCatalogTypeSuffix == new.showCatalogTypeSuffix
                    && old.showFullReleaseDate == new.showFullReleaseDate
                    && old.showImdbRatings == new.showImdbRatings
                    && old.localeTag == new.localeTag
                    && old.catalogRows.size == new.catalogRows.size
            }
            .debounce {
                // Use a longer debounce while catalogs are still loading to
                // avoid repeated expensive presentation builds during the
                // initial burst of catalog arrivals.
                if (catalogsLoadInProgress) 300L else 80L
            }
            .collectLatest { input ->
                val shouldWarmStart = _modernHomePresentation.value.rows.list.isEmpty()
                val visibleCatalogRowCount = input.catalogRows.count { it.items.isNotEmpty() }
                val warmStartCatalogRowCount = if (input.continueWatchingItems.isNotEmpty()) 2 else 3

                if (shouldWarmStart && visibleCatalogRowCount > warmStartCatalogRowCount) {
                    val warmStartPresentation = withContext(Dispatchers.Default) {
                        buildModernHomePresentation(
                            input = input,
                            cache = modernCarouselRowBuildCache,
                            context = appContext,
                            maxCatalogRows = warmStartCatalogRowCount
                        )
                    }
                    if (_modernHomePresentation.value != warmStartPresentation) {
                        _modernHomePresentation.value = warmStartPresentation
                    }
                }

                val presentation = withContext(Dispatchers.Default) {
                    buildModernHomePresentation(
                        input = input,
                        cache = modernCarouselRowBuildCache,
                        context = appContext
                    )
                }
                if (_modernHomePresentation.value != presentation) {
                    _modernHomePresentation.value = presentation
                }
            }
    }
}

internal fun HomeViewModel.observeExternalMetaPrefetchPreferencePipeline() {
    viewModelScope.launch {
        layoutPreferenceDataStore.preferExternalMetaAddonDetail
            .collectLatest { enabled ->
                externalMetaPrefetchEnabled = enabled
                if (!enabled) {
                    externalMetaPrefetchJob?.cancel()
                    pendingExternalMetaPrefetchItemId = null
                    externalMetaPrefetchInFlightIds.clear()
                }
            }
    }
}

internal fun HomeViewModel.requestTrailerPreviewPipeline(item: MetaPreview) {
    requestTrailerPreviewPipeline(
        itemId = item.id,
        title = item.name,
        releaseInfo = item.releaseInfo,
        apiType = item.apiType,
        fallbackYtId = item.trailerYtIds.firstOrNull()
    )
}

internal fun HomeViewModel.requestTrailerPreviewPipeline(
    itemId: String,
    title: String,
    releaseInfo: String?,
    apiType: String,
    fallbackYtId: String? = null,
    /** How tall (px) the trailer will play; null is full screen. Auto trailer quality fetches to match it. */
    surfaceHeightPx: Int? = null
) {
    if (!AppFeaturePolicy.inAppTrailerPlaybackEnabled) return
    if (startupGracePeriodActive) return

    // Resolve fallbackYtId from catalog item if not provided
    val resolvedFallbackYtId = fallbackYtId ?: findCatalogItemById(itemId)?.trailerYtIds?.firstOrNull()

    // Always bump version — only the latest request (highest version) will proceed after debounce
    activeTrailerPreviewItemId = itemId
    trailerPreviewRequestVersion++
    val requestVersion = trailerPreviewRequestVersion

    if (trailerPreviewNegativeCache.contains(itemId)) return
    val surface = surfaceHeightPx ?: Int.MAX_VALUE
    if (trailerPreviewUrlsState.containsKey(itemId)) {
        // Fetched for a surface at least this tall: it does. Fetched for a smaller one (a card's, now the hero's), it's
        // fetched again; the old one is let go, so the bigger surface waits on its backdrop rather than restarting.
        if ((trailerPreviewSurfaces[itemId] ?: Int.MAX_VALUE) >= surface) return
        if (trailerPreviewLoadingIds.contains(itemId)) return
        trailerPreviewUrlsState.remove(itemId)
        trailerPreviewAudioUrlsState.remove(itemId)
    }
    if (!trailerPreviewLoadingIds.add(itemId)) return

    trailerPreviewJob?.cancel()
    val trailerContext = Dispatchers.IO + (surfaceHeightPx?.let { com.nuvio.tv.core.player.TrailerSurface(it) }
        ?: kotlin.coroutines.EmptyCoroutineContext)
    trailerPreviewJob = viewModelScope.launch(trailerContext) {
        try {
            // Debounce: wait for focus to settle before hitting network
            delay(180)

            // Only the LATEST request proceeds — all earlier ones are stale
            if (trailerPreviewRequestVersion != requestVersion) {
                return@launch
            }

            val tmdbId = try {
                tmdbService.ensureTmdbId(itemId, apiType)
            } catch (_: Exception) {
                null
            }

            val trailerSource = trailerService.getTrailerPlaybackSource(
                title = title,
                year = extractYear(releaseInfo),
                tmdbId = tmdbId,
                type = apiType
            )

            withContext(Dispatchers.Main) {
                if (trailerSource?.videoUrl.isNullOrBlank()) {
                    val fallbackSource = resolvedFallbackYtId?.let { ytId ->
                        trailerService.getTrailerPlaybackSourceFromYouTubeUrl(
                            youtubeUrl = "https://www.youtube.com/watch?v=$ytId",
                            title = title,
                            year = extractYear(releaseInfo)
                        )
                    }
                    if (fallbackSource?.videoUrl != null) {
                        if (trailerPreviewUrlsState[itemId] != fallbackSource.videoUrl) {
                            trailerPreviewUrlsState[itemId] = fallbackSource.videoUrl
                        }
                        trailerPreviewSurfaces[itemId] = surface
                        val fallbackAudio = fallbackSource.audioUrl
                        if (fallbackAudio.isNullOrBlank()) {
                            trailerPreviewAudioUrlsState.remove(itemId)
                        } else if (trailerPreviewAudioUrlsState[itemId] != fallbackAudio) {
                            trailerPreviewAudioUrlsState[itemId] = fallbackAudio
                        }
                    } else {
                        // While YouTube is rate limiting, a miss isn't remembered, so the
                        // trailer comes back on a later focus once the pause is over.
                        if (!trailerService.isRateLimited()) {
                            trailerPreviewNegativeCache.add(itemId)
                        }
                        trailerPreviewUrlsState.remove(itemId)
                        trailerPreviewAudioUrlsState.remove(itemId)
                    }
                } else {
                    val videoUrl = trailerSource.videoUrl
                    if (trailerPreviewUrlsState[itemId] != videoUrl) {
                        trailerPreviewUrlsState[itemId] = videoUrl
                    }
                    trailerPreviewSurfaces[itemId] = surface
                    val audioUrl = trailerSource.audioUrl
                    if (audioUrl.isNullOrBlank()) {
                        trailerPreviewAudioUrlsState.remove(itemId)
                    } else if (trailerPreviewAudioUrlsState[itemId] != audioUrl) {
                        trailerPreviewAudioUrlsState[itemId] = audioUrl
                    }
                }
            }
        } finally {
            trailerPreviewLoadingIds.remove(itemId)
        }
    }
}

/**
 * What an external meta prefetch produced. A failed fetch must be distinguishable from an addon
 * that answered with nothing to add: the first is retried on the next focus, the second is not.
 * Both used to collapse to null, so one unreachable addon suppressed enrichment for the session.
 *
 * Callers own the in-flight id: they claim it before launching and release it on completion.
 */
private sealed interface ExternalMetaOutcome {
    data class Resolved(val meta: Meta) : ExternalMetaOutcome
    /**
     * The addons answered and there is nothing more to fetch: either the catalog item is already
     * sufficient, or no addon carries this item. Both are final, so neither is retried.
     */
    object Final : ExternalMetaOutcome
    object Failed : ExternalMetaOutcome
}

/**
 * Whether an external meta fetch is still outstanding. A TMDB success must not stand in for one
 * that has not resolved, and both gates ask this rather than keeping their own copy of the rule.
 */
private fun HomeViewModel.externalEnrichmentOutstanding(itemId: String): Boolean =
    externalMetaPrefetchEnabled && itemId !in prefetchedExternalMetaIds

private suspend fun HomeViewModel.fetchExternalMetaOutcome(item: MetaPreview): ExternalMetaOutcome =
    try {
        val result = metaRepository.getMetaFromAllAddons(item.apiType, item.id, item.sourceAddonBaseUrl)
            .first { it is NetworkResult.Success || it is NetworkResult.Error }
        when {
            result is NetworkResult.Success -> ExternalMetaOutcome.Resolved(result.data)
            result is NetworkResult.Error &&
                (result.code == NetworkResult.SOURCE_SUFFICIENT_CODE ||
                    result.code == NetworkResult.META_NOT_FOUND_CODE) ->
                ExternalMetaOutcome.Final
            else -> ExternalMetaOutcome.Failed
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // The repository reports request failures as NetworkResult.Error, so this is the path it
        // does not promise: anything thrown outside its own per-addon handling. It used to leave
        // the launched enrichment coroutine to fail with it, which loses the focus pipeline for
        // that item. Treating it as Failed keeps the outcome exhaustive and lets the next focus
        // retry, which is what any other failure does.
        Log.w(HomeViewModel.TAG, "External meta fetch threw for ${item.id}: ${e.message}")
        ExternalMetaOutcome.Failed
    }

internal fun HomeViewModel.onItemFocusPipeline(item: MetaPreview) {
    if (startupGracePeriodActive) {
        deferredEnrichItem = item
        return
    }
    if (item.id in prefetchedTmdbIds || item.id in prefetchedExternalMetaIds) {
        // Only external enrichment re-opens this gate, so a cached external result still shuts out
        // an unresolved TMDB fetch. That is unchanged from before and deliberate: TMDB is never
        // marked prefetched when an item has no TMDB match, so gating on it too would re-enter on
        // every focus and call ensureTmdbId each time. Fixing it needs a terminal marker for TMDB
        // first. The fetches below are gated per source, so re-entering issues only the external
        // request.
        if (!externalEnrichmentOutstanding(item.id)) {
            // Ensure enrichedPreviews contains this item so the UI can display
            // hero data immediately (e.g. when adjacent prefetch resolved it
            // before the user focused on it).
            if (item.id !in _enrichedPreviews.value) {
                var enriched = findCatalogItemById(item.id) ?: item
                // Preserve MDBList ratings from batch prefetch if the catalog
                // snapshot lost them (e.g. row rebuild between batch and focus).
                if (enriched.mdbListRatings == null) {
                    val existing = _enrichedPreviews.value[item.id]
                    if (existing?.mdbListRatings != null) {
                        enriched = enriched.copy(
                            mdbListRatings = existing.mdbListRatings,
                            mdbListRatingOrder = existing.mdbListRatingOrder,
                            imdbRating = existing.mdbListRatings.imdb?.toFloat() ?: enriched.imdbRating
                        )
                    }
                }
                addEnrichedPreview(item.id, enriched)
            }
            if (_enrichingItemId.value == item.id) setEnrichingItemId(null)
            // Still prefetch full meta in background for instant detail screen.
            if (item.id !in backgroundMetaPrefetchedIds) {
                backgroundMetaPrefetchedIds.add(item.id)
                viewModelScope.launch {
                    metaRepository.getMetaFromAllAddons(
                        type = item.apiType,
                        id = item.id
                    ).first { it !is NetworkResult.Loading }
                }
            }
            return
        }
    }
    if (pendingTmdbEnrichItemId == item.id) return

    // Clear enriching for previous item immediately when focus moves away
    if (_enrichingItemId.value != null && _enrichingItemId.value != item.id) {
        setEnrichingItemId(null)
    }

    val tmdbEnabledForCurrentLayout = currentTmdbSettings.enabled && currentTmdbSettings.modernHomeEnabled
    val willEnrich = tmdbEnabledForCurrentLayout || externalMetaPrefetchEnabled

    pendingTmdbEnrichItemId = item.id
    tmdbEnrichFocusJob?.cancel()
    tmdbEnrichFocusJob = viewModelScope.launch(Dispatchers.IO) {
        delay(HomeViewModel.EXTERNAL_META_PREFETCH_FOCUS_DEBOUNCE_MS)
        if (pendingTmdbEnrichItemId != item.id) {
            if (_enrichingItemId.value == item.id) setEnrichingItemId(null)
            return@launch
        }
        // Don't hide the hero if we already have enriched data for this item.
        // The enrichment can proceed in the background without visual disruption.
        val alreadyEnriched = item.id in _enrichedPreviews.value
        if (willEnrich && !alreadyEnriched) setEnrichingItemId(item.id)
        if (item.id in prefetchedTmdbIds || item.id in prefetchedExternalMetaIds) {
            if (!externalEnrichmentOutstanding(item.id)) {
                if (_enrichingItemId.value == item.id) setEnrichingItemId(null)
                // Still prefetch full meta in background for instant detail screen.
                if (item.id !in backgroundMetaPrefetchedIds) {
                    backgroundMetaPrefetchedIds.add(item.id)
                    launch {
                        metaRepository.getMetaFromAllAddons(
                            type = item.apiType,
                            id = item.id
                        ).first { it !is NetworkResult.Loading }
                    }
                }
                return@launch
            }
        }

        try {
            // Launch TMDB and external meta addon fetch in parallel.
            // Which sources are used depends on settings:
            // - tmdbEnabledForCurrentLayout: controls TMDB enrichment
            // - externalMetaPrefetchEnabled: controls external meta addon fetch
            val tmdbDeferred = if (tmdbEnabledForCurrentLayout && item.id !in prefetchedTmdbIds) {
                val tmdbId = runCatching { tmdbService.ensureTmdbId(item.id, item.apiType) }.getOrNull()
                if (tmdbId != null) async {
                    runCatching {
                        tmdbMetadataService.fetchEnrichment(
                            tmdbId = tmdbId,
                            contentType = item.type,
                            language = currentTmdbSettings.language
                        )
                    }.getOrNull()
                } else null
            } else null

            val externalMetaDeferred = if (externalMetaPrefetchEnabled &&
                item.id !in prefetchedExternalMetaIds &&
                externalMetaPrefetchInFlightIds.add(item.id)
            ) {
                // The id is claimed here, in the enclosing coroutine, but released when the
                // deferred completes rather than inside its body. A focus that moves on during
                // the debounce cancels this job before the body runs, and a body that never runs
                // never reaches its own finally, which would strand the id in the in-flight set
                // and block every later fetch for that item.
                async { fetchExternalMetaOutcome(item) }
                    .also { d -> d.invokeOnCompletion { externalMetaPrefetchInFlightIds.remove(item.id) } }
            } else null

            // Await both results
            val tmdbEnrichment = tmdbDeferred?.await()
            val externalOutcome = externalMetaDeferred?.await()
            val externalMeta = (externalOutcome as? ExternalMetaOutcome.Resolved)?.meta

            // Mark as prefetched
            if (tmdbEnrichment != null) prefetchedTmdbIds.add(item.id)
            if (externalOutcome != null && externalOutcome != ExternalMetaOutcome.Failed) {
                prefetchedExternalMetaIds.add(item.id)
            }

            // Merge results: apply external meta first (base layer), then TMDB on top
            // respecting which TMDB settings are enabled.
            if (externalMeta != null) {
                updateCatalogItemWithMeta(item.id, externalMeta)
            }
            if (tmdbEnrichment != null) {
                updateCatalogItemWithTmdb(item.id, tmdbEnrichment)
            }

            // If neither source produced anything, mark enrichment in previews
            // so UI doesn't keep showing spinner. Skip titles a merge already reached: a row
            // loaded after that merge carries raw data, and publishing it would downgrade them.
            if (tmdbEnrichment == null && externalMeta == null && item.id !in enrichmentMergedIds) {
                var preview = findCatalogItemById(item.id) ?: item
                // Preserve MDBList ratings from batch prefetch.
                if (preview.mdbListRatings == null) {
                    val existing = _enrichedPreviews.value[item.id]
                    if (existing?.mdbListRatings != null) {
                        preview = preview.copy(
                            mdbListRatings = existing.mdbListRatings,
                            mdbListRatingOrder = existing.mdbListRatingOrder,
                            imdbRating = existing.mdbListRatings.imdb?.toFloat() ?: preview.imdbRating
                        )
                    }
                }
                if (_enrichedPreviews.value[item.id] != preview) addEnrichedPreview(item.id, preview)
            }

            // Always prefetch full meta in background for instant detail screen loading.
            if (item.id !in backgroundMetaPrefetchedIds) {
                backgroundMetaPrefetchedIds.add(item.id)
                viewModelScope.launch {
                    metaRepository.getMetaFromAllAddons(
                        type = item.apiType,
                        id = item.id
                    ).first { it !is NetworkResult.Loading }
                }
            }

            // Warm up watch progress pipeline so detail screen reads are fast.
            viewModelScope.launch {
                watchProgressRepository.getAllEpisodeProgress(item.id.substringBefore(":")).first()
            }

        } finally {
            // Release the claim only if it is still ours: a later focus may have claimed another item.
            if (pendingTmdbEnrichItemId == item.id) pendingTmdbEnrichItemId = null
            if (_enrichingItemId.value == item.id) {
                setEnrichingItemId(null)
                // If enrichment completed but no enriched data exists for this item,
                // mark it as failed so the UI can show addon data immediately.
                if (item.id !in _enrichedPreviews.value &&
                    item.id !in prefetchedExternalMetaIds &&
                    item.id !in prefetchedTmdbIds) {
                    markEnrichmentFailed(item.id)
                }
            }
        }
    }
}

private fun HomeViewModel.updateCatalogItemWithTmdb(itemId: String, enrichment: TmdbEnrichment) {
    enrichmentMergedIds.add(itemId)
    fun mergeItem(currentItem: MetaPreview): MetaPreview {
        var merged = currentItem
        if (currentTmdbSettings.useBasicInfo) {
            merged = merged.copy(
                name = enrichment.localizedTitle ?: merged.name,
                description = enrichment.description ?: merged.description,
                genres = if (enrichment.genres.isNotEmpty()) enrichment.genres else merged.genres
            )
        }
        if (currentTmdbSettings.useArtwork) {
            merged = merged.copy(
                background = enrichment.backdrop ?: merged.background,
                logo = enrichment.logo ?: merged.logo
            )
        }
        if (currentTmdbSettings.useDetails) {
            merged = merged.copy(
                runtime = enrichment.runtimeMinutes?.toString() ?: merged.runtime,
                ageRating = enrichment.ageRating ?: merged.ageRating,
                status = enrichment.status ?: merged.status
            )
        }
        return merged
    }

    updateIndexedCatalogItem(itemId, ::mergeItem)
    clearEnrichmentFailure(itemId)

    findCatalogItemById(itemId)?.let { enriched ->
        _lastEnrichedPreview.value = enriched
        addEnrichedPreview(itemId, enriched)
    }
}

internal fun HomeViewModel.updateCatalogItemMdbListRatings(
    itemId: String,
    ratings: com.nuvio.tv.domain.model.MDBListRatings
) {
    val order = currentMdbListSettings.ratingOrder
    fun mergeItem(currentItem: MetaPreview): MetaPreview =
        currentItem.copy(
            mdbListRatings = ratings,
            mdbListRatingOrder = order,
            imdbRating = ratings.imdb?.toFloat() ?: currentItem.imdbRating
        )

    updateIndexedCatalogItem(itemId, ::mergeItem)
    // MDB ratings must reach catalogRows so the carousel presentation rebuild picks them up.
    _uiState.update { state ->
        var changed = false
        val updatedRows = state.catalogRows.map { row ->
            val idx = row.items.indexOfFirst { it.id == itemId }
            if (idx < 0) row
            else {
                val merged = mergeItem(row.items[idx])
                if (merged == row.items[idx]) row
                else {
                    changed = true
                    row.copy(items = row.items.toMutableList().apply { set(idx, merged) })
                }
            }
        }
        if (changed) state.copy(catalogRows = updatedRows) else state
    }

    val existing = _enrichedPreviews.value[itemId]
    if (existing != null) {
        val enriched = findCatalogItemById(itemId)
        if (enriched != null && enriched != existing) {
            _lastEnrichedPreview.value = enriched
            addEnrichedPreview(itemId, enriched)
        }
    }
}

internal fun HomeViewModel.updateCatalogItemImdbRating(itemId: String, rating: Float) {
    updateIndexedCatalogItem(itemId) { currentItem ->
        currentItem.copy(imdbRating = rating)
    }
    _uiState.update { state ->
        var changed = false
        val updatedRows = state.catalogRows.map { row ->
            val idx = row.items.indexOfFirst { it.id == itemId }
            if (idx < 0) row
            else {
                val updated = row.items[idx].copy(imdbRating = rating)
                if (updated == row.items[idx]) row
                else {
                    changed = true
                    val mutableItems = row.items.toMutableList()
                    mutableItems[idx] = updated
                    row.copy(items = mutableItems)
                }
            }
        }
        if (changed) state.copy(catalogRows = updatedRows) else state
    }
}

private fun HomeViewModel.updateCatalogItemWithMeta(itemId: String, meta: Meta) {
    enrichmentMergedIds.add(itemId)
    val incomingTrailerYtIds = meta.trailerYtIds
    val seasonCount = meta.videos
        .asSequence()
        .mapNotNull { it.season }
        .filter { it > 0 }
        .distinct()
        .count()
        .takeIf { it > 0 }

    fun mergeItem(currentItem: MetaPreview): MetaPreview = currentItem.copy(
        background = meta.backdropUrl ?: currentItem.backdropUrl,
        logo = meta.logo ?: currentItem.logo,
        description = meta.description ?: currentItem.description,
        imdbRating = meta.imdbRating ?: currentItem.imdbRating,
        genres = if (meta.genres.isNotEmpty()) meta.genres else currentItem.genres,
        runtime = meta.runtime ?: currentItem.runtime,
        status = meta.status ?: currentItem.status,
        ageRating = meta.ageRating ?: currentItem.ageRating,
        language = meta.language ?: currentItem.language,
        country = meta.country ?: currentItem.country,
        seasonCount = seasonCount ?: currentItem.seasonCount,
        trailerYtIds = if (incomingTrailerYtIds.isNotEmpty()) incomingTrailerYtIds else currentItem.trailerYtIds
    )

    updateIndexedCatalogItem(itemId, ::mergeItem)
    clearEnrichmentFailure(itemId)

    findCatalogItemById(itemId)?.let { enriched ->
        _lastEnrichedPreview.value = enriched
        addEnrichedPreview(itemId, enriched)
    }

    // If external meta brought new trailerYtIds and the item has no trailer resolved yet, retry.
    // Only retry if this item is currently focused — avoid prefetching trailers for adjacent items.
    if (incomingTrailerYtIds.isNotEmpty() && !trailerPreviewUrlsState.containsKey(itemId) && activeTrailerPreviewItemId == itemId) {
        trailerPreviewNegativeCache.remove(itemId)
        trailerPreviewLoadingIds.remove(itemId)
        // Bump version so any in-flight pipeline for this item treats itself as stale
        // and won't overwrite the retry result with a negative cache entry.
        trailerPreviewRequestVersion++
        val currentItem = findCatalogItemById(itemId) ?: return
        requestTrailerPreviewPipeline(currentItem)
    }
}

private fun HomeViewModel.updateCatalogItemArtworkOnly(itemId: String, meta: Meta) {
    fun mergeItem(currentItem: MetaPreview): MetaPreview = currentItem.copy(
        background = meta.backdropUrl ?: currentItem.backdropUrl,
        logo = meta.logo ?: currentItem.logo
    )

    updateIndexedCatalogItem(itemId, ::mergeItem)

    _uiState.update { state ->
        var changed = false
        val updatedRows = state.catalogRows.map { row ->
            val itemIndex = row.items.indexOfFirst { it.id == itemId }
            if (itemIndex < 0) {
                row
            } else {
                val mergedItem = mergeItem(row.items[itemIndex])
                if (mergedItem == row.items[itemIndex]) {
                    row
                } else {
                    changed = true
                    val mutableItems = row.items.toMutableList()
                    mutableItems[itemIndex] = mergedItem
                    row.copy(items = mutableItems)
                }
            }
        }
        if (changed) state.copy(catalogRows = updatedRows) else state
    }
    findCatalogItemById(itemId)?.let { enriched ->
        _lastEnrichedPreview.value = enriched
        addEnrichedPreview(itemId, enriched)
    }
}

internal suspend fun HomeViewModel.enrichHeroItemsPipeline(
    items: List<MetaPreview>,
    settings: TmdbSettings
): List<MetaPreview> {
    if (items.isEmpty()) return items
    val mdbSettings = currentMdbListSettings
    val mdbEnabled = mdbListRepository.isAvailable(mdbSettings) && mdbSettings.showOnHero

    return coroutineScope {
        val semaphore = Semaphore(TMDB_HERO_ENRICHMENT_CONCURRENCY)

        items.map { item ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    try {
                        val tmdbDeferred = async {
                            val tmdbId = tmdbService.ensureTmdbId(item.id, item.apiType) ?: return@async null
                            tmdbId.toIntOrNull()?.let { numericId ->
                                runCatching { tmdbService.tmdbToImdb(numericId, item.apiType) }
                            }
                            tmdbMetadataService.fetchEnrichment(
                                tmdbId = tmdbId,
                                contentType = item.type,
                                language = settings.language
                            )
                        }
                        // MdbListRatingsLoader auto-batches concurrent calls within a 50ms window,
                        // so per-item getRatingsForMeta calls are efficiently grouped.
                        val mdbDeferred = if (mdbEnabled) async {
                            runCatching {
                                mdbListRepository.getRatingsForMeta(
                                    meta = com.nuvio.tv.domain.model.Meta(
                                        id = item.id, type = item.type, name = item.name,
                                        poster = item.poster, posterShape = item.posterShape,
                                        background = item.background, logo = item.logo,
                                        description = item.description, releaseInfo = item.releaseInfo,
                                        imdbRating = item.imdbRating, genres = item.genres,
                                        runtime = item.runtime, director = item.director,
                                        cast = emptyList(), videos = emptyList(),
                                        country = item.country, awards = null,
                                        language = item.language, links = item.links
                                    ),
                                    fallbackItemId = item.id,
                                    fallbackItemType = item.apiType
                                )
                            }.getOrNull()
                        } else null

                        val enrichment = tmdbDeferred.await()
                        val mdbResult = mdbDeferred?.await()

                        var enriched = item

                        // Attach full MDBList ratings (already filtered by user settings in repository).
                        if (mdbResult != null) {
                            enriched = enriched.copy(
                                mdbListRatings = mdbResult.ratings,
                                mdbListRatingOrder = mdbSettings.ratingOrder,
                                imdbRating = mdbResult.ratings.imdb?.toFloat() ?: enriched.imdbRating
                            )
                        }

                        if (enrichment == null) return@withPermit enriched

                        if (settings.useArtwork) {
                            enriched = enriched.copy(
                                background = enrichment.backdrop ?: enriched.background,
                                logo = enrichment.logo ?: enriched.logo,
                                poster = enrichment.poster ?: enriched.poster
                            )
                        }

                        if (settings.useBasicInfo) {
                            enriched = enriched.copy(
                                name = enrichment.localizedTitle ?: enriched.name,
                                description = enrichment.description ?: enriched.description,
                                genres = if (enrichment.genres.isNotEmpty()) enrichment.genres else enriched.genres,
                                imdbRating = enriched.imdbRating
                            )
                        }

                        if (settings.useDetails) {
                            enriched = enriched.copy(
                                runtime = enrichment.runtimeMinutes?.toString() ?: enriched.runtime,
                                status = enrichment.status ?: enriched.status,
                                ageRating = enrichment.ageRating ?: enriched.ageRating,
                                country = enrichment.countries?.joinToString(", ") ?: enriched.country,
                                language = enrichment.language ?: enriched.language
                            )
                        }

                        enriched
                    } catch (e: Exception) {
                        Log.w(HomeViewModel.TAG, "Hero enrichment failed for ${item.id}: ${e.message}")
                        item
                    }
                }
            }
        }.awaitAll()
    }
}

internal fun HomeViewModel.heroEnrichmentSignaturePipeline(
    items: List<MetaPreview>,
    settings: TmdbSettings
): String {
    val itemSignature = items.joinToString(separator = "|") { item ->
        "${item.id}:${item.apiType}:${item.name}:${item.backdropUrl}:${item.logo}:${item.poster}"
    }
    return buildString {
        append(settings.enabled)
        append(':')
        append(settings.language)
        append(':')
        append(settings.useArtwork)
        append(':')
        append(settings.useBasicInfo)
        append(':')
        append(settings.useDetails)
        append("::")
        append(itemSignature)
    }
}
