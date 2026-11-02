@file:Suppress("unused")

package com.lagradost.cloudstream3.syncproviders

import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.utils.UiText

/**
 * CloudStream's tracker accounts (AniList, Simkl), as extensions see them. Echo has its own Trakt, Simkl and
 * MDBList sign-ins, so these never have a signed-in user: an extension asking for one gets none, and carries on
 * as CloudStream does for someone not signed in.
 */
abstract class SyncAPI {
    open val name: String = ""
    open val idPrefix: String = ""
    open val mainUrl: String = ""

    data class LibraryList(
        val name: UiText,
        val items: List<SearchResponse>
    )

    data class LibraryMetadata(
        val allLibraryLists: List<LibraryList>,
        val supportedListSorting: Set<Any> = emptySet()
    )
}

data class AuthUser(
    val name: String?,
    val id: Int,
    val profilePicture: String? = null,
    val profilePictureHeaders: Map<String, String>? = null
)

class SyncRepo(val api: SyncAPI) {
    val name: String get() = api.name
    val idPrefix: String get() = api.idPrefix

    fun authUser(): AuthUser? = null

    suspend fun library(): Result<SyncAPI.LibraryMetadata?> = Result.success(null)
}

class AccountManager {
    companion object {
        const val NONE_ID: Int = -1

        @JvmStatic
        val aniListApi = com.lagradost.cloudstream3.syncproviders.providers.AniListApi()

        @JvmStatic
        val simklApi = com.lagradost.cloudstream3.syncproviders.providers.SimklApi()

        @JvmStatic
        val malApi = com.lagradost.cloudstream3.syncproviders.providers.MALApi()
    }
}
