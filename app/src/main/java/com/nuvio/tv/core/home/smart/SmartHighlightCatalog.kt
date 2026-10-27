package com.nuvio.tv.core.home.smart

/**
 * One TMDB discover query behind a Smart Highlight, for movies or for shows.
 * [genres] and [keywords] use TMDB's syntax: "," means all of, "|" means any of. Keyword ids were read from
 * themoviedb.org's keyword pages (2026-10-13), the most-used id where a word has several.
 */
data class ThemeQuery(
    val genres: String? = null,
    val withoutGenres: String? = null,
    val keywords: String? = null,
    /** Minutes; for shows TMDB compares the episode runtime. */
    val runtimeMin: Int? = null,
    val runtimeMax: Int? = null,
    val minVotes: Int = 150,
    val maxVotes: Int? = null,
    val minRating: Double? = 6.0,
    /** yyyy-MM-dd: only titles released before this day. */
    val releasedBefore: String? = null,
    val sort: String = "popularity.desc"
)

/**
 * A Smart Highlight: a themed row with a name the app owns. Gemini may check which titles fit and, with
 * "AI-written names" on, suggest a different name, but it can't invent a theme or show a name the filter rejects.
 */
data class HighlightTheme(
    val id: String,
    val title: String,
    /** What the theme means, for Gemini's fit check. */
    val definition: String,
    val movie: ThemeQuery?,
    val tv: ThemeQuery?,
    /** TMDB genre ids (movie and TV) that make this theme more likely for someone who watches them. */
    val affinityGenres: Set<Int> = emptySet()
)

/** The built-in Smart Highlights. Ids are stored when a user blocks one, so they never change. */
object SmartHighlightCatalog {

    // TMDB genre ids. Movies and shows share some (Animation, Comedy, Crime, Drama, Mystery...).
    private const val ACTION = 28
    private const val ANIMATION = 16
    private const val COMEDY = 35
    private const val CRIME = 80
    private const val DRAMA = 18
    private const val FAMILY = 10751
    private const val HORROR = 27
    private const val MYSTERY = 9648
    private const val ROMANCE = 10749
    private const val SCI_FI = 878
    private const val THRILLER = 53
    private const val TV_ACTION = 10759
    private const val TV_SCI_FI = 10765

    val themes: List<HighlightTheme> = listOf(
        // ---- Asked for by name ----
        HighlightTheme(
            "butler_did_it", "The Butler Did It",
            "Whodunits and murder mysteries where the audience tries to guess the killer.",
            movie = ThemeQuery(genres = "$MYSTERY", keywords = "12570|207046"),
            tv = ThemeQuery(keywords = "12570|207046", minVotes = 50),
            affinityGenres = setOf(MYSTERY, CRIME)
        ),
        HighlightTheme(
            "tear_jerker", "Tear Jerker",
            "Sad, moving dramas built to make you cry.",
            movie = ThemeQuery(genres = "$DRAMA", keywords = "156924|10614", minRating = 6.5),
            tv = ThemeQuery(genres = "$DRAMA", keywords = "156924|10614", minVotes = 50),
            affinityGenres = setOf(DRAMA, ROMANCE)
        ),
        HighlightTheme(
            "grab_the_tissues", "Grab the Tissues",
            "Stories of illness, loss and goodbyes, often bittersweet love stories.",
            movie = ThemeQuery(genres = "$DRAMA", keywords = "6564|241364|278730", minRating = 6.5),
            tv = ThemeQuery(genres = "$DRAMA", keywords = "6564|241364|278730", minVotes = 50),
            affinityGenres = setOf(DRAMA, ROMANCE)
        ),
        HighlightTheme(
            "back_to_back_action", "Back-to-Back Action",
            "Non-stop action: fights, shootouts and set pieces one after another.",
            movie = ThemeQuery(genres = "$ACTION", keywords = "779|10950", minRating = 6.3, minVotes = 500),
            tv = ThemeQuery(genres = "$TV_ACTION", minRating = 7.0, minVotes = 300),
            affinityGenres = setOf(ACTION, TV_ACTION, THRILLER)
        ),
        HighlightTheme(
            "short_watches", "Short Watches",
            "Good films under about an hour and a half, and shows with short episodes.",
            movie = ThemeQuery(runtimeMin = 60, runtimeMax = 95, minRating = 6.5, minVotes = 300),
            tv = ThemeQuery(runtimeMax = 25, minRating = 7.0, minVotes = 200)
        ),
        HighlightTheme(
            "back_in_blood", "Back in Blood: Revenge",
            "Revenge stories: someone wronged comes back to settle the score.",
            movie = ThemeQuery(genres = "$ACTION|$THRILLER|$CRIME", keywords = "9748|232766", minVotes = 300),
            tv = ThemeQuery(keywords = "9748|232766", minVotes = 100),
            affinityGenres = setOf(ACTION, THRILLER, CRIME, TV_ACTION)
        ),
        HighlightTheme(
            "zero_to_hero", "Zero to Hero",
            "Underdogs and nobodies who rise to become heroes or champions.",
            movie = ThemeQuery(keywords = "240|5626|4238|242214", minVotes = 200),
            tv = ThemeQuery(keywords = "240|5626|4238|242214", minVotes = 50)
        ),
        HighlightTheme(
            "hero_to_zero", "Hero to Zero",
            "Someone at the top who falls from grace: rise-and-fall stories and downfalls.",
            movie = ThemeQuery(keywords = "199660|6974|12413", minVotes = 100),
            tv = ThemeQuery(keywords = "199660|6974|12413", minVotes = 30),
            affinityGenres = setOf(DRAMA, CRIME)
        ),
        HighlightTheme(
            "enemies_to_lovers", "Enemies to Lovers",
            "Romances where two people who can't stand each other fall in love.",
            movie = ThemeQuery(genres = "$ROMANCE", keywords = "282986|282985|4516", minVotes = 50),
            tv = ThemeQuery(keywords = "282986|282985|4516", minVotes = 30),
            affinityGenres = setOf(ROMANCE, COMEDY)
        ),
        HighlightTheme(
            "love_triangle", "Love Triangle",
            "Romances where someone is torn between two people.",
            movie = ThemeQuery(genres = "$ROMANCE", keywords = "128", minVotes = 200),
            tv = ThemeQuery(keywords = "128", minVotes = 100),
            affinityGenres = setOf(ROMANCE, DRAMA)
        ),

        // ---- Twenty more ----
        HighlightTheme(
            "heist_night", "Heist Night",
            "Robberies and capers: a crew, a plan and a big score.",
            movie = ThemeQuery(keywords = "10051", minVotes = 300),
            tv = ThemeQuery(keywords = "10051", minVotes = 50),
            affinityGenres = setOf(CRIME, THRILLER, ACTION)
        ),
        HighlightTheme(
            "didnt_see_that_coming", "Didn't See That Coming",
            "Stories famous for a big plot twist.",
            movie = ThemeQuery(keywords = "275311", minVotes = 100, minRating = 6.5),
            tv = ThemeQuery(keywords = "275311", minVotes = 30),
            affinityGenres = setOf(THRILLER, MYSTERY)
        ),
        HighlightTheme(
            "feel_good", "Feel-Good Picks",
            "Warm, uplifting stories that leave you in a good mood.",
            movie = ThemeQuery(keywords = "275276", withoutGenres = "$HORROR", minVotes = 100),
            tv = ThemeQuery(keywords = "275276", minVotes = 30),
            affinityGenres = setOf(COMEDY, FAMILY, ROMANCE)
        ),
        HighlightTheme(
            "mind_benders", "Mind Benders",
            "Puzzling psychological stories that play with time, reality or memory.",
            movie = ThemeQuery(keywords = "12565|157171|243230", minRating = 6.8, minVotes = 500),
            tv = ThemeQuery(keywords = "12565|157171|243230", minRating = 7.0, minVotes = 100),
            affinityGenres = setOf(THRILLER, SCI_FI, MYSTERY, TV_SCI_FI)
        ),
        HighlightTheme(
            "time_loop", "Stuck in a Time Loop",
            "Characters reliving the same day or moment over and over.",
            movie = ThemeQuery(keywords = "10854", minVotes = 100),
            tv = ThemeQuery(keywords = "10854", minVotes = 30),
            affinityGenres = setOf(SCI_FI, TV_SCI_FI, COMEDY)
        ),
        HighlightTheme(
            "edge_of_your_seat", "Edge of Your Seat",
            "Tense, highly rated thrillers full of suspense.",
            movie = ThemeQuery(genres = "$THRILLER", minRating = 7.0, minVotes = 1500),
            tv = ThemeQuery(keywords = "288394", minRating = 7.5, minVotes = 200),
            affinityGenres = setOf(THRILLER, CRIME, MYSTERY)
        ),
        HighlightTheme(
            "laugh_out_loud", "Laugh Out Loud",
            "Out-and-out comedies, light on drama.",
            movie = ThemeQuery(genres = "$COMEDY", withoutGenres = "$DRAMA,$HORROR", minRating = 6.8, minVotes = 800),
            tv = ThemeQuery(genres = "$COMEDY", withoutGenres = "$DRAMA", minRating = 7.5, minVotes = 300),
            affinityGenres = setOf(COMEDY)
        ),
        HighlightTheme(
            "true_story", "Based on a True Story",
            "Films and shows based on real events or real people.",
            movie = ThemeQuery(keywords = "9672", minRating = 6.8, minVotes = 500),
            tv = ThemeQuery(keywords = "9672", minRating = 7.0, minVotes = 100),
            affinityGenres = setOf(DRAMA, 36)
        ),
        HighlightTheme(
            "coming_of_age", "Coming of Age",
            "Growing-up stories: first loves, friendships and finding yourself.",
            movie = ThemeQuery(keywords = "10683", minRating = 6.8, minVotes = 500),
            tv = ThemeQuery(keywords = "10683", minRating = 7.0, minVotes = 100),
            affinityGenres = setOf(DRAMA, COMEDY)
        ),
        HighlightTheme(
            "survival_mode", "Survival Mode",
            "Stranded, lost or trapped: people fighting to stay alive.",
            movie = ThemeQuery(keywords = "10349|3593|249530", minRating = 6.3, minVotes = 500),
            tv = ThemeQuery(keywords = "10349|3593|249530", minVotes = 100),
            affinityGenres = setOf(THRILLER, ACTION, 12)
        ),
        HighlightTheme(
            "lost_in_space", "Lost in Space",
            "Stories set among the stars: spaceships, space travel and other worlds.",
            movie = ThemeQuery(genres = "$SCI_FI", keywords = "252634|3801|252937", minVotes = 300),
            tv = ThemeQuery(genres = "$TV_SCI_FI", keywords = "252634|3801|252937", minVotes = 50),
            affinityGenres = setOf(SCI_FI, TV_SCI_FI)
        ),
        HighlightTheme(
            "road_trip", "Road Trip",
            "Journeys on the open road, where getting there is the story.",
            movie = ThemeQuery(keywords = "7312", minRating = 6.3, minVotes = 300),
            tv = ThemeQuery(keywords = "7312", minVotes = 50),
            affinityGenres = setOf(COMEDY, DRAMA, 12)
        ),
        HighlightTheme(
            "bump_in_the_night", "Things That Go Bump in the Night",
            "Ghosts, hauntings and the supernatural.",
            movie = ThemeQuery(genres = "$HORROR", keywords = "3358|6152", minRating = 6.0, minVotes = 500),
            tv = ThemeQuery(keywords = "3358|6152", minRating = 7.0, minVotes = 200),
            affinityGenres = setOf(HORROR, TV_SCI_FI)
        ),
        HighlightTheme(
            "grown_up_animation", "Animation for Grown-Ups",
            "Animated films and series made for adults rather than kids.",
            movie = ThemeQuery(genres = "$ANIMATION", keywords = "161919", minVotes = 100),
            tv = ThemeQuery(genres = "$ANIMATION", keywords = "161919", minVotes = 100),
            affinityGenres = setOf(ANIMATION, COMEDY)
        ),
        HighlightTheme(
            "binge_weekend", "Binge It This Weekend",
            "Limited series and miniseries you can finish in a weekend.",
            movie = null,
            tv = ThemeQuery(keywords = "11162", minRating = 7.3, minVotes = 200),
            affinityGenres = setOf(DRAMA, CRIME)
        ),
        HighlightTheme(
            "timeless_classics", "Timeless Classics",
            "Highly rated films from before the mid-90s that still hold up.",
            movie = ThemeQuery(minRating = 7.6, minVotes = 3000, releasedBefore = "1995-01-01"),
            tv = null
        ),
        HighlightTheme(
            "hidden_gems", "Hidden Gems",
            "Very well rated titles that not many people have seen.",
            movie = ThemeQuery(minRating = 7.3, minVotes = 150, maxVotes = 1500, sort = "vote_average.desc"),
            tv = ThemeQuery(minRating = 7.8, minVotes = 100, maxVotes = 800, sort = "vote_average.desc")
        ),
        HighlightTheme(
            "order_in_the_court", "Order in the Court",
            "Courtroom and legal dramas: trials, lawyers and verdicts.",
            movie = ThemeQuery(keywords = "33519|222517|11038", minRating = 6.5, minVotes = 300),
            tv = ThemeQuery(keywords = "33519|222517|11038", minVotes = 50),
            affinityGenres = setOf(DRAMA, CRIME)
        ),
        HighlightTheme(
            "spy_games", "Spy Games",
            "Spies, secret agents and espionage.",
            movie = ThemeQuery(keywords = "470|5265|4289", minVotes = 500),
            tv = ThemeQuery(keywords = "470|5265|4289", minVotes = 100),
            affinityGenres = setOf(ACTION, THRILLER, TV_ACTION)
        ),
        HighlightTheme(
            "found_family", "Found Family",
            "Misfits and strangers who become a family of their own.",
            movie = ThemeQuery(keywords = "248927|167982", minVotes = 200),
            tv = ThemeQuery(keywords = "248927|167982", minVotes = 50),
            affinityGenres = setOf(DRAMA, COMEDY, FAMILY)
        )
    )

    private val byId = themes.associateBy { it.id }

    fun theme(id: String): HighlightTheme? = byId[id]
}
