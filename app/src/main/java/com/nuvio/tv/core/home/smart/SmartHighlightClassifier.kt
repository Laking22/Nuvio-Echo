package com.nuvio.tv.core.home.smart

import android.util.Log
import com.nuvio.tv.domain.model.MetaPreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
 * Optional Gemini pass over the day's Smart Highlights, in one request: drops titles that don't really fit a theme
 * and, when asked, suggests a name for each row. The reply must follow a schema whose theme field is an enum of the
 * app's own theme ids, so Gemini can't add themes; names it suggests still go through [HighlightNameFilter] before
 * they're shown. Without a Gemini key (Settings → Integrations → API keys), or if Gemini is slow or fails, the rows stay as TMDB gave them.
 * The key goes in a header, never in a URL or a log.
 */
@Singleton
class SmartHighlightClassifier @Inject constructor(baseClient: OkHttpClient) {
    private val client = baseClient.newBuilder()
        .callTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    val available: Boolean get() = com.nuvio.tv.core.apikeys.ApiKeys.gemini.isNotBlank()

    data class Review(
        /** Ids of the titles that fit, best first. */
        val keep: List<String>,
        /** A suggested row name, unchecked; null when none was asked for or given. */
        val name: String?
    )

    suspend fun review(
        rows: List<Pair<HighlightTheme, List<MetaPreview>>>,
        suggestNames: Boolean
    ): Map<String, Review> {
        if (!available || rows.isEmpty()) return emptyMap()
        // A few themes per request, asked together, so a long Heavy day doesn't outrun the timeout.
        return withContext(Dispatchers.IO) {
            rows.chunked(THEMES_PER_REQUEST).map { chunk ->
                async {
                    try {
                        ask(chunk, suggestNames)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "review skipped: ${e.javaClass.simpleName}")
                        null
                    }
                }
            }.awaitAll().fold(emptyMap()) { all, part -> all + part.orEmpty() }
        }
    }

    private fun ask(rows: List<Pair<HighlightTheme, List<MetaPreview>>>, suggestNames: Boolean): Map<String, Review>? {
        val themesText = rows.joinToString("\n\n") { (theme, items) ->
            val list = items.mapIndexed { i, c ->
                val kind = if (c.apiType.equals("series", true)) "show" else "movie"
                "  $i. ${c.name}${c.releaseInfo?.let { " ($it)" } ?: ""} [$kind]"
            }.joinToString("\n")
            "Theme \"${theme.id}\" (shown as \"${theme.title}\"): ${theme.definition}\nCandidates:\n$list"
        }
        val nameRule = if (suggestNames) {
            """
            Also give each theme a fresh row name that suits the titles you kept: 2 to 5 words, title case, playful
            but family-friendly, with no swearing, slurs, innuendo or anything crude. Use an empty string to keep
            the shown name.
            """.trimIndent()
        } else {
            "Leave name empty."
        }
        val prompt = """
            You are curating themed rows for a streaming app's home screen.
            For each theme, keep only the candidates that clearly fit its description, best fit first, and leave out
            any that don't. Use the candidate numbers.
            $nameRule

            $themesText
        """.trimIndent()

        val themeIds = JSONArray().apply { rows.forEach { put(it.first.id) } }
        val schema = JSONObject()
            .put("type", "ARRAY")
            .put("items", JSONObject()
                .put("type", "OBJECT")
                .put("properties", JSONObject()
                    .put("theme", JSONObject().put("type", "STRING").put("enum", themeIds))
                    .put("keep", JSONObject().put("type", "ARRAY").put("items", JSONObject().put("type", "INTEGER")))
                    .put("name", JSONObject().put("type", "STRING")))
                .put("required", JSONArray().put("theme").put("keep")))
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            .put("generationConfig", JSONObject()
                .put("responseMimeType", "application/json")
                .put("responseSchema", schema)
                .put("temperature", if (suggestNames) 0.7 else 0.2))
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent")
            .header("x-goog-api-key", com.nuvio.tv.core.apikeys.ApiKeys.gemini)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "review skipped: HTTP ${response.code}")
                return null
            }
            val text = JSONObject(response.body.string())
                .optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)
                ?.optString("text") ?: return null
            return parse(text, rows, suggestNames)
        }
    }

    internal companion object {
        private const val TAG = "SmartHighlights"
        private const val MODEL = "gemini-flash-lite-latest"
        private const val THEMES_PER_REQUEST = 8

        /** Gemini's reply, mapped back onto the candidates. Unknown themes and out-of-range numbers are ignored. */
        fun parse(
            text: String,
            rows: List<Pair<HighlightTheme, List<MetaPreview>>>,
            suggestNames: Boolean
        ): Map<String, Review> {
            val byTheme = rows.associate { it.first.id to it.second }
            val array = JSONArray(text.trim().removePrefix("```json").removeSuffix("```").trim())
            val out = HashMap<String, Review>()
            for (i in 0 until array.length()) {
                val entry = array.optJSONObject(i) ?: continue
                val themeId = entry.optString("theme")
                val items = byTheme[themeId] ?: continue
                val keep = entry.optJSONArray("keep") ?: JSONArray()
                val ids = (0 until keep.length()).mapNotNull { items.getOrNull(keep.optInt(it, -1))?.id }.distinct()
                val name = entry.optString("name").trim().takeIf { suggestNames && it.isNotEmpty() }
                out[themeId] = Review(ids, name)
            }
            return out
        }
    }
}
