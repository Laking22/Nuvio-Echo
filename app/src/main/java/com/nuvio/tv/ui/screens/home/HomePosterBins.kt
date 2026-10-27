package com.nuvio.tv.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.bins.Bin
import com.nuvio.tv.core.bins.BinNameResult
import com.nuvio.tv.core.bins.BinSort
import com.nuvio.tv.core.bins.BinsReducer
import com.nuvio.tv.core.bins.BinsRepository
import com.nuvio.tv.core.bins.BinsState
import com.nuvio.tv.core.bins.binKey
import com.nuvio.tv.core.bins.cleanBinName
import com.nuvio.tv.domain.model.MetaPreview
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The bins for Home's poster options, so a held title goes into a bin as on the details page: the starred bin with
 * one press, or any bin (or a new one) from the list.
 */
@HiltViewModel
class HomePosterBinsViewModel @Inject constructor(
    private val binsRepository: BinsRepository
) : ViewModel() {
    val state: StateFlow<BinsState> = binsRepository.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BinsState())

    /** Every bin, in the Library's custom order. */
    fun bins(state: BinsState): List<Bin> = BinsReducer.sortedBins(state.bins, BinSort.CUSTOM)

    /** The bins [item] is in. */
    fun holding(state: BinsState, item: MetaPreview): List<Bin> {
        val key = binKey(item.id, item.apiType)
        return state.bins.filter { it.contains(key) }
    }

    fun setInBin(binId: String, item: MetaPreview, addonBaseUrl: String?, add: Boolean) {
        viewModelScope.launch {
            runCatching {
                if (add) binsRepository.add(binId, item.toLibraryEntryInput(addonBaseUrl))
                else binsRepository.remove(binId, item.id, item.apiType)
            }
        }
    }

    /** Makes a bin called [name] and puts [item] in it. Null when done, else why not (shown under the name box). */
    suspend fun createAndAdd(name: String, item: MetaPreview, addonBaseUrl: String?, blank: String, taken: (String) -> String): String? =
        when (val result = binsRepository.createBin(name)) {
            is BinNameResult.Ok -> {
                runCatching { binsRepository.add(result.binId, item.toLibraryEntryInput(addonBaseUrl)) }
                null
            }
            BinNameResult.Blank -> blank
            BinNameResult.Duplicate -> taken(cleanBinName(name))
        }
}
