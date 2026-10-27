package com.nuvio.tv.core.home.smart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartHighlightPlannerTest {

    private val themes = SmartHighlightCatalog.themes

    private fun order(day: Long, blocked: Set<String> = emptySet(), snoozed: Set<String> = emptySet(), affinity: Map<Int, Double> = emptyMap()) =
        SmartHighlightPlanner.order(themes, blocked, snoozed, day, profileSeed = 1, affinity = affinity).map { it.id }

    @Test
    fun `the same day gives the same order and another day a different one`() {
        assertEquals(order(20_000), order(20_000))
        assertNotEquals(order(20_000).take(3), order(20_001).take(3))
    }

    @Test
    fun `blocked and snoozed themes are left out until the snooze ends`() {
        val day = 20_000L
        val out = order(day, blocked = setOf("love_triangle"), snoozed = setOf("heist_night@${day + 5}"))
        assertFalse("love_triangle" in out)
        assertFalse("heist_night" in out)
        assertTrue("heist_night" in order(day + 5, snoozed = setOf("heist_night@${day + 5}")))
        assertEquals(themes.size - 2, out.size)
    }

    @Test
    fun `themes shown in the last few days sink, today's showing doesn't count`() {
        val day = 20_000L
        val top = order(day).first()
        assertNotEquals(top, SmartHighlightPlanner.order(themes, emptySet(), emptySet(), day, 1, emptyMap(), mapOf(top to day - 1)).first().id)
        assertEquals(order(day), SmartHighlightPlanner.order(themes, emptySet(), emptySet(), day, 1, emptyMap(), mapOf(top to day)).map { it.id })
        assertEquals(order(day), SmartHighlightPlanner.order(themes, emptySet(), emptySet(), day, 1, emptyMap(), mapOf(top to day - 4)).map { it.id })
    }

    @Test
    fun `watching a genre lifts its themes`() {
        // Over many days, a romance watcher sees Enemies to Lovers in the first three far more often.
        val romance = mapOf(10749 to 1.0)
        val days = 20_000L until 20_200L
        fun top3Share(affinity: Map<Int, Double>) = days.count { "enemies_to_lovers" in order(it, affinity = affinity).take(3) }
        assertTrue(top3Share(romance) > top3Share(emptyMap()) * 2)
    }

    @Test
    fun `affinity weights the most-watched genre as one`() {
        val weights = SmartHighlightPlanner.affinity(listOf(listOf(18, 80), listOf(18), listOf(18, 18, 35)))
        assertEquals(1.0, weights.getValue(18), 0.0)
        assertEquals(1.0 / 3, weights.getValue(80), 1e-9)
        assertEquals(1.0 / 3, weights.getValue(35), 1e-9)
    }

    @Test
    fun `a malformed snooze entry does not hide anything`() {
        assertFalse(SmartHighlightPlanner.isSnoozed("heist_night", setOf("heist_night", "heist_night@x"), 1))
    }

    /** Home's rows with highlights placed: true marks a highlight (anchor or placed). */
    private fun layout(anchors: List<Boolean>, count: Int): List<Boolean> {
        val slots = SmartHighlightPlanner.placements(anchors, count)
        val out = ArrayList<Boolean>()
        var next = 0
        anchors.forEachIndexed { i, anchor ->
            while (next < slots.size && slots[next] == i) { out += true; next++ }
            out += anchor
        }
        while (next < slots.size) { out += true; next++ }
        return out
    }

    /** Rows between each highlight and the one before it (the first counted from the top). */
    private fun gaps(rows: List<Boolean>): List<Int> {
        val out = ArrayList<Int>()
        var since = 0
        rows.forEach { if (it) { out += since; since = 0 } else since++ }
        return out
    }

    @Test
    fun `highlights spread evenly, two to five rows apart`() {
        val rows = layout(List(12) { false }, 3)
        assertEquals(listOf(3, 3, 3), gaps(rows))

        // Heavy on a short page: as many as fit two rows apart.
        val heavy = layout(List(14) { false }, 10)
        assertEquals(7, heavy.count { it })
        assertTrue(gaps(heavy).all { it == 2 })

        // A long page with few highlights doesn't stretch past five rows between them.
        assertTrue(gaps(layout(List(40) { false }, 3)).all { it in 2..5 })
    }

    @Test
    fun `the user's grand rows count as highlights and stay put`() {
        val anchors = listOf(false, false, false, true, false, false, false, false, false, false)
        val rows = layout(anchors, 2)
        assertTrue(gaps(rows).all { it >= SmartHighlightPlanner.MIN_GAP })
        assertEquals(3, rows.count { it })
        // The grand row is still fourth among the page's own rows.
        assertEquals(3, rows.filterIndexed { i, _ -> i < rows.indexOf(true) }.size)
    }

    @Test
    fun `each Enrichment level shows its range of highlights, the same all day`() {
        val days = 20_000L..20_060L
        assertTrue(days.all { SmartHighlightPlanner.dailyCount(1, it) == 3 })
        val medium = days.map { SmartHighlightPlanner.dailyCount(2, it) }
        assertTrue(medium.all { it in 3..5 } && medium.toSet().size > 1)
        // Heavy has no upper limit: as many as the page has room for.
        assertTrue(days.all { SmartHighlightPlanner.dailyCount(3, it) == Int.MAX_VALUE })
        assertEquals(SmartHighlightPlanner.dailyCount(2, 20_001L), SmartHighlightPlanner.dailyCount(2, 20_001L))
        assertEquals(0, SmartHighlightPlanner.dailyCount(0, 20_001L))
        assertFalse(SmartHighlightPlanner.isGrand(1, 1))
        assertTrue(SmartHighlightPlanner.isGrand(2, 1) && SmartHighlightPlanner.isGrand(3, 4))
    }
}
