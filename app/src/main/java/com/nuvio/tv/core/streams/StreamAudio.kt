package com.nuvio.tv.core.streams

import com.nuvio.tv.domain.model.Stream

/** Audio languages the player's audio menu can list, in the order the menu and settings show them. */
enum class AudioLanguage(val code: String, val englishName: String) {
    ENGLISH("en", "English"),
    JAPANESE("ja", "Japanese"),
    KOREAN("ko", "Korean"),
    SPANISH("es", "Spanish"),
    LATIN_SPANISH("es-419", "Latin American Spanish"),
    FRENCH("fr", "French"),
    GERMAN("de", "German"),
    ITALIAN("it", "Italian"),
    PORTUGUESE("pt", "Portuguese"),
    RUSSIAN("ru", "Russian"),
    UKRAINIAN("uk", "Ukrainian"),
    POLISH("pl", "Polish"),
    HINDI("hi", "Hindi"),
    TAMIL("ta", "Tamil"),
    TELUGU("te", "Telugu"),
    MALAYALAM("ml", "Malayalam"),
    KANNADA("kn", "Kannada"),
    BENGALI("bn", "Bengali"),
    CHINESE("zh", "Chinese"),
    CANTONESE("yue", "Cantonese"),
    THAI("th", "Thai"),
    VIETNAMESE("vi", "Vietnamese"),
    INDONESIAN("id", "Indonesian"),
    MALAY("ms", "Malay"),
    FILIPINO("fil", "Filipino"),
    ARABIC("ar", "Arabic"),
    TURKISH("tr", "Turkish"),
    PERSIAN("fa", "Persian"),
    HEBREW("he", "Hebrew"),
    DUTCH("nl", "Dutch"),
    SWEDISH("sv", "Swedish"),
    NORWEGIAN("no", "Norwegian"),
    DANISH("da", "Danish"),
    FINNISH("fi", "Finnish"),
    CZECH("cs", "Czech"),
    HUNGARIAN("hu", "Hungarian"),
    ROMANIAN("ro", "Romanian"),
    GREEK("el", "Greek");

    companion object {
        /** Settings key for "the title's original language" (resolved per title). */
        const val ORIGINAL_KEY = "original"

        /** Shown in the audio menu until changed in Beta speed-ups. */
        val defaultMenuKeys: Set<String> = setOf(ORIGINAL_KEY, ENGLISH.code, JAPANESE.code, KOREAN.code)

        private val byCode: Map<String, AudioLanguage> = buildMap {
            AudioLanguage.entries.forEach { put(it.code, it) }
            // ISO 639-2 (bibliographic and terminology), regional tags and TMDB's oddities.
            mapOf(
                "eng" to ENGLISH, "jpn" to JAPANESE, "kor" to KOREAN,
                "spa" to SPANISH, "es-es" to SPANISH, "es-419" to LATIN_SPANISH, "es-mx" to LATIN_SPANISH,
                "es-us" to LATIN_SPANISH, "es-ar" to LATIN_SPANISH, "es-la" to LATIN_SPANISH,
                "fre" to FRENCH, "fra" to FRENCH, "fr-fr" to FRENCH, "fr-ca" to FRENCH,
                "ger" to GERMAN, "deu" to GERMAN, "ita" to ITALIAN,
                "por" to PORTUGUESE, "pt-br" to PORTUGUESE, "pt-pt" to PORTUGUESE, "pob" to PORTUGUESE,
                // Not codes, but some files are labelled with them.
                "jp" to JAPANESE, "kr" to KOREAN,
                "rus" to RUSSIAN, "ukr" to UKRAINIAN, "pol" to POLISH,
                "hin" to HINDI, "tam" to TAMIL, "tel" to TELUGU, "mal" to MALAYALAM, "kan" to KANNADA,
                "ben" to BENGALI, "chi" to CHINESE, "zho" to CHINESE, "cmn" to CHINESE, "zh-cn" to CHINESE,
                "zh-tw" to CHINESE, "zh-hans" to CHINESE, "zh-hant" to CHINESE,
                "zh-hk" to CANTONESE, "zh-yue" to CANTONESE, "cn" to CHINESE,
                "tha" to THAI, "vie" to VIETNAMESE, "ind" to INDONESIAN, "in" to INDONESIAN,
                "msa" to MALAY, "may" to MALAY, "tl" to FILIPINO, "tgl" to FILIPINO,
                "ara" to ARABIC, "tur" to TURKISH, "per" to PERSIAN, "fas" to PERSIAN,
                "heb" to HEBREW, "iw" to HEBREW, "dut" to DUTCH, "nld" to DUTCH, "swe" to SWEDISH,
                "nor" to NORWEGIAN, "nob" to NORWEGIAN, "nno" to NORWEGIAN, "nb" to NORWEGIAN, "nn" to NORWEGIAN,
                "dan" to DANISH, "fin" to FINNISH, "cze" to CZECH, "ces" to CZECH, "hun" to HUNGARIAN,
                "rum" to ROMANIAN, "ron" to ROMANIAN, "gre" to GREEK, "ell" to GREEK
            ).forEach { (code, language) -> put(code, language) }
        }

        /** A language code as players and metadata give it (en, eng, pt-BR, es_419...); null if not listed. */
        fun fromCode(code: String?): AudioLanguage? {
            val normalized = code?.trim()?.lowercase()?.replace('_', '-')?.takeIf { it.isNotEmpty() } ?: return null
            byCode[normalized]?.let { return it }
            // Spanish of any region but Spain (es-CL, es-CO, es-PE...) is the Latin American dub.
            if (normalized.startsWith("es-")) return LATIN_SPANISH
            return byCode[normalized.substringBefore('-')]
        }

        fun fromKey(key: String): AudioLanguage? = entries.firstOrNull { it.code == key }
    }
}

/** What a source's text says about its audio, before the title's original language is known. */
data class StreamAudioTags(
    val languages: Set<AudioLanguage> = emptySet(),
    /** "Dual audio": usually the original plus English. */
    val dual: Boolean = false,
    /** "MULTi": the original plus one or more dubs. */
    val multi: Boolean = false,
    /** "Dubbed" without saying into what. */
    val dubbed: Boolean = false,
    /** "VOSTFR", "Subbed", "RAW": the original audio with subtitles. */
    val originalAudio: Boolean = false
) {
    operator fun plus(other: StreamAudioTags) = StreamAudioTags(
        languages = languages + other.languages,
        dual = dual || other.dual,
        multi = multi || other.multi,
        dubbed = dubbed || other.dubbed,
        originalAudio = originalAudio || other.originalAudio
    )

    val isEmpty: Boolean get() = languages.isEmpty() && !dual && !multi && !dubbed && !originalAudio
}

/** The audio a source is taken to have once the title's original language is known. */
data class StreamAudio(
    val languages: Set<AudioLanguage>,
    /** Some of its audio (or all of it) couldn't be named. */
    val unknown: Boolean,
    /** Nothing was tagged, so it's taken to be the original (scene naming only tags dubs). */
    val assumedOriginal: Boolean = false
)

/**
 * Reads the audio languages an addon advertises for a stream: language names, release tags
 * (ENG, iTA, MULTi, Dual Audio, VOSTFR), and flags. Subtitle languages ("Eng Subs", a 💬 line)
 * are left out.
 */
object StreamAudioParser {

    fun tags(stream: Stream): StreamAudioTags {
        val parsed = stream.clientResolve?.stream?.raw?.parsed
        val key = buildString {
            append(stream.name); append('\u0001'); append(stream.title); append('\u0001')
            append(stream.description); append('\u0001'); append(stream.behaviorHints?.filename); append('\u0001')
            append(stream.clientResolve?.filename); append('\u0001'); append(parsed?.languages)
        }
        synchronized(cache) { cache[key] }?.let { return it }
        var tags = structuredTags(parsed?.languages)
        listOf(
            stream.name,
            stream.title,
            stream.description,
            stream.behaviorHints?.filename,
            stream.clientResolve?.filename,
            stream.clientResolve?.stream?.raw?.filename
        ).forEach { tags += parseText(it) }
        synchronized(cache) { cache[key] = tags }
        return tags
    }

    /**
     * Applies naming conventions with the title's original language: untagged releases are the
     * original, "Dual audio" is the original plus English, "MULTi" includes the original.
     */
    fun resolve(tags: StreamAudioTags, original: AudioLanguage?): StreamAudio {
        val languages = tags.languages.toMutableSet()
        var unknown = false
        if (tags.originalAudio && original != null) languages += original
        if (tags.dubbed && languages.isEmpty()) {
            if (original != null && original != AudioLanguage.ENGLISH) languages += AudioLanguage.ENGLISH else unknown = true
        }
        if (tags.dual && languages.size < 2) {
            if (original != null && original != AudioLanguage.ENGLISH) {
                languages += original
                languages += AudioLanguage.ENGLISH
            } else {
                original?.let { languages += it }
                if (languages.size < 2) unknown = true
            }
        }
        if (tags.multi) {
            original?.let { languages += it }
            if (languages.size < 2) unknown = true
        }
        if (languages.isEmpty() && !unknown) {
            if (original != null) return StreamAudio(setOf(original), unknown = false, assumedOriginal = true)
            unknown = true
        }
        return StreamAudio(languages, unknown)
    }

    fun audio(stream: Stream, original: AudioLanguage?, titles: List<String?> = emptyList()): StreamAudio =
        resolve(if (titles.isEmpty()) tags(stream) else tagsWithoutTitles(stream, titles), original)

    /** Like [tags], but words of the title itself ("The French Dispatch", "Johnny English") don't count. */
    fun tagsWithoutTitles(stream: Stream, titles: List<String?>): StreamAudioTags {
        val titleTokens = titles.mapNotNull { title -> title?.let { tokens(it).map { t -> t.lower } }?.takeIf { it.isNotEmpty() } }
        if (titleTokens.isEmpty() ||
            titleTokens.none { words -> words.any { it in nameWords || it in markerWords || it in codeWords || it in shortCodes } }
        ) {
            return tags(stream)
        }
        val parsed = stream.clientResolve?.stream?.raw?.parsed
        var tags = structuredTags(parsed?.languages)
        listOf(
            stream.name,
            stream.title,
            stream.description,
            stream.behaviorHints?.filename,
            stream.clientResolve?.filename,
            stream.clientResolve?.stream?.raw?.filename
        ).forEach { tags += parseText(it, titleTokens) }
        return tags
    }

    /**
     * The language of a track in the playing file: its language code, else a language named in its title. A code
     * in any case counts only when it's the whole title ("ita"); in a longer one only in capitals, so a track
     * called "Cast commentary" or "Commentary with Ben" isn't Spanish or Bengali.
     */
    fun trackLanguage(language: String?, name: String?): AudioLanguage? {
        AudioLanguage.fromCode(language)?.let { coded ->
            // Files tag the Latin American dub "spa" too; its name tells them apart ("Latino", "Spanish (LatAm)").
            if (coded == AudioLanguage.SPANISH && !name.isNullOrBlank() &&
                AudioLanguage.LATIN_SPANISH in parseText(name, lenientCodes = true).languages
            ) return AudioLanguage.LATIN_SPANISH
            return coded
        }
        if (name.isNullOrBlank()) return null
        val lenient = tokens(name).size == 1
        val named = parseText(name, lenientCodes = lenient).languages
        return named.singleOrNull()
    }

    // ── Text parsing ───────────────────────────────────────────────────────────────────────────

    private class Token(val text: String, val lower: String, val isFlag: Boolean)

    private val tokenRegex = Regex("(?:[\\x{1F1E6}-\\x{1F1FF}]{2})|[\\p{L}\\p{N}]+")

    private fun tokens(text: String): List<Token> = tokenRegex.findAll(text).map {
        val value = it.value
        Token(value, value.lowercase(), value.codePointAt(0) in 0x1F1E6..0x1F1FF)
    }.toList()

    /** Full names and unambiguous release words, any case. */
    private val nameWords: Map<String, AudioLanguage> = mapOf(
        "english" to AudioLanguage.ENGLISH,
        "japanese" to AudioLanguage.JAPANESE,
        "korean" to AudioLanguage.KOREAN,
        "spanish" to AudioLanguage.SPANISH, "castellano" to AudioLanguage.SPANISH, "castilian" to AudioLanguage.SPANISH,
        "español" to AudioLanguage.SPANISH, "espanol" to AudioLanguage.SPANISH,
        "latino" to AudioLanguage.LATIN_SPANISH, "latam" to AudioLanguage.LATIN_SPANISH,
        "french" to AudioLanguage.FRENCH, "truefrench" to AudioLanguage.FRENCH, "français" to AudioLanguage.FRENCH,
        "francais" to AudioLanguage.FRENCH, "vff" to AudioLanguage.FRENCH, "vfq" to AudioLanguage.FRENCH,
        "vf2" to AudioLanguage.FRENCH, "vfi" to AudioLanguage.FRENCH,
        "german" to AudioLanguage.GERMAN, "deutsch" to AudioLanguage.GERMAN,
        "italian" to AudioLanguage.ITALIAN, "italiano" to AudioLanguage.ITALIAN,
        "portuguese" to AudioLanguage.PORTUGUESE, "português" to AudioLanguage.PORTUGUESE,
        "portugues" to AudioLanguage.PORTUGUESE, "brazilian" to AudioLanguage.PORTUGUESE, "dublado" to AudioLanguage.PORTUGUESE,
        "russian" to AudioLanguage.RUSSIAN, "ukrainian" to AudioLanguage.UKRAINIAN, "polish" to AudioLanguage.POLISH,
        "hindi" to AudioLanguage.HINDI, "tamil" to AudioLanguage.TAMIL, "telugu" to AudioLanguage.TELUGU,
        "malayalam" to AudioLanguage.MALAYALAM, "kannada" to AudioLanguage.KANNADA,
        "bengali" to AudioLanguage.BENGALI, "bangla" to AudioLanguage.BENGALI,
        "chinese" to AudioLanguage.CHINESE, "mandarin" to AudioLanguage.CHINESE, "cantonese" to AudioLanguage.CANTONESE,
        "thai" to AudioLanguage.THAI, "vietnamese" to AudioLanguage.VIETNAMESE, "indonesian" to AudioLanguage.INDONESIAN,
        "malay" to AudioLanguage.MALAY, "filipino" to AudioLanguage.FILIPINO, "tagalog" to AudioLanguage.FILIPINO,
        "arabic" to AudioLanguage.ARABIC, "turkish" to AudioLanguage.TURKISH, "persian" to AudioLanguage.PERSIAN,
        "farsi" to AudioLanguage.PERSIAN, "hebrew" to AudioLanguage.HEBREW, "dutch" to AudioLanguage.DUTCH,
        "flemish" to AudioLanguage.DUTCH, "swedish" to AudioLanguage.SWEDISH, "norwegian" to AudioLanguage.NORWEGIAN,
        "danish" to AudioLanguage.DANISH, "finnish" to AudioLanguage.FINNISH, "czech" to AudioLanguage.CZECH,
        "hungarian" to AudioLanguage.HUNGARIAN, "romanian" to AudioLanguage.ROMANIAN, "greek" to AudioLanguage.GREEK,
        // In their own scripts, as some addons write them.
        "日本語" to AudioLanguage.JAPANESE, "한국어" to AudioLanguage.KOREAN, "中文" to AudioLanguage.CHINESE,
        "普通话" to AudioLanguage.CHINESE, "國語" to AudioLanguage.CHINESE, "粵語" to AudioLanguage.CANTONESE,
        "русский" to AudioLanguage.RUSSIAN, "українська" to AudioLanguage.UKRAINIAN, "polski" to AudioLanguage.POLISH,
        "türkçe" to AudioLanguage.TURKISH, "العربية" to AudioLanguage.ARABIC, "ελληνικά" to AudioLanguage.GREEK
    )

    /** Release codes. Words like "Cast", "Ben" or "Fin" are titles, so these count only in capitals (ENG, iTA). */
    private val codeWords: Map<String, AudioLanguage> = mapOf(
        "eng" to AudioLanguage.ENGLISH, "jpn" to AudioLanguage.JAPANESE, "jap" to AudioLanguage.JAPANESE,
        "kor" to AudioLanguage.KOREAN, "spa" to AudioLanguage.SPANISH, "esp" to AudioLanguage.SPANISH,
        "cast" to AudioLanguage.SPANISH, "lat" to AudioLanguage.LATIN_SPANISH,
        "fre" to AudioLanguage.FRENCH, "fra" to AudioLanguage.FRENCH, "vf" to AudioLanguage.FRENCH,
        "ger" to AudioLanguage.GERMAN, "deu" to AudioLanguage.GERMAN, "ita" to AudioLanguage.ITALIAN,
        "por" to AudioLanguage.PORTUGUESE, "ptbr" to AudioLanguage.PORTUGUESE,
        "rus" to AudioLanguage.RUSSIAN, "ukr" to AudioLanguage.UKRAINIAN, "pol" to AudioLanguage.POLISH,
        "hin" to AudioLanguage.HINDI, "tam" to AudioLanguage.TAMIL, "tel" to AudioLanguage.TELUGU,
        "mal" to AudioLanguage.MALAYALAM, "kan" to AudioLanguage.KANNADA, "ben" to AudioLanguage.BENGALI,
        "chi" to AudioLanguage.CHINESE, "chn" to AudioLanguage.CHINESE, "zho" to AudioLanguage.CHINESE,
        "tha" to AudioLanguage.THAI, "vie" to AudioLanguage.VIETNAMESE, "ind" to AudioLanguage.INDONESIAN,
        "fil" to AudioLanguage.FILIPINO, "ara" to AudioLanguage.ARABIC, "tur" to AudioLanguage.TURKISH,
        "per" to AudioLanguage.PERSIAN, "heb" to AudioLanguage.HEBREW, "dut" to AudioLanguage.DUTCH,
        "nld" to AudioLanguage.DUTCH, "swe" to AudioLanguage.SWEDISH, "nor" to AudioLanguage.NORWEGIAN,
        "dan" to AudioLanguage.DANISH, "fin" to AudioLanguage.FINNISH, "cze" to AudioLanguage.CZECH,
        "hun" to AudioLanguage.HUNGARIAN, "rum" to AudioLanguage.ROMANIAN, "gre" to AudioLanguage.GREEK
    )

    /** Two-letter codes ("EN • JA") count only in capitals on a line that's about audio or languages. */
    private val shortCodes: Map<String, AudioLanguage> = mapOf(
        "en" to AudioLanguage.ENGLISH, "ja" to AudioLanguage.JAPANESE, "jp" to AudioLanguage.JAPANESE,
        "ko" to AudioLanguage.KOREAN, "kr" to AudioLanguage.KOREAN, "es" to AudioLanguage.SPANISH,
        "fr" to AudioLanguage.FRENCH, "de" to AudioLanguage.GERMAN, "it" to AudioLanguage.ITALIAN,
        "pt" to AudioLanguage.PORTUGUESE, "ru" to AudioLanguage.RUSSIAN, "hi" to AudioLanguage.HINDI,
        "zh" to AudioLanguage.CHINESE, "ar" to AudioLanguage.ARABIC, "tr" to AudioLanguage.TURKISH,
        "pl" to AudioLanguage.POLISH, "nl" to AudioLanguage.DUTCH, "th" to AudioLanguage.THAI
    )

    private val flagCountries: Map<String, AudioLanguage> = buildMap {
        listOf("GB", "US", "AU", "NZ", "IE", "CA").forEach { put(it, AudioLanguage.ENGLISH) }
        put("JP", AudioLanguage.JAPANESE); put("KR", AudioLanguage.KOREAN); put("ES", AudioLanguage.SPANISH)
        listOf("MX", "AR", "CO", "CL", "PE", "VE").forEach { put(it, AudioLanguage.LATIN_SPANISH) }
        put("FR", AudioLanguage.FRENCH); put("DE", AudioLanguage.GERMAN); put("AT", AudioLanguage.GERMAN)
        put("IT", AudioLanguage.ITALIAN); put("PT", AudioLanguage.PORTUGUESE); put("BR", AudioLanguage.PORTUGUESE)
        put("RU", AudioLanguage.RUSSIAN); put("UA", AudioLanguage.UKRAINIAN); put("PL", AudioLanguage.POLISH)
        put("IN", AudioLanguage.HINDI); put("CN", AudioLanguage.CHINESE); put("TW", AudioLanguage.CHINESE)
        put("HK", AudioLanguage.CANTONESE); put("TH", AudioLanguage.THAI); put("VN", AudioLanguage.VIETNAMESE)
        put("ID", AudioLanguage.INDONESIAN); put("MY", AudioLanguage.MALAY); put("PH", AudioLanguage.FILIPINO)
        listOf("SA", "AE", "EG").forEach { put(it, AudioLanguage.ARABIC) }
        put("TR", AudioLanguage.TURKISH); put("IR", AudioLanguage.PERSIAN); put("IL", AudioLanguage.HEBREW)
        put("NL", AudioLanguage.DUTCH); put("SE", AudioLanguage.SWEDISH); put("NO", AudioLanguage.NORWEGIAN)
        put("DK", AudioLanguage.DANISH); put("FI", AudioLanguage.FINNISH); put("CZ", AudioLanguage.CZECH)
        put("HU", AudioLanguage.HUNGARIAN); put("RO", AudioLanguage.ROMANIAN); put("GR", AudioLanguage.GREEK)
    }

    private val subtitleWords = setOf(
        "sub", "subs", "subbed", "subtitle", "subtitles", "subtitled", "subtitulos", "subtitulado", "legenda", "legendas",
        "legendado", "softsub", "softsubs", "hardsub", "hardsubs", "vost", "vostfr", "cc"
    )
    private val subtitleEmoji = listOf("💬", "📝", "🔤", "📜") // 💬 📝 🔤 📜
    private val audioEmoji = listOf("🗣", "🔊", "🎧", "🎙", "🌐", "🌍", "🌎", "🌏") // 🗣 🔊 🎧 🎙 🌐 🌍 🌎 🌏
    private val audioWords = setOf("audio", "audios", "dub", "dubbed", "dual", "multi", "lang", "language", "languages", "langs")
    private val markerWords = setOf("dual", "multi", "dub", "dubbed", "dublado", "doblado", "doppiato", "vostfr", "vost", "subbed", "raw")

    private val cache = object : LinkedHashMap<String, StreamAudioTags>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, StreamAudioTags>?): Boolean = size > 2_000
    }

    private fun structuredTags(languages: List<String>?): StreamAudioTags {
        if (languages.isNullOrEmpty()) return StreamAudioTags()
        val found = mutableSetOf<AudioLanguage>()
        var multi = false
        var dual = false
        languages.forEach { value ->
            val lower = value.trim().lowercase()
            when {
                lower.startsWith("multi") -> multi = true
                lower.startsWith("dual") -> dual = true
                else -> (AudioLanguage.fromCode(lower) ?: nameWords[lower])?.let { found += it }
            }
        }
        return StreamAudioTags(languages = found, multi = multi, dual = dual)
    }

    internal fun parseText(
        text: String?,
        titleTokens: List<List<String>> = emptyList(),
        lenientCodes: Boolean = false
    ): StreamAudioTags {
        if (text.isNullOrBlank()) return StreamAudioTags()
        var result = StreamAudioTags()
        text.lineSequence().forEach { line -> result += parseLine(line, titleTokens, lenientCodes) }
        return result
    }

    private val ptBrRegex = Regex("(?i)(?<![a-z0-9])pt[-_. ]?br(?![a-z0-9])")
    private val latamRegex = Regex("(?i)(?<![a-z0-9])es[-_]?(419|la|mx)(?![a-z0-9])")

    private fun parseLine(rawLine: String, titleTokens: List<List<String>>, lenientCodes: Boolean): StreamAudioTags {
        val line = rawLine.replace(ptBrRegex, " PTBR ").replace(latamRegex, " LATINO ")
        val all = tokens(line)
        if (all.isEmpty()) return StreamAudioTags()
        val ignored = BooleanArray(all.size)
        // The title's own words ("Johnny English", "Dual", "The French Connection").
        titleTokens.forEach { words ->
            if (words.isEmpty()) return@forEach
            var i = 0
            while (i + words.size <= all.size) {
                if (words.indices.all { all[i + it].lower == words[it] }) {
                    for (j in words.indices) ignored[i + j] = true
                    i += words.size
                } else {
                    i++
                }
            }
        }
        val lower = line.lowercase()
        val audioLine = audioEmoji.any { it in line } || all.any { it.lower in audioWords }
        val subtitleLine = subtitleEmoji.any { it in line } ||
            all.withIndex().any { (i, t) -> !ignored[i] && isSubtitleMarker(t.lower) }
        if (subtitleLine && !audioLine && subtitleEmoji.any { it in line }) {
            // A line that lists subtitles: only "VOSTFR"-style words say anything about audio.
            return StreamAudioTags(originalAudio = all.any { it.lower == "vostfr" || it.lower == "vost" })
        }

        val languageAt = arrayOfNulls<AudioLanguage>(all.size)
        all.forEachIndexed { i, token ->
            if (ignored[i]) return@forEachIndexed
            languageAt[i] = languageOf(token, audioLine, lenientCodes)
        }
        // A release name ("BEN-HUR.1959.1080p", "THE.FIN.2023") puts its language tags after the year: a code before
        // it is a word of the title.
        val year = all.indexOfFirst { it.text.length == 4 && it.text.toIntOrNull()?.let { y -> y in 1900..2099 } == true }
        if (year > 0 && isReleaseName(rawLine)) {
            for (i in 0 until year) if (all[i].lower in codeWords || all[i].lower in shortCodes) languageAt[i] = null
        }
        // "English Subs", "Subs: ENG, FRE", "Sub ITA": the languages next to a subtitle word are subtitles.
        all.forEachIndexed { i, token ->
            if (ignored[i] || !isSubtitleMarker(token.lower)) return@forEachIndexed
            if (i > 0 && languageAt[i - 1] != null) languageAt[i - 1] = null
            var j = i + 1
            while (j < all.size && (languageAt[j] != null || all[j].lower in listConnectors)) {
                languageAt[j] = null
                j++
            }
        }

        val words = all.withIndex().filter { !ignored[it.index] }.map { it.value.lower }.toSet()
        // "MULTi-SUBS", "Dual Subs": several subtitle tracks, which says nothing about the audio.
        fun aboutSubtitles(i: Int): Boolean = i + 1 < all.size && !ignored[i + 1] && isSubtitleMarker(all[i + 1].lower)
        val dual = all.indices.any { i -> !ignored[i] && all[i].lower == "dual" && !aboutSubtitles(i) }
        val multi = all.indices.any { i ->
            !ignored[i] && all[i].lower.let { it == "multi" || it == "multiaudio" || it == "multilang" } && !aboutSubtitles(i)
        }
        val dubbed = words.any { it == "dub" || it == "dubbed" || it == "dublado" || it == "doblado" || it == "doppiato" }
        val original = words.any { it == "vostfr" || it == "vost" || it == "subbed" || it == "raw" } ||
            Regex("original[ ._-]?audio").containsMatchIn(lower)
        var languages = languageAt.filterNotNull().toSet()
        // "Spanish Latino" is the Latin American dub, not Castilian.
        if (AudioLanguage.LATIN_SPANISH in languages && AudioLanguage.SPANISH in languages &&
            words.none { it == "castellano" || it == "castilian" || it == "cast" } && all.none { it.isFlag && it.flagCountry() == "ES" }
        ) {
            languages = languages - AudioLanguage.SPANISH
        }
        // The Indian flag stands for Hindi unless a regional language is named.
        if (AudioLanguage.HINDI in languages && words.none { it == "hindi" || it == "hin" } &&
            languages.any { it in indianRegional }
        ) {
            languages = languages - AudioLanguage.HINDI
        }
        return StreamAudioTags(languages, dual = dual, multi = multi, dubbed = dubbed, originalAudio = original)
    }

    private val listConnectors = setOf("and", "y", "e", "et", "und")

    /** Words joined by dots or dashes rather than spaces, as scene releases are named. */
    private fun isReleaseName(line: String): Boolean {
        val name = line.trim()
        return name.isNotEmpty() && name.count { it == ' ' } <= 1 && name.count { it == '.' || it == '-' || it == '_' } >= 2
    }
    private val indianRegional = setOf(
        AudioLanguage.TAMIL, AudioLanguage.TELUGU, AudioLanguage.MALAYALAM, AudioLanguage.KANNADA, AudioLanguage.BENGALI
    )

    private fun isSubtitleMarker(lower: String): Boolean =
        lower in subtitleWords && lower != "vostfr" && lower != "vost" && lower != "subbed" ||
            lower.length in 4..9 && (lower.endsWith("sub") || lower.endsWith("subs")) // ESub, EngSub, MSubs

    private fun Token.flagCountry(): String {
        val first = text.codePointAt(0) - 0x1F1E6
        val second = text.codePointAt(Character.charCount(text.codePointAt(0))) - 0x1F1E6
        return "${'A' + first}${'A' + second}"
    }

    private fun languageOf(token: Token, audioLine: Boolean, lenientCodes: Boolean): AudioLanguage? {
        if (token.isFlag) return flagCountries[token.flagCountry()]
        nameWords[token.lower]?.let { return it }
        val capitals = token.text.count { it.isUpperCase() }
        // On a line about audio ("Dual Audio [Eng-Jap]") a code counts in any case.
        codeWords[token.lower]?.let { if (lenientCodes || audioLine || capitals >= 2) return it }
        if (token.lower.length == 2 && audioLine && capitals == 2) shortCodes[token.lower]?.let { return it }
        return null
    }
}

/** An audio track in the playing file, as the menu needs it. */
data class AudioTrackRef(
    val index: Int,
    val language: String?,
    val name: String?,
    val detail: String?
)

/** One row of the audio menu: a track in this file, or a language other sources have. */
data class AudioMenuOption(
    /** Null for audio that couldn't be named. */
    val language: AudioLanguage?,
    val isOriginal: Boolean,
    /** A track of the playing file; picking it stays on this source. */
    val trackIndex: Int? = null,
    val trackName: String? = null,
    val trackDetail: String? = null,
    /** Other sources with this audio. */
    val sourceCount: Int = 0,
    val isCurrent: Boolean = false
) {
    val isAvailable: Boolean get() = trackIndex != null || sourceCount > 0
    val key: String get() = "${language?.code ?: "unknown"}:${trackIndex ?: "sources"}"
}

object AudioLanguageMenu {

    /**
     * Rows for the menu: the playing file's tracks and, for each language chosen in settings that
     * the file lacks, how many other sources have it. Languages not chosen are left out, except the
     * track that's playing.
     */
    fun buildOptions(
        streams: List<Stream>,
        isCurrentStream: (Stream) -> Boolean,
        tracks: List<AudioTrackRef>,
        selectedTrackIndex: Int,
        original: AudioLanguage?,
        shownKeys: Set<String>,
        showUnknown: Boolean,
        titles: List<String?> = emptyList()
    ): List<AudioMenuOption> {
        val shownLanguages = shownKeys.mapNotNull { AudioLanguage.fromKey(it) }.toMutableSet()
        val showOriginal = AudioLanguage.ORIGINAL_KEY in shownKeys && original != null
        if (showOriginal) shownLanguages += original!!

        val current = streams.firstOrNull(isCurrentStream)
        val currentAudio = current?.let { StreamAudioParser.audio(it, original, titles) }
        val others = streams.filter { it.isInPlayerSwitchable() && !isCurrentStream(it) }
            .map { StreamAudioParser.audio(it, original, titles) }

        val rows = mutableListOf<AudioMenuOption>()
        val inFile = mutableSetOf<AudioLanguage?>()
        tracks.forEach { track ->
            val language = StreamAudioParser.trackLanguage(track.language, track.name)
                ?: if (tracks.size == 1) currentAudio?.takeIf { !it.unknown }?.languages?.singleOrNull() else null
            val selected = track.index == selectedTrackIndex
            val visible = selected || if (language == null) showUnknown else language in shownLanguages
            if (!visible) return@forEach
            inFile += language
            rows += AudioMenuOption(
                language = language,
                isOriginal = language != null && language == original,
                trackIndex = track.index,
                trackName = track.name,
                trackDetail = track.detail,
                isCurrent = selected
            )
        }
        shownLanguages.filter { it !in inFile }.forEach { language ->
            rows += AudioMenuOption(
                language = language,
                isOriginal = language == original,
                sourceCount = others.count { language in it.languages }
            )
        }
        if (showUnknown && null !in inFile) {
            val unknownCount = others.count { it.unknown && it.languages.isEmpty() }
            if (unknownCount > 0) rows += AudioMenuOption(language = null, isOriginal = false, sourceCount = unknownCount)
        }
        return rows.sortedWith(
            compareBy<AudioMenuOption> { row ->
                when {
                    row.isOriginal -> -1
                    row.language == null -> Int.MAX_VALUE
                    else -> row.language.ordinal
                }
            }.thenBy { it.trackIndex ?: Int.MAX_VALUE }
        )
    }

    /** Whether [stream] has [language] (null: audio that couldn't be named). */
    fun matches(stream: Stream, language: AudioLanguage?, original: AudioLanguage?, titles: List<String?> = emptyList()): Boolean {
        val audio = StreamAudioParser.audio(stream, original, titles)
        return if (language == null) audio.unknown && audio.languages.isEmpty() else language in audio.languages
    }

    /**
     * Sources with [language], best first: ones naming this episode, then the playing addon's
     * (same server), then tagged ones before those only assumed to be the original, then the
     * quality closest to [currentQuality], in addon order. Sources naming a different episode
     * are left out.
     */
    fun rankForLanguage(
        streams: List<Stream>,
        language: AudioLanguage?,
        original: AudioLanguage?,
        preferredAddon: String?,
        currentQuality: StreamQuality?,
        isCurrentStream: (Stream) -> Boolean,
        titles: List<String?> = emptyList(),
        season: Int? = null,
        episode: Int? = null
    ): List<Stream> {
        return streams.withIndex()
            .filter { (_, stream) ->
                stream.isInPlayerSwitchable() && !isCurrentStream(stream) && matches(stream, language, original, titles) &&
                    StreamEpisodeNames.matches(stream, season, episode) != false
            }
            .sortedBy { (index, stream) ->
                val namesEpisode = if (StreamEpisodeNames.matches(stream, season, episode) == true) 0 else 1
                val sameAddon = if (preferredAddon != null && stream.addonName == preferredAddon) 0 else 1
                val assumed = if (StreamAudioParser.audio(stream, original, titles).assumedOriginal) 1 else 0
                val quality = StreamFallbackSelector.qualityDistance(StreamQualityParser.parse(stream), currentQuality)
                // Each part outweighs everything after it: quality distance runs up to 100 (unknown), so 100 * 10_000.
                namesEpisode * 1_000_000_000L + sameAddon * 100_000_000L + assumed * 10_000_000L + quality * 10_000L + index
            }
            .map { it.value }
    }

    fun pickForLanguage(
        streams: List<Stream>,
        language: AudioLanguage?,
        original: AudioLanguage?,
        preferredAddon: String?,
        currentQuality: StreamQuality?,
        isCurrentStream: (Stream) -> Boolean,
        titles: List<String?> = emptyList(),
        season: Int? = null,
        episode: Int? = null
    ): Stream? = rankForLanguage(
        streams, language, original, preferredAddon, currentQuality, isCurrentStream, titles, season, episode
    ).firstOrNull()

    /**
     * Whether a source's length fits the one it replaces. Releases of one episode differ by a
     * minute or two (recaps, credits); a movie or a whole-season file offered for an episode
     * doesn't fit.
     */
    fun durationFits(expectedMs: Long, actualMs: Long): Boolean {
        if (expectedMs <= 0L || actualMs <= 0L) return true
        val tolerance = maxOf(3 * 60_000L, expectedMs * 15 / 100)
        return kotlin.math.abs(actualMs - expectedMs) <= tolerance
    }
}

/** The episodes a source's name or filename says it is (S01E02, 1x02, and runs: S01E01-E03, S01E01E02). */
object StreamEpisodeNames {
    // An optional second episode ends a run: "E03" after a dash or straight on, or a bare "-03".
    private val sxe = Regex(
        "(?i)(?<![a-z0-9])s(\\d{1,2})[ ._-]?e(\\d{1,4})" +
            "(?:[ ._]?-?[ ._]?e(\\d{1,4})(?![0-9])|-(\\d{1,3})(?![a-z0-9]))?(?![0-9])"
    )
    // Not after a digit or a dot, so "5.1x264" (audio channels and a codec) isn't season 1 episode 264.
    // Nor after a letter, so "AAC2x264" (a codec) isn't season 2 episode 264.
    private val nxn = Regex("(?i)(?<![0-9a-z.])(\\d{1,2})x(\\d{2,3})(?![0-9])")
    private val spelled = Regex("(?i)season[ ._-]?(\\d{1,2})[ ._,-]*(?:episode|ep)[ ._-]?(\\d{1,4})(?![0-9])")

    /** The longest run of episodes one file is taken to hold. */
    private const val MAX_RUN = 50

    fun named(stream: Stream): Set<Pair<Int, Int>> {
        val found = mutableSetOf<Pair<Int, Int>>()
        listOf(
            stream.name,
            stream.title,
            stream.description,
            stream.behaviorHints?.filename,
            stream.clientResolve?.filename,
            stream.clientResolve?.stream?.raw?.filename
        ).forEach { text ->
            if (text.isNullOrBlank()) return@forEach
            sxe.findAll(text).forEach { match ->
                val season = match.groupValues[1].toInt()
                val first = match.groupValues[2].toInt()
                val last = (match.groupValues[3].ifEmpty { match.groupValues[4] }).toIntOrNull()
                if (last != null && last > first && last - first <= MAX_RUN) {
                    for (episode in first..last) found += season to episode
                } else {
                    found += season to first
                }
            }
            nxn.findAll(text).forEach { found += it.groupValues[1].toInt() to it.groupValues[2].toInt() }
            spelled.findAll(text).forEach { found += it.groupValues[1].toInt() to it.groupValues[2].toInt() }
        }
        return found
    }

    /** True: names this episode. False: names only other episodes. Null: names none, or no episode playing. */
    fun matches(stream: Stream, season: Int?, episode: Int?): Boolean? {
        if (season == null || episode == null) return null
        val named = named(stream)
        if (named.isEmpty()) return null
        return (season to episode) in named
    }
}
