package com.nuvio.tv.core.home

/**
 * Where the built-in rows sit among Home's catalog and collection rows. Shared by Home and the catalog order screen
 * so both always agree.
 *
 * The catalog order itself stays in the layout settings (it syncs to the account); the built-in rows' places are
 * kept separately as a saved merged list of catalog keys and built-in row keys. A built-in row goes right after the
 * nearest row before it in that list that's still there, so disabling, removing or reordering catalogs elsewhere
 * never loses it. Built-in rows that were never moved sit where Home has always put them.
 */
object HomeRowOrder {

    fun isBuiltIn(key: String): Boolean = key.startsWith(BUILT_IN_PREFIX)

    /**
     * [catalogKeys] in order with [builtIns] (row keys) placed among them, and Continue Watching
     * ([CONTINUE_WATCHING_KEY]) too when [continueWatching]: it moves like a built-in row and starts at the top.
     * [typeOf] gives a catalog's type ("movie", "series"...) for the default places of the Top 20 rows.
     */
    fun merge(
        catalogKeys: List<String>,
        builtIns: List<BuiltInRow>,
        saved: List<String>,
        continueWatching: Boolean = false,
        typeOf: (String) -> String?
    ): List<String> {
        val result = catalogKeys.toMutableList()
        val builtInKeys = builtIns.map { it.rowKey }.toSet() + if (continueWatching) setOf(CONTINUE_WATCHING_KEY) else emptySet()

        // Saved built-ins, in saved order: after the nearest earlier saved key that's already placed.
        val savedPositions = saved.withIndex().filter { it.value in builtInKeys }
        val placed = HashSet<String>()
        for ((index, key) in savedPositions) {
            if (!placed.add(key)) continue
            var anchor = -1
            for (j in index - 1 downTo 0) {
                val at = result.indexOf(saved[j])
                if (at >= 0) {
                    anchor = at
                    break
                }
            }
            result.add(anchor + 1, key)
        }

        // Continue Watching where Home has always had it, first.
        if (continueWatching && placed.add(CONTINUE_WATCHING_KEY)) result.add(0, CONTINUE_WATCHING_KEY)

        // The rest where Home has always put them: the personal rows and Trending at the top, each Top 20 row
        // after the first catalog of its type (or after the first catalog when there's none).
        var leadingAt = 0
        for (row in builtIns) {
            if (row.rowKey in placed) continue
            when (row) {
                BuiltInRow.TRENDING_WEEK, BuiltInRow.BECAUSE_YOU_WATCHED, BuiltInRow.FOR_YOU -> {
                    // After the leading built-ins already at the top, so they keep their own order.
                    while (leadingAt < result.size && isBuiltIn(result[leadingAt]) && result[leadingAt] in placed) leadingAt++
                    result.add(leadingAt, row.rowKey)
                    leadingAt++
                }
                BuiltInRow.TOP_MOVIES_TODAY, BuiltInRow.TOP_SHOWS_TODAY -> {
                    val wanted = if (row == BuiltInRow.TOP_MOVIES_TODAY) setOf("movie") else setOf("series", "tv")
                    val first = result.indexOfFirst { !isBuiltIn(it) && typeOf(it)?.lowercase() in wanted }
                    val fallback = result.indexOfFirst { !isBuiltIn(it) }
                    val after = if (first >= 0) first else fallback
                    result.add(if (after >= 0) after + 1 else result.size, row.rowKey)
                }
            }
            placed += row.rowKey
        }
        return result
    }

    /** One step up (-1) or down (+1) in the merged list; the list unchanged at either end. */
    fun move(order: List<String>, key: String, direction: Int): List<String> {
        val from = order.indexOf(key)
        val to = from + direction
        if (from < 0 || to !in order.indices) return order
        return order.toMutableList().apply { add(to, removeAt(from)) }
    }

    fun encode(order: List<String>): String = order.joinToString(SEPARATOR)

    fun decode(text: String?): List<String> =
        text?.split(SEPARATOR)?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    const val BUILT_IN_PREFIX = "builtin:"

    /** Continue Watching (and the upcoming episodes that follow it) in the merged order. */
    const val CONTINUE_WATCHING_KEY = BUILT_IN_PREFIX + "continue_watching"
    private const val SEPARATOR = "\n"
}
