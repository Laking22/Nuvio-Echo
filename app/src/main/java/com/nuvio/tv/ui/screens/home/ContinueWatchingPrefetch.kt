package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.core.streams.StreamPrefetcher

/**
 * Continue Watching cards skip the details page and open the Streams screen directly, so the
 * details-page prefetch never runs for them. Warm the same request the card's route will make
 * (see createContinueWatchingRoute in NuvioNavHost) once focus rests on it.
 */
fun dagger.Lazy<StreamPrefetcher>.prefetchOnFocus(item: ContinueWatchingItem?) {
    when (item) {
        is ContinueWatchingItem.InProgress -> get().prefetchAfterDwell(
            type = item.progress.contentType,
            videoId = item.progress.videoId,
            season = item.progress.season,
            episode = item.progress.episode
        )
        is ContinueWatchingItem.NextUp -> get().prefetchAfterDwell(
            type = item.info.contentType,
            videoId = item.info.videoId,
            season = item.info.season,
            episode = item.info.episode
        )
        null -> Unit
    }
}
