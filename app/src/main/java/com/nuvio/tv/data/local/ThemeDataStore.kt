package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.AppFont
import com.nuvio.tv.domain.model.AppTheme
import com.nuvio.tv.domain.model.CustomThemeColors
import com.nuvio.tv.domain.model.SettingsUiStyle
import com.nuvio.tv.domain.model.ThemeSelection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class SavedHomeColorTheme(
    val id: String,
    val title: String,
    val backdropUrl: String,
    val palette: List<Int> = emptyList()
)

data class HomeColorThemeSettings(
    val dynamic: Boolean = true,
    val selectedStaticThemeId: String? = null,
    val savedThemes: List<SavedHomeColorTheme> = emptyList()
)

@Singleton
class ThemeDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "theme_settings"
        private val homeThemeDynamicKey = booleanPreferencesKey("home_theme_dynamic")
        private val selectedHomeThemeKey = stringPreferencesKey("selected_home_theme")
        private val savedHomeThemesKey = stringPreferencesKey("saved_home_themes")
        private val homeThemeJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    private val themeKey = stringPreferencesKey("selected_theme")
    private val customThemeColorsKey = stringPreferencesKey("custom_theme_colors")
    private val fontKey = stringPreferencesKey("selected_font")
    private val amoledModeKey = booleanPreferencesKey("amoled_mode")
    private val amoledSurfacesModeKey = booleanPreferencesKey("amoled_surfaces_mode")
    private val settingsUiStyleKey = stringPreferencesKey("settings_ui_style")

    val homeColorThemeSettings: Flow<HomeColorThemeSettings> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { prefs ->
            val saved = prefs[savedHomeThemesKey]?.let { raw ->
                runCatching { homeThemeJson.decodeFromString<List<SavedHomeColorTheme>>(raw) }.getOrNull()
            }.orEmpty()
            val selectedId = prefs[selectedHomeThemeKey]?.takeIf { id -> saved.any { it.id == id } }
            HomeColorThemeSettings(
                dynamic = prefs[homeThemeDynamicKey] ?: true,
                selectedStaticThemeId = selectedId,
                savedThemes = saved
            )
        }
    }

    val themeSelection: Flow<ThemeSelection> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { prefs ->
            ThemeSelection(
                theme = prefs[themeKey]?.let { name -> AppTheme.entries.firstOrNull { it.name == name } },
                customColors = CustomThemeColors.decode(prefs[customThemeColorsKey])
            )
        }
    }

    val selectedFont: Flow<AppFont> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { prefs ->
            val fontName = prefs[fontKey] ?: AppFont.INTER.name
            try {
                AppFont.valueOf(fontName)
            } catch (e: IllegalArgumentException) {
                AppFont.INTER
            }
        }
    }

    val amoledMode: Flow<Boolean> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { prefs ->
            prefs[amoledModeKey] ?: false
        }
    }

    val amoledSurfacesMode: Flow<Boolean> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { prefs ->
            prefs[amoledSurfacesModeKey] ?: false
        }
    }

    val settingsUiStyle: Flow<SettingsUiStyle> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { prefs ->
            val styleName = prefs[settingsUiStyleKey] ?: SettingsUiStyle.CLASSIC.name
            try {
                SettingsUiStyle.valueOf(styleName)
            } catch (e: IllegalArgumentException) {
                SettingsUiStyle.CLASSIC
            }
        }
    }

    suspend fun setTheme(theme: AppTheme) {
        store().edit { prefs ->
            prefs[themeKey] = theme.name
        }
    }

    suspend fun setCustomTheme(colors: CustomThemeColors) {
        store().edit { prefs ->
            prefs[customThemeColorsKey] = colors.encode()
            prefs[themeKey] = AppTheme.CUSTOM.name
        }
    }

    suspend fun setFont(font: AppFont) {
        store().edit { prefs ->
            prefs[fontKey] = font.name
        }
    }

    suspend fun setAmoledMode(enabled: Boolean) {
        store().edit { prefs ->
            prefs[amoledModeKey] = enabled
            if (!enabled) {
                prefs[amoledSurfacesModeKey] = false
            }
        }
    }

    suspend fun setAmoledSurfacesMode(enabled: Boolean) {
        store().edit { prefs ->
            prefs[amoledSurfacesModeKey] = enabled
        }
    }

    suspend fun setSettingsUiStyle(style: SettingsUiStyle) {
        store().edit { prefs ->
            prefs[settingsUiStyleKey] = style.name
        }
    }

    suspend fun selectDynamicHomeTheme() {
        store().edit { prefs ->
            prefs[homeThemeDynamicKey] = true
            prefs.remove(selectedHomeThemeKey)
        }
    }

    suspend fun selectStaticHomeTheme(id: String) {
        store().edit { prefs ->
            val saved = prefs[savedHomeThemesKey]?.let { raw ->
                runCatching { homeThemeJson.decodeFromString<List<SavedHomeColorTheme>>(raw) }.getOrNull()
            }.orEmpty()
            if (saved.any { it.id == id }) {
                prefs[homeThemeDynamicKey] = false
                prefs[selectedHomeThemeKey] = id
            }
        }
    }

    suspend fun saveStaticHomeTheme(
        title: String,
        backdropUrl: String,
        palette: List<Int> = emptyList()
    ): SavedHomeColorTheme? {
        val cleanTitle = title.trim().ifBlank { "Home theme" }.take(80)
        val cleanUrl = backdropUrl.trim()
        if (cleanUrl.isBlank()) return null
        val validPalette = palette.filter { it in 0..0xFFFFFF }.take(3)
        var savedTheme = SavedHomeColorTheme(UUID.randomUUID().toString(), cleanTitle, cleanUrl, validPalette)
        store().edit { prefs ->
            val saved = prefs[savedHomeThemesKey]?.let { raw ->
                runCatching { homeThemeJson.decodeFromString<List<SavedHomeColorTheme>>(raw) }.getOrNull()
            }.orEmpty().toMutableList()
            val existingIndex = saved.indexOfFirst {
                it.backdropUrl == cleanUrl && it.title.equals(cleanTitle, ignoreCase = true)
            }
            if (existingIndex >= 0) {
                savedTheme = saved[existingIndex].copy(palette = validPalette.ifEmpty { saved[existingIndex].palette })
                saved[existingIndex] = savedTheme
            } else {
                saved += savedTheme
            }
            prefs[savedHomeThemesKey] = homeThemeJson.encodeToString(saved.takeLast(24))
            prefs[homeThemeDynamicKey] = false
            prefs[selectedHomeThemeKey] = savedTheme.id
        }
        return savedTheme
    }

    suspend fun getThemeForProfile(profileId: Int): AppTheme? {
        val prefs = factory.get(profileId, FEATURE).data.first()
        return prefs[themeKey]?.let { name -> AppTheme.entries.firstOrNull { it.name == name } }
    }

    fun observeThemeForProfile(profileId: Int): Flow<AppTheme?> =
        factory.get(profileId, FEATURE).data.map { prefs ->
            prefs[themeKey]?.let { name -> AppTheme.entries.firstOrNull { it.name == name } }
        }
}
