package com.nuvio.tv.core.apikeys

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** A service key the person brings: none is built into the app. */
enum class ApiKeyKind(internal val prefKey: String) {
    /** TMDB: built-in rows, Smart Highlights, Genres, Calendar, credits, reviews and TMDB enrichment. */
    TMDB("tmdb"),
    /** Gemini: re-ranks the personal rows and names and checks the Smart Highlights. */
    GEMINI("gemini")
}

/**
 * The TMDB and Gemini keys this device uses, entered in Settings → Integrations → API keys. Kept on the device (not
 * synced, not per profile) and read synchronously, so code that runs at startup already sees them. A key that's
 * switched off reads as blank, the same as no key, and whatever needs it stays off.
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

    /** The TMDB key to use, or blank when there's none or it's switched off. */
    val tmdb: String get() = active(ApiKeyKind.TMDB)

    /** The Gemini key to use, or blank when there's none or it's switched off. */
    val gemini: String get() = active(ApiKeyKind.GEMINI)

    fun active(kind: ApiKeyKind): String = if (isOn(kind)) stored(kind) else ""

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
