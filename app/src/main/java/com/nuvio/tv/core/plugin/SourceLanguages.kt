package com.nuvio.tv.core.plugin

import java.util.Locale

/**
 * Which languages' sources are asked for streams, as CloudStream's "provider languages": a source whose language
 * isn't chosen sits out. Repos tag languages loosely ("pt-br", "mx" for Mexican Spanish), so codes are reduced to
 * their base language; a source with no language, or one marked as several, always runs.
 */
object SourceLanguages {
    private val Aliases = mapOf("mx" to "es", "jp" to "ja", "cn" to "zh", "kr" to "ko", "iw" to "he", "in" to "id")
    private val AnyLanguage = setOf("multi", "mul", "uni", "xx", "und", "all")

    /** The base language of [code] ("pt-BR" gives "pt", "mx" gives "es"), or null when blank. */
    fun normalize(code: String?): String? {
        val base = code?.trim()?.lowercase(Locale.ROOT)?.substringBefore('-')?.substringBefore('_')
            ?.takeIf { it.isNotEmpty() } ?: return null
        return Aliases[base] ?: base
    }

    /** Until the languages are chosen: English and the app's own language. */
    fun defaults(appLanguage: String = Locale.getDefault().language): Set<String> =
        setOfNotNull("en", normalize(appLanguage))

    /** Whether a source tagged [languages] runs when [selected] are chosen. */
    fun allows(languages: Collection<String?>, selected: Set<String>): Boolean {
        val codes = languages.mapNotNull(::normalize)
        if (codes.isEmpty() || codes.any { it in AnyLanguage }) return true
        return codes.any { it in selected }
    }

    /** [code]'s name in the app's language ("hi" gives "Hindi"). */
    fun displayName(code: String): String {
        val name = Locale.forLanguageTag(code).getDisplayLanguage(Locale.getDefault())
        return name.takeIf { it.isNotBlank() && !it.equals(code, ignoreCase = true) }
            ?.replaceFirstChar { it.titlecase(Locale.getDefault()) }
            ?: code.uppercase(Locale.ROOT)
    }
}
