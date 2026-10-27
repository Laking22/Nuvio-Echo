package com.nuvio.tv.ui.screens.addon

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.home.BuiltInRow
import com.nuvio.tv.core.home.HomeRowOrder
import com.nuvio.tv.core.sync.HomeCatalogSettingsSyncService
import com.nuvio.tv.core.sync.homeCatalogKey
import com.nuvio.tv.core.sync.homeLegacyDisabledCatalogKey
import com.nuvio.tv.data.local.BetaPerformanceSettings
import com.nuvio.tv.data.local.BetaPerformanceSettingsDataStore
import com.nuvio.tv.data.local.CollectionsDataStore
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.Collection
import com.nuvio.tv.domain.model.enabledAddons
import com.nuvio.tv.domain.repository.AddonRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CatalogOrderViewModel @Inject constructor(
    private val addonRepository: AddonRepository,
    private val collectionsDataStore: CollectionsDataStore,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val homeCatalogSettingsSyncService: HomeCatalogSettingsSyncService,
    private val betaSettings: BetaPerformanceSettingsDataStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(CatalogOrderUiState())
    val uiState: StateFlow<CatalogOrderUiState> = _uiState.asStateFlow()
    private var disabledKeysCache: Set<String> = emptySet()
    /** The catalogs and collections alone, in order: what the layout settings' catalog order holds. */
    private var catalogItemsCache: List<CatalogOrderItem> = emptyList()

    init {
        observeCatalogs()
    }

    fun moveUp(key: String) = move(key, -1)

    fun moveDown(key: String) = move(key, 1)

    private fun move(key: String, direction: Int) {
        val state = _uiState.value
        // Built-in rows move one step among everything, and so does any row when the order is free.
        if (HomeRowOrder.isBuiltIn(key) || !state.followAddonsOrder) {
            moveInMergedOrder(key, direction)
        } else if (state.followAddonsOrder) {
            moveCollectionBetweenAddons(key, direction)
        } else {
            moveCatalog(key, direction)
        }
    }

    /** Moves one row a step in the list shown, then saves the catalog order and the built-in rows' places. */
    private fun moveInMergedOrder(key: String, direction: Int) {
        val merged = _uiState.value.items.map { it.key }
        val moved = HomeRowOrder.move(merged, key, direction)
        if (moved == merged) return
        val catalogOrder = moved.filterNot(HomeRowOrder::isBuiltIn)
        viewModelScope.launch {
            if (catalogOrder != merged.filterNot(HomeRowOrder::isBuiltIn)) {
                layoutPreferenceDataStore.setHomeCatalogOrderKeys(catalogOrder)
                homeCatalogSettingsSyncService.triggerPush()
            }
            betaSettings.setHomeRowOrder(moved)
        }
    }

    /** Turns a row on or off on Home: built-in rows in the beta settings, the rest in the layout settings. */
    fun toggleEnabled(item: CatalogOrderItem) {
        val builtIn = BuiltInRow.fromRowKey(item.key)
        if (builtIn != null) {
            viewModelScope.launch { betaSettings.setBuiltInRowShown(builtIn.key, item.isDisabled) }
        } else {
            toggleCatalogEnabled(item.disableKey)
        }
    }

    /** Highlighted and grand are one choice: picking one turns the other off. */
    fun toggleHighlighted(item: CatalogOrderItem) {
        val highlighted = item.highlighted ?: return
        val builtIn = BuiltInRow.fromRowKey(item.key)
        viewModelScope.launch {
            if (item.grand == true) betaSettings.setRowGrand(builtIn?.key ?: item.key, false)
            if (builtIn != null) betaSettings.setBuiltInRowHighlighted(builtIn.key, !highlighted)
            else betaSettings.setRowHighlighted(item.key, !highlighted)
        }
    }

    fun toggleGrand(item: CatalogOrderItem) {
        val grand = item.grand ?: return
        val builtIn = BuiltInRow.fromRowKey(item.key)
        viewModelScope.launch { betaSettings.setRowGrand(builtIn?.key ?: item.key, !grand) }
    }

    fun toggleFollowAddonsOrder(enabled: Boolean) {
        viewModelScope.launch {
            layoutPreferenceDataStore.setFollowAddonsOrder(enabled)
        }
    }

    fun toggleCatalogEnabled(disableKey: String) {
        val updatedDisabled = disabledKeysCache.toMutableSet().apply {
            if (disableKey in this) remove(disableKey) else add(disableKey)
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.setDisabledHomeCatalogKeys(updatedDisabled.toList())
            homeCatalogSettingsSyncService.triggerPush()
        }
    }

    private fun moveCatalog(key: String, direction: Int) {
        val currentKeys = catalogItemsCache.map { it.key }
        val currentIndex = currentKeys.indexOf(key)
        if (currentIndex == -1) return

        val newIndex = currentIndex + direction
        if (newIndex !in currentKeys.indices) return

        val reordered = currentKeys.toMutableList().apply {
            val item = removeAt(currentIndex)
            add(newIndex, item)
        }

        viewModelScope.launch {
            layoutPreferenceDataStore.setHomeCatalogOrderKeys(reordered)
            homeCatalogSettingsSyncService.triggerPush()
        }
    }

    /**
     * When followAddonsOrder is enabled, collections jump between addon boundaries.
     * Moving up means jumping above the previous addon block (all catalogs from one addon).
     * Moving down means jumping below the next addon block.
     */
    private fun moveCollectionBetweenAddons(key: String, direction: Int) {
        if (!key.startsWith("collection_")) return

        val items = catalogItemsCache
        val currentIndex = items.indexOfFirst { it.key == key }
        if (currentIndex == -1) return

        val currentKeys = items.map { it.key }

        val newIndex: Int
        if (direction < 0) {
            // Moving up: find the start of the previous addon block
            // Skip any adjacent collections above
            var scanIdx = currentIndex - 1
            while (scanIdx >= 0 && currentKeys[scanIdx].startsWith("collection_")) {
                scanIdx--
            }
            if (scanIdx < 0) return // already at top

            // scanIdx is now pointing at an addon catalog. Find the start of its addon block.
            val targetAddonName = items[scanIdx].addonName
            while (scanIdx > 0 && !currentKeys[scanIdx - 1].startsWith("collection_") &&
                items[scanIdx - 1].addonName == targetAddonName) {
                scanIdx--
            }
            newIndex = scanIdx
        } else {
            // Moving down: find the end of the next addon block
            var scanIdx = currentIndex + 1
            while (scanIdx < currentKeys.size && currentKeys[scanIdx].startsWith("collection_")) {
                scanIdx++
            }
            if (scanIdx >= currentKeys.size) return // already at bottom

            // scanIdx is now pointing at an addon catalog. Find the end of its addon block.
            val targetAddonName = items[scanIdx].addonName
            while (scanIdx < currentKeys.lastIndex && !currentKeys[scanIdx + 1].startsWith("collection_") &&
                items[scanIdx + 1].addonName == targetAddonName) {
                scanIdx++
            }
            newIndex = scanIdx
        }

        if (newIndex == currentIndex) return

        val reordered = currentKeys.toMutableList().apply {
            removeAt(currentIndex)
            // After removal, if target is after current position, shift index by -1
            val insertAt = if (direction < 0) {
                newIndex
            } else {
                newIndex // currentIndex < newIndex, after removal target shifts by -1, but we want AFTER the block
            }
            add(insertAt, key)
        }

        viewModelScope.launch {
            layoutPreferenceDataStore.setHomeCatalogOrderKeys(reordered)
            homeCatalogSettingsSyncService.triggerPush()
        }
    }

    private fun observeCatalogs() {
        viewModelScope.launch {
            combine(
                addonRepository.getInstalledAddons(),
                collectionsDataStore.collections,
                layoutPreferenceDataStore.homeCatalogOrderKeys,
                layoutPreferenceDataStore.disabledHomeCatalogKeys,
                layoutPreferenceDataStore.customCatalogTitles,
                layoutPreferenceDataStore.followAddonsOrder,
                betaSettings.settings
            ) { values ->
                @Suppress("UNCHECKED_CAST")
                val addons = values[0] as List<Addon>
                @Suppress("UNCHECKED_CAST")
                val collections = values[1] as List<Collection>
                @Suppress("UNCHECKED_CAST")
                val savedOrderKeys = values[2] as List<String>
                @Suppress("UNCHECKED_CAST")
                val disabledKeys = (values[3] as List<String>).toSet()
                @Suppress("UNCHECKED_CAST")
                val customTitles = values[4] as Map<String, String>
                val followAddons = values[5] as Boolean
                val beta = values[6] as BetaPerformanceSettings

                val items = buildOrderedCatalogItems(
                    addons = addons.enabledAddons(),
                    collections = collections,
                    savedOrderKeys = savedOrderKeys,
                    disabledKeys = disabledKeys,
                    customTitles = customTitles,
                    followAddonsOrder = followAddons
                )
                Triple(items, followAddons, beta)
            }.collectLatest { (orderedItems, followAddons, beta) ->
                catalogItemsCache = orderedItems
                disabledKeysCache = orderedItems.filter { it.isDisabled }.map { it.disableKey }.toSet()
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        items = withBuiltInRows(orderedItems, followAddons, beta),
                        followAddonsOrder = followAddons
                    )
                }
            }
        }
    }

    private fun buildOrderedCatalogItems(
        addons: List<Addon>,
        collections: List<Collection> = emptyList(),
        savedOrderKeys: List<String>,
        disabledKeys: Set<String>,
        customTitles: Map<String, String> = emptyMap(),
        followAddonsOrder: Boolean = false
    ): List<CatalogOrderItem> {
        val defaultEntries = buildDefaultCatalogEntries(addons)
        val collectionEntries = collections.map { collection ->
            CatalogOrderEntry(
                key = "collection_${collection.id}",
                disableKey = "collection_${collection.id}",
                catalogName = collection.title,
                addonName = "${collection.folders.size} folder${if (collection.folders.size != 1) "s" else ""}",
                typeLabel = "collection"
            )
        }
        val allEntries = defaultEntries + collectionEntries
        val availableMap = allEntries.associateBy { it.key }
        val defaultOrderKeys = allEntries.map { it.key }

        val effectiveOrder: List<String>
        if (followAddonsOrder) {
            // In follow mode, addon catalogs stay in manifest order.
            // Collections are positioned based on their relative position in savedOrderKeys.
            val addonKeys = defaultEntries.map { it.key }
            val collectionKeys = collectionEntries.map { it.key }.toSet()

            val savedValid = savedOrderKeys.filter { it in availableMap }.distinct()

            if (savedValid.isNotEmpty()) {
                // Rebuild order: take saved order but replace addon sequence with manifest order.
                // Strategy: walk through savedValid, output addon keys in manifest order,
                // insert collections at their saved positions relative to addon boundaries.
                val result = mutableListOf<String>()
                var addonPointer = 0 // pointer into addonKeys (manifest order)

                for (savedKey in savedValid) {
                    if (savedKey in collectionKeys) {
                        // Place collection here - but first flush any addon keys up to this point
                        // that haven't been placed yet
                        result.add(savedKey)
                    } else {
                        // It's an addon catalog key in saved order - advance manifest pointer
                        // to include all addon keys up to and including this one
                        val targetManifestIdx = addonKeys.indexOf(savedKey)
                        if (targetManifestIdx >= 0) {
                            while (addonPointer <= targetManifestIdx) {
                                val ak = addonKeys[addonPointer]
                                if (ak !in result) {
                                    result.add(ak)
                                }
                                addonPointer++
                            }
                        }
                    }
                }
                // Append any remaining addon keys not yet placed
                while (addonPointer < addonKeys.size) {
                    val ak = addonKeys[addonPointer]
                    if (ak !in result) {
                        result.add(ak)
                    }
                    addonPointer++
                }
                // Append any collections not in savedValid
                for (ck in collectionKeys) {
                    if (ck !in result) {
                        result.add(ck)
                    }
                }
                // Normalize: ensure collections sit at addon block boundaries, not mid-block.
                // If a collection is between two catalogs of the same addon, push it after that block.
                effectiveOrder = normalizeCollectionPositions(result, availableMap)
            } else {
                // No saved order - addon manifest order + collections at end
                effectiveOrder = addonKeys + collectionKeys.toList()
            }
        } else {
            val savedValid = savedOrderKeys
                .asSequence()
                .filter { it in availableMap }
                .distinct()
                .toList()

            val savedKeySet = savedValid.toSet()
            val missing = defaultOrderKeys.filterNot { it in savedKeySet }
            effectiveOrder = savedValid + missing
        }

        return effectiveOrder.mapIndexedNotNull { index, key ->
            val entry = availableMap[key] ?: return@mapIndexedNotNull null
            val displayName = customTitles[key]?.takeIf { it.isNotBlank() } ?: entry.catalogName
            val isCollection = key.startsWith("collection_")

            val canMoveUp: Boolean
            val canMoveDown: Boolean
            if (followAddonsOrder) {
                if (isCollection) {
                    canMoveUp = index > 0
                    canMoveDown = index < effectiveOrder.lastIndex
                } else {
                    canMoveUp = false
                    canMoveDown = false
                }
            } else {
                canMoveUp = index > 0
                canMoveDown = index < effectiveOrder.lastIndex
            }

            CatalogOrderItem(
                key = entry.key,
                disableKey = entry.disableKey,
                catalogName = displayName,
                addonName = entry.addonName,
                typeLabel = entry.typeLabel,
                isDisabled = entry.disableKey in disabledKeys ||
                    (entry.legacyDisableKey != null && entry.legacyDisableKey in disabledKeys),
                canMoveUp = canMoveUp,
                canMoveDown = canMoveDown
            )
        }
    }

    /**
     * New look: Nuvio's built-in rows listed among the catalogs where Home shows them, and every row's highlighted
     * state. Built-in rows can always move a step; with the order free, so can every row.
     */
    private fun withBuiltInRows(
        catalogItems: List<CatalogOrderItem>,
        followAddonsOrder: Boolean,
        beta: BetaPerformanceSettings
    ): List<CatalogOrderItem> {
        val byKey = catalogItems.associateBy { it.key }
        val merged = HomeRowOrder.merge(
            catalogItems.map { it.key }, BuiltInRow.entries, beta.homeRowOrder, continueWatching = true
        ) { key ->
            byKey[key]?.typeLabel
        }
        return merged.mapIndexedNotNull { index, key ->
            val canMoveUp = index > 0
            val canMoveDown = index < merged.lastIndex
            val builtIn = BuiltInRow.fromRowKey(key)
            if (key == HomeRowOrder.CONTINUE_WATCHING_KEY) {
                // Only moves: it's shown or hidden in its own settings, and has no highlighted or grand look.
                CatalogOrderItem(
                    key = key,
                    disableKey = "",
                    catalogName = "",
                    addonName = "",
                    typeLabel = "",
                    isDisabled = false,
                    canMoveUp = canMoveUp,
                    canMoveDown = canMoveDown,
                    builtInLabelRes = R.string.continue_watching,
                    canDisable = false
                )
            } else if (builtIn != null) {
                CatalogOrderItem(
                    key = key,
                    disableKey = builtIn.key,
                    catalogName = "",
                    addonName = "",
                    typeLabel = "",
                    isDisabled = builtIn.key in beta.hiddenBuiltInRows,
                    canMoveUp = canMoveUp,
                    canMoveDown = canMoveDown,
                    builtInLabelRes = builtIn.labelRes,
                    highlighted = builtIn.key !in beta.plainBuiltInRows && builtIn.key !in beta.grandRows,
                    grand = builtIn.key in beta.grandRows
                )
            } else {
                val item = byKey[key] ?: return@mapIndexedNotNull null
                item.copy(
                    canMoveUp = if (followAddonsOrder) item.canMoveUp else canMoveUp,
                    canMoveDown = if (followAddonsOrder) item.canMoveDown else canMoveDown,
                    highlighted = key in beta.highlightedRows && key !in beta.grandRows,
                    // Collection folders have no trailers or details to fill a grand card.
                    grand = if (key.startsWith("collection_")) null else key in beta.grandRows
                )
            }
        }
    }

    /**
     * Ensures collections are positioned at addon block boundaries.
     * If a collection ended up between two catalogs of the same addon,
     * push it to the end of that addon's block.
     */
    private fun normalizeCollectionPositions(
        order: List<String>,
        availableMap: Map<String, CatalogOrderEntry>
    ): List<String> {
        val result = order.toMutableList()
        var changed = true
        while (changed) {
            changed = false
            var i = 0
            while (i < result.size) {
                val key = result[i]
                if (!key.startsWith("collection_")) {
                    i++
                    continue
                }
                val prevAddon = findAddonNameBefore(result, i, availableMap)
                val nextAddon = findAddonNameAfter(result, i, availableMap)
                if (prevAddon != null && nextAddon != null && prevAddon == nextAddon) {
                    result.removeAt(i)
                    var insertPos = i
                    while (insertPos < result.size &&
                        !result[insertPos].startsWith("collection_") &&
                        availableMap[result[insertPos]]?.addonName == prevAddon
                    ) {
                        insertPos++
                    }
                    result.add(insertPos, key)
                    if (insertPos != i) changed = true
                    i++
                } else {
                    i++
                }
            }
        }
        return result
    }

    private fun findAddonNameBefore(
        order: List<String>,
        index: Int,
        availableMap: Map<String, CatalogOrderEntry>
    ): String? {
        for (j in index - 1 downTo 0) {
            if (!order[j].startsWith("collection_")) {
                return availableMap[order[j]]?.addonName
            }
        }
        return null
    }

    private fun findAddonNameAfter(
        order: List<String>,
        index: Int,
        availableMap: Map<String, CatalogOrderEntry>
    ): String? {
        for (j in index + 1 until order.size) {
            if (!order[j].startsWith("collection_")) {
                return availableMap[order[j]]?.addonName
            }
        }
        return null
    }

    private fun buildDefaultCatalogEntries(addons: List<Addon>): List<CatalogOrderEntry> {
        val entries = mutableListOf<CatalogOrderEntry>()
        val seenKeys = mutableSetOf<String>()

        addons.forEach { addon ->
            addon.catalogs
                .filterNot { it.isSearchOnlyCatalog() }
                .forEach { catalog ->
                    val key = catalogKey(
                        addonId = addon.id,
                        type = catalog.apiType,
                        catalogId = catalog.id
                    )
                    if (seenKeys.add(key)) {
                        entries.add(
                            CatalogOrderEntry(
                                key = key,
                                disableKey = homeCatalogKey(
                                    addonId = addon.id,
                                    type = catalog.apiType,
                                    catalogId = catalog.id
                                ),
                                legacyDisableKey = homeLegacyDisabledCatalogKey(
                                    addonBaseUrl = addon.baseUrl,
                                    type = catalog.apiType,
                                    catalogId = catalog.id,
                                    catalogName = catalog.name
                                ),
                                catalogName = catalog.name,
                                addonName = addon.displayName,
                                typeLabel = catalog.apiType
                            )
                        )
                    }
                }
        }

        return entries
    }

    private fun catalogKey(addonId: String, type: String, catalogId: String): String {
        return homeCatalogKey(addonId, type, catalogId)
    }

    private fun CatalogDescriptor.isSearchOnlyCatalog(): Boolean {
        return extra.any { extra -> extra.name.equals("search", ignoreCase = true) && extra.isRequired }
    }
}

data class CatalogOrderUiState(
    val isLoading: Boolean = true,
    val items: List<CatalogOrderItem> = emptyList(),
    val followAddonsOrder: Boolean = false
)

data class CatalogOrderItem(
    val key: String,
    val disableKey: String,
    val catalogName: String,
    val addonName: String,
    val typeLabel: String,
    val isDisabled: Boolean,
    val canMoveUp: Boolean,
    val canMoveDown: Boolean,
    /** A built-in row's name (its [catalogName] is blank); null for catalogs and collections. */
    @param:androidx.annotation.StringRes val builtInLabelRes: Int? = null,
    /** New look: whether Home shows the row highlighted; null in the classic look. */
    val highlighted: Boolean? = null,
    /** New look: whether Home shows the row as a grand row; null where it can't be one (classic look, collections). */
    val grand: Boolean? = null,
    /** False for Continue Watching, which this screen only moves. */
    val canDisable: Boolean = true
) {
    val isBuiltIn: Boolean get() = builtInLabelRes != null
}

private data class CatalogOrderEntry(
    val key: String,
    val disableKey: String,
    val legacyDisableKey: String? = null,
    val catalogName: String,
    val addonName: String,
    val typeLabel: String
)
