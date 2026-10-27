package com.nuvio.tv.data.remote.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path

interface ParentalGuideApi {

    @GET("titles/{imdbId}/parentsGuide")
    suspend fun getParentsGuide(
        @Path("imdbId") imdbId: String
    ): Response<ImdbApiParentsGuideResponse>

    @GET("titles/{imdbId}/certificates")
    suspend fun getCertificates(
        @Path("imdbId") imdbId: String
    ): Response<ImdbApiCertificatesResponse>
}

@JsonClass(generateAdapter = true)
data class ImdbApiCertificatesResponse(
    @Json(name = "certificates") val certificates: List<ImdbApiCertificate>? = null
)

@JsonClass(generateAdapter = true)
data class ImdbApiCertificate(
    @Json(name = "rating") val rating: String? = null,
    @Json(name = "country") val country: ImdbApiCountry? = null,
    @Json(name = "attributes") val attributes: List<String>? = null
)

@JsonClass(generateAdapter = true)
data class ImdbApiCountry(
    @Json(name = "code") val code: String? = null
)

@JsonClass(generateAdapter = true)
data class ImdbApiParentsGuideResponse(
    @Json(name = "parentsGuide") val parentsGuide: List<ImdbApiParentsGuideCategory>? = null
)

@JsonClass(generateAdapter = true)
data class ImdbApiParentsGuideCategory(
    @Json(name = "category") val category: String,
    @Json(name = "severityBreakdowns") val severityBreakdowns: List<ImdbApiSeverityBreakdown>? = null,
    @Json(name = "reviews") val reviews: List<ImdbApiParentsGuideReview>? = null
)

@JsonClass(generateAdapter = true)
data class ImdbApiSeverityBreakdown(
    @Json(name = "severityLevel") val severityLevel: String,
    @Json(name = "voteCount") val voteCount: Int
)

@JsonClass(generateAdapter = true)
data class ImdbApiParentsGuideReview(
    @Json(name = "text") val text: String? = null,
    @Json(name = "isSpoiler") val isSpoiler: Boolean? = null
)
