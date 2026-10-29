package com.nuvio.tv.ui.screens.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.local.AddCustomAvatarResult
import com.nuvio.tv.data.local.CustomAvatarDataStore
import com.nuvio.tv.data.remote.supabase.AvatarCatalogItem
import com.nuvio.tv.data.remote.supabase.AvatarRepository
import com.nuvio.tv.data.repository.MemberAccessRepository
import com.nuvio.tv.domain.model.CosmeticEntitlement
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AvatarSettingsUiState(
    val avatars: List<AvatarCatalogItem> = emptyList(),
    val savedCustomAvatarIds: Set<String> = emptySet(),
    val loading: Boolean = true
)

@HiltViewModel
class AvatarSettingsViewModel @Inject constructor(
    private val avatarRepository: AvatarRepository,
    private val customAvatarDataStore: CustomAvatarDataStore,
    memberAccessRepository: MemberAccessRepository
) : ViewModel() {
    private val customAvatars = customAvatarDataStore.avatars
    private val catalog = MutableStateFlow<List<AvatarCatalogItem>>(emptyList())
    private val loading = MutableStateFlow(true)

    val uiState = combine(catalog, customAvatars, loading) { defaults, custom, isLoading ->
        AvatarSettingsUiState(
            avatars = defaults + custom,
            savedCustomAvatarIds = custom.mapTo(mutableSetOf()) { it.id },
            loading = isLoading
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AvatarSettingsUiState())

    init {
        viewModelScope.launch {
            memberAccessRepository.access
                .map { access -> access.entitlements.includes(CosmeticEntitlement.PROFILE_AVATARS) }
                .distinctUntilChanged()
                .collect { hasMemberAccess ->
                    loading.value = true
                    try {
                        catalog.value = avatarRepository.getAvatarCatalog(hasMemberAccess)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Log.w("AvatarSettingsVM", "Could not load the avatar catalog", error)
                    } finally {
                        loading.value = false
                    }
                }
        }
    }

    fun addAvatar(url: String, onResult: (AddCustomAvatarResult) -> Unit) {
        viewModelScope.launch {
            onResult(customAvatarDataStore.add(url))
        }
    }
}
