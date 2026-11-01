package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.data.remote.supabase.AvatarCatalogItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
private data class StoredCustomAvatar(
    val id: String,
    val displayName: String,
    val imageUrl: String
)

@Serializable
private data class StoredCustomAvatarList(val items: List<StoredCustomAvatar> = emptyList())

enum class AddCustomAvatarResult { ADDED, DUPLICATE, INVALID }

/** Image URLs the primary profile added to the avatar catalog; every profile's picker offers them. */
@Singleton
class CustomAvatarDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory
) {
    companion object {
        private const val FEATURE = "custom_avatars"
        private const val PRIMARY_PROFILE_ID = 1
        private val AVATARS_KEY = stringPreferencesKey("avatars")
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }

    // Only the primary profile can open the Avatars page, so its list is the one every profile picks from.
    private fun store() = factory.get(PRIMARY_PROFILE_ID, FEATURE)

    val avatars: Flow<List<AvatarCatalogItem>> = store().data.map { prefs ->
        decode(prefs[AVATARS_KEY]).mapIndexed { index, avatar ->
            AvatarCatalogItem(
                id = avatar.id,
                displayName = avatar.displayName,
                imageUrl = avatar.imageUrl,
                category = "Custom",
                sortOrder = 10_000 + index,
                memberOnly = false
            )
        }
    }

    suspend fun add(rawUrl: String): AddCustomAvatarResult {
        val normalizedUrl = normalizeAvatarImageUrl(rawUrl) ?: return AddCustomAvatarResult.INVALID
        val stored = store()
        var result = AddCustomAvatarResult.ADDED
        stored.edit { prefs ->
            val entries = decode(prefs[AVATARS_KEY]).toMutableList()
            if (entries.any { it.imageUrl.equals(normalizedUrl, ignoreCase = true) }) {
                result = AddCustomAvatarResult.DUPLICATE
            } else {
                val uri = URI(normalizedUrl)
                val fileName = uri.path.substringAfterLast('/').substringBeforeLast('.')
                val displayName = fileName
                    .removeSuffix("-profile-avatar")
                    .replace(Regex("[-_]+"), " ")
                    .split(' ')
                    .filter(String::isNotBlank)
                    .joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
                    .ifBlank { "Custom avatar" }
                entries += StoredCustomAvatar(
                    id = "custom-${sha256(normalizedUrl)}",
                    displayName = displayName,
                    imageUrl = normalizedUrl
                )
                prefs[AVATARS_KEY] = json.encodeToString(StoredCustomAvatarList(entries.takeLast(50)))
            }
        }
        return result
    }

    suspend fun remove(id: String) {
        store().edit { prefs ->
            val entries = decode(prefs[AVATARS_KEY])
            val kept = entries.filterNot { it.id == id }
            if (kept.size != entries.size) prefs[AVATARS_KEY] = json.encodeToString(StoredCustomAvatarList(kept))
        }
    }

    private fun decode(raw: String?): List<StoredCustomAvatar> = raw?.let {
        runCatching { json.decodeFromString<StoredCustomAvatarList>(it).items }.getOrNull()
    }.orEmpty()
}

/** Accepts a direct HTTPS image URL and strips the common sentence-ending period from pasted links. */
internal fun normalizeAvatarImageUrl(rawUrl: String): String? {
    val cleaned = rawUrl.trim().trim('<', '>', '"', '\'').trimEnd('.', ',', ';', '!', ')', ']')
    val uri = runCatching { URI(cleaned) }.getOrNull() ?: return null
    if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank() || uri.userInfo != null) return null
    val extension = uri.path.orEmpty().substringAfterLast('.', "").lowercase()
    if (extension !in setOf("webp", "png", "jpg", "jpeg", "gif", "avif")) return null
    return uri.toASCIIString()
}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .take(8)
    .joinToString("") { byte -> "%02x".format(byte) }
