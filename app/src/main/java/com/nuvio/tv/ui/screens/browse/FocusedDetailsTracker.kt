package com.nuvio.tv.ui.screens.browse

import com.nuvio.tv.core.browse.BrowseDetailsLoader
import com.nuvio.tv.core.browse.BrowseItemDetails
import com.nuvio.tv.domain.model.MetaPreview
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Details for the info panel, kept apart from the screen state so a focus move only redraws the
 * panel. Holding a direction scrolls past posters without touching the panel at all; it fills in
 * once focus rests, first from the grid item, then with the full details.
 */
class FocusedDetailsTracker(
    private val scope: CoroutineScope,
    private val loader: BrowseDetailsLoader
) {
    private val _details = MutableStateFlow<BrowseItemDetails?>(null)
    val details: StateFlow<BrowseItemDetails?> = _details.asStateFlow()

    private var job: Job? = null

    fun onFocused(item: MetaPreview) {
        job?.cancel()
        job = scope.launch {
            delay(SETTLE_MS)
            loader.cached(item)?.let {
                _details.value = it
                return@launch
            }
            _details.value = loader.preview(item)
            delay(LOAD_DELAY_MS)
            val loaded = runCatching { loader.load(item) }.getOrNull() ?: return@launch
            if (_details.value?.id == item.id) _details.value = loaded
        }
    }

    private companion object {
        /** Longer than a held D-pad's repeat interval, shorter than a deliberate pause. */
        const val SETTLE_MS = 120L
        const val LOAD_DELAY_MS = 180L
    }
}
