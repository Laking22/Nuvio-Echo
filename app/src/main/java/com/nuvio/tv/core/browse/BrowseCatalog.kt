package com.nuvio.tv.core.browse

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import com.nuvio.tv.R
import java.time.LocalDate

/** [ANIME] is Japanese animation, movies and shows, narrowed by whichever genre is picked. */
enum class BrowseMediaMode { MOVIES, SHOWS, ALL, ANIME }

enum class BrowseSort { POPULAR, LATEST, MOST_RATED }

/**
 * A TMDB keyword. Curated ones carry only a name and are looked up by it the first time they are
 * used; ones that come from a TMDB keyword search already know their [tmdbId].
 */
@Immutable
data class BrowseKeyword(
    val label: String,
    val tmdbName: String = label.lowercase(),
    val tmdbId: Int? = null
) {
    val key: String get() = tmdbId?.toString() ?: tmdbName
}

/**
 * How a genre maps onto TMDB discover for one media type. TMDB folds several movie genres into
 * combined TV ones (Action & Adventure, Sci-Fi & Fantasy, War & Politics) and has no TV genre at
 * all for some, which use a [keyword] instead.
 */
@Immutable
data class TmdbGenreFilter(
    val genreIds: String? = null,
    val originalLanguage: String? = null,
    val keyword: BrowseKeyword? = null
)

@Immutable
data class BrowseGenre(
    val id: String,
    @StringRes val labelRes: Int,
    val movie: TmdbGenreFilter?,
    val tv: TmdbGenreFilter?,
    /** Cinemeta genre name for the keyless fallback; null hides the genre there. */
    val cinemetaGenre: String?,
    val cinemetaSeriesOnly: Boolean = false,
    val keywords: List<BrowseKeyword>,
    /**
     * Kitsu category for anime when TMDB has too little: "" for all anime, null when Kitsu has no
     * matching category.
     */
    val kitsuCategory: String? = null,
    /** False where neither TMDB nor Kitsu has any anime, which hides the genre in Anime mode. */
    val hasAnime: Boolean = true
)

/** What a browse grid shows. */
@Immutable
data class BrowseRequest(
    val mode: BrowseMediaMode = BrowseMediaMode.ALL,
    val genre: BrowseGenre? = null,
    val sort: BrowseSort = BrowseSort.POPULAR,
    val keyword: BrowseKeyword? = null,
    val trending: Boolean = false
) {
    val key: String
        get() = buildString {
            append(mode.name)
            append('|')
            append(if (trending) "trending" else genre?.id.orEmpty())
            append('|')
            append(sort.name)
            append('|')
            append(keyword?.key.orEmpty())
        }
}

object BrowseCatalog {

    /**
     * TMDB ids for every curated keyword, looked up once while building this list so picking one
     * costs a single discover request. Declared first: the lists below read it while initialising.
     */
    private val knownKeywordIds: Map<String, Int> = mapOf(
        "19th Century" to 207928,
        "Action Hero" to 219404,
        "Addiction" to 6782,
        "Alien" to 9951,
        "Amnesia" to 1453,
        "Ancient Greece" to 162861,
        "Ancient Rome" to 5049,
        "Animals" to 18165,
        "Anthropomorphism" to 11477,
        "Apache Nation" to 4616,
        "Artificial Intelligence" to 310,
        "Assassin" to 782,
        "Band" to 311916,
        "Based on Comic" to 9717,
        "Based on Manga" to 13141,
        "Based on True Story" to 9672,
        "Battle" to 14643,
        "Biography" to 5565,
        "Buddy Comedy" to 167541,
        "Car Crash" to 9844,
        "Cartoon" to 6513,
        "Celebrity" to 6562,
        "Christmas" to 207317,
        "Civil War" to 393,
        "Clone" to 402,
        "Cold War" to 2106,
        "Coming of Age" to 10683,
        "Competition" to 271,
        "Concert" to 6029,
        "Conspiracy" to 10410,
        "Cooking" to 1918,
        "Corruption" to 417,
        "Courtroom" to 33519,
        "Cyberpunk" to 12190,
        "Dance" to 1691,
        "Dark Comedy" to 10123,
        "Dating" to 13043,
        "Demon" to 15001,
        "Desert" to 18034,
        "Detective" to 703,
        "Dog" to 15162,
        "Dragon" to 12554,
        "Drug Cartel" to 10175,
        "Dystopia" to 4565,
        "Elves" to 603,
        "Enemies to Lovers" to 282986,
        "Espionage" to 5265,
        "Exorcism" to 2626,
        "Fairy Tale" to 3205,
        "Family Relationships" to 10235,
        "Fashion" to 15479,
        "First Love" to 157303,
        "Food" to 10637,
        "Forbidden Love" to 3691,
        "Found Footage" to 163053,
        "Friends to Lovers" to 282988,
        "Friendship" to 6054,
        "Game Show" to 4325,
        "Gangster" to 3149,
        "Ghost" to 162846,
        "Gunfight" to 12371,
        "Gunslinger" to 75,
        "Hacker" to 2157,
        "Haunted House" to 3358,
        "Heist" to 10051,
        "High School" to 6270,
        "Hip Hop" to 898,
        "Historical Figure" to 6165,
        "Hitman" to 2708,
        "Holiday" to 65,
        "Holocaust" to 1739,
        "Home Renovation" to 182804,
        "Horror" to 315058,
        "Horse" to 2673,
        "Hospital" to 11612,
        "Hostage" to 1562,
        "Investigation" to 5340,
        "Investigative Journalism" to 195240,
        "Isekai" to 237451,
        "Island" to 2041,
        "Jazz" to 1416,
        "Jungle" to 10787,
        "Kidnapping" to 1930,
        "Love" to 9673,
        "Love Triangle" to 128,
        "Love at First Sight" to 699,
        "Love of One's Life" to 2038,
        "Mafia" to 10391,
        "Magic" to 2343,
        "Makeover" to 34130,
        "Martial Arts" to 779,
        "Mecha" to 10046,
        "Medieval" to 161257,
        "Military" to 162365,
        "Missing Person" to 156091,
        "Mockumentary" to 11800,
        "Monster" to 1299,
        "Murder" to 9826,
        "Music" to 283297,
        "Musical" to 4344,
        "Musician" to 4048,
        "Mythology" to 2035,
        "Native American" to 10322,
        "Nature" to 18330,
        "Navy" to 3588,
        "Nazi" to 2652,
        "Opera" to 2199,
        "Organized Crime" to 10291,
        "Parallel World" to 33465,
        "Parody" to 9755,
        "Period Drama" to 15060,
        "Police" to 6149,
        "Political Thriller" to 209817,
        "Politics" to 6078,
        "Possession" to 9712,
        "Post-apocalyptic Future" to 4458,
        "Princess" to 7376,
        "Prison" to 378,
        "Prisoner of War" to 1968,
        "Psychological Horror" to 295907,
        "Psychological Thriller" to 12565,
        "Puzzle" to 9228,
        "Quest" to 207372,
        "Resistance" to 836,
        "Revenge" to 9748,
        "Road Trip" to 7312,
        "Robbery" to 642,
        "Robot" to 14544,
        "Rock" to 281554,
        "Romance" to 9840,
        "Romantic Comedy" to 9799,
        "Royalty" to 9920,
        "Saloon" to 1582,
        "Samurai" to 1462,
        "Satire" to 8201,
        "School" to 10873,
        "Second Chance" to 34004,
        "Secret" to 295809,
        "Serial Killer" to 10714,
        "Sheriff" to 798,
        "Shootout" to 10950,
        "Shounen" to 207826,
        "Singer" to 10229,
        "Sitcom" to 193171,
        "Slapstick" to 9253,
        "Slasher" to 12339,
        "Small Town" to 1415,
        "Sniper" to 1589,
        "Soldier" to 13065,
        "Space" to 9882,
        "Space Opera" to 161176,
        "Spoof" to 11931,
        "Sports" to 6075,
        "Spy" to 470,
        "Stalker" to 6844,
        "Stand-up Comedy" to 9716,
        "Super Power" to 33637,
        "Superhero" to 9715,
        "Supernatural" to 6152,
        "Supernatural Horror" to 256183,
        "Survival" to 10349,
        "Sword and Sorcery" to 234213,
        "Talent Show" to 8290,
        "Talking Animal" to 267848,
        "Teen Comedy" to 155722,
        "Thriller" to 316362,
        "Time Travel" to 4379,
        "Toy" to 265589,
        "Travel" to 9935,
        "True Crime" to 33722,
        "Unrequited Love" to 10048,
        "Vampire" to 3133,
        "Vietnam War" to 2957,
        "Vigilante" to 7002,
        "Villain" to 3289,
        "Virus" to 188957,
        "War" to 273967,
        "War Veteran" to 1543,
        "Wedding" to 13027,
        "Whodunit" to 12570,
        "Wild West" to 155573,
        "Wilderness" to 3593,
        "Wildlife" to 9902,
        "Witch" to 616,
        "Wizard" to 177912,
        "World War I" to 2504,
        "World War II" to 1956,
        "Young Love" to 165100,
        "Zombie" to 12377
    )

    /** Kitsu category slugs (checked against Kitsu) for each genre; missing means Kitsu has none. */
    private val kitsuCategories: Map<String, String> = mapOf(
        "action" to "action",
        "adventure" to "adventure",
        "animation" to "",
        "comedy" to "comedy",
        "crime" to "crime",
        "drama" to "drama",
        "family" to "kids",
        "fantasy" to "fantasy",
        "history" to "historical",
        "horror" to "horror",
        "music" to "music",
        "mystery" to "mystery",
        "romance" to "romance",
        "scifi" to "science-fiction",
        "thriller" to "thriller",
        "war" to "military"
    )

    /** Genres with no anime anywhere. */
    private val genresWithoutAnime = setOf("reality")

    private fun genre(
        id: String,
        @StringRes labelRes: Int,
        movie: TmdbGenreFilter?,
        tv: TmdbGenreFilter?,
        cinemeta: String?,
        vararg keywords: String,
        cinemetaSeriesOnly: Boolean = false
    ) = BrowseGenre(
        id = id,
        labelRes = labelRes,
        movie = movie,
        tv = tv,
        cinemetaGenre = cinemeta,
        cinemetaSeriesOnly = cinemetaSeriesOnly,
        keywords = keywords.map(::keyword),
        kitsuCategory = kitsuCategories[id],
        hasAnime = id !in genresWithoutAnime
    )

    private fun ids(value: String) = TmdbGenreFilter(genreIds = value)

    /** Display name to TMDB keyword, for the few whose TMDB name differs from the label. */
    private fun keyword(label: String): BrowseKeyword {
        val tmdbName = when (label) {
            "Artificial Intelligence" -> "artificial intelligence (a.i.)"
            "Holocaust" -> "holocaust (shoah)"
            "Hip Hop" -> "hip-hop"
            "Romantic Comedy" -> "romcom"
            "Slapstick" -> "slapstick comedy"
            else -> label.lowercase()
        }
        return BrowseKeyword(label, tmdbName, knownKeywordIds[label])
    }

    val genres: List<BrowseGenre> = listOf(
        genre(
            "action", R.string.browse_genre_action, ids("28"), ids("10759"), "Action",
            "Post-apocalyptic Future", "Conspiracy", "Kidnapping", "Spy", "Organized Crime", "Soldier",
            "Vigilante", "Robbery", "Villain", "Super Power", "Martial Arts", "Survival"
        ),
        genre(
            "adventure", R.string.browse_genre_adventure, ids("12"), ids("10759"), "Adventure",
            "Animals", "Jungle", "Dog", "Survival", "Monster", "Island", "Super Power", "Cartoon",
            "Action Hero", "Magic", "19th Century", "Villain"
        ),
        genre(
            "animation", R.string.browse_genre_animation, ids("16"), ids("16"), "Animation",
            "Anthropomorphism", "Cartoon", "Superhero", "Magic", "Based on Comic", "Dragon",
            "Friendship", "Talking Animal", "Fairy Tale", "Musical", "Princess", "Toy"
        ),
        genre(
            "comedy", R.string.browse_genre_comedy, ids("35"), ids("35"), "Comedy",
            "Parody", "Sitcom", "Dark Comedy", "Romantic Comedy", "Spoof", "Satire", "Mockumentary",
            "Stand-up Comedy", "Buddy Comedy", "Road Trip", "Teen Comedy", "Slapstick"
        ),
        genre(
            "crime", R.string.browse_genre_crime, ids("80"), ids("80"), "Crime",
            "Detective", "Serial Killer", "Murder", "Heist", "Police", "Gangster", "Drug Cartel",
            "Mafia", "Investigation", "Prison", "Hitman", "Corruption"
        ),
        genre(
            "documentary", R.string.browse_genre_documentary, ids("99"), ids("99"), "Documentary",
            "Nature", "True Crime", "Biography", "Music", "Sports", "Wildlife", "War", "Space", "Food",
            "Politics", "Concert", "Investigative Journalism"
        ),
        genre(
            "drama", R.string.browse_genre_drama, ids("18"), ids("18"), "Drama",
            "Based on True Story", "Biography", "Family Relationships", "Coming of Age", "Addiction",
            "Friendship", "Love", "Politics", "Sports", "Period Drama", "Courtroom", "Hospital"
        ),
        genre(
            "family", R.string.browse_genre_family, ids("10751"), ids("10751"), "Family",
            "Christmas", "Dog", "Talking Animal", "Holiday", "Magic", "Fairy Tale", "Princess",
            "Coming of Age", "Friendship", "School", "Superhero", "Toy"
        ),
        genre(
            "fantasy", R.string.browse_genre_fantasy, ids("14"), ids("10765"), "Fantasy",
            "Magic", "Dragon", "Sword and Sorcery", "Witch", "Mythology", "Fairy Tale", "Wizard",
            "Vampire", "Time Travel", "Parallel World", "Elves", "Quest"
        ),
        genre(
            "history", R.string.browse_genre_history, ids("36"), TmdbGenreFilter(keyword = keyword("Period Drama")), "History",
            "World War II", "Ancient Rome", "Medieval", "Biography", "Based on True Story",
            "Historical Figure", "19th Century", "Royalty", "Civil War", "Cold War", "Holocaust",
            "Ancient Greece"
        ),
        genre(
            "horror", R.string.browse_genre_horror, ids("27"), TmdbGenreFilter(keyword = keyword("Horror")), "Horror",
            "Supernatural Horror", "Slasher", "Zombie", "Haunted House", "Possession", "Vampire",
            "Ghost", "Demon", "Found Footage", "Exorcism", "Monster", "Psychological Horror"
        ),
        genre(
            "music", R.string.browse_genre_music, ids("10402"), TmdbGenreFilter(keyword = keyword("Music")), null,
            "Concert", "Musical", "Biography", "Rock", "Hip Hop", "Band", "Singer", "Dance", "Jazz",
            "Opera", "Musician", "Talent Show"
        ),
        genre(
            "mystery", R.string.browse_genre_mystery, ids("9648"), ids("9648"), "Mystery",
            "Detective", "Whodunit", "Murder", "Investigation", "Missing Person", "Conspiracy",
            "Psychological Thriller", "Small Town", "Secret", "Amnesia", "Puzzle", "Supernatural"
        ),
        genre(
            "reality", R.string.browse_genre_reality, null, ids("10764"), "Reality-TV",
            "Competition", "Cooking", "Dating", "Talent Show", "Survival", "Game Show", "Makeover",
            "Home Renovation", "Travel", "Celebrity", "Fashion", "Wedding",
            cinemetaSeriesOnly = true
        ),
        genre(
            "romance", R.string.browse_genre_romance, ids("10749"), TmdbGenreFilter(keyword = keyword("Romance")), "Romance",
            "Love Triangle", "Forbidden Love", "First Love", "Romantic Comedy", "Wedding",
            "Love at First Sight", "Friends to Lovers", "Enemies to Lovers", "Second Chance",
            "Young Love", "Love of One's Life", "Unrequited Love"
        ),
        genre(
            "scifi", R.string.browse_genre_scifi, ids("878"), ids("10765"), "Sci-Fi",
            "Space", "Time Travel", "Alien", "Artificial Intelligence", "Dystopia", "Robot",
            "Cyberpunk", "Post-apocalyptic Future", "Parallel World", "Space Opera", "Virus", "Clone"
        ),
        genre(
            "thriller", R.string.browse_genre_thriller, ids("53"), TmdbGenreFilter(keyword = keyword("Thriller")), "Thriller",
            "Psychological Thriller", "Conspiracy", "Kidnapping", "Revenge", "Serial Killer", "Hostage",
            "Espionage", "Stalker", "Survival", "Hacker", "Assassin", "Political Thriller"
        ),
        genre(
            "war", R.string.browse_genre_war, ids("10752"), ids("10768"), "War",
            "World War II", "Vietnam War", "World War I", "Soldier", "Military", "Battle", "Nazi",
            "Resistance", "War Veteran", "Prisoner of War", "Navy", "Sniper"
        ),
        genre(
            "western", R.string.browse_genre_western, ids("37"), ids("37"), "Western",
            "Horse", "Sheriff", "19th Century", "Gunfight", "Native American", "Revenge", "Gunslinger",
            "Shootout", "Apache Nation", "Wild West", "Saloon", "Desert"
        )
    )

    /** Keywords offered under the search keyboard before anything is typed. */
    private val suggestedKeywordPool: List<BrowseKeyword> = listOf(
        "Island", "Virus", "War Veteran", "Supernatural Horror", "Car Crash", "Celebrity", "Wilderness",
        "Love of One's Life", "Time Travel", "Heist", "Zombie", "Serial Killer", "Dystopia", "Space",
        "Haunted House", "Road Trip", "Spy", "Revenge", "Alien", "Dragon", "Coming of Age", "Survival",
        "Conspiracy", "Superhero", "Mafia", "Vampire", "Martial Arts", "Post-apocalyptic Future",
        "Artificial Intelligence", "Small Town", "Kidnapping", "Parallel World"
    ).map(::keyword)

    /** Eight keywords that change once a day, so the list feels fresh without shuffling on every visit. */
    fun suggestedKeywords(today: LocalDate = LocalDate.now(), count: Int = 8): List<BrowseKeyword> =
        suggestedKeywordPool.shuffled(java.util.Random(today.toEpochDay())).take(count)
}
