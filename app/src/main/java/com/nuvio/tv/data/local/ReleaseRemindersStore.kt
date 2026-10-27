package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** "Remind me" on one episode or movie that isn't out yet. [season]/[episode] are null for a movie. */
data class ReleaseReminder(
    val contentId: String,
    val contentType: String,
    val title: String,
    val season: Int?,
    val episode: Int?,
    val episodeTitle: String?,
    val releaseDate: LocalDate
) {
    val key: String get() = keyOf(contentId, season, episode)

    companion object {
        fun keyOf(contentId: String, season: Int?, episode: Int?): String =
            if (season == null || episode == null) "m:$contentId" else "e:$season:$episode:$contentId"
    }
}

/**
 * The details page's "Remind me", per profile: one entry per episode or movie, delivered once on Home the first
 * day it's out (see com.nuvio.tv.ui.screens.detail.ReleaseReminderHost), then removed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class ReleaseRemindersStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    val reminders: Flow<Map<String, ReleaseReminder>> = profileManager.activeProfileId.flatMapLatest(::observe)

    fun observe(profileId: Int): Flow<Map<String, ReleaseReminder>> =
        factory.get(profileId, FEATURE).data.map(::readReleaseReminders)

    /** Turns the reminder on, or off when it's already on. */
    suspend fun toggle(reminder: ReleaseReminder) {
        factory.get(profileManager.activeProfileId.value, FEATURE).edit { prefs ->
            val key = stringPreferencesKey(reminder.key)
            if (prefs.contains(key)) prefs.remove(key) else prefs[key] = encode(reminder)
        }
    }

    /** Reminders whose day has come, for the active profile. */
    suspend fun due(today: LocalDate = LocalDate.now()): List<ReleaseReminder> =
        observe(profileManager.activeProfileId.value).first().values.filter { !it.releaseDate.isAfter(today) }

    suspend fun remove(keys: Collection<String>) {
        if (keys.isEmpty()) return
        factory.get(profileManager.activeProfileId.value, FEATURE).edit { prefs ->
            keys.forEach { prefs.remove(stringPreferencesKey(it)) }
        }
    }

    internal companion object {
        const val FEATURE = "release_reminders"

        fun encode(r: ReleaseReminder): String = listOf(
            r.releaseDate.toString(), r.contentType, r.title.replace('\t', ' '), r.episodeTitle.orEmpty().replace('\t', ' ')
        ).joinToString("\t")
    }
}

internal fun readReleaseReminders(preferences: Preferences): Map<String, ReleaseReminder> {
    val out = LinkedHashMap<String, ReleaseReminder>()
    for ((key, value) in preferences.asMap()) {
        val raw = value as? String ?: continue
        val parts = raw.split('\t', limit = 4)
        val date = parts.getOrNull(0)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: continue
        val type = parts.getOrNull(1).orEmpty()
        val title = parts.getOrNull(2).orEmpty()
        val episodeTitle = parts.getOrNull(3)?.takeIf { it.isNotBlank() }
        val name = key.name
        val reminder = when {
            name.startsWith("m:") -> ReleaseReminder(name.removePrefix("m:"), type, title, null, null, null, date)
            name.startsWith("e:") -> {
                val rest = name.removePrefix("e:")
                val season = rest.substringBefore(':').toIntOrNull() ?: continue
                val afterSeason = rest.substringAfter(':')
                val episode = afterSeason.substringBefore(':').toIntOrNull() ?: continue
                val id = afterSeason.substringAfter(':', "").takeIf { it.isNotBlank() } ?: continue
                ReleaseReminder(id, type, title, season, episode, episodeTitle, date)
            }
            else -> continue
        }
        if (reminder.contentId.isNotBlank()) out[reminder.key] = reminder
    }
    return out
}
