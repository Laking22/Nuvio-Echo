package com.nuvio.tv.domain.repository

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.Meta
import kotlinx.coroutines.flow.Flow

interface MetaRepository {
    fun getMeta(
        addonBaseUrl: String,
        type: String,
        id: String
    ): Flow<NetworkResult<Meta>>
    
    fun getMetaFromAllAddons(
        type: String,
        id: String,
        sourceAddonBaseUrl: String? = null
    ): Flow<NetworkResult<Meta>>

    fun getMetaFromPrimaryAddon(
        type: String,
        id: String
    ): Flow<NetworkResult<Meta>>

    /**
     * Returns cached meta if available (no network call). Useful for
     * reading backdrop URLs synchronously before navigation.
     */
    fun getCachedMeta(type: String, id: String): Meta?
    
    fun clearCache()
}

/**
 * Marks a meta lookup as one someone is waiting on (the details page opening). Collect a lookup in a coroutine
 * carrying this and it skips the queue background lookups wait in (Continue Watching, Calendar, Trakt progress,
 * Home's prefetch), so a slow addon busy with those can't hold up the page.
 */
object ForegroundMetaFetch : kotlin.coroutines.CoroutineContext.Element {
    object Key : kotlin.coroutines.CoroutineContext.Key<ForegroundMetaFetch>
    override val key: kotlin.coroutines.CoroutineContext.Key<*> get() = Key
}
