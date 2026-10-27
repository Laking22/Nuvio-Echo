package com.nuvio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nuvio.tv.core.streams.AudioLanguage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

private val Context.betaPerformanceSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "beta_performance_settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

/** Device-wide switches for the beta's speed-ups and new screens. All default to on. */
data class BetaPerformanceSettings(
    /** Start the source search as soon as a title is opened. */
    val prefetchOnOpen: Boolean = true,
    /** Start the source search when focus rests on a Continue Watching card or episode row. */
    val prefetchOnFocus: Boolean = true,
    /** Start the source search when a regular row's poster opens out for its trailer (the new look's Home). */
    val fetchOnExpansion: Boolean = true,
    /** Open connections to addon servers at launch so the first search skips the handshake. */
    val warmUpAddonConnections: Boolean = true,
    /** Pre-connect to the stream's server and start playback with a smaller initial buffer. */
    val fastPlaybackStart: Boolean = true,
    /** When a source fails to play, switch to the next addon's source instead of showing an error. */
    val sourceFallback: Boolean = true,
    /**
     * A quality or audio picked in the player gets this long, in seconds, to start playing
     * ([SOURCE_SWITCH_TIMEOUT_MIN]..[SOURCE_SWITCH_TIMEOUT_MAX]); then the next source with that quality or audio is tried.
     */
    val sourceSwitchTimeoutSeconds: Int = 8,
    /**
     * The quality menu offers Auto, picked at the start of each playback: the quality steps up or down on its
     * own with the connection and how smoothly the current one plays.
     */
    val autoQuality: Boolean = false,
    /**
     * How long Auto announces a change before making it, in seconds ([AUTO_QUALITY_CONFIRM_MAX] at most);
     * 0 switches at once.
     */
    val autoQualityConfirmSeconds: Int = 5,
    /** Auto only moves to qualities offered in the audio language being listened to; off, any language will do. */
    val autoQualityLanguageLoyal: Boolean = true,
    /**
     * Experimental: Auto's upgrades load the new source in the background on the same player (no second
     * decoder) and swap to it at a set point, instead of stopping to load it. Off, none of it runs.
     */
    val autoQualityHotSwap: Boolean = false,
    /** Search and Genres: the info panel's background is a mesh of the focused poster's colours (off: black). */
    val posterColourMesh: Boolean = true,
    /** New look: Home's background takes the colours of the carousel's current title, blurred (off: near black). */
    val homeAmbientBackground: Boolean = true,
    /** With [homeAmbientBackground]: the details page's background takes its title's colours too. */
    val detailsAmbientBackground: Boolean = true,
    /** New look: the hero carousel fills the screen edge to edge and fades into the page, instead of a rounded window. */
    val heroEdgeToEdge: Boolean = true,
    /** New look: focus is a white edge with a slight lift and soft shadow, instead of a ring in the theme's colour. */
    val quietFocus: Boolean = true,
    /**
     * Trailer quality: [TRAILER_QUALITY_AUTO] fetches each trailer to match what it plays on (a card, a highlight, the
     * hero, full screen); otherwise the tallest picture to fetch, one of [TRAILER_QUALITIES].
     */
    val trailerQuality: Int = TRAILER_QUALITY_AUTO,
    /** Player shows only play, next episode, subtitles, audio and quality; the rest sit behind the arrow. */
    val minimalPlayer: Boolean = true,
    /** Play the short logo intro while a stream loads; playback waits for it to finish. */
    val playbackIntro: Boolean = true,
    /** How long the intro runs, in seconds: one of [INTRO_LENGTHS]. */
    val introLengthSeconds: Int = 5,
    /** While scrubbing, show a picture of the spot above the progress bar (MKV and MP4 streams). */
    val seekThumbnails: Boolean = true,
    /** Seek thumbnails: how much one stream may download for them, in MB (one of [SEEK_PREVIEW_BUDGETS]; 0 = no limit). */
    val seekPreviewBudgetMb: Int = 800,
    /**
     * Seek thumbnails: the lowest quality another source of the same title may have to take them from (a height in
     * [SEEK_PREVIEW_QUALITIES]; 0 = off, always the playing file). The smallest files at or above it are tried first,
     * then the playing file.
     */
    val seekPreviewQuality: Int = 480,
    /** Seek thumbnails: a row of frames either side of the scrub position instead of one above it. */
    val seekPreviewFilmstrip: Boolean = true,
    /** The audio menu lists languages from every source, not just the playing file's tracks. */
    val audioLanguageMenu: Boolean = true,
    /** Languages the audio menu lists: language codes plus [AudioLanguage.ORIGINAL_KEY]. */
    val audioMenuLanguages: Set<String> = AudioLanguage.defaultMenuKeys,
    /** List sources whose audio language couldn't be read. */
    val showUnknownAudio: Boolean = true,
    /** Personal OMDb key, used only for the parental guide's age rating fallback. Blank skips OMDb. */
    val omdbApiKey: String = "",
    /**
     * New look: trailers on Home play on their own video layer (SurfaceView), so the app doesn't redraw Home on every
     * video frame. Off: the older TextureView, which fades in and can trim letterboxing. Off by default: with it on,
     * trailers once stopped showing, so it stays opt-in.
     */
    val trailerVideoLayer: Boolean = false,
    /** New look: how long a focused poster waits before it opens out, in ms (one of [POSTER_OPEN_DELAYS]). */
    val posterOpenDelayMs: Int = 750,
    /** New look: the menu stays out of sight until it's selected (Left from a row, or Up for the top bar). */
    val hideMenuUntilSelected: Boolean = false,
    /** New look: the menu is a glass tab bar at the top instead of the sidebar. */
    val menuOnTop: Boolean = false,
    /** New look: how opaque the menu's glass is, 0 (clear, as it was) to 2 (frosted): one of [MENU_GLASS_LEVELS]. */
    val menuGlass: Int = 0,
    /** New look: how notifications come in. */
    val notificationStyle: NotificationStyle = NotificationStyle.ISLAND,
    /** Because You Watched: when a title counts as viewed, in minutes or percent of its runtime. */
    val viewedThresholdUnit: ViewedThresholdUnit = ViewedThresholdUnit.MINUTES,
    val viewedThresholdMinutes: Int = 20,
    val viewedThresholdPercent: Int = 50,
    /** Catalog and collection rows shown as highlighted rows, by row key. */
    val highlightedRows: Set<String> = emptySet(),
    /**
     * Rows shown as grand rows (hero-sized cards that each play their trailer, then move on), by row key for catalogs
     * and by [com.nuvio.tv.core.home.BuiltInRow] key for built-in rows. Grand wins over highlighted.
     */
    val grandRows: Set<String> = emptySet(),
    /** Built-in highlighted rows the user turned off, by [com.nuvio.tv.core.home.BuiltInRow] key. */
    val hiddenBuiltInRows: Set<String> = emptySet(),
    /** Built-in rows shown as plain poster rows instead of highlighted, by [com.nuvio.tv.core.home.BuiltInRow] key. */
    val plainBuiltInRows: Set<String> = emptySet(),
    /**
     * Where the built-in rows sit among the catalogs: a merged list of catalog and built-in row keys, set from the
     * catalog order screen (see [com.nuvio.tv.core.home.HomeRowOrder]). Empty keeps the default places.
     */
    val homeRowOrder: List<String> = emptyList(),
    /**
     * New look: Enrichment, how many Smart Highlights (themed rows like "The Butler Did It", "Short Watches") Home
     * spreads through its rows each day: [ENRICHMENT_OFF], [ENRICHMENT_LOW] (up to 3), [ENRICHMENT_MEDIUM] (3 to 5,
     * some as grand rows) or [ENRICHMENT_HEAVY] (no limit: as many as the page has room for). See [com.nuvio.tv.core.home.smart.SmartHighlightPlanner].
     */
    val enrichment: Int = ENRICHMENT_LOW,
    /** Smart Highlights: Gemini may write a row's name; off, every row keeps its catalogue name. */
    val smartHighlightAiNames: Boolean = false,
    /**
     * Smart Highlights the user blocked: theme ids, and AI-written names as [SMART_NAME_PREFIX] + their normalised
     * form (see [com.nuvio.tv.core.home.smart.HighlightNameFilter.normalise]).
     */
    val blockedHighlights: Set<String> = emptySet(),
    /** Smart Highlights hidden for a while: "themeId@epochDay", hidden until that day. */
    val snoozedHighlights: Set<String> = emptySet(),
    /** Titles taken out of one Smart Highlight: "themeId|itemId". */
    val highlightExclusions: Set<String> = emptySet()
) {
    /** Any Smart Highlights at all (Enrichment above Off). */
    val smartHighlights: Boolean get() = enrichment > ENRICHMENT_OFF

    companion object {
        const val ENRICHMENT_OFF = 0
        const val ENRICHMENT_LOW = 1
        const val ENRICHMENT_MEDIUM = 2
        const val ENRICHMENT_HEAVY = 3

        /** The intro lengths offered, in seconds. */
        val INTRO_LENGTHS = listOf(3, 4, 5, 6)

        const val SOURCE_SWITCH_TIMEOUT_MIN = 3
        const val SOURCE_SWITCH_TIMEOUT_MAX = 15

        const val AUTO_QUALITY_CONFIRM_MAX = 10

        /** Marks a blocked AI-written name in [blockedHighlights], as opposed to a theme id. */
        const val SMART_NAME_PREFIX = "name:"

        /** Poster expand delays offered, in ms. */
        val MENU_GLASS_LEVELS = listOf(0, 1, 2)
        const val TRAILER_QUALITY_AUTO = com.nuvio.tv.core.player.TrailerVideoPolicy.AUTO
        val TRAILER_QUALITIES = com.nuvio.tv.core.player.TrailerVideoPolicy.QUALITIES
        val POSTER_OPEN_DELAYS = listOf(0, 250, 500, 750, 1000, 1500, 2000, 3000, 4000, 5000)

        /** Seek preview data limits offered, in MB; 0 is no limit. */
        val SEEK_PREVIEW_BUDGETS = listOf(200, 400, 800, 1600, 3200, 0)

        /** Preview qualities offered, as heights; 0 is off. */
        val SEEK_PREVIEW_QUALITIES = listOf(0, 360, 480, 720, 1080, 2160)
    }
}

@Singleton
class BetaPerformanceSettingsDataStore @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val dataStore = context.betaPerformanceSettingsDataStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val prefetchOnOpenKey = booleanPreferencesKey("prefetch_on_open")
    private val prefetchOnFocusKey = booleanPreferencesKey("prefetch_on_focus")
    private val fetchOnExpansionKey = booleanPreferencesKey("fetch_on_expansion")
    private val warmUpAddonConnectionsKey = booleanPreferencesKey("warm_up_addon_connections")
    private val fastPlaybackStartKey = booleanPreferencesKey("fast_playback_start")
    private val sourceFallbackKey = booleanPreferencesKey("source_fallback")
    private val sourceSwitchTimeoutKey = intPreferencesKey("source_switch_timeout_seconds")
    private val autoQualityKey = booleanPreferencesKey("auto_quality")
    private val autoQualityConfirmKey = intPreferencesKey("auto_quality_confirm_seconds")
    private val autoQualityLanguageLoyalKey = booleanPreferencesKey("auto_quality_language_loyal")
    private val autoQualityHotSwapKey = booleanPreferencesKey("auto_quality_hot_swap")
    private val posterColourMeshKey = booleanPreferencesKey("poster_colour_mesh")
    private val homeAmbientBackgroundKey = booleanPreferencesKey("home_ambient_background")
    private val detailsAmbientBackgroundKey = booleanPreferencesKey("details_ambient_background")
    private val heroEdgeToEdgeKey = booleanPreferencesKey("hero_edge_to_edge")
    private val quietFocusKey = booleanPreferencesKey("quiet_focus")
    private val trailerQualityKey = intPreferencesKey("trailer_quality")
    private val minimalPlayerKey = booleanPreferencesKey("minimal_player")
    private val playbackIntroKey = booleanPreferencesKey("playback_intro")
    private val introLengthKey = intPreferencesKey("intro_length_seconds")
    private val seekThumbnailsKey = booleanPreferencesKey("seek_thumbnails")
    private val seekPreviewBudgetKey = intPreferencesKey("seek_preview_budget_mb")
    private val seekPreviewQualityKey = intPreferencesKey("seek_preview_quality")
    private val seekPreviewFilmstripKey = booleanPreferencesKey("seek_preview_filmstrip")
    private val audioLanguageMenuKey = booleanPreferencesKey("audio_language_menu")
    private val audioMenuLanguagesKey = stringSetPreferencesKey("audio_menu_languages")
    private val showUnknownAudioKey = booleanPreferencesKey("show_unknown_audio")
    private val omdbApiKeyKey = stringPreferencesKey("omdb_api_key")
    private val trailerVideoLayerKey = booleanPreferencesKey("new_look_trailer_video_layer_v2") // v2: everyone back on the default (off)
    private val posterOpenDelayKey = intPreferencesKey("new_look_poster_open_delay_ms")
    private val hideMenuKey = booleanPreferencesKey("new_look_hide_menu")
    private val menuOnTopKey = booleanPreferencesKey("new_look_menu_on_top")
    private val menuGlassKey = intPreferencesKey("new_look_menu_glass")
    private val notificationStyleKey = stringPreferencesKey("notification_style")
    private val viewedUnitKey = stringPreferencesKey("viewed_threshold_unit")
    private val viewedMinutesKey = intPreferencesKey("viewed_threshold_minutes")
    private val viewedPercentKey = intPreferencesKey("viewed_threshold_percent")
    private val highlightedRowsKey = stringSetPreferencesKey("highlighted_rows")
    private val hiddenBuiltInRowsKey = stringSetPreferencesKey("hidden_built_in_rows")
    private val grandRowsKey = stringSetPreferencesKey("grand_rows")
    private val plainBuiltInRowsKey = stringSetPreferencesKey("plain_built_in_rows")
    private val homeRowOrderKey = stringPreferencesKey("home_row_order")
    /** Before Enrichment: Smart Highlights on or off (off reads as Enrichment Off). */
    private val smartHighlightsKey = booleanPreferencesKey("smart_highlights")
    private val enrichmentKey = intPreferencesKey("home_enrichment")
    private val smartHighlightAiNamesKey = booleanPreferencesKey("smart_highlight_ai_names")
    private val blockedHighlightsKey = stringSetPreferencesKey("smart_highlights_blocked")
    private val snoozedHighlightsKey = stringSetPreferencesKey("smart_highlights_snoozed")
    private val highlightExclusionsKey = stringSetPreferencesKey("smart_highlights_excluded")

    val settings: Flow<BetaPerformanceSettings> = dataStore.data.map { prefs ->
        val defaults = BetaPerformanceSettings()
        BetaPerformanceSettings(
            prefetchOnOpen = prefs[prefetchOnOpenKey] ?: defaults.prefetchOnOpen,
            prefetchOnFocus = prefs[prefetchOnFocusKey] ?: defaults.prefetchOnFocus,
            fetchOnExpansion = prefs[fetchOnExpansionKey] ?: defaults.fetchOnExpansion,
            warmUpAddonConnections = prefs[warmUpAddonConnectionsKey] ?: defaults.warmUpAddonConnections,
            fastPlaybackStart = prefs[fastPlaybackStartKey] ?: defaults.fastPlaybackStart,
            sourceFallback = prefs[sourceFallbackKey] ?: defaults.sourceFallback,
            sourceSwitchTimeoutSeconds = prefs[sourceSwitchTimeoutKey]?.coerceIn(
                BetaPerformanceSettings.SOURCE_SWITCH_TIMEOUT_MIN,
                BetaPerformanceSettings.SOURCE_SWITCH_TIMEOUT_MAX
            ) ?: defaults.sourceSwitchTimeoutSeconds,
            autoQuality = prefs[autoQualityKey] ?: defaults.autoQuality,
            autoQualityConfirmSeconds = prefs[autoQualityConfirmKey]
                ?.coerceIn(0, BetaPerformanceSettings.AUTO_QUALITY_CONFIRM_MAX) ?: defaults.autoQualityConfirmSeconds,
            autoQualityLanguageLoyal = prefs[autoQualityLanguageLoyalKey] ?: defaults.autoQualityLanguageLoyal,
            autoQualityHotSwap = prefs[autoQualityHotSwapKey] ?: defaults.autoQualityHotSwap,
            posterColourMesh = prefs[posterColourMeshKey] ?: defaults.posterColourMesh,
            homeAmbientBackground = prefs[homeAmbientBackgroundKey] ?: defaults.homeAmbientBackground,
            detailsAmbientBackground = prefs[detailsAmbientBackgroundKey] ?: defaults.detailsAmbientBackground,
            heroEdgeToEdge = prefs[heroEdgeToEdgeKey] ?: defaults.heroEdgeToEdge,
            quietFocus = prefs[quietFocusKey] ?: defaults.quietFocus,
            trailerQuality = prefs[trailerQualityKey]?.takeIf { it in BetaPerformanceSettings.TRAILER_QUALITIES }
                ?: defaults.trailerQuality,
            minimalPlayer = prefs[minimalPlayerKey] ?: defaults.minimalPlayer,
            playbackIntro = prefs[playbackIntroKey] ?: defaults.playbackIntro,
            introLengthSeconds = prefs[introLengthKey]
                ?.takeIf { it in BetaPerformanceSettings.INTRO_LENGTHS } ?: defaults.introLengthSeconds,
            seekThumbnails = prefs[seekThumbnailsKey] ?: defaults.seekThumbnails,
            seekPreviewBudgetMb = prefs[seekPreviewBudgetKey]?.takeIf { it in BetaPerformanceSettings.SEEK_PREVIEW_BUDGETS }
                ?: defaults.seekPreviewBudgetMb,
            seekPreviewQuality = prefs[seekPreviewQualityKey]?.takeIf { it in BetaPerformanceSettings.SEEK_PREVIEW_QUALITIES }
                ?: defaults.seekPreviewQuality,
            seekPreviewFilmstrip = prefs[seekPreviewFilmstripKey] ?: defaults.seekPreviewFilmstrip,
            audioLanguageMenu = prefs[audioLanguageMenuKey] ?: defaults.audioLanguageMenu,
            audioMenuLanguages = prefs[audioMenuLanguagesKey] ?: defaults.audioMenuLanguages,
            showUnknownAudio = prefs[showUnknownAudioKey] ?: defaults.showUnknownAudio,
            omdbApiKey = prefs[omdbApiKeyKey] ?: defaults.omdbApiKey,
            trailerVideoLayer = prefs[trailerVideoLayerKey] ?: defaults.trailerVideoLayer,
            posterOpenDelayMs = prefs[posterOpenDelayKey]?.takeIf { it in BetaPerformanceSettings.POSTER_OPEN_DELAYS }
                ?: defaults.posterOpenDelayMs,
            hideMenuUntilSelected = prefs[hideMenuKey] ?: defaults.hideMenuUntilSelected,
            menuOnTop = prefs[menuOnTopKey] ?: defaults.menuOnTop,
            menuGlass = prefs[menuGlassKey]?.takeIf { it in BetaPerformanceSettings.MENU_GLASS_LEVELS } ?: defaults.menuGlass,
            notificationStyle = NotificationStyle.fromKey(prefs[notificationStyleKey]),
            viewedThresholdUnit = ViewedThresholdUnit.fromKey(prefs[viewedUnitKey]),
            viewedThresholdMinutes = prefs[viewedMinutesKey]?.coerceIn(1, 120) ?: defaults.viewedThresholdMinutes,
            viewedThresholdPercent = prefs[viewedPercentKey]?.coerceIn(5, 100) ?: defaults.viewedThresholdPercent,
            highlightedRows = prefs[highlightedRowsKey] ?: defaults.highlightedRows,
            hiddenBuiltInRows = prefs[hiddenBuiltInRowsKey] ?: defaults.hiddenBuiltInRows,
            grandRows = prefs[grandRowsKey] ?: defaults.grandRows,
            plainBuiltInRows = prefs[plainBuiltInRowsKey] ?: defaults.plainBuiltInRows,
            homeRowOrder = com.nuvio.tv.core.home.HomeRowOrder.decode(prefs[homeRowOrderKey]),
            enrichment = prefs[enrichmentKey]?.coerceIn(BetaPerformanceSettings.ENRICHMENT_OFF, BetaPerformanceSettings.ENRICHMENT_HEAVY)
                ?: if (prefs[smartHighlightsKey] == false) BetaPerformanceSettings.ENRICHMENT_OFF else defaults.enrichment,
            smartHighlightAiNames = prefs[smartHighlightAiNamesKey] ?: defaults.smartHighlightAiNames,
            blockedHighlights = prefs[blockedHighlightsKey] ?: defaults.blockedHighlights,
            snoozedHighlights = prefs[snoozedHighlightsKey] ?: defaults.snoozedHighlights,
            highlightExclusions = prefs[highlightExclusionsKey] ?: defaults.highlightExclusions
        )
    }

    /**
     * Latest settings for hot paths (focus callbacks, per-addon requests) that can't suspend.
     * Starts from the defaults until the file has been read once.
     */
    val current: StateFlow<BetaPerformanceSettings> =
        settings.stateIn(scope, SharingStarted.Eagerly, BetaPerformanceSettings())

    /** Returns once [current] holds what's in the file rather than the defaults it starts from. */
    suspend fun awaitLoaded() {
        val fromFile = settings.first()
        current.first { it == fromFile }
    }

    suspend fun setPrefetchOnOpen(enabled: Boolean) = set(prefetchOnOpenKey, enabled)
    suspend fun setPrefetchOnFocus(enabled: Boolean) = set(prefetchOnFocusKey, enabled)
    suspend fun setFetchOnExpansion(enabled: Boolean) = set(fetchOnExpansionKey, enabled)
    suspend fun setWarmUpAddonConnections(enabled: Boolean) = set(warmUpAddonConnectionsKey, enabled)
    suspend fun setFastPlaybackStart(enabled: Boolean) = set(fastPlaybackStartKey, enabled)
    suspend fun setSourceFallback(enabled: Boolean) = set(sourceFallbackKey, enabled)

    suspend fun setAutoQuality(enabled: Boolean) = set(autoQualityKey, enabled)

    suspend fun setAutoQualityLanguageLoyal(enabled: Boolean) = set(autoQualityLanguageLoyalKey, enabled)

    suspend fun setAutoQualityHotSwap(enabled: Boolean) = set(autoQualityHotSwapKey, enabled)

    suspend fun setAutoQualityConfirm(seconds: Int) {
        dataStore.edit { prefs ->
            prefs[autoQualityConfirmKey] = seconds.coerceIn(0, BetaPerformanceSettings.AUTO_QUALITY_CONFIRM_MAX)
        }
    }

    suspend fun setSourceSwitchTimeout(seconds: Int) {
        dataStore.edit { prefs ->
            prefs[sourceSwitchTimeoutKey] = seconds.coerceIn(
                BetaPerformanceSettings.SOURCE_SWITCH_TIMEOUT_MIN,
                BetaPerformanceSettings.SOURCE_SWITCH_TIMEOUT_MAX
            )
        }
    }
    suspend fun setPosterColourMesh(enabled: Boolean) = set(posterColourMeshKey, enabled)
    suspend fun setHomeAmbientBackground(enabled: Boolean) = set(homeAmbientBackgroundKey, enabled)
    suspend fun setDetailsAmbientBackground(enabled: Boolean) = set(detailsAmbientBackgroundKey, enabled)
    suspend fun setHeroEdgeToEdge(enabled: Boolean) = set(heroEdgeToEdgeKey, enabled)
    suspend fun setQuietFocus(enabled: Boolean) = set(quietFocusKey, enabled)
    suspend fun setTrailerQuality(height: Int) {
        if (height !in BetaPerformanceSettings.TRAILER_QUALITIES) return
        dataStore.edit { prefs -> prefs[trailerQualityKey] = height }
    }
    suspend fun setTrailerVideoLayer(enabled: Boolean) = set(trailerVideoLayerKey, enabled)
    suspend fun setMinimalPlayer(enabled: Boolean) = set(minimalPlayerKey, enabled)
    suspend fun setPlaybackIntro(enabled: Boolean) = set(playbackIntroKey, enabled)
    suspend fun setSeekThumbnails(enabled: Boolean) = set(seekThumbnailsKey, enabled)
    suspend fun setSeekPreviewBudget(mb: Int) {
        if (mb !in BetaPerformanceSettings.SEEK_PREVIEW_BUDGETS) return
        dataStore.edit { prefs -> prefs[seekPreviewBudgetKey] = mb }
    }
    suspend fun setSeekPreviewFilmstrip(enabled: Boolean) = set(seekPreviewFilmstripKey, enabled)
    suspend fun setSeekPreviewQuality(height: Int) {
        if (height !in BetaPerformanceSettings.SEEK_PREVIEW_QUALITIES) return
        dataStore.edit { prefs -> prefs[seekPreviewQualityKey] = height }
    }
    suspend fun setIntroLength(seconds: Int) {
        if (seconds !in BetaPerformanceSettings.INTRO_LENGTHS) return
        dataStore.edit { prefs -> prefs[introLengthKey] = seconds }
    }
    suspend fun setAudioLanguageMenu(enabled: Boolean) = set(audioLanguageMenuKey, enabled)
    suspend fun setShowUnknownAudio(enabled: Boolean) = set(showUnknownAudioKey, enabled)
    suspend fun setAudioMenuLanguage(key: String, shown: Boolean) {
        dataStore.edit { prefs ->
            val current = prefs[audioMenuLanguagesKey] ?: AudioLanguage.defaultMenuKeys
            prefs[audioMenuLanguagesKey] = if (shown) current + key else current - key
        }
    }
    suspend fun setOmdbApiKey(key: String) {
        dataStore.edit { prefs -> prefs[omdbApiKeyKey] = key.trim() }
    }

    suspend fun setPosterOpenDelay(ms: Int) {
        if (ms !in BetaPerformanceSettings.POSTER_OPEN_DELAYS) return
        dataStore.edit { prefs -> prefs[posterOpenDelayKey] = ms }
    }
    suspend fun setHideMenuUntilSelected(enabled: Boolean) = set(hideMenuKey, enabled)
    suspend fun setMenuOnTop(enabled: Boolean) = set(menuOnTopKey, enabled)
    suspend fun setMenuGlass(level: Int) {
        if (level !in BetaPerformanceSettings.MENU_GLASS_LEVELS) return
        dataStore.edit { prefs -> prefs[menuGlassKey] = level }
    }
    suspend fun setNotificationStyle(style: NotificationStyle) {
        dataStore.edit { prefs -> prefs[notificationStyleKey] = style.key }
    }
    suspend fun setViewedThresholdUnit(unit: ViewedThresholdUnit) {
        dataStore.edit { prefs -> prefs[viewedUnitKey] = unit.key }
    }
    suspend fun setViewedThresholdMinutes(minutes: Int) {
        dataStore.edit { prefs -> prefs[viewedMinutesKey] = minutes.coerceIn(1, 120) }
    }
    suspend fun setViewedThresholdPercent(percent: Int) {
        dataStore.edit { prefs -> prefs[viewedPercentKey] = percent.coerceIn(5, 100) }
    }
    suspend fun setRowHighlighted(rowKey: String, highlighted: Boolean) {
        dataStore.edit { prefs ->
            val current = prefs[highlightedRowsKey] ?: emptySet()
            prefs[highlightedRowsKey] = if (highlighted) current + rowKey else current - rowKey
        }
    }
    /** [key]: a catalog row key, or a built-in row's own key. */
    suspend fun setRowGrand(key: String, grand: Boolean) {
        dataStore.edit { prefs ->
            val current = prefs[grandRowsKey] ?: emptySet()
            prefs[grandRowsKey] = if (grand) current + key else current - key
        }
    }
    suspend fun setBuiltInRowShown(rowKey: String, shown: Boolean) {
        dataStore.edit { prefs ->
            val current = prefs[hiddenBuiltInRowsKey] ?: emptySet()
            prefs[hiddenBuiltInRowsKey] = if (shown) current - rowKey else current + rowKey
        }
    }

    suspend fun setBuiltInRowHighlighted(rowKey: String, highlighted: Boolean) {
        dataStore.edit { prefs ->
            val current = prefs[plainBuiltInRowsKey] ?: emptySet()
            prefs[plainBuiltInRowsKey] = if (highlighted) current - rowKey else current + rowKey
        }
    }
    suspend fun setHomeRowOrder(order: List<String>) {
        dataStore.edit { prefs -> prefs[homeRowOrderKey] = com.nuvio.tv.core.home.HomeRowOrder.encode(order) }
    }

    suspend fun setEnrichment(level: Int) {
        dataStore.edit { prefs ->
            prefs[enrichmentKey] = level.coerceIn(BetaPerformanceSettings.ENRICHMENT_OFF, BetaPerformanceSettings.ENRICHMENT_HEAVY)
        }
    }
    suspend fun setSmartHighlightAiNames(enabled: Boolean) = set(smartHighlightAiNamesKey, enabled)

    /** Never show this Smart Highlight (a theme id, or [BetaPerformanceSettings.SMART_NAME_PREFIX] + a name). */
    suspend fun blockHighlight(entry: String) = addTo(blockedHighlightsKey, entry)

    /** Hide a Smart Highlight until [untilEpochDay]; an older snooze of the same theme is replaced. */
    suspend fun snoozeHighlight(themeId: String, untilEpochDay: Long) {
        dataStore.edit { prefs ->
            val kept = (prefs[snoozedHighlightsKey] ?: emptySet()).filterNot { it.substringBefore('@') == themeId }
            prefs[snoozedHighlightsKey] = kept.toSet() + "$themeId@$untilEpochDay"
        }
    }

    /** Take one title out of one Smart Highlight. */
    suspend fun excludeFromHighlight(themeId: String, itemId: String) = addTo(highlightExclusionsKey, "$themeId|$itemId")

    /** Bring back every blocked, hidden or trimmed Smart Highlight. */
    suspend fun resetBlockedHighlights() {
        dataStore.edit { prefs ->
            prefs.remove(blockedHighlightsKey)
            prefs.remove(snoozedHighlightsKey)
            prefs.remove(highlightExclusionsKey)
        }
    }

    private suspend fun addTo(key: Preferences.Key<Set<String>>, entry: String) {
        dataStore.edit { prefs -> prefs[key] = (prefs[key] ?: emptySet()) + entry }
    }

    private suspend fun set(key: Preferences.Key<Boolean>, enabled: Boolean) {
        dataStore.edit { prefs -> prefs[key] = enabled }
    }
}
