package com.nuvio.tv.data.remote.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

/** Kitsu's public anime catalogue (no key). Used where TMDB has too little anime for a genre. */
interface KitsuApi {

    /**
     * @param category a Kitsu category slug; Kitsu ignores unknown slugs and returns everything, so
     * slugs must be checked with [categories] first.
     * @param text searches titles and synopses, so it also finds themes Kitsu has no category for.
     * @param sort popularityRank, ratingRank or -startDate.
     */
    @GET("anime")
    suspend fun anime(
        @Query("filter[categories]") category: String?,
        @Query("filter[text]") text: String? = null,
        @Query("filter[status]") status: String?,
        @Query("sort") sort: String,
        @Query("page[limit]") limit: Int = 20,
        @Query("page[offset]") offset: Int = 0,
        @Query("include") include: String = "categories",
        @Query("fields[categories]") categoryFields: String = "title"
    ): Response<KitsuAnimeResponse>

    @GET("categories")
    suspend fun categories(
        @Query("filter[slug]") slug: String,
        @Query("page[limit]") limit: Int = 1
    ): Response<KitsuCategoriesResponse>
}

@JsonClass(generateAdapter = true)
data class KitsuAnimeResponse(
    @Json(name = "data") val data: List<KitsuAnime>? = null,
    @Json(name = "included") val included: List<KitsuIncluded>? = null,
    @Json(name = "links") val links: KitsuLinks? = null
)

@JsonClass(generateAdapter = true)
data class KitsuAnime(
    @Json(name = "id") val id: String,
    @Json(name = "attributes") val attributes: KitsuAnimeAttributes? = null,
    @Json(name = "relationships") val relationships: KitsuAnimeRelationships? = null
)

@JsonClass(generateAdapter = true)
data class KitsuAnimeAttributes(
    @Json(name = "canonicalTitle") val canonicalTitle: String? = null,
    @Json(name = "titles") val titles: Map<String, String?>? = null,
    @Json(name = "synopsis") val synopsis: String? = null,
    @Json(name = "startDate") val startDate: String? = null,
    @Json(name = "averageRating") val averageRating: String? = null,
    @Json(name = "ageRating") val ageRating: String? = null,
    @Json(name = "subtype") val subtype: String? = null,
    @Json(name = "nsfw") val nsfw: Boolean? = null,
    @Json(name = "episodeLength") val episodeLength: Int? = null,
    @Json(name = "posterImage") val posterImage: KitsuImage? = null,
    @Json(name = "coverImage") val coverImage: KitsuImage? = null
)

@JsonClass(generateAdapter = true)
data class KitsuImage(
    @Json(name = "small") val small: String? = null,
    @Json(name = "medium") val medium: String? = null,
    @Json(name = "large") val large: String? = null,
    @Json(name = "original") val original: String? = null
)

@JsonClass(generateAdapter = true)
data class KitsuAnimeRelationships(
    @Json(name = "categories") val categories: KitsuRelationship? = null
)

@JsonClass(generateAdapter = true)
data class KitsuRelationship(
    @Json(name = "data") val data: List<KitsuResourceId>? = null
)

@JsonClass(generateAdapter = true)
data class KitsuResourceId(
    @Json(name = "type") val type: String? = null,
    @Json(name = "id") val id: String? = null
)

@JsonClass(generateAdapter = true)
data class KitsuIncluded(
    @Json(name = "type") val type: String? = null,
    @Json(name = "id") val id: String? = null,
    @Json(name = "attributes") val attributes: KitsuCategoryAttributes? = null
)

@JsonClass(generateAdapter = true)
data class KitsuCategoryAttributes(
    @Json(name = "title") val title: String? = null,
    @Json(name = "slug") val slug: String? = null
)

@JsonClass(generateAdapter = true)
data class KitsuLinks(
    @Json(name = "next") val next: String? = null
)

@JsonClass(generateAdapter = true)
data class KitsuCategoriesResponse(
    @Json(name = "data") val data: List<KitsuIncluded>? = null
)
