package com.nuvio.tv.ui.screens.home

import androidx.lifecycle.viewModelScope
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaPreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

internal fun HomeViewModel.onCatalogRowItemsChanged(rowKey: String) {
    if (!isMdbBatchPrefetchEnabled()) return

    viewModelScope.launch(Dispatchers.IO) {
        val row = readCatalogRow(rowKey) ?: return@launch
        val newItems = row.items.filter { it.mdbListRatings == null && it.id !in mdbBatchNegativeIds }
        if (newItems.isEmpty()) return@launch
        batchFetchRatingsForItems(newItems)
    }
}

private suspend fun HomeViewModel.batchFetchRatingsForItems(items: List<MetaPreview>) {
    if (items.isEmpty()) return

    try {
        coroutineScope {
            items.map { item ->
                async(Dispatchers.IO) {
                    try {
                        val result = mdbListRepository.getRatingsForMeta(
                            meta = item.toMdbMeta(),
                            fallbackItemId = item.id,
                            fallbackItemType = item.apiType
                        )
                        if (result != null) {
                            updateCatalogItemMdbListRatings(item.id, result.ratings)
                        } else {
                            mdbBatchNegativeIds.add(item.id)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) { }
                }
            }.awaitAll()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) { }
}

private fun HomeViewModel.isMdbBatchPrefetchEnabled(): Boolean {
    val settings = currentMdbListSettings
    return mdbListRepository.isAvailable(settings) && settings.showOnHero
}

private fun MetaPreview.toMdbMeta(): Meta = Meta(
    id = id,
    type = type,
    name = name,
    poster = poster,
    posterShape = posterShape,
    background = background,
    logo = logo,
    description = description,
    releaseInfo = releaseInfo,
    imdbRating = imdbRating,
    genres = genres,
    runtime = runtime,
    director = director,
    cast = emptyList(),
    videos = emptyList(),
    country = country,
    awards = null,
    language = language,
    links = links
)
