package com.nuvio.tv.ui.screens.library

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.cloud.CloudLibraryFile
import com.nuvio.tv.core.cloud.CloudLibraryItem
import com.nuvio.tv.core.cloud.CloudLibraryItemType
import com.nuvio.tv.core.cloud.CloudLibraryPlaybackContext
import com.nuvio.tv.core.cloud.CloudLibraryPlaybackInfo
import com.nuvio.tv.core.cloud.CloudLibraryPlaybackResult
import com.nuvio.tv.core.cloud.CloudLibraryPlaybackSessionStore
import com.nuvio.tv.core.cloud.CloudLibraryRepository
import com.nuvio.tv.core.cloud.CloudLibraryUiState
import com.nuvio.tv.core.debrid.DebridProviderCapability
import com.nuvio.tv.core.debrid.DebridProviders
import com.nuvio.tv.core.debrid.supports
import com.nuvio.tv.core.player.ExternalPlaybackMetadata
import com.nuvio.tv.core.player.ExternalPlaybackTracker
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.data.local.PlayerPreference
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

data class FilterOption(
    val key: String,
    val label: String,
    val count: Int
)

data class CloudUiState(
    val cloudLibrary: CloudLibraryUiState = CloudLibraryUiState(),
    val visibleItems: List<CloudLibraryItem> = emptyList(),
    val providers: List<FilterOption> = emptyList(),
    val types: List<FilterOption> = emptyList(),
    val selectedProviderId: String? = null,
    val selectedType: CloudLibraryItemType? = null,
    /** Free-text filter over the loaded cloud library; it never queries a provider. */
    val searchQuery: String = "",
    val resolvingFileKey: String? = null,
    val settingsVersion: Long = 0L,
    val message: String? = null
)

/** The Library's Cloud tab: what's in the connected debrid services, playable from here. */
@HiltViewModel
class CloudLibraryViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cloudLibraryRepository: CloudLibraryRepository,
    private val cloudPlaybackSessionStore: CloudLibraryPlaybackSessionStore,
    private val externalPlaybackTracker: ExternalPlaybackTracker,
    private val playerSettingsDataStore: PlayerSettingsDataStore,
    private val debridSettingsDataStore: DebridSettingsDataStore,
    private val profileManager: ProfileManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(CloudUiState())
    val uiState: StateFlow<CloudUiState> = _uiState.asStateFlow()
    private var refreshJob: Job? = null
    private var messageJob: Job? = null

    init {
        observeSettings()
    }

    fun ensureLoaded() {
        val current = _uiState.value.cloudLibrary
        if (current.isLoaded || current.isRefreshing) return
        refresh()
    }

    fun refresh() {
        if (_uiState.value.cloudLibrary.isRefreshing) return
        refreshJob = viewModelScope.launch {
            _uiState.update { state ->
                state.copy(
                    cloudLibrary = state.cloudLibrary.copy(
                        isRefreshing = true,
                        providers = state.cloudLibrary.providers.map { it.copy(isLoading = true, errorMessage = null) }
                    )
                )
            }
            runCatching { cloudLibraryRepository.refresh() }
                .onSuccess { refreshed -> _uiState.update { it.copy(cloudLibrary = refreshed).withVisibleItems() } }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    showMessage(error.message ?: context.getString(R.string.cloud_library_play_failed))
                    _uiState.update {
                        it.copy(cloudLibrary = it.cloudLibrary.copy(isLoaded = true, isRefreshing = false)).withVisibleItems()
                    }
                }
        }
    }

    fun selectProvider(providerId: String?) = _uiState.update { it.copy(selectedProviderId = providerId).withVisibleItems() }

    fun selectType(type: CloudLibraryItemType?) = _uiState.update { it.copy(selectedType = type).withVisibleItems() }

    fun search(query: String) = _uiState.update { it.copy(searchQuery = query).withVisibleItems() }

    fun onNoPlayableFiles() = showMessage(context.getString(R.string.cloud_library_no_playable_files))

    fun resolvePlayback(item: CloudLibraryItem, file: CloudLibraryFile, onResolved: (CloudLibraryPlaybackInfo) -> Unit) {
        if (_uiState.value.resolvingFileKey != null) return
        val resolveKey = "${item.stableKey}:${file.stableKey}"
        viewModelScope.launch {
            _uiState.update { it.copy(resolvingFileKey = resolveKey) }
            val result = cloudLibraryRepository.resolvePlayback(item, file)
            _uiState.update { it.copy(resolvingFileKey = null) }
            when (result) {
                is CloudLibraryPlaybackResult.Success -> {
                    val playbackContext = CloudLibraryPlaybackContext.create(item, file)
                    if (playbackContext == null) {
                        showMessage(context.getString(R.string.cloud_library_play_failed))
                        return@launch
                    }
                    onResolved(
                        CloudLibraryPlaybackInfo(
                            item = item,
                            file = file,
                            url = result.url,
                            filename = result.filename ?: file.name.takeIf { it.isNotBlank() },
                            videoSizeBytes = result.videoSizeBytes ?: file.sizeBytes,
                            sessionToken = cloudPlaybackSessionStore.create(playbackContext),
                            sequenceIndex = playbackContext.currentIndex
                        )
                    )
                }
                CloudLibraryPlaybackResult.MissingCredentials -> showMessage(context.getString(R.string.cloud_library_connect_message))
                CloudLibraryPlaybackResult.NotPlayable -> showMessage(context.getString(R.string.cloud_library_no_playable_files))
                is CloudLibraryPlaybackResult.Failed -> showMessage(result.message ?: context.getString(R.string.cloud_library_play_failed))
            }
        }
    }

    suspend fun launchExternally(info: CloudLibraryPlaybackInfo, activityContext: Context): Boolean {
        val filename = info.filename ?: info.file.name
        val metadata = ExternalPlaybackMetadata(
            contentId = info.item.stableKey,
            contentType = "cloud",
            contentName = info.item.name,
            poster = null,
            backdrop = null,
            logo = null,
            videoId = "${info.item.stableKey}:${info.file.stableKey}",
            season = 1,
            episode = info.sequenceIndex + 1,
            episodeTitle = filename,
            year = null,
            profileId = profileManager.activeProfileId.value
        )
        val launched = runCatching {
            externalPlaybackTracker.launchPlayer(
                metadata = metadata,
                url = info.url,
                title = filename,
                headers = null,
                cloudSessionToken = info.sessionToken,
                context = activityContext
            )
        }.getOrDefault(false)
        if (!launched) showMessage(context.getString(R.string.cloud_library_play_failed))
        return launched
    }

    suspend fun playerPreference(): PlayerPreference = playerSettingsDataStore.playerSettings.first().playerPreference

    private fun observeSettings() {
        viewModelScope.launch {
            debridSettingsDataStore.settings
                .map { settings ->
                    settings.cloudLibraryEnabled to DebridProviders.configuredServices(settings)
                        .filter { it.provider.supports(DebridProviderCapability.CloudLibrary) }
                        .map { "${it.provider.id}:${it.apiKey}" }
                }
                .distinctUntilChanged()
                .collectLatest { (enabled, connections) ->
                    refreshJob?.cancel()
                    _uiState.update { state ->
                        state.copy(
                            cloudLibrary = when {
                                !enabled -> CloudLibraryUiState(isLoaded = true, isEnabled = false)
                                connections.isEmpty() -> CloudLibraryUiState(isLoaded = true, isEnabled = true)
                                else -> CloudLibraryUiState(isLoaded = false, isEnabled = true)
                            },
                            visibleItems = emptyList(),
                            providers = emptyList(),
                            types = emptyList(),
                            selectedProviderId = null,
                            selectedType = null,
                            resolvingFileKey = null,
                            settingsVersion = state.settingsVersion + 1L
                        )
                    }
                }
        }
    }

    private fun CloudUiState.withVisibleItems(): CloudUiState {
        val all = cloudLibrary.items
        val byProvider = selectedProviderId?.let { id -> all.filter { it.providerId == id } } ?: all
        val byType = selectedType?.let { type -> byProvider.filter { it.type == type } } ?: byProvider
        // Matches the item name or any of its files, so a release title or a filename both work.
        val query = searchQuery.trim()
        val visible = if (query.isEmpty()) byType else byType.filter { item ->
            item.name.contains(query, ignoreCase = true) || item.files.any { it.name.contains(query, ignoreCase = true) }
        }
        val providerCounts = all.groupBy { it.providerId to it.providerName }
            .map { (provider, items) -> FilterOption(provider.first, provider.second, items.size) }
            .sortedBy { it.label.lowercase(Locale.ROOT) }
        val typeCounts = byProvider.groupBy { it.type }
            .map { (type, items) -> FilterOption(type.name, typeLabel(type), items.size) }
            .sortedBy { CloudLibraryItemType.valueOf(it.key).ordinal }
        return copy(
            visibleItems = visible,
            providers = providerCounts,
            types = typeCounts,
            selectedProviderId = selectedProviderId?.takeIf { id -> providerCounts.any { it.key == id } },
            selectedType = selectedType?.takeIf { type -> typeCounts.any { it.key == type.name } }
        )
    }

    private fun typeLabel(type: CloudLibraryItemType): String = context.getString(
        when (type) {
            CloudLibraryItemType.Torrent -> R.string.cloud_library_type_torrents
            CloudLibraryItemType.Usenet -> R.string.cloud_library_type_usenet
            CloudLibraryItemType.WebDownload -> R.string.cloud_library_type_web
            CloudLibraryItemType.File -> R.string.cloud_library_type_files
        }
    )

    private fun showMessage(text: String) {
        _uiState.update { it.copy(message = text) }
        messageJob?.cancel()
        messageJob = viewModelScope.launch {
            delay(2_800)
            _uiState.update { it.copy(message = null) }
        }
    }
}
