package com.nuvio.tv.core.apikeys

import android.content.Context
import android.content.SharedPreferences
import com.nuvio.tv.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** A service key the person can bring. TMDB also has one built into the app; Gemini doesn't. */
enum class ApiKeyKind(internal val prefKey: String) {
    /** TMDB: built-in rows, Smart Highlights, Genres, Calendar, credits, reviews and TMDB enrichment. */
    TMDB("tmdb"),
    /** Gemini: re-ranks the personal rows and names and checks the Smart Highlights. */
    GEMINI("gemini")
}

/**
 * The TMDB and Gemini keys this device uses, entered in Settings → Integrations → API keys. Kept on the device (not
 * synced, not per profile) and read synchronously, so code that runs at startup already sees them. A personal key
 * that's switched off reads as blank, the same as no key: TMDB then falls back to the app's built-in key, and
 * whatever needs Gemini stays off.
 */
object ApiKeys {
    private const val FILE = "api_keys"
    private const val ON_SUFFIX = "_on"

    @Volatile
    private var prefs: SharedPreferences? = null

    private val _changes = MutableStateFlow(0)
    /** Moves on every change, for screens showing the keys. */
    val changes: StateFlow<Int> = _changes.asStateFlow()

    /** Called first thing when the app starts. */
    fun init(context: Context) {
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    /** The TMDB key to use: the person's own when it's entered and on, else the built-in one (blank if none). */
    val tmdb: String get() = active(ApiKeyKind.TMDB).ifBlank { builtIn(ApiKeyKind.TMDB) }

    /** The Gemini key to use, or blank when there's none or it's switched off. */
    val gemini: String get() = active(ApiKeyKind.GEMINI)

    /** The person's own key when it's entered and on, else blank. Doesn't include the built-in key. */
    fun active(kind: ApiKeyKind): String = if (isOn(kind)) stored(kind) else ""

    /** The key built into this build of the app, or blank. Only TMDB has one. */
    fun builtIn(kind: ApiKeyKind): String = when (kind) {
        ApiKeyKind.TMDB -> BuildConfig.TMDB_API_KEY
        ApiKeyKind.GEMINI -> ""
    }

    /** The saved key, whether or not it's switched on. */
    fun stored(kind: ApiKeyKind): String = prefs?.getString(kind.prefKey, null)?.trim().orEmpty()

    fun isOn(kind: ApiKeyKind): Boolean = prefs?.getBoolean(kind.prefKey + ON_SUFFIX, true) ?: false

    /** Saves [key] and switches it on; blank removes it. */
    fun save(kind: ApiKeyKind, key: String) {
        val trimmed = key.trim()
        prefs?.edit()?.apply {
            if (trimmed.isEmpty()) remove(kind.prefKey) else putString(kind.prefKey, trimmed)
            putBoolean(kind.prefKey + ON_SUFFIX, true)
        }?.apply()
        _changes.update { it + 1 }
    }

    fun setOn(kind: ApiKeyKind, on: Boolean) {
        prefs?.edit()?.putBoolean(kind.prefKey + ON_SUFFIX, on)?.apply()
        _changes.update { it + 1 }
    }
}
