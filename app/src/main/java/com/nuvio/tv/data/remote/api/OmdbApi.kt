package com.nuvio.tv.data.remote.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

/** OMDb (needs a free personal key). Only its age rating is used, as the parental guide's last fallback. */
interface OmdbApi {
    @GET("/")
    suspend fun title(
        @Query("i") imdbId: String,
        @Query("apikey") apiKey: String
    ): Response<OmdbTitleResponse>
}

@JsonClass(generateAdapter = true)
data class OmdbTitleResponse(
    @Json(name = "Rated") val rated: String? = null,
    @Json(name = "Response") val response: String? = null,
    @Json(name = "Error") val error: String? = null
)
