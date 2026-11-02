package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.data.remote.supabase.AvatarCatalogItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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

/** What adding avatar links did: how many were added, already saved, or not usable image links. */
data class AddAvatarsReport(val added: Int, val duplicate: Int, val invalid: Int)

@Serializable
private data class AvatarPack(val name: String? = null, val avatars: List<AvatarPackItem> = emptyList())

@Serializable
private data class AvatarPackItem(val name: String? = null, val url: String? = null, val imageUrl: String? = null)

private val packJson = Json { ignoreUnknownKeys = true; encodeDefaults = false; prettyPrint = true }

/** Links in pasted text: separated by commas, spaces or new lines. */
internal fun splitAvatarLinks(raw: String): List<Pair<String?, String>> =
    raw.split(',', '\n', ' ', '\t', ';').map(String::trim).filter(String::isNotEmpty).map { null to it }

/**
 * A pack's avatars as (name, link): Echo's own JSON ({"avatars": [{"name", "url"}]}), a JSON list of links or of
 * objects with "url"/"imageUrl", or plain text with links separated by commas, spaces or new lines.
 */
internal fun parseAvatarPack(text: String): List<Pair<String?, String>> {
    val trimmed = text.trim()
    if (trimmed.startsWith("{")) {
        runCatching { packJson.decodeFromString<AvatarPack>(trimmed) }.getOrNull()?.let { pack ->
            return pack.avatars.mapNotNull { item -> (item.url ?: item.imageUrl)?.let { item.name to it } }
        }
    }
    if (trimmed.startsWith("[")) {
        runCatching { packJson.decodeFromString<List<String>>(trimmed) }.getOrNull()?.let { list ->
            return list.map { null to it }
        }
        runCatching { packJson.decodeFromString<List<AvatarPackItem>>(trimmed) }.getOrNull()?.let { list ->
            return list.mapNotNull { item -> (item.url ?: item.imageUrl)?.let { item.name to it } }
        }
    }
    return splitAvatarLinks(trimmed)
}

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
        private const val MAX_AVATARS = 100
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

    /**
     * Adds every image link in [raw] (one, or several separated by commas, spaces or new lines), skipping links already
     * saved and ones that aren't direct HTTPS images.
     */
    suspend fun addAll(raw: String): AddAvatarsReport = addLinks(splitAvatarLinks(raw))

    suspend fun addLinks(links: List<Pair<String?, String>>): AddAvatarsReport {
        var added = 0
        var duplicate = 0
        var invalid = 0
        store().edit { prefs ->
            val entries = decode(prefs[AVATARS_KEY]).toMutableList()
            for ((name, rawUrl) in links) {
                val url = normalizeAvatarImageUrl(rawUrl)
                when {
                    url == null -> invalid++
                    entries.any { it.imageUrl.equals(url, ignoreCase = true) } -> duplicate++
                    else -> {
                        entries += StoredCustomAvatar(
                            id = "custom-${sha256(url)}",
                            displayName = name?.trim()?.takeIf(String::isNotBlank)?.take(60) ?: nameFromUrl(url),
                            imageUrl = url
                        )
                        added++
                    }
                }
            }
            if (added > 0) prefs[AVATARS_KEY] = json.encodeToString(StoredCustomAvatarList(entries.takeLast(MAX_AVATARS)))
        }
        return AddAvatarsReport(added, duplicate, invalid)
    }

    /** The saved avatars as a pack (JSON) another device or person can import ([parseAvatarPack]). */
    suspend fun exportPack(): String {
        val entries = decode(store().data.first()[AVATARS_KEY])
        return packJson.encodeToString(
            AvatarPack(name = "Nuvio Echo avatars", avatars = entries.map { AvatarPackItem(it.displayName, it.imageUrl) })
        )
    }

    suspend fun links(): List<String> = decode(store().data.first()[AVATARS_KEY]).map { it.imageUrl }

    private fun nameFromUrl(url: String): String = URI(url).path.substringAfterLast('/').substringBeforeLast('.')
        .removeSuffix("-profile-avatar")
        .replace(Regex("[-_]+"), " ")
        .split(' ')
        .filter(String::isNotBlank)
        .joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
        .ifBlank { "Custom avatar" }

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
