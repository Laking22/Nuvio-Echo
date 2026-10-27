package com.nuvio.tv.core.home

import android.util.Log
import com.nuvio.tv.domain.model.MetaPreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Optional: re-ranks Because You Watched and For You with Gemini, putting first the candidates that are really
 * close to what was watched and dropping poor matches. Without a Gemini key (Settings → Integrations → API keys), or if
 * Gemini is slow or fails, the TMDB order is kept. The key goes in a header, never in a URL or a log.
 */
@Singleton
class GeminiRanker @Inject constructor(baseClient: OkHttpClient) {
    private val client = baseClient.newBuilder()
        .callTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    private val cache = HashMap<String, List<String>>()

    val available: Boolean get() = com.nuvio.tv.core.apikeys.ApiKeys.gemini.isNotBlank()

    /** The details page's thumbs, by title name, so the ranking can lean towards or away from them. */
    data class Taste(
        val loved: List<String> = emptyList(),
        val liked: List<String> = emptyList(),
        val disliked: List<String> = emptyList()
    ) {
        val isEmpty: Boolean get() = loved.isEmpty() && liked.isEmpty() && disliked.isEmpty()
    }

    /**
     * [watched] are titles the user watched, most recent first. Returns [candidates] in Gemini's order (only the
     * ones it kept, when it kept at least [minKeep]), or unchanged.
     */
    suspend fun rank(
        watched: List<String>,
        candidates: List<MetaPreview>,
        minKeep: Int,
        taste: Taste = Taste()
    ): List<MetaPreview> {
        if (!available || candidates.size < 2 || watched.isEmpty()) return candidates
        val cacheKey = watched.joinToString("|") + "#" + candidates.joinToString(",") { it.id } +
            "#" + taste.loved.joinToString("|") + "#" + taste.liked.joinToString("|") + "#" + taste.disliked.joinToString("|")
        synchronized(cache) { cache[cacheKey] }?.let { ids -> return reorder(candidates, ids, minKeep) }
        val ids = withContext(Dispatchers.IO) {
            try {
                ask(watched, candidates, taste)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "re-rank skipped: ${e.javaClass.simpleName}")
                null
            }
        } ?: return candidates
        synchronized(cache) { cache[cacheKey] = ids }
        return reorder(candidates, ids, minKeep)
    }

    private fun reorder(candidates: List<MetaPreview>, ids: List<String>, minKeep: Int): List<MetaPreview> {
        val byId = candidates.associateBy { it.id }
        val ranked = ids.mapNotNull { byId[it] }.distinctBy { it.id }
        return if (ranked.size >= minKeep) ranked else candidates
    }

    private fun ask(watched: List<String>, candidates: List<MetaPreview>, taste: Taste): List<String>? {
        val list = candidates.mapIndexed { i, c ->
            val kind = if (c.apiType.equals("series", true)) "show" else "movie"
            "$i. ${c.name}${c.releaseInfo?.let { " ($it)" } ?: ""} [$kind]"
        }.joinToString("\n")
        val tasteLines = buildList {
            if (taste.loved.isNotEmpty()) add("They loved: ${taste.loved.take(10).joinToString("; ")}.")
            if (taste.liked.isNotEmpty()) add("They liked: ${taste.liked.take(10).joinToString("; ")}.")
            if (taste.disliked.isNotEmpty()) add(
                "They marked as not for them: ${taste.disliked.take(10).joinToString("; ")}. Rank down or leave out candidates like these."
            )
        }.joinToString("\n")
        val prompt = """
            Someone recently watched, most recent first: ${watched.joinToString("; ")}.
            $tasteLines
            Rank these candidates by how much that person would enjoy them next, judging by real closeness in tone,
            genre and audience to what they watched. Leave out ones that are a poor match.
            Candidates:
            $list
            Reply with only a JSON array of the candidate numbers, best first.
        """.trimIndent()
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("temperature", 0.2))
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent")
            .header("x-goog-api-key", com.nuvio.tv.core.apikeys.ApiKeys.gemini)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "re-rank skipped: HTTP ${response.code}")
                return null
            }
            val text = JSONObject(response.body.string())
                .optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)
                ?.optString("text") ?: return null
            val array = JSONArray(text.trim().removePrefix("```json").removeSuffix("```").trim())
            return (0 until array.length()).mapNotNull { candidates.getOrNull(array.optInt(it, -1))?.id }
        }
    }

    private companion object {
        const val TAG = "GeminiRanker"
        const val MODEL = "gemini-flash-lite-latest"
    }
}
