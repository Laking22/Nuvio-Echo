package com.nuvio.tv.core.home.smart

/**
 * Decides whether an AI-written Smart Highlight name may be shown. A name that fails falls back to the theme's own
 * name, so a bad name never reaches the screen. Checked:
 * - shape: 2 to 7 words, at most 34 characters, letters, digits and a little punctuation only (no emoji, quotes,
 *   hashtags or links);
 * - language: swear words and slurs, also when disguised ("m1nd f*ck", "sh!t", "f u c k");
 * - the user's own blocks, compared in [normalise]d form so case and spacing don't matter.
 */
object HighlightNameFilter {

    /**
     * Lower case, look-alike characters mapped to letters, everything else dropped: "M1nd F*ck" -> "mindfck".
     * "!" and "|" count as "i" only inside a word ("Sh!t"), so a name's closing "!" doesn't change it.
     */
    fun normalise(name: String): String {
        val lower = name.lowercase()
        return buildString {
            lower.forEachIndexed { i, c ->
                val mapped = when (c) {
                    '0' -> 'o'
                    '1' -> 'i'
                    '!', '|' -> if (lower.getOrNull(i + 1)?.isLetterOrDigit() == true) 'i' else c
                    '3' -> 'e'
                    '4', '@' -> 'a'
                    '5', '$' -> 's'
                    '7', '+' -> 't'
                    '8' -> 'b'
                    else -> c
                }
                if (mapped in 'a'..'z') append(mapped)
            }
        }
    }

    fun isAcceptable(name: String, blockedNames: Set<String> = emptySet()): Boolean {
        val trimmed = name.trim()
        if (trimmed.length !in 3..MAX_LENGTH) return false
        if (!SHAPE.matches(trimmed)) return false
        val words = trimmed.split(WHITESPACE).filter { it.isNotEmpty() }
        if (words.size !in 2..7) return false
        if (isOffensive(trimmed)) return false
        return normalise(trimmed) !in blockedNames
    }

    /** True for a name with a swear word or slur in it, however it's spelled or spaced. */
    fun isOffensive(name: String): Boolean {
        val words = name.lowercase().split(NON_WORD).map(::normalise).filter { it.isNotEmpty() }
        // Words are checked one by one, so two innocent words can't make a bad one across the gap
        // ("Short Watches"); letters spelled out one at a time ("f u c k") are joined back up first.
        val pieces = ArrayList<String>(words)
        var run = StringBuilder()
        for (word in words + "") {
            if (word.length == 1) run.append(word) else {
                if (run.length >= 3) pieces += run.toString()
                run = StringBuilder()
            }
        }
        return pieces.any { piece ->
            // Collapsed repeats catch "fuuuck" and "shiiit".
            val collapsed = piece.replace(REPEATS, "$1")
            ANYWHERE.any { it in piece || it in collapsed } ||
                WHOLE_WORDS.any { piece == it || piece == it + "s" } ||
                PREFIXES.any { piece.startsWith(it) }
        }
    }

    private const val MAX_LENGTH = 34
    private val SHAPE = Regex("""[\p{L}\p{N} '&:,.!?\-]+""")
    private val WHITESPACE = Regex("""\s+""")
    private val NON_WORD = Regex("""[\s\-:,.&]+""")
    private val REPEATS = Regex("""([a-z])\1+""")

    /** Never part of an innocent word, so they're caught anywhere, spaces and symbols removed. */
    private val ANYWHERE = listOf(
        "fuck", "fuk", "fck", "fuq", "phuck", "shit", "bitch", "biatch", "nigg", "whore", "slut", "pussy", "wanker",
        "twat", "motherf", "porn", "dildo", "jizz", "bollock", "faggot", "retard", "nazi", "tranny", "bastard",
        "arsehole", "asshole", "dickhead", "cocksuck", "blowjob", "handjob", "orgasm", "boner", "goddamn"
    )

    /**
     * Ones that hide inside innocent words ("class", "title", "scrape", "Scunthorpe", "suspicious", "thorny",
     * "swanky"), so matched as whole words only.
     */
    private val WHOLE_WORDS = listOf(
        "ass", "arse", "tit", "tits", "titty", "cum", "fag", "hoe", "sex", "sexy", "dick", "cock", "prick", "piss",
        "fk", "fs", "wtf", "stfu", "milf", "thot", "bs", "nude", "nudes", "rape", "raped", "boob", "boobs", "slag",
        "skank", "cunt", "sht", "spic", "chink", "kike", "niga", "horny", "wank"
    )

    /** Word starts that are never innocent. */
    private val PREFIXES = listOf("rapist", "raping", "pissed", "sexual", "erotic", "kinky", "naked")
}
