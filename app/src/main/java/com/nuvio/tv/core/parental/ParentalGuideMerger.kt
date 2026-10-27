package com.nuvio.tv.core.parental

import com.nuvio.tv.data.remote.api.AniListMedia
import com.nuvio.tv.data.remote.api.ImdbApiParentsGuideCategory

enum class ParentalCategory { NUDITY, VIOLENCE, PROFANITY, ALCOHOL, FRIGHTENING }

/**
 * How strongly a category applies. [PRESENT] means a source says it's there without saying how
 * much; [NONE] means a source with enough votes says it isn't, which stops weaker sources adding it.
 */
enum class ParentalLevel { NONE, PRESENT, MILD, MODERATE, SEVERE }

/** Specific content beyond the five categories, shown as one "Also" line. */
enum class ContentNote { GORE, SEXUAL_VIOLENCE, SUICIDE, SELF_HARM, TORTURE, ABUSE, CANNIBALISM, ANIMAL_HARM, BODY_HORROR }

/**
 * Sources, most detailed first:
 * IMDb's parents guide rates every category by thousands of votes; AniList ranks anime content
 * tags by relevance; TMDB has rating-board descriptors for some countries and content keywords;
 * OMDb only has the age rating.
 */
enum class ParentalSource { IMDB, ANILIST, TMDB, OMDB }

data class ParentalReport(
    val source: ParentalSource,
    val levels: Map<ParentalCategory, ParentalLevel> = emptyMap(),
    val notes: Set<ContentNote> = emptySet()
)

data class ParentalGuideSummary(
    val rating: String?,
    /** Present categories, most severe first. */
    val categories: List<Pair<ParentalCategory, ParentalLevel>>,
    val notes: List<ContentNote>
) {
    val isEmpty: Boolean get() = rating == null && categories.isEmpty() && notes.isEmpty()
}

object ParentalGuideMerger {

    /** IMDb "none" needs this many votes before it overrules other sources. */
    private const val IMDB_NONE_MIN_VOTES = 10
    private const val ANILIST_MIN_RANK = 40
    private const val MAX_NOTES = 4

    /**
     * Each category comes from the first source (in [reports] order) that has an opinion on it.
     * Notes are pooled from every source.
     */
    fun merge(rating: String?, reports: List<ParentalReport>): ParentalGuideSummary {
        val ordered = reports.sortedBy { it.source.ordinal }
        val categories = ParentalCategory.entries.mapNotNull { category ->
            val level = ordered.firstNotNullOfOrNull { it.levels[category] } ?: return@mapNotNull null
            if (level == ParentalLevel.NONE) null else category to level
        }.sortedByDescending { it.second.ordinal }
        val notes = ordered.flatMap { it.notes }.distinct().sortedBy { it.ordinal }.take(MAX_NOTES)
        return ParentalGuideSummary(
            rating = rating?.trim()?.takeIf { it.isNotEmpty() },
            categories = categories,
            notes = notes
        )
    }

    // ── IMDb ────────────────────────────────────────────────────────────────────────────────

    fun fromImdb(categories: List<ImdbApiParentsGuideCategory>): ParentalReport {
        val byName = categories.associateBy { it.category.uppercase() }
        val levels = buildMap {
            fun put(category: ParentalCategory, key: String) {
                imdbLevel(byName[key])?.let { put(category, it) }
            }
            put(ParentalCategory.NUDITY, "SEXUAL_CONTENT")
            put(ParentalCategory.VIOLENCE, "VIOLENCE")
            put(ParentalCategory.PROFANITY, "PROFANITY")
            put(ParentalCategory.ALCOHOL, "ALCOHOL_DRUGS")
            put(ParentalCategory.FRIGHTENING, "FRIGHTENING_INTENSE_SCENES")
        }
        return ParentalReport(ParentalSource.IMDB, levels)
    }

    /** The most-voted severity; "none" only counts when it wins with enough votes to trust. */
    private fun imdbLevel(category: ImdbApiParentsGuideCategory?): ParentalLevel? {
        val votes = category?.severityBreakdowns ?: return null
        val none = votes.firstOrNull { it.severityLevel.equals("none", true) }?.voteCount ?: 0
        val dominant = votes
            .filterNot { it.severityLevel.equals("none", true) }
            .maxByOrNull { it.voteCount }
        if (dominant == null || dominant.voteCount <= none) {
            return if (none >= IMDB_NONE_MIN_VOTES) ParentalLevel.NONE else null
        }
        return when (dominant.severityLevel.lowercase()) {
            "severe" -> ParentalLevel.SEVERE
            "moderate" -> ParentalLevel.MODERATE
            "mild" -> ParentalLevel.MILD
            else -> null
        }
    }

    // ── AniList ─────────────────────────────────────────────────────────────────────────────

    fun fromAniList(media: AniListMedia): ParentalReport {
        val levels = mutableMapOf<ParentalCategory, ParentalLevel>()
        val notes = mutableSetOf<ContentNote>()
        fun raise(category: ParentalCategory, level: ParentalLevel) {
            val current = levels[category]
            if (current == null || level.ordinal > current.ordinal) levels[category] = level
        }

        val genres = media.genres.orEmpty().map { it.lowercase() }
        if (media.isAdult == true) raise(ParentalCategory.NUDITY, ParentalLevel.SEVERE)
        if ("ecchi" in genres) raise(ParentalCategory.NUDITY, ParentalLevel.MODERATE)
        if ("horror" in genres) raise(ParentalCategory.FRIGHTENING, ParentalLevel.MODERATE)

        media.tags.orEmpty()
            .filter { it.isMediaSpoiler != true && (it.rank ?: 0) >= ANILIST_MIN_RANK }
            .forEach { tag ->
                val level = rankLevel(tag.rank ?: 0)
                when (tag.name.lowercase()) {
                    "nudity" -> raise(ParentalCategory.NUDITY, level)
                    "gore" -> {
                        raise(ParentalCategory.VIOLENCE, maxOf(level, ParentalLevel.MODERATE))
                        notes += ContentNote.GORE
                    }
                    "torture" -> {
                        raise(ParentalCategory.VIOLENCE, maxOf(level, ParentalLevel.MODERATE))
                        notes += ContentNote.TORTURE
                    }
                    "drugs" -> raise(ParentalCategory.ALCOHOL, level)
                    "body horror" -> {
                        raise(ParentalCategory.FRIGHTENING, level)
                        notes += ContentNote.BODY_HORROR
                    }
                    "cosmic horror" -> raise(ParentalCategory.FRIGHTENING, level)
                    "ero guro" -> {
                        raise(ParentalCategory.FRIGHTENING, ParentalLevel.SEVERE)
                        raise(ParentalCategory.VIOLENCE, ParentalLevel.SEVERE)
                    }
                    "suicide" -> notes += ContentNote.SUICIDE
                    "cannibalism" -> notes += ContentNote.CANNIBALISM
                    "rape" -> notes += ContentNote.SEXUAL_VIOLENCE
                }
            }
        return ParentalReport(ParentalSource.ANILIST, levels, notes)
    }

    /** AniList's rank is how central a tag is to the show, so it stands in for intensity. */
    private fun rankLevel(rank: Int): ParentalLevel = when {
        rank >= 80 -> ParentalLevel.SEVERE
        rank >= 60 -> ParentalLevel.MODERATE
        else -> ParentalLevel.MILD
    }

    // ── TMDB ────────────────────────────────────────────────────────────────────────────────

    /** Rating-board descriptors ("Violence", "Threat") and content keywords; neither carries a level. */
    fun fromTmdb(descriptors: List<String>, keywords: List<String>): ParentalReport {
        val levels = mutableMapOf<ParentalCategory, ParentalLevel>()
        val notes = mutableSetOf<ContentNote>()

        descriptors.map { it.lowercase() }.forEach { descriptor ->
            DESCRIPTOR_CATEGORIES.forEach { (words, category) ->
                if (words.any { it in descriptor }) levels[category] = ParentalLevel.PRESENT
            }
            if ("suicide" in descriptor) notes += ContentNote.SUICIDE
            if ("self-harm" in descriptor || "self harm" in descriptor) notes += ContentNote.SELF_HARM
        }
        keywords.map { it.lowercase().trim() }.forEach { keyword ->
            KEYWORD_CATEGORIES[keyword]?.let { levels[it] = ParentalLevel.PRESENT }
            KEYWORD_NOTES[keyword]?.let { notes += it }
        }
        return ParentalReport(ParentalSource.TMDB, levels, notes)
    }

    private val DESCRIPTOR_CATEGORIES: List<Pair<List<String>, ParentalCategory>> = listOf(
        listOf("violence", "injury", "fighting", "weapon", "blood") to ParentalCategory.VIOLENCE,
        listOf("sex", "nudity", "erotic") to ParentalCategory.NUDITY,
        listOf("language", "swearing", "profanity", "obscen") to ParentalCategory.PROFANITY,
        listOf("drug", "alcohol", "tobacco", "smoking", "substance") to ParentalCategory.ALCOHOL,
        listOf("threat", "fear", "horror", "frighten", "stressful", "scary", "disturbing") to ParentalCategory.FRIGHTENING
    )

    private val KEYWORD_CATEGORIES: Map<String, ParentalCategory> = buildMap {
        listOf("nudity", "female nudity", "male nudity", "full frontal nudity", "sex scene", "explicit sex", "sexual content")
            .forEach { put(it, ParentalCategory.NUDITY) }
        listOf("gore", "splatter", "graphic violence", "extreme violence", "brutality")
            .forEach { put(it, ParentalCategory.VIOLENCE) }
        listOf("drug use", "drug addiction", "drug abuse", "heroin", "cocaine", "alcoholism", "drunkenness", "hallucinogenic drug")
            .forEach { put(it, ParentalCategory.ALCOHOL) }
        listOf("jump scare", "body horror")
            .forEach { put(it, ParentalCategory.FRIGHTENING) }
    }

    private val KEYWORD_NOTES: Map<String, ContentNote> = buildMap {
        listOf("gore", "splatter").forEach { put(it, ContentNote.GORE) }
        listOf("rape", "sexual assault", "sexual abuse", "sexual violence", "rape and revenge").forEach { put(it, ContentNote.SEXUAL_VIOLENCE) }
        listOf("suicide", "suicide attempt").forEach { put(it, ContentNote.SUICIDE) }
        listOf("self harm", "self-harm", "self mutilation").forEach { put(it, ContentNote.SELF_HARM) }
        put("torture", ContentNote.TORTURE)
        listOf("child abuse", "domestic violence", "domestic abuse").forEach { put(it, ContentNote.ABUSE) }
        listOf("cannibalism", "cannibal").forEach { put(it, ContentNote.CANNIBALISM) }
        listOf("animal cruelty", "animal abuse").forEach { put(it, ContentNote.ANIMAL_HARM) }
        put("body horror", ContentNote.BODY_HORROR)
    }
}
