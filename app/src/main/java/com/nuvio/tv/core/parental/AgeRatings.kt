package com.nuvio.tv.core.parental

/**
 * Minimum viewer age for an age rating ("PG-13" → "13+", "G" → "0+"), shown next to the rating
 * in the player's parental guide. Ratings come from many countries' boards, so letter ratings are
 * looked up and numeric ones ("16", "K-16", "MA15+", "+13") use their number. Ambiguous ratings
 * (Mexico's "A" means all ages, India's "A" adults only) get no age.
 */
object AgeRatings {

    private val LETTER_AGES: Map<String, Int> = mapOf(
        // US film and TV
        "G" to 0, "PG" to 8, "PG-13" to 13, "R" to 17, "NC-17" to 18, "X" to 18,
        "TV-Y" to 0, "TV-G" to 0, "TV-Y7" to 7, "TV-Y7-FV" to 7, "TV-PG" to 8, "TV-14" to 14, "TV-MA" to 17,
        // UK, Ireland, Australia, Canada, New Zealand
        "U" to 0, "UC" to 0, "12A" to 12, "R18" to 18, "R18+" to 18,
        "M" to 15, "MA15+" to 15, "14A" to 14, "18A" to 18, "C" to 0, "C8" to 8,
        // All-ages labels elsewhere
        "ALL" to 0, "TP" to 0, "AL" to 0, "L" to 0, "ATP" to 0, "AA" to 0, "T" to 0, "BTL" to 0, "S" to 0
    )

    private val NUMBER = Regex("\\d{1,2}")

    fun minimumAge(rating: String?): Int? {
        val normalized = rating?.trim()?.uppercase()?.replace(" ", "")?.takeIf { it.isNotEmpty() } ?: return null
        LETTER_AGES[normalized]?.let { return it }
        return NUMBER.find(normalized)?.value?.toIntOrNull()?.takeIf { it in 0..21 }
    }

    /** "13+", or null when the rating's age can't be told. */
    fun label(rating: String?): String? = minimumAge(rating)?.let { "$it+" }

    /** True when the rating already is the age ("16"), so showing "16+" beside it would repeat it. */
    fun isNumericOnly(rating: String?): Boolean =
        rating?.trim()?.let { it.isNotEmpty() && it.all(Char::isDigit) } == true
}
