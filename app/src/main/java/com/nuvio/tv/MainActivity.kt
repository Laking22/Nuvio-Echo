package com.nuvio.tv

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.GridView
import androidx.compose.ui.res.vectorResource
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.ui.components.LocalStartupLoadingState
import com.nuvio.tv.ui.components.LocalStartupSplashEnabled
import com.nuvio.tv.ui.components.StartupLoadingState
import com.nuvio.tv.ui.components.StartupDestination
import com.nuvio.tv.ui.components.shouldShowStartupSplash
import com.nuvio.tv.ui.components.startupDestinationForRoute
import com.nuvio.tv.core.runtime.PluginRuntimeHooks
import androidx.compose.ui.unit.Density
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.lifecycleScope
import androidx.metrics.performance.JankStats
import androidx.metrics.performance.PerformanceMetricsState
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.tv.material3.Card
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.contentColorFor
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import com.nuvio.tv.R
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.auth.DeviceSessionRegistration
import com.nuvio.tv.core.deeplink.DeepLinkHandler
import com.nuvio.tv.core.deeplink.DeepLinkParser
import com.nuvio.tv.core.player.PlayerWindowBackdrop
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sync.ProfileSyncService
import com.nuvio.tv.core.sync.StartupSyncService
import com.nuvio.tv.core.tracking.TrackingProgressRefreshCoordinator
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.data.local.AppOnboardingDataStore
import com.nuvio.tv.data.local.AuthSessionNoticeDataStore
import com.nuvio.tv.data.local.ExperienceModeDataStore
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.StartupAuthNotice
import com.nuvio.tv.data.local.ThemeDataStore
import com.nuvio.tv.data.repository.MemberAccessRepository
import com.nuvio.tv.data.remote.supabase.AvatarRepository
import com.nuvio.tv.domain.model.AppFont
import com.nuvio.tv.domain.model.AppTheme
import com.nuvio.tv.domain.model.CustomThemeColors
import com.nuvio.tv.domain.model.AuthState
import com.nuvio.tv.domain.model.CardDepthStyle
import com.nuvio.tv.domain.model.CosmeticEntitlement
import com.nuvio.tv.domain.model.DiscoverLocation
import com.nuvio.tv.domain.model.ExperienceMode
import com.nuvio.tv.domain.model.MemberAccess
import com.nuvio.tv.domain.model.ProfileBackgroundSelection
import com.nuvio.tv.domain.model.resolveProfileBackgroundSelection
import com.nuvio.tv.domain.model.SettingsUiStyle
import com.nuvio.tv.domain.model.resolveAppTheme
import com.nuvio.tv.domain.model.resolveCustomThemeColors
import com.nuvio.tv.domain.deeplink.AppDeepLink
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.ui.components.NuvioScrollDefaults
import com.nuvio.tv.ui.components.LocalCardDepthStyle
import com.nuvio.tv.ui.components.LocalLandscapePosterMode
import com.nuvio.tv.ui.components.LocalAlwaysBackdropWithLogo
import com.nuvio.tv.ui.navigation.Screen
import com.nuvio.tv.ui.membership.LocalMemberAccess
import com.nuvio.tv.ui.screens.account.AuthQrSignInScreen
import com.nuvio.tv.ui.screens.addon.EssentialAddonSetupScreen
import com.nuvio.tv.ui.screens.profile.ProfileSelectionScreen
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.theme.brandWordmarkResource
import com.nuvio.tv.ui.util.LocalFastHorizontalNavigationEnabled
import com.nuvio.tv.ui.util.LocalRecompositionHighlighterEnabled
import com.nuvio.tv.updater.UpdateViewModel
import com.nuvio.tv.updater.ui.UpdateBannerHost
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

val LocalSidebarExpanded = compositionLocalOf { false }
val LocalContentFocusRequester = compositionLocalOf { FocusRequester.Default }

data class SplashBackground(
    val profileColorHex: String? = null,
    val backgroundUrl: String? = null,
    val backgroundCacheKey: String? = null,
    val skipGradient: Boolean = false,
    val brandWordmarkRes: Int? = null
)
val LocalSplashBackground = compositionLocalOf { SplashBackground() }

private const val MAX_SUPPORTED_FONT_SCALE = 1.15f

data class DrawerItem(
    val route: String,
    val label: String,
    val iconRes: Int? = null,
    val icon: ImageVector? = null
)

private data class MainUiPrefs(
    val theme: AppTheme = AppTheme.WHITE,
    val customThemeColors: CustomThemeColors = CustomThemeColors.Default,
    val memberAccess: MemberAccess = MemberAccess.None,
    val font: AppFont = AppFont.INTER,
    val amoledMode: Boolean = false,
    val amoledSurfacesMode: Boolean = false,
    val hasChosenLayout: Boolean? = null,
    val experienceMode: ExperienceMode? = null,
    val experienceModeLoaded: Boolean = false,
    val addonSetupSkipped: Boolean = false,
    val discoverLocation: DiscoverLocation? = null,
    val smoothBringIntoViewEnabled: Boolean = true,
    val fastHorizontalNavigationEnabled: Boolean = false,
    val composeHighlighterEnabled: Boolean = false,
    val settingsUiStyle: SettingsUiStyle = SettingsUiStyle.CLASSIC,
    val cardDepthStyle: CardDepthStyle = CardDepthStyle(),
    val landscapePosterMode: Boolean = false,
    val alwaysBackdropWithLogo: Boolean = false
)

@AndroidEntryPoint
open class MainActivity : ComponentActivity() {

    @Inject
    lateinit var themeDataStore: ThemeDataStore

    @Inject
    lateinit var layoutPreferenceDataStore: LayoutPreferenceDataStore

    @Inject
    lateinit var experienceModeDataStore: ExperienceModeDataStore

    @Inject
    lateinit var memberAccessRepository: MemberAccessRepository

    @Inject
    lateinit var addonRepository: AddonRepository

    @Inject
    lateinit var trackingProgressRefreshCoordinator: TrackingProgressRefreshCoordinator

    @Inject
    lateinit var startupSyncService: StartupSyncService

    @Inject
    lateinit var androidTvChannelSyncService: com.nuvio.tv.core.sync.androidtv.AndroidTvChannelSyncService

    @Inject
    lateinit var profileSyncService: ProfileSyncService

    @Inject
    lateinit var profileManager: ProfileManager

    @Inject
    lateinit var authManager: AuthManager

    @Inject
    lateinit var deviceSessionRegistration: DeviceSessionRegistration

    @Inject
    lateinit var authSessionNoticeDataStore: AuthSessionNoticeDataStore

    @Inject
    lateinit var appOnboardingDataStore: AppOnboardingDataStore

    @Inject
    lateinit var avatarRepository: AvatarRepository

    @Inject
    lateinit var profileBackgroundRepository: com.nuvio.tv.data.remote.supabase.ProfileBackgroundRepository

    @Inject
    lateinit var trailerPlayerPool: com.nuvio.tv.core.player.TrailerPlayerPool

    @Inject
    lateinit var externalPlaybackTracker: com.nuvio.tv.core.player.ExternalPlaybackTracker

    @Inject
    lateinit var deepLinkHandler: DeepLinkHandler

    private val pendingDeepLinkUrl = MutableStateFlow<String?>(null)
    private val pendingLaunchIntent = MutableStateFlow<Intent?>(null)

    private lateinit var jankStats: JankStats

    /** Activity-level launcher for external video players. Survives all navigation changes. */
    private val externalPlayerLauncher = registerForActivityResult(
        com.nuvio.tv.core.player.ExternalPlayerResultContract()
    ) { result ->
        Log.d("MainActivity", "External player ActivityResult: $result")
        externalPlaybackTracker.onActivityResult(result)
    }

    /** True until the first onResume after onCreate completes. */
    private var isFirstResumeAfterCreate = false

    @OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
    override fun attachBaseContext(newBase: Context) {
        val tag = LocaleCache.localeTag.takeIf { it != LocaleCache.UNSET }

        if (!tag.isNullOrEmpty()) {
            val locale = Locale.forLanguageTag(tag)
            Locale.setDefault(locale)
            val config = Configuration(newBase.resources.configuration)
            config.setLocale(locale)
            super.attachBaseContext(newBase.createConfigurationContext(config))
        } else {
            // Cache not ready yet (very early cold start) — use system locale
            // The IO coroutine in Application.onCreate will finish before any activity
            // is usually created, but if not, we just use system locale until next launch
            super.attachBaseContext(newBase)
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        isFirstResumeAfterCreate = true
        window?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))

        // Wire the Activity-level launcher to the tracker
        externalPlaybackTracker.activityLauncher = externalPlayerLauncher

        PluginRuntimeHooks.onActivityCreate(this)

        window?.decorView?.post {
            val snapshot = com.nuvio.tv.core.player.DisplayCapabilities.detect(this)
            com.nuvio.tv.core.player.DisplayCapabilities.logSummary(snapshot)
        }

        // Extract extras set by the Continue Watching launcher channel preview programs.
        val launchContentId = intent?.getStringExtra("contentId")
        val launchContentType = intent?.getStringExtra("contentType")
        val launchMode = intent?.getStringExtra("launchMode")
        val launchVideoId = intent?.getStringExtra("videoId")
        val launchName = intent?.getStringExtra("name")
        val launchPoster = intent?.getStringExtra("poster")
        val launchBackdrop = intent?.getStringExtra("backdrop")
        val launchLogo = intent?.getStringExtra("logo")
        val launchSeason = intent?.getIntExtra("season", -1)?.takeIf { it >= 0 }
        val launchEpisode = intent?.getIntExtra("episode", -1)?.takeIf { it >= 0 }
        val launchEpisodeTitle = intent?.getStringExtra("episodeTitle")
        captureDeepLinkIntent(intent)

        setContent {
            var hasSelectedProfileThisSession by rememberSaveable { mutableStateOf(false) }
            var startupSession by remember { mutableIntStateOf(0) }
            // Triggered immediately on profile click (before system confirms selection).
            var splashTriggered by remember { mutableStateOf(false) }
            // Overrides splash background with the currently focused profile
            // so the splash matches the profile selection background.
            var focusedSplashColor by remember { mutableStateOf<String?>(null) }
            var focusedSplashBgUrl by remember { mutableStateOf<String?>(null) }
            var focusedSplashCacheKey by remember { mutableStateOf<String?>(null) }
            var focusedSplashTheme by remember { mutableStateOf<AppTheme?>(null) }
            var onboardingCompletedThisSession by remember { mutableStateOf(false) }
            var onboardingProfileSyncInProgress by remember { mutableStateOf(false) }
            val hasSeenAuthQrFlow = remember(appOnboardingDataStore) {
                appOnboardingDataStore.hasSeenAuthQrOnFirstLaunch.map<Boolean, Boolean?> { it }
            }
            val hasSeenAuthQrOnFirstLaunch by hasSeenAuthQrFlow.collectAsState(initial = null)
            val authState by authManager.authState.collectAsState()
            val context = LocalContext.current

            LaunchedEffect(authSessionNoticeDataStore, context) {
                authSessionNoticeDataStore.pendingNotice.collect { notice ->
                    if (notice == StartupAuthNotice.NUVIO) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.auth_notice_nuvio_logged_out),
                            Toast.LENGTH_LONG
                        ).show()
                        authSessionNoticeDataStore.consumeNotice(notice)
                    }
                }
            }

            LaunchedEffect(hasSeenAuthQrOnFirstLaunch, authState) {
                if (hasSeenAuthQrOnFirstLaunch == false && authState is AuthState.FullAccount) {
                    appOnboardingDataStore.setHasSeenAuthQrOnFirstLaunch(true)
                    onboardingCompletedThisSession = true
                }
            }

            val activeProfileId by profileManager.activeProfileId.collectAsState()
            val startupSplashEnabled by profileManager.startupSplashEnabled.collectAsState()
            val startupLoadingState = remember(activeProfileId, startupSession) { StartupLoadingState() }
            val profiles by profileManager.profiles.collectAsState()
            val hasEverSelectedProfile by profileManager.hasEverSelectedProfile.collectAsState()
            val rememberLastProfileEnabled by profileManager.rememberLastProfileEnabled.collectAsState()
            val activeProfile = remember(activeProfileId, profiles) {
                profiles.firstOrNull { it.id == activeProfileId }
            }
            var profilePinStates by remember { mutableStateOf<Map<Int, Boolean>>(emptyMap()) }

            LaunchedEffect(authState, profiles) {
                if (authState is AuthState.FullAccount) {
                    profileSyncService.pullProfileLockStates()
                        .onSuccess { profilePinStates = it }
                        .onFailure { profilePinStates = emptyMap() }
                } else {
                    profilePinStates = emptyMap()
                }
            }

            val activeProfileHasPin = remember(activeProfileId, profilePinStates) {
                profilePinStates[activeProfileId] == true
            }

            var profileSwitchedManually by remember { mutableStateOf(false) }
            val shouldAutoSelectProfile = rememberLastProfileEnabled &&
                hasEverSelectedProfile && !activeProfileHasPin &&
                !hasSelectedProfileThisSession && !profileSwitchedManually
            if (shouldAutoSelectProfile && !splashTriggered) {
                splashTriggered = true
                hasSelectedProfileThisSession = true
            }
            LaunchedEffect(shouldAutoSelectProfile) {
                if (shouldAutoSelectProfile) {
                    if (authManager.authState.value is AuthState.FullAccount) {
                        startupSyncService.requestSyncNow()
                    }
                    // Preload profile background catalog so the splash can show it
                    activeProfile?.profileBackgroundId?.let {
                        profileBackgroundRepository.loadSelectedAndPreload(it)
                    }
                }
            }

            var avatarCatalog by remember { mutableStateOf(emptyList<com.nuvio.tv.data.remote.supabase.AvatarCatalogItem>()) }
            val avatarMemberAccess by memberAccessRepository.access.collectAsState()
            val hasProfileAvatarAccess = avatarMemberAccess.entitlements
                .includes(CosmeticEntitlement.PROFILE_AVATARS)

            LaunchedEffect(hasProfileAvatarAccess) {
                avatarCatalog = runCatching {
                    avatarRepository.getAvatarCatalog(hasProfileAvatarAccess)
                }
                    .getOrDefault(emptyList())
            }

            val activeProfileAvatarImageUrl = remember(activeProfile, avatarCatalog) {
                activeProfile?.avatarUrl?.takeIf { it.isNotBlank() }
                    ?: activeProfile?.avatarId?.let { avatarRepository.getAvatarImageUrl(it, avatarCatalog) }
            }

            val mainUiPrefsFlow = remember(
                activeProfileId,
                startupSession,
                themeDataStore,
                layoutPreferenceDataStore,
                experienceModeDataStore,
                memberAccessRepository
            ) {
                val activeThemeFlow = combine(
                    themeDataStore.themeSelection,
                    memberAccessRepository.access
                ) { selection, memberAccess ->
                    selection.copy(
                        theme = resolveAppTheme(selection.theme, memberAccess.entitlements),
                        customColors = resolveCustomThemeColors(selection.customColors, memberAccess.tier)
                    ) to memberAccess
                }
                // Group flows into two batches to reduce intermediate flow allocations.
                // Each batch uses a single combine() instead of chaining .combine() calls,
                // which avoids N intermediate flow objects and redundant emissions on startup.
                val themeAndExperienceFlow = combine(
                    activeThemeFlow,
                    themeDataStore.selectedFont,
                    themeDataStore.amoledMode,
                    themeDataStore.amoledSurfacesMode,
                    experienceModeDataStore.mode,
                ) { themeAndAccess, font, amoledMode, amoledSurfacesMode, experienceMode ->
                    MainUiPrefs(
                        theme = themeAndAccess.first.theme ?: AppTheme.WHITE,
                        customThemeColors = themeAndAccess.first.customColors,
                        memberAccess = themeAndAccess.second,
                        font = font,
                        amoledMode = amoledMode,
                        amoledSurfacesMode = amoledSurfacesMode,
                        experienceMode = experienceMode,
                        experienceModeLoaded = true,
                    )
                }
                val layoutAndFeaturesFlow = combine(
                    layoutPreferenceDataStore.hasChosenLayout,
                    layoutPreferenceDataStore.discoverLocation,
                ) { hasChosenLayout, discoverLocation ->
                    MainUiPrefs(
                        hasChosenLayout = hasChosenLayout,
                        discoverLocation = discoverLocation,
                    )
                }
                val extraFeaturesFlow = combine(
                    experienceModeDataStore.addonSetupSkipped,
                    layoutPreferenceDataStore.smoothBringIntoViewEnabled,
                    layoutPreferenceDataStore.fastHorizontalNavigationEnabled,
                    layoutPreferenceDataStore.composeHighlighterEnabled,
                    themeDataStore.settingsUiStyle,
                ) { addonSetupSkipped, smoothBringIntoView, fastHorizontalNav, composeHighlighter, settingsUiStyle ->
                    MainUiPrefs(
                        addonSetupSkipped = addonSetupSkipped,
                        smoothBringIntoViewEnabled = smoothBringIntoView,
                        fastHorizontalNavigationEnabled = fastHorizontalNav,
                        composeHighlighterEnabled = composeHighlighter,
                        settingsUiStyle = settingsUiStyle,
                    )
                }
                val landscapePrefsFlow = combine(
                    layoutPreferenceDataStore.modernLandscapePostersEnabled,
                    layoutPreferenceDataStore.alwaysShowLandscapeClearlogo
                ) { landscape, backdropWithLogo -> landscape to backdropWithLogo }

                combine(
                    themeAndExperienceFlow,
                    layoutAndFeaturesFlow,
                    extraFeaturesFlow,
                    layoutPreferenceDataStore.cardDepthStyle,
                    landscapePrefsFlow
                ) { themePrefs, layoutPrefs, extraPrefs, cardDepthStyle, (landscapePosterMode, alwaysBackdropWithLogo) ->
                    themePrefs.copy(
                        hasChosenLayout = layoutPrefs.hasChosenLayout,
                        discoverLocation = layoutPrefs.discoverLocation,
                        addonSetupSkipped = extraPrefs.addonSetupSkipped,
                        smoothBringIntoViewEnabled = extraPrefs.smoothBringIntoViewEnabled,
                        fastHorizontalNavigationEnabled = extraPrefs.fastHorizontalNavigationEnabled,
                        composeHighlighterEnabled = extraPrefs.composeHighlighterEnabled,
                        settingsUiStyle = extraPrefs.settingsUiStyle,
                        cardDepthStyle = cardDepthStyle,
                        landscapePosterMode = landscapePosterMode,
                        alwaysBackdropWithLogo = alwaysBackdropWithLogo
                    )
                }
            }
            val mainUiPrefs by key(activeProfileId, startupSession) {
                mainUiPrefsFlow.collectAsState(initial = MainUiPrefs(hasChosenLayout = null))
            }
            val installedAddons by key(activeProfileId, startupSession) {
                remember(addonRepository) {
                    addonRepository.getInstalledAddons()
                }.collectAsState(initial = null)
            }
            val discoverLocation = mainUiPrefs.discoverLocation

            NuvioTheme(
                appTheme = mainUiPrefs.theme,
                customThemeColors = mainUiPrefs.customThemeColors,
                appFont = mainUiPrefs.font,
                amoledMode = mainUiPrefs.amoledMode,
                amoledSurfacesMode = mainUiPrefs.amoledSurfacesMode,
                settingsUiStyle = mainUiPrefs.settingsUiStyle
            ) {
                val defaultBringIntoViewSpec = LocalBringIntoViewSpec.current
                val bringIntoViewSpec = if (mainUiPrefs.smoothBringIntoViewEnabled) {
                    NuvioScrollDefaults.smoothScrollSpec
                } else {
                    defaultBringIntoViewSpec
                }
                val systemDensity = LocalDensity.current
                // The interface stays at its normal size (the beta's Interface size became Card size, Home only).
                val clampedFontScaleDensity = remember(systemDensity) {
                    Density(
                        density = systemDensity.density,
                        fontScale = systemDensity.fontScale.coerceAtMost(MAX_SUPPORTED_FONT_SCALE)
                    )
                }
                val highlighterEnabled = BuildConfig.IS_DEBUG_BUILD && mainUiPrefs.composeHighlighterEnabled
                com.nuvio.tv.ui.util.RecompositionHighlighterFlag.enabled = highlighterEnabled
                val profileBgSelection = resolveProfileBackgroundSelection(
                    profile = activeProfile,
                    entitlements = mainUiPrefs.memberAccess.entitlements
                )
                val profileBgCatalog by profileBackgroundRepository.catalog.collectAsState()
                // Cache splash background in SharedPreferences so the splash
                // can show the correct background on cold start when
                // "remember last profile" skips the profile selection screen.
                val splashPrefs = remember {
                    context.getSharedPreferences("startup_splash", android.content.Context.MODE_PRIVATE)
                }
                val splashPreferencesReady = mainUiPrefs.hasChosenLayout != null && mainUiPrefs.experienceModeLoaded
                val splashBackground = remember(
                    profileBgSelection, profileBgCatalog, activeProfile, activeProfileId,
                    focusedSplashColor, focusedSplashBgUrl, focusedSplashCacheKey,
                    focusedSplashTheme, splashPreferencesReady, mainUiPrefs.theme
                ) {
                    val bgUrl = when (profileBgSelection) {
                        is ProfileBackgroundSelection.Custom -> profileBgSelection.url
                        is ProfileBackgroundSelection.Catalog -> {
                            profileBgCatalog.firstOrNull { it.id == profileBgSelection.id }
                                ?.imageFile?.toURI()?.toString()
                        }
                        else -> null
                    }
                    val colorHex = activeProfile?.avatarColorHex
                    // Only overwrite bg_url when we have a real value or when
                    // we know the profile has no background at all.
                    if (splashPreferencesReady && colorHex != null) {
                        splashPrefs.edit().putString("color_$activeProfileId", colorHex).apply()
                    }
                    if (splashPreferencesReady && bgUrl != null) {
                        splashPrefs.edit()
                            .putString("bg_url_$activeProfileId", bgUrl)
                            .apply()
                    } else if (splashPreferencesReady && profileBgSelection == null && activeProfile != null) {
                        splashPrefs.edit()
                            .remove("bg_url_$activeProfileId")
                            .apply()
                    }
                    val activeTheme = mainUiPrefs.theme
                    if (splashPreferencesReady && activeTheme != AppTheme.WHITE) {
                        splashPrefs.edit().putString("theme_$activeProfileId", activeTheme.name).apply()
                    } else if (splashPreferencesReady) {
                        splashPrefs.edit().remove("theme_$activeProfileId").apply()
                    }
                    // Fall back to cached values only on cold start (no focused override)
                    val hasFocusedOverride = focusedSplashColor != null
                    val fallbackColor = if (colorHex == null && !hasFocusedOverride) splashPrefs.getString("color_$activeProfileId", null) else null
                    val fallbackBg = if (
                        bgUrl == null && !hasFocusedOverride && (!splashPreferencesReady || profileBgSelection != null)
                    ) splashPrefs.getString("bg_url_$activeProfileId", null) else null
                    val resolvedBgUrl = if (hasFocusedOverride) focusedSplashBgUrl else (bgUrl ?: fallbackBg)
                    val skipGradient = !hasFocusedOverride && resolvedBgUrl == null &&
                        profileBgSelection is ProfileBackgroundSelection.Catalog
                    // Resolve branded logo: focused theme (profile screen) > active theme > cached theme > default
                    val resolvedTheme = focusedSplashTheme
                        ?: activeTheme.takeIf { splashPreferencesReady }
                        ?: splashPrefs.getString("theme_$activeProfileId", null)
                            ?.let { name -> AppTheme.entries.firstOrNull { it.name == name } }
                        ?: AppTheme.WHITE
                    SplashBackground(
                        profileColorHex = if (hasFocusedOverride) focusedSplashColor else (colorHex ?: fallbackColor),
                        backgroundUrl = resolvedBgUrl,
                        backgroundCacheKey = if (hasFocusedOverride) focusedSplashCacheKey else when (profileBgSelection) {
                            is ProfileBackgroundSelection.Catalog -> profileBgCatalog
                                .firstOrNull { it.id == profileBgSelection.id }
                                ?.let { "profile-background-${it.id}-v${it.assetVersion}" }
                            is ProfileBackgroundSelection.Custom -> "custom-profile-background-${profileBgSelection.url}"
                            null -> null
                        },
                        skipGradient = skipGradient,
                        brandWordmarkRes = resolvedTheme.brandWordmarkResource
                    )
                }
                CompositionLocalProvider(
                    LocalDensity provides clampedFontScaleDensity,
                    LocalBringIntoViewSpec provides bringIntoViewSpec,
                    LocalFastHorizontalNavigationEnabled provides mainUiPrefs.fastHorizontalNavigationEnabled,
                    LocalRecompositionHighlighterEnabled provides highlighterEnabled,
                    LocalCardDepthStyle provides mainUiPrefs.cardDepthStyle,
                    LocalLandscapePosterMode provides mainUiPrefs.landscapePosterMode,
                    LocalAlwaysBackdropWithLogo provides mainUiPrefs.alwaysBackdropWithLogo,
                    LocalMemberAccess provides mainUiPrefs.memberAccess,
                    com.nuvio.tv.core.player.LocalTrailerPlayerPool provides trailerPlayerPool,
                    LocalSplashBackground provides splashBackground,
                    LocalStartupLoadingState provides startupLoadingState,
                    LocalStartupSplashEnabled provides startupSplashEnabled
                ) {
                val transparentPlayerBackdrop = PlayerWindowBackdrop.isTransparentRequested
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = RectangleShape,
                    colors = SurfaceDefaults.colors(
                        containerColor = if (transparentPlayerBackdrop) {
                            Color.Transparent
                        } else {
                            NuvioTheme.colors.Background
                        },
                        contentColor = contentColorFor(NuvioTheme.colors.Background)
                    )
                ) {
                    // Wrap everything in a Box. This prevents any black flash between
                    // profile selection and the home content
                    Box(modifier = Modifier.fillMaxSize()) {

                    var startupDestination = StartupDestination.Loading
                    val surfaceContentReady = hasSeenAuthQrOnFirstLaunch != null &&
                        authState !is AuthState.Loading

                    if (!surfaceContentReady) {
                        // Still loading auth state; nothing to show yet.
                    } else if (
                        hasSeenAuthQrOnFirstLaunch == false &&
                        authState !is AuthState.FullAccount &&
                        !onboardingCompletedThisSession
                    ) {
                        startupDestination = StartupDestination.Setup
                        AuthQrSignInScreen(
                            onBackPress = { finish() },
                            onContinue = {
                                lifecycleScope.launch {
                                    val shouldRunRemoteOnboardingSync =
                                        authManager.authState.value is AuthState.FullAccount

                                    if (shouldRunRemoteOnboardingSync) {
                                        if (onboardingProfileSyncInProgress) return@launch
                                        onboardingProfileSyncInProgress = true
                                        val maxAttempts = 3
                                        var synced = false
                                        for (attempt in 0 until maxAttempts) {
                                            val result = profileSyncService.pullFromRemote()
                                            if (result.isSuccess) {
                                                synced = true
                                                break
                                            }
                                            if (attempt < maxAttempts - 1) {
                                                delay(1_000)
                                            }
                                        }
                                        if (!synced) {
                                            android.util.Log.w(
                                                "MainActivity",
                                                "Onboarding profile sync failed after retries; continuing"
                                            )
                                        }
                                    }
                                    appOnboardingDataStore.setHasSeenAuthQrOnFirstLaunch(true)
                                    onboardingCompletedThisSession = true
                                    onboardingProfileSyncInProgress = false
                                }
                                if (authManager.authState.value is AuthState.FullAccount) {
                                    startupSyncService.requestSyncNow()
                                }
                            }
                        )
                    } else {

                    val shouldShowProfileSelection =
                        !hasSelectedProfileThisSession && (profiles.size > 1 || activeProfileHasPin)

                    if (shouldShowProfileSelection) {
                        startupDestination = if (splashTriggered) StartupDestination.Loading else StartupDestination.ProfileSelection
                        val pickerBetaSettings by com.nuvio.tv.ui.screens.player.rememberBetaPerformanceSettings()
                        androidx.compose.runtime.CompositionLocalProvider(
                            com.nuvio.tv.ui.newlook.LocalQuietFocus provides pickerBetaSettings.quietFocus
                        ) {
                        ProfileSelectionScreen(
                            onProfileClicked = {
                                splashTriggered = true
                            },
                            onProfileSelectionFailed = {
                                splashTriggered = false
                            },
                            onProfileFocusChanged = { colorHex, bgUrl, cacheKey ->
                                if (!splashTriggered) {
                                    focusedSplashColor = colorHex
                                    focusedSplashBgUrl = bgUrl
                                    focusedSplashCacheKey = cacheKey
                                }
                            },
                            onProfileThemeFocused = { theme ->
                                if (!splashTriggered) {
                                    focusedSplashTheme = theme
                                }
                            },
                            onProfileSelected = {
                                hasSelectedProfileThisSession = true
                                if (authManager.authState.value is AuthState.FullAccount) {
                                    startupSyncService.requestSyncNow()
                                }
                            }
                        )
                        }
                    } else {

                    // Profiles set up before experience modes existed only have the old
                    // "layout chosen" flag; they count as Advanced.
                    val legacySetupDone = mainUiPrefs.hasChosenLayout
                    val prefsLoading = legacySetupDone == null || !mainUiPrefs.experienceModeLoaded || installedAddons == null
                    if (!prefsLoading) {
                    val effectiveExperienceMode = mainUiPrefs.experienceMode
                        ?: if (legacySetupDone) ExperienceMode.ADVANCED else null
                    val needsExperienceSelection = effectiveExperienceMode == null
                    val setupDone = !needsExperienceSelection
                    val needsEssentialAddonSetup =
                        effectiveExperienceMode == ExperienceMode.ESSENTIAL &&
                            installedAddons.orEmpty().isEmpty() &&
                            !mainUiPrefs.addonSetupSkipped
                    val pendingDeepLink by pendingDeepLinkUrl.collectAsState()

                    LaunchedEffect(pendingDeepLink) {
                        val url = pendingDeepLink ?: return@LaunchedEffect
                        val deepLink = DeepLinkParser.parse(url)
                        if (deepLink is AppDeepLink.AddonInstall && (needsEssentialAddonSetup || !setupDone)) {
                            Toast.makeText(context, context.getString(R.string.addon_installing), Toast.LENGTH_SHORT).show()
                            val installResult = deepLinkHandler.installAddon(deepLink.manifestUrl)
                            if (pendingDeepLinkUrl.value == url) {
                                pendingDeepLinkUrl.value = null
                            }
                            Toast.makeText(context, installResult.message, Toast.LENGTH_LONG).show()
                        }
                    }

                    if (needsEssentialAddonSetup) {
                        startupDestination = StartupDestination.Setup
                        EssentialAddonSetupScreen(
                            onSkip = {
                                lifecycleScope.launch {
                                    experienceModeDataStore.setAddonSetupSkipped(true)
                                }
                            }
                        )
                    } else {
                    val startDestination = if (needsExperienceSelection) {
                        Screen.ExperienceModeSelection.route
                    } else {
                        Screen.Home.route
                    }
                    val navController = rememberNavController()
                    var optimisticRoute by remember { mutableStateOf<String?>(null) }
                    val navBackStackEntry by navController.currentBackStackEntryAsState()
                    val actualRoute = navBackStackEntry?.destination?.route
                    val currentRoute = optimisticRoute ?: actualRoute
                    startupDestination = startupDestinationForRoute(actualRoute ?: startDestination)

                    LaunchedEffect(actualRoute) {
                        optimisticRoute = null
                    }

                    // Auto-play next episode for EXTERNAL players: the tracker resolves the
                    // next episode and we navigate into the same Screen.Stream auto-play route
                    // the internal onPlaybackEnded path uses. Collected from the root composable
                    // so it survives StreamScreen's self-pop and a process kill (metadata is
                    // recovered from disk and the event replayed).
                    LaunchedEffect(navController) {
                        externalPlaybackTracker.autoPlayNext.collect { next ->
                            if (!externalPlaybackTracker.claimAutoPlayNextNavigation(next)) {
                                return@collect
                            }
                            Log.d(
                                "MainActivity",
                                "autoPlayNext received: S${next.nextSeason}E${next.nextEpisode} " +
                                    "videoId=${next.nextVideoId}; navigating to Stream"
                            )
                            navController.navigate(
                                Screen.Stream.createRoute(
                                    videoId = next.nextVideoId,
                                    contentType = next.contentType,
                                    title = next.contentName,
                                    poster = next.poster,
                                    backdrop = next.backdrop,
                                    logo = next.logo,
                                    season = next.nextSeason,
                                    episode = next.nextEpisode,
                                    year = next.year,
                                    contentId = next.contentId,
                                    contentName = next.contentName,
                                    returnToDetailOnBack = next.contentType.equals("series", ignoreCase = true),
                                    profileId = next.profileId
                                )
                            ) {
                                // Replace any lingering Stream screen (e.g. the previous
                                // episode's, restored from the backstack after a process restart).
                                popUpTo(Screen.Stream.route) { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    }

                    // Navigate to content when launched from the Continue Watching channel row.
                    LaunchedEffect(navController) {
                        if (launchContentId != null && launchContentType != null && setupDone) {
                            if (launchMode == "stream" && launchVideoId != null && launchName != null) {
                                navController.navigate(
                                    Screen.Stream.createRoute(
                                        videoId = launchVideoId,
                                        contentType = launchContentType,
                                        title = launchName,
                                        poster = launchPoster,
                                        backdrop = launchBackdrop,
                                        logo = launchLogo,
                                        season = launchSeason,
                                        episode = launchEpisode,
                                        episodeName = launchEpisodeTitle,
                                        contentId = launchContentId,
                                        contentName = launchName,
                                        returnToDetailOnBack = launchContentType.equals("series", ignoreCase = true),
                                        returnToHomeOnBack = true
                                    )
                                )
                            } else {
                                navController.navigate(
                                    Screen.Detail.createRoute(
                                        itemId = launchContentId,
                                        itemType = launchContentType
                                    )
                                )
                            }
                        }
                    }

                    val pendingLaunch by pendingLaunchIntent.collectAsState()
                    LaunchedEffect(navController, setupDone, pendingLaunch) {
                        val intent = pendingLaunch ?: return@LaunchedEffect
                        if (!setupDone) return@LaunchedEffect
                        pendingLaunchIntent.value = null
                        val contentId = intent.getStringExtra("contentId") ?: return@LaunchedEffect
                        val contentType = intent.getStringExtra("contentType") ?: return@LaunchedEffect
                        val videoId = intent.getStringExtra("videoId")
                        val name = intent.getStringExtra("name")
                        if (videoId != null && name != null) {
                            navController.navigate(
                                Screen.Stream.createRoute(
                                    videoId = videoId,
                                    contentType = contentType,
                                    title = name,
                                    poster = intent.getStringExtra("poster"),
                                    backdrop = intent.getStringExtra("backdrop"),
                                    logo = intent.getStringExtra("logo"),
                                    season = intent.getIntExtra("season", -1).takeIf { it >= 0 },
                                    episode = intent.getIntExtra("episode", -1).takeIf { it >= 0 },
                                    episodeName = intent.getStringExtra("episodeTitle"),
                                    contentId = contentId,
                                    contentName = name,
                                    returnToDetailOnBack = contentType.equals("series", ignoreCase = true),
                                    returnToHomeOnBack = true
                                )
                            )
                        } else {
                            navController.navigate(
                                Screen.Detail.createRoute(
                                    itemId = contentId,
                                    itemType = contentType
                                )
                            )
                        }
                    }

                    LaunchedEffect(navController, setupDone, pendingDeepLink) {
                        val url = pendingDeepLink ?: return@LaunchedEffect
                        if (!setupDone) return@LaunchedEffect
                        when (val deepLink = DeepLinkParser.parse(url)) {
                            is AppDeepLink.Meta -> {
                                pendingDeepLinkUrl.value = null
                                navController.navigate(
                                    Screen.Detail.createRoute(
                                        itemId = deepLink.id,
                                        itemType = deepLink.type
                                    )
                                ) {
                                    launchSingleTop = true
                                }
                            }
                            is AppDeepLink.AddonInstall -> {
                                navController.navigate(Screen.AddonManager.route) {
                                    launchSingleTop = true
                                }
                                Toast.makeText(context, context.getString(R.string.addon_installing), Toast.LENGTH_SHORT).show()
                                val installResult = deepLinkHandler.installAddon(deepLink.manifestUrl)
                                if (pendingDeepLinkUrl.value == url) {
                                    pendingDeepLinkUrl.value = null
                                }
                                Toast.makeText(context, installResult.message, Toast.LENGTH_LONG).show()
                            }
                            null -> {
                                pendingDeepLinkUrl.value = null
                            }
                        }
                    }

                    val view = LocalView.current
                    LaunchedEffect(currentRoute) {
                        val holder = PerformanceMetricsState.getHolderForHierarchy(view)
                        if (currentRoute != null) {
                            holder.state?.putState("Screen", currentRoute)
                        }
                    }

                    LaunchedEffect(discoverLocation, currentRoute) {
                        if (discoverLocation == null) return@LaunchedEffect
                        val onDiscoverRoute = currentRoute == Screen.Discover.route ||
                            currentRoute?.startsWith("${Screen.Discover.route}/") == true
                        if (discoverLocation == DiscoverLocation.OFF && onDiscoverRoute) {
                            navController.navigate(Screen.Home.route) {
                                popUpTo(navController.graph.startDestinationId) { saveState = false }
                                launchSingleTop = true
                            }
                        }
                    }

                    val betaSettings by com.nuvio.tv.ui.screens.player.rememberBetaPerformanceSettings()
                    val rootRoutes = remember(discoverLocation) {
                        buildSet {
                            add(Screen.Home.route)
                            add(Screen.Search.route)
                            add(Screen.Genres.route)
                            add(Screen.Calendar.route)
                            add(Screen.Library.route)
                            add(Screen.Settings.route)
                            if (discoverLocation == DiscoverLocation.IN_SIDEBAR) {
                                add(Screen.Discover.route)
                            }
                        }
                    }

                    val strNavHome = stringResource(R.string.nav_home)
                    val strNavDiscover = stringResource(R.string.nav_discover)
                    val strNavSearch = stringResource(R.string.nav_search)
                    val strNavLibrary = stringResource(R.string.nav_library)
                    val strNavSettings = stringResource(R.string.nav_settings)
                    val strNavGenres = stringResource(R.string.nav_genres)
                    val strNavCalendar = stringResource(R.string.nav_calendar)
                    val calendarIcon = ImageVector.vectorResource(R.drawable.ic_nav_calendar)
                    val drawerItems = remember(
                        strNavHome,
                        strNavDiscover,
                        strNavSearch,
                        strNavLibrary,
                        strNavSettings,
                        strNavGenres,
                        strNavCalendar,
                        discoverLocation,
                        calendarIcon
                    ) {
                        buildList {
                            add(
                                DrawerItem(
                                    route = Screen.Home.route,
                                    label = strNavHome,
                                    icon = Icons.Default.Home
                                )
                            )
                            if (discoverLocation == DiscoverLocation.IN_SIDEBAR) {
                                add(
                                    DrawerItem(
                                        route = Screen.Discover.route,
                                        label = strNavDiscover,
                                        icon = Icons.Default.Explore
                                    )
                                )
                            }
                            add(
                                DrawerItem(
                                    route = Screen.Search.route,
                                    label = strNavSearch,
                                    iconRes = R.raw.sidebar_search
                                )
                            )
                            add(
                                DrawerItem(
                                    route = Screen.Genres.route,
                                    label = strNavGenres,
                                    icon = Icons.Default.GridView
                                )
                            )
                            add(
                                DrawerItem(
                                    route = Screen.Calendar.route,
                                    label = strNavCalendar,
                                    icon = calendarIcon
                                )
                            )
                            add(
                                DrawerItem(
                                    route = Screen.Library.route,
                                    label = strNavLibrary,
                                    iconRes = R.raw.sidebar_library
                                )
                            )
                            add(
                                DrawerItem(
                                    route = Screen.Settings.route,
                                    label = strNavSettings,
                                    iconRes = R.raw.sidebar_settings
                                )
                            )
                        }
                    }
                    val selectedDrawerRoute = drawerItems.firstOrNull { item ->
                        currentRoute == item.route || currentRoute?.startsWith("${item.route}/") == true
                    }?.route

                    val confirmExitEnabled by profileManager.confirmExitEnabled.collectAsState()
                    var backPressedOnce by remember { mutableStateOf(false) }
                    LaunchedEffect(backPressedOnce) {
                        if (backPressedOnce) {
                            delay(2000L)
                            backPressedOnce = false
                        }
                    }
                    val handleExitApp: () -> Unit = {
                        if (!confirmExitEnabled || backPressedOnce) {
                            finishAffinity()
                            finishAndRemoveTask()
                            if (confirmExitEnabled) {
                                // Kill the process to free RAM on low-memory devices.
                                android.os.Process.killProcess(android.os.Process.myPid())
                            }
                        } else {
                            backPressedOnce = true
                            Toast.makeText(this@MainActivity, getString(R.string.confirm_exit_toast), Toast.LENGTH_SHORT).show()
                        }
                    }

                    val updateViewModel: UpdateViewModel = hiltViewModel(this@MainActivity)
                    val updateState by updateViewModel.uiState.collectAsState()
                    val updateBannerState = updateState.copy(
                        showBanner = updateState.showBanner && currentRoute?.startsWith("player/") != true
                    )
                    // An update also gets a heads-up notification, once per app start.
                    var updateAnnounced by remember { mutableStateOf(false) }
                    LaunchedEffect(updateState.showBanner) {
                        if (updateState.showBanner && !updateAnnounced) {
                            updateAnnounced = true
                            com.nuvio.tv.ui.newlook.NewLookNotifications.post(
                                com.nuvio.tv.ui.newlook.NewLookNotifications.Kind.UPDATE,
                                getString(R.string.notif_update_title),
                                getString(R.string.notif_update_text)
                            )
                        }
                    }

                    UpdateBannerHost(
                        state = updateBannerState,
                        onDismissBanner = updateViewModel::dismissBanner,
                        onDownload = updateViewModel::downloadUpdate,
                        onInstall = updateViewModel::installUpdateOrRequestPermission,
                        onDismissUnknownSources = updateViewModel::dismissUnknownSourcesDialog,
                        onOpenUnknownSources = updateViewModel::openUnknownSourcesSettings,
                        onFeedbackShown = updateViewModel::consumeFeedbackMessage
                    ) {
                        val handleSwitchProfile = {
                            startupSession++
                            splashTriggered = false
                            profileSwitchedManually = true
                            focusedSplashColor = null
                            focusedSplashBgUrl = null
                            focusedSplashCacheKey = null
                            focusedSplashTheme = null
                            hasSelectedProfileThisSession = false
                        }
                        Box(modifier = Modifier.fillMaxSize()) {
                          CompositionLocalProvider(
                              com.nuvio.tv.ui.screens.browse.LocalPosterColourMesh provides betaSettings.posterColourMesh,
                              com.nuvio.tv.ui.components.LocalTrailerVideoLayer provides betaSettings.trailerVideoLayer,
                              com.nuvio.tv.ui.newlook.LocalQuietFocus provides betaSettings.quietFocus,
                              com.nuvio.tv.ui.newlook.LocalMenuGlass provides betaSettings.menuGlass,
                              com.nuvio.tv.ui.screens.detail.LocalDetailsAmbient provides
                                  (betaSettings.homeAmbientBackground && betaSettings.detailsAmbientBackground)
                          ) {
                            com.nuvio.tv.ui.newlook.NewLookScaffold(
                                longPressBackHeld = longPressBackHeld,
                                navController = navController,
                                startDestination = startDestination,
                                currentRoute = currentRoute,
                                rootRoutes = rootRoutes,
                                drawerItems = drawerItems,
                                selectedDrawerRoute = selectedDrawerRoute,
                                hideMenu = betaSettings.hideMenuUntilSelected,
                                menuOnTop = betaSettings.menuOnTop,
                                notificationStyle = betaSettings.notificationStyle,
                                activeProfileName = activeProfile?.name ?: "",
                                activeProfileColorHex = activeProfile?.avatarColorHex ?: "#1E88E5",
                                activeProfileAvatarImageUrl = activeProfileAvatarImageUrl,
                                showProfileSelector = profiles.size > 1,
                                onSwitchProfile = handleSwitchProfile,
                                onNavigate = { optimisticRoute = it },
                                onExitApp = handleExitApp,
                                navigateToRoute = { target ->
                                    navigateToDrawerRoute(navController, currentRoute, target)
                                },
                                drawerIcon = { item, tint, modifier ->
                                    DrawerItemIcon(iconRes = item.iconRes, icon = item.icon, tint = tint, modifier = modifier)
                                }
                            )
                          }

                            val autoNextOverlay by externalPlaybackTracker.autoNextOverlay.collectAsState()
                            autoNextOverlay?.let { ov ->
                                com.nuvio.tv.ui.screens.player.LoadingOverlay(
                                    visible = true,
                                    backdropUrl = ov.backdrop,
                                    logoUrl = ov.logo,
                                    title = ov.title,
                                    message = ov.message ?: stringResource(R.string.external_auto_next_loading),
                                    progress = ov.progress,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }
                } 
            } 
        } 
    }

                    LaunchedEffect(startupDestination, startupLoadingState) {
                        if (startupDestination == StartupDestination.Content) {
                            startupLoadingState.complete = true
                        }
                    }
                    val showSplash = shouldShowStartupSplash(
                        enabled = startupSplashEnabled,
                        complete = startupLoadingState.complete,
                        destination = startupDestination
                    )
                    val animatedSplashAlpha by animateFloatAsState(
                        targetValue = if (showSplash) 1f else 0f,
                        animationSpec = tween(if (showSplash) 0 else 400),
                        label = "startupSplashAlpha"
                    )
                    val splashAlpha = if (showSplash) 1f else animatedSplashAlpha
                    LaunchedEffect(splashAlpha, startupDestination) {
                        if (splashAlpha == 0f && startupDestination != StartupDestination.ProfileSelection) {
                            focusedSplashColor = null
                            focusedSplashBgUrl = null
                            focusedSplashCacheKey = null
                            focusedSplashTheme = null
                        }
                    }
                    if (splashAlpha > 0f) {
                        com.nuvio.tv.ui.components.StartupSplashScreen(
                            profileColorHex = splashBackground.profileColorHex,
                            profileBackgroundUrl = splashBackground.backgroundUrl,
                            backgroundCacheKey = splashBackground.backgroundCacheKey,
                            skipGradient = splashBackground.skipGradient,
                            brandWordmarkRes = splashBackground.brandWordmarkRes,
                            modifier = Modifier.graphicsLayer { alpha = splashAlpha }
                        )
                    }
                    }
                }
                }
            }
        }

        // Developer diagnostics only: it hooks every frame and logs a long state string on each
        // slow one, which costs the most exactly when the TV is already busy.
        if (BuildConfig.IS_DEBUG_BUILD) {
            jankStats = JankStats.createAndTrack(window) { frameData ->
                if (frameData.isJank) {
                    Log.w(
                        "JankStats",
                        "JANK: ${frameData.frameDurationUiNanos / 1_000_000}ms | states: ${frameData.states}"
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::jankStats.isInitialized) jankStats.isTrackingEnabled = true
        memberAccessRepository.refreshIfStale()
        lifecycleScope.launch {
            deviceSessionRegistration.requestForegroundRegistration()
            startupSyncService.requestForegroundSync()
        }
        lifecycleScope.launch {
            val refreshIntent = if (isFirstResumeAfterCreate) {
                isFirstResumeAfterCreate = false
                TrackingRefreshIntent.INVALIDATED
            } else {
                TrackingRefreshIntent.AUTOMATIC
            }
            trackingProgressRefreshCoordinator.refreshConnected(refreshIntent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        captureDeepLinkIntent(intent)
        captureLaunchIntent(intent)
    }

    private fun captureDeepLinkIntent(intent: Intent?) {
        val url = intent?.dataString?.trim()?.takeIf(String::isNotBlank) ?: return
        pendingDeepLinkUrl.value = url
    }

    private fun captureLaunchIntent(intent: Intent?) {
        val contentId = intent?.getStringExtra("contentId") ?: return
        val launchMode = intent.getStringExtra("launchMode") ?: return
        if (launchMode != "stream") return
        pendingLaunchIntent.value = intent
    }

    override fun onPause() {
        super.onPause()
        if (::jankStats.isInitialized) jankStats.isTrackingEnabled = false
    }

    // Intercept Back at the Activity level, before any Compose BackHandler, so the auto-next loader
    // can always be dismissed. Compose back-dispatch ordering kept putting the destination screen's
    // handler above the loader's, so Back never reached it.
    // Tracks whether a long-press Back sequence is in progress. When true, all Back
    // key events are consumed at the Activity level so that repeated DOWN events from a
    // held Back key don't cascade through Compose BackHandlers (e.g. opening the sidebar
    // and then immediately exiting the app).
    val longPressBackHeld = mutableStateOf(false)

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (longPressBackHeld.value) {
                if (event.action == KeyEvent.ACTION_UP) longPressBackHeld.value = false
                return true
            }
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK &&
            externalPlaybackTracker.autoNextOverlay.value != null
        ) {
            if (event.action == KeyEvent.ACTION_UP) {
                Log.d("ExtAutoNext", "dispatchKeyEvent BACK -> dismissAutoNextOverlay (loader showing)")
                externalPlaybackTracker.dismissAutoNextOverlay()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStart() {
        // Returning from an external player: raise the auto-next loader before the player's
        // result is dispatched and before the window repaints, so the transition shows the
        // loader instantly with no episode-list flash. No-op unless a series episode is being
        // tracked; onActivityResult keeps it for a completion or dismisses it otherwise.
        externalPlaybackTracker.raiseAutoNextOverlayOnReturn()
        super.onStart()
        startupSyncService.startPeriodicSurfacePulls()
        androidTvChannelSyncService.onForegroundChanged(true)
    }

    override fun onStop() {
        externalPlaybackTracker.onExternalPlayerCoveredApp()
        super.onStop()
        startupSyncService.stopPeriodicSurfacePulls()
        // App going to background (e.g. user returning to the launcher): reconcile the
        // Continue Watching channel once so Projectivy repaints it with fresh progress.
        androidTvChannelSyncService.onForegroundChanged(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        PluginRuntimeHooks.onActivityDestroy()
    }
}

private fun navigateToDrawerRoute(
    navController: NavHostController,
    currentRoute: String?,
    targetRoute: String
) {
    if (currentRoute == targetRoute) {
        if (targetRoute == Screen.Home.route) {
            // Scroll Home to top by clearing saved focus/scroll state on the ViewModel.
            val homeEntry = try {
                navController.getBackStackEntry(Screen.Home.route)
            } catch (_: IllegalArgumentException) {
                // "home" not yet on the back stack (e.g. nav graph not fully initialized).
                return
            }
            val homeViewModel = androidx.lifecycle.ViewModelProvider(homeEntry)[com.nuvio.tv.ui.screens.home.HomeViewModel::class.java]
            homeViewModel.requestScrollToTop()
        }
        return
    }
    try {
        navController.navigate(targetRoute) {
            popUpTo(navController.graph.startDestinationId) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    } catch (e: IllegalArgumentException) {
        Log.w("NuvioNavigation", "Route not found in nav graph: $targetRoute", e)
    }
}

@Composable
internal fun DrawerItemIcon(
    iconRes: Int?,
    icon: ImageVector?,
    modifier: Modifier = Modifier,
    tint: Color = androidx.tv.material3.LocalContentColor.current,
    brush: Brush? = null
) {
    val iconModifier = if (brush == null) {
        modifier
    } else {
        modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithCache {
                onDrawWithContent {
                    drawContent()
                    drawRect(brush = brush, blendMode = BlendMode.SrcIn)
                }
            }
    }
    val iconTint = if (brush == null) tint else Color.White
    when {
        icon != null -> Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = iconModifier
        )

        iconRes != null -> Icon(
            painter = rememberRawSvgPainter(iconRes),
            contentDescription = null,
            tint = iconTint,
            modifier = iconModifier
        )
    }
}

@Composable
private fun rememberRawSvgPainter(rawIconRes: Int): Painter {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val sizePx = with(density) { NuvioTheme.spacing.xl.roundToPx() }
    return rememberAsyncImagePainter(
        model = ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
            .data(rawIconRes)
            .size(sizePx)
            .build()
    )
}

object LocaleCache {
    const val UNSET = "__UNSET__"

    @Volatile
    var localeTag: String = UNSET
}
