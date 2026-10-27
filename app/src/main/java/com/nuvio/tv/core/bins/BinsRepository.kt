package com.nuvio.tv.core.bins

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.R
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.LOCAL_LIBRARY_LIST_KEY
import com.nuvio.tv.data.local.LibraryPreferences
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.domain.model.LibraryEntryInput
import com.nuvio.tv.domain.model.ListMembershipChanges
import com.nuvio.tv.domain.model.SavedLibraryItem
import com.nuvio.tv.domain.repository.LibraryRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

sealed interface BinNameResult {
    data class Ok(val binId: String) : BinNameResult
    data object Blank : BinNameResult
    data object Duplicate : BinNameResult
}

interface BinsRepository {
    /** The active profile's bins. */
    val state: Flow<BinsState>

    suspend fun createBin(name: String): BinNameResult
    suspend fun renameBin(binId: String, name: String): BinNameResult
    suspend fun deleteBin(binId: String)
    suspend fun toggleStar(binId: String)
    suspend fun setSort(sort: BinSort)

    /** Turns on custom order, starting from the bins in the order they're shown. */
    suspend fun useDisplayedBinOrder(orderedBinIds: List<String>)
    suspend fun swapBins(a: String, b: String)
    suspend fun useDisplayedEntryOrder(binId: String, orderedKeys: List<String>)
    suspend fun swapEntries(binId: String, a: String, b: String)

    suspend fun markBinViewed(binId: String)
    suspend fun markEntryViewed(binId: String, key: String)

    suspend fun add(binId: String, item: LibraryEntryInput)
    suspend fun remove(binId: String, itemId: String, itemType: String)
}

/**
 * Bins per profile, stored as one JSON document. Every title in a bin is also in the saved library, and every saved
 * title is in a bin (see [BinsReducer.reconcile]), so the rest of the app keeps reading the library as before. Both
 * are changed under one lock, and the reconcile pass takes it too, so it never sees one written without the other.
 */
@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class BinsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager,
    private val libraryPreferences: LibraryPreferences,
    private val libraryRepository: LibraryRepository
) : BinsRepository {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val stateKey = stringPreferencesKey("bins_state")
    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            libraryPreferences.libraryItems.collectLatest {
                // Let a burst of library writes (a sync pull) settle first.
                delay(300)
                runCatching { reconcile(profileManager.activeProfileId.value) }
                    .onFailure { Log.w(TAG, "Couldn't reconcile bins with the library", it) }
            }
        }
    }

    override val state: Flow<BinsState> = profileManager.activeProfileId
        .flatMapLatest { profileId -> factory.get(profileId, FEATURE).data.map { decode(it[stateKey]) } }
        .distinctUntilChanged()

    override suspend fun createBin(name: String): BinNameResult = lock.withLock {
        val clean = cleanBinName(name)
        val profileId = profileManager.activeProfileId.value
        val current = read(profileId)
        when {
            clean.isEmpty() -> BinNameResult.Blank
            current.bins.any { it.name.equals(clean, ignoreCase = true) } -> BinNameResult.Duplicate
            else -> {
                val id = UUID.randomUUID().toString()
                write(profileId) { BinsReducer.create(it, id, clean, now()) }
                BinNameResult.Ok(id)
            }
        }
    }

    override suspend fun renameBin(binId: String, name: String): BinNameResult = lock.withLock {
        val clean = cleanBinName(name)
        val profileId = profileManager.activeProfileId.value
        val current = read(profileId)
        when {
            clean.isEmpty() -> BinNameResult.Blank
            current.bins.any { it.id != binId && it.name.equals(clean, ignoreCase = true) } -> BinNameResult.Duplicate
            else -> {
                write(profileId) { BinsReducer.rename(it, binId, clean) }
                BinNameResult.Ok(binId)
            }
        }
    }

    override suspend fun deleteBin(binId: String) = lock.withLock {
        val profileId = profileManager.activeProfileId.value
        val before = read(profileId)
        val bin = before.bins.firstOrNull { it.id == binId } ?: return@withLock
        val after = write(profileId) { BinsReducer.delete(it, binId) }
        // Titles that were only in this bin leave the library with it.
        bin.entries.filterNot { entry -> after.bins.any { it.contains(entry.key) } }
            .forEach { setInLibrary(it.toInput(), false) }
    }

    override suspend fun toggleStar(binId: String) = edit { BinsReducer.toggleStar(it, binId) }

    override suspend fun setSort(sort: BinSort) = edit { BinsReducer.setSort(it, sort) }

    override suspend fun useDisplayedBinOrder(orderedBinIds: List<String>) =
        edit { BinsReducer.useDisplayedBinOrder(it, orderedBinIds) }

    override suspend fun swapBins(a: String, b: String) = edit { BinsReducer.swapBins(it, a, b) }

    override suspend fun useDisplayedEntryOrder(binId: String, orderedKeys: List<String>) =
        edit { BinsReducer.useDisplayedEntryOrder(it, binId, orderedKeys) }

    override suspend fun swapEntries(binId: String, a: String, b: String) =
        edit { BinsReducer.swapEntries(it, binId, a, b) }

    override suspend fun markBinViewed(binId: String) = edit { BinsReducer.markBinViewed(it, binId, now()) }

    override suspend fun markEntryViewed(binId: String, key: String) =
        edit { BinsReducer.markEntryViewed(it, binId, key, now()) }

    override suspend fun add(binId: String, item: LibraryEntryInput) = lock.withLock {
        val profileId = profileManager.activeProfileId.value
        if (read(profileId).bins.none { it.id == binId }) return@withLock
        // Library first: if it fails, the bin is left as it was.
        setInLibrary(item, true)
        write(profileId) { BinsReducer.addEntry(it, binId, item.toEntry(), now()) }
        Unit
    }

    override suspend fun remove(binId: String, itemId: String, itemType: String) = lock.withLock {
        val profileId = profileManager.activeProfileId.value
        val key = binKey(itemId, itemType)
        val entry = read(profileId).bins.firstOrNull { it.id == binId }?.entries?.firstOrNull { it.key == key }
            ?: return@withLock
        val after = write(profileId) { BinsReducer.removeEntry(it, binId, key) }
        if (after.bins.none { it.contains(key) }) setInLibrary(entry.toInput(), false)
    }

    private suspend fun reconcile(profileId: Int) = lock.withLock {
        val library = libraryPreferences.getAllItems(profileId).map { it.toEntry() }
        write(profileId) {
            BinsReducer.reconcile(
                state = it,
                library = library,
                now = now(),
                newBinId = { UUID.randomUUID().toString() },
                defaultName = context.getString(R.string.bins_default_name)
            )
        }
        Unit
    }

    private suspend fun edit(change: (BinsState) -> BinsState) {
        lock.withLock { write(profileManager.activeProfileId.value, change) }
    }

    private suspend fun read(profileId: Int): BinsState =
        decode(factory.get(profileId, FEATURE).data.first()[stateKey])

    private suspend fun write(profileId: Int, change: (BinsState) -> BinsState): BinsState {
        var result = BinsState()
        factory.get(profileId, FEATURE).edit { prefs ->
            val next = change(decode(prefs[stateKey]))
            result = next
            prefs[stateKey] = json.encodeToString(BinsState.serializer(), next)
        }
        return result
    }

    private suspend fun setInLibrary(item: LibraryEntryInput, inLibrary: Boolean) {
        libraryRepository.applyMembershipChanges(
            item = item,
            changes = ListMembershipChanges(mapOf(LOCAL_LIBRARY_LIST_KEY to inLibrary))
        )
    }

    private fun decode(raw: String?): BinsState =
        raw?.let { runCatching { json.decodeFromString(BinsState.serializer(), it) }.getOrNull() } ?: BinsState()

    private fun now() = System.currentTimeMillis()

    private companion object {
        const val FEATURE = "bins"
        const val TAG = "Bins"
    }
}

fun LibraryEntryInput.toEntry(): BinEntry = BinEntry(
    id = itemId,
    type = itemType,
    name = title,
    poster = poster,
    background = background,
    logo = logo,
    description = description,
    releaseInfo = releaseInfo,
    imdbRating = imdbRating,
    genres = genres,
    addonBaseUrl = addonBaseUrl
)

fun BinEntry.toInput(): LibraryEntryInput = LibraryEntryInput(
    itemId = id,
    itemType = type,
    title = name,
    poster = poster,
    background = background,
    logo = logo,
    description = description,
    releaseInfo = releaseInfo,
    imdbRating = imdbRating,
    genres = genres,
    addonBaseUrl = addonBaseUrl
)

private fun SavedLibraryItem.toEntry(): BinEntry = BinEntry(
    id = id,
    type = type,
    name = name,
    poster = poster,
    background = background,
    logo = logo,
    description = description,
    releaseInfo = releaseInfo,
    imdbRating = imdbRating,
    genres = genres,
    addonBaseUrl = addonBaseUrl,
    addedAt = addedAt
)
