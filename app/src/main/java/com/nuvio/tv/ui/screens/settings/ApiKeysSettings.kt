package com.nuvio.tv.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.apikeys.ApiKeyKind
import com.nuvio.tv.core.apikeys.ApiKeys
import com.nuvio.tv.data.remote.api.TmdbApi
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/** One key as Settings shows it. */
data class ApiKeyState(val stored: String, val on: Boolean)

@HiltViewModel
class ApiKeysViewModel @Inject constructor(
    private val tmdbApi: TmdbApi,
    baseClient: OkHttpClient
) : ViewModel() {
    private val client = baseClient.newBuilder().callTimeout(10, TimeUnit.SECONDS).build()

    val keys: StateFlow<Map<ApiKeyKind, ApiKeyState>> = ApiKeys.changes
        .map { snapshot() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), snapshot())

    private val _validating = MutableStateFlow(false)
    val validating: StateFlow<Boolean> = _validating.asStateFlow()

    private fun snapshot(): Map<ApiKeyKind, ApiKeyState> =
        ApiKeyKind.entries.associateWith { ApiKeyState(ApiKeys.stored(it), ApiKeys.isOn(it)) }

    fun setOn(kind: ApiKeyKind, on: Boolean) = ApiKeys.setOn(kind, on)

    /** Blank clears the key; anything else is saved unless the service turns it down. */
    fun save(kind: ApiKeyKind, key: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val trimmed = key.trim()
            if (trimmed.isNotEmpty()) {
                _validating.value = true
                val rejected = withContext(Dispatchers.IO) { rejected(kind, trimmed) }
                _validating.value = false
                if (rejected) {
                    onResult(false)
                    return@launch
                }
            }
            ApiKeys.save(kind, trimmed)
            onResult(true)
        }
    }

    /**
     * True only when the service answers that the key is wrong. No answer (offline, a timeout) saves the key
     * anyway, so a slow connection can't stop someone entering theirs.
     */
    private suspend fun rejected(kind: ApiKeyKind, key: String): Boolean = runCatching {
        when (kind) {
            ApiKeyKind.TMDB -> tmdbApi.getMovieGenres(key).code() in REJECTED_CODES
            ApiKeyKind.GEMINI -> client.newCall(
                Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1")
                    .header("x-goog-api-key", key)
                    .build()
            ).execute().use { it.code in REJECTED_CODES }
        }
    }.getOrDefault(false)

    private companion object {
        val REJECTED_CODES = setOf(400, 401, 403)
    }
}

/**
 * Integrations → API keys: the TMDB and Gemini keys this device uses. Each can be entered (checked with its service
 * first) and switched off without being forgotten. TMDB works without one when the build has a key built in.
 */
@Composable
internal fun ApiKeysSettingsRows(viewModel: ApiKeysViewModel = hiltViewModel()) {
    val keys by viewModel.keys.collectAsStateWithLifecycle()
    val validating by viewModel.validating.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<ApiKeyKind?>(null) }
    val notSet = stringResource(R.string.beta_omdb_key_not_set)

    SettingsSectionLabel(
        text = stringResource(R.string.settings_api_keys),
        description = stringResource(R.string.settings_api_keys_subtitle)
    )
    ApiKeyKind.entries.forEach { kind ->
        val state = keys[kind] ?: ApiKeyState("", false)
        // With a key built in, the person's own is optional: without it (or with it off) the built-in one is used.
        val builtIn = ApiKeys.builtIn(kind).isNotBlank()
        SettingsActionRow(
            title = stringResource(kind.titleRes()),
            subtitle = stringResource(if (builtIn) R.string.settings_api_key_tmdb_subtitle_builtin else kind.subtitleRes()),
            value = maskDeviceApiKey(state.stored, if (builtIn) stringResource(R.string.settings_api_key_builtin) else notSet),
            onClick = { editing = kind }
        )
        SettingsToggleRow(
            title = stringResource(kind.useTitleRes()),
            subtitle = if (builtIn) stringResource(R.string.settings_api_key_use_builtin_off) else null,
            checked = state.on && state.stored.isNotEmpty(),
            enabled = state.stored.isNotEmpty(),
            onToggle = { viewModel.setOn(kind, !state.on) }
        )
    }

    editing?.let { kind ->
        ApiKeyDialog(
            title = stringResource(kind.titleRes()),
            subtitle = stringResource(kind.subtitleRes()),
            placeholder = stringResource(R.string.settings_api_key_placeholder),
            invalidMessage = stringResource(R.string.settings_api_key_invalid),
            currentValue = keys[kind]?.stored.orEmpty(),
            validating = validating,
            onSave = { key, onResult -> viewModel.save(kind, key, onResult) },
            onDismiss = { editing = null }
        )
    }
}

private fun ApiKeyKind.titleRes(): Int = when (this) {
    ApiKeyKind.TMDB -> R.string.settings_api_key_tmdb
    ApiKeyKind.GEMINI -> R.string.settings_api_key_gemini
}

private fun ApiKeyKind.subtitleRes(): Int = when (this) {
    ApiKeyKind.TMDB -> R.string.settings_api_key_tmdb_subtitle
    ApiKeyKind.GEMINI -> R.string.settings_api_key_gemini_subtitle
}

private fun ApiKeyKind.useTitleRes(): Int = when (this) {
    ApiKeyKind.TMDB -> R.string.settings_api_key_tmdb_use
    ApiKeyKind.GEMINI -> R.string.settings_api_key_gemini_use
}

/** Only the last four characters show. */
private fun maskDeviceApiKey(key: String, notSetLabel: String): String {
    val trimmed = key.trim()
    if (trimmed.isEmpty()) return notSetLabel
    return if (trimmed.length <= 4) "••••" else "••••••${trimmed.takeLast(4)}"
}
