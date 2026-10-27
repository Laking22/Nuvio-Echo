package com.nuvio.tv.data.remote.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/** AniList's public GraphQL endpoint (no key). Used for anime content tags in the parental guide. */
interface AniListApi {
    @POST(".")
    suspend fun query(@Body body: AniListQuery): Response<AniListResponse>
}

@JsonClass(generateAdapter = true)
data class AniListQuery(
    @Json(name = "query") val query: String,
    @Json(name = "variables") val variables: AniListVariables
)

@JsonClass(generateAdapter = true)
data class AniListVariables(
    @Json(name = "search") val search: String? = null,
    @Json(name = "idMal") val idMal: Int? = null
)

@JsonClass(generateAdapter = true)
data class AniListResponse(
    @Json(name = "data") val data: AniListData? = null
)

@JsonClass(generateAdapter = true)
data class AniListData(
    @Json(name = "Page") val page: AniListPage? = null
)

@JsonClass(generateAdapter = true)
data class AniListPage(
    @Json(name = "media") val media: List<AniListMedia>? = null
)

@JsonClass(generateAdapter = true)
data class AniListMedia(
    @Json(name = "id") val id: Int,
    @Json(name = "idMal") val idMal: Int? = null,
    @Json(name = "isAdult") val isAdult: Boolean? = null,
    @Json(name = "format") val format: String? = null,
    @Json(name = "genres") val genres: List<String>? = null,
    @Json(name = "startDate") val startDate: AniListDate? = null,
    @Json(name = "title") val title: AniListTitle? = null,
    @Json(name = "tags") val tags: List<AniListTag>? = null
)

@JsonClass(generateAdapter = true)
data class AniListDate(
    @Json(name = "year") val year: Int? = null
)

@JsonClass(generateAdapter = true)
data class AniListTitle(
    @Json(name = "romaji") val romaji: String? = null,
    @Json(name = "english") val english: String? = null
)

@JsonClass(generateAdapter = true)
data class AniListTag(
    @Json(name = "name") val name: String,
    @Json(name = "rank") val rank: Int? = null,
    @Json(name = "isMediaSpoiler") val isMediaSpoiler: Boolean? = null
)
