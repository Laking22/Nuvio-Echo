package com.nuvio.tv.ui.screens.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.nuvio.tv.data.local.AddAvatarsReport
import com.nuvio.tv.data.local.CustomAvatarDataStore
import com.nuvio.tv.data.remote.supabase.AvatarCatalogItem
import com.nuvio.tv.data.remote.supabase.AvatarRepository
import com.nuvio.tv.data.repository.MemberAccessRepository
import com.nuvio.tv.domain.model.CosmeticEntitlement
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AvatarSettingsUiState(
    val catalog: List<AvatarCatalogItem> = emptyList(),
    val custom: List<AvatarCatalogItem> = emptyList(),
    val loading: Boolean = true
)

@HiltViewModel
class AvatarSettingsViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val avatarRepository: AvatarRepository,
    private val customAvatarDataStore: CustomAvatarDataStore,
    memberAccessRepository: MemberAccessRepository
) : ViewModel() {
    private val customAvatars = customAvatarDataStore.avatars
    private val catalog = MutableStateFlow<List<AvatarCatalogItem>>(emptyList())
    private val loading = MutableStateFlow(true)

    val uiState = combine(catalog, customAvatars, loading) { defaults, custom, isLoading ->
        AvatarSettingsUiState(catalog = defaults, custom = custom, loading = isLoading)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AvatarSettingsUiState())

    init {
        viewModelScope.launch {
            memberAccessRepository.access
                .map { access -> access.entitlements.includes(CosmeticEntitlement.PROFILE_AVATARS) }
                .distinctUntilChanged()
                .collect { hasMemberAccess ->
                    loading.value = true
                    try {
                        catalog.value = avatarRepository.getAvatarCatalog(hasMemberAccess)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Log.w("AvatarSettingsVM", "Could not load the avatar catalog", error)
                    } finally {
                        loading.value = false
                    }
                }
        }
    }

    fun removeAvatar(id: String) {
        viewModelScope.launch { customAvatarDataStore.remove(id) }
    }

    /** Adds the link, or every link when several are pasted separated by commas. */
    fun addAvatars(raw: String, onResult: (AddAvatarsReport) -> Unit) {
        viewModelScope.launch { onResult(customAvatarDataStore.addAll(raw)) }
    }

    /**
     * Imports a pack: [input] is a link to one (Echo's JSON, a JSON list or a text list of links, fetched over HTTPS)
     * or the pack's links pasted straight in. Null when the link couldn't be read.
     */
    fun importPack(input: String, onResult: (AddAvatarsReport?) -> Unit) {
        viewModelScope.launch {
            val text = input.trim()
            val links = if (isPackLink(text)) {
                fetchText(text)?.let { com.nuvio.tv.data.local.parseAvatarPack(it) }
            } else {
                com.nuvio.tv.data.local.parseAvatarPack(text)
            }
            onResult(links?.let { customAvatarDataStore.addLinks(it) })
        }
    }

    /** Imports the newest avatar pack in Downloads (one exported from Echo); null when there's none to read. */
    fun importFromDownloads(onResult: (AddAvatarsReport?) -> Unit) {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) { runCatching { readNewestPack() }.getOrNull() }
            onResult(text?.let { customAvatarDataStore.addLinks(com.nuvio.tv.data.local.parseAvatarPack(it)) })
        }
    }

    /** Writes the saved avatars to a pack in Downloads; gives where it went (null if it couldn't) and the links. */
    fun exportPack(onResult: (location: String?, links: List<String>) -> Unit) {
        viewModelScope.launch {
            val pack = customAvatarDataStore.exportPack()
            val location = withContext(Dispatchers.IO) { runCatching { writePack(pack) }.getOrNull() }
            onResult(location, customAvatarDataStore.links())
        }
    }

    /** A single link that isn't itself an image: a pack to fetch. */
    private fun isPackLink(text: String): Boolean {
        if (text.any { it == ',' || it.isWhitespace() }) return false
        if (!text.startsWith("https://", ignoreCase = true)) return false
        return com.nuvio.tv.data.local.normalizeAvatarImageUrl(text) == null
    }

    /** The pack at [url]: HTTPS only, at most [MAX_PACK_BYTES], so a wrong link can't pull down something huge. */
    private suspend fun fetchText(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val connection = java.net.URL(url).openConnection() as javax.net.ssl.HttpsURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            try {
                if (connection.responseCode !in 200..299) return@runCatching null
                val bytes = connection.inputStream.use { input ->
                    val buffer = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(8 * 1024)
                    while (true) {
                        val read = input.read(chunk)
                        if (read < 0) break
                        buffer.write(chunk, 0, read)
                        if (buffer.size() > MAX_PACK_BYTES) return@runCatching null
                    }
                    buffer.toByteArray()
                }
                bytes.toString(Charsets.UTF_8)
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }

    private fun writePack(json: String): String? {
        val name = "nuvio-avatars-${java.time.LocalDate.now()}.json"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            resolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) } ?: return null
            return "${Environment.DIRECTORY_DOWNLOADS}/$name"
        }
        // Before Android 10 public Downloads needs a storage permission; the app's own folder doesn't.
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return null
        val file = java.io.File(dir, name)
        file.writeText(json)
        return file.absolutePath
    }

    private fun readNewestPack(): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.DISPLAY_NAME} LIKE ?",
                arrayOf("nuvio-avatars%"),
                "${MediaStore.Downloads.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return null
                val uri = android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(0))
                return resolver.openInputStream(uri)?.use { input ->
                    input.readBytes().takeIf { it.size <= MAX_PACK_BYTES }?.toString(Charsets.UTF_8)
                }
            }
            return null
        }
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return null
        return dir.listFiles { file -> file.name.startsWith("nuvio-avatars") }
            ?.maxByOrNull { it.lastModified() }
            ?.takeIf { it.length() <= MAX_PACK_BYTES }
            ?.readText()
    }

    private companion object {
        const val MAX_PACK_BYTES = 256 * 1024
    }
}
