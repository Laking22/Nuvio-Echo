package com.nuvio.tv.ui.screens.plugin

import android.graphics.Bitmap
import com.nuvio.tv.core.plugin.TestDiagnostics
import com.nuvio.tv.domain.model.LocalScraperResult
import com.nuvio.tv.domain.model.PluginRepository
import com.nuvio.tv.domain.model.ScraperInfo

data class PluginUiState(
    val pluginsEnabled: Boolean = true,
    val groupStreamsByRepository: Boolean = false,
    /** The DNS CloudStream extensions use (its key), and the choices as (key, label); none in the Play Store build. */
    val extensionDns: Int = 0,
    val extensionDnsOptions: List<Pair<Int, Int>> = emptyList(),
    /** The source languages that run, and each language the installed sources are in with how many. */
    val sourceLanguages: Set<String> = emptySet(),
    val installedLanguages: Map<String, Int> = emptyMap(),
    /** CloudStream extensions that have their own settings screen. */
    val scrapersWithSettings: Set<String> = emptySet(),
    val repositories: List<PluginRepository> = emptyList(),
    val scrapers: List<ScraperInfo> = emptyList(),
    val isLoading: Boolean = false,
    val isAddingRepo: Boolean = false,
    val isTesting: Boolean = false,
    val testResults: List<LocalScraperResult>? = null,
    val testDiagnostics: TestDiagnostics? = null,
    val testScraperId: String? = null,
    val errorMessage: String? = null,
    val successMessage: String? = null,
    // QR mode
    val isQrModeActive: Boolean = false,
    val qrCodeBitmap: Bitmap? = null,
    val serverUrl: String? = null,
    // Pending change from phone
    val pendingRepoChange: PendingRepoChangeInfo? = null,
    // Pending scraper enable confirmation
    val pendingScraperEnable: PendingScraperEnableInfo? = null
)

data class PendingRepoChangeInfo(
    val changeId: String,
    val proposedUrls: List<String>,
    val addedUrls: List<String>,
    val removedUrls: List<String>,
    val isApplying: Boolean = false
)

data class PendingScraperEnableInfo(
    val scraperId: String,
    val scraperName: String
)

sealed interface PluginUiEvent {
    data class AddRepository(val url: String) : PluginUiEvent
    data class RemoveRepository(val repoId: String) : PluginUiEvent
    data class RefreshRepository(val repoId: String) : PluginUiEvent
    data class ToggleScraper(val scraperId: String, val enabled: Boolean) : PluginUiEvent
    data class ToggleAllScrapersForRepo(val repoId: String, val enabled: Boolean) : PluginUiEvent
    data class TestScraper(val scraperId: String) : PluginUiEvent
    data class SetPluginsEnabled(val enabled: Boolean) : PluginUiEvent
    data class SetGroupStreamsByRepository(val enabled: Boolean) : PluginUiEvent
    object CycleExtensionDns : PluginUiEvent
    data class ToggleSourceLanguage(val language: String) : PluginUiEvent
    data class OpenExtensionSettings(val scraperId: String) : PluginUiEvent
    object ClearTestResults : PluginUiEvent
    object ClearError : PluginUiEvent
    object ClearSuccess : PluginUiEvent
    object StartQrMode : PluginUiEvent
    object StopQrMode : PluginUiEvent
    object ConfirmPendingRepoChange : PluginUiEvent
    object RejectPendingRepoChange : PluginUiEvent
    object ConfirmPendingScraperEnable : PluginUiEvent
    object DismissPendingScraperEnable : PluginUiEvent
}
