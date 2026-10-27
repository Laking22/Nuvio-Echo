package com.nuvio.tv.core.bins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BinsReducerTest {

    private fun entry(id: String, name: String = id, addedAt: Long = 0L) = BinEntry(id = id, type = "movie", name = name, addedAt = addedAt)

    private fun threeBins(): BinsState {
        var s = BinsState(migrated = true)
        s = BinsReducer.create(s, "a", "Horror Binge", now = 1)
        s = BinsReducer.create(s, "b", "Date Night", now = 2)
        s = BinsReducer.create(s, "c", "Prestige TV", now = 3)
        return s
    }

    private fun BinsState.custom() = BinsReducer.sortedBins(bins, BinSort.CUSTOM).map { it.id }

    @Test
    fun `the first bin is starred and only one bin is ever starred`() {
        var s = threeBins()
        assertEquals(listOf("a"), s.bins.filter { it.starred }.map { it.id })
        s = BinsReducer.toggleStar(s, "c")
        assertEquals(listOf("c"), s.bins.filter { it.starred }.map { it.id })
        s = BinsReducer.toggleStar(s, "c")
        assertTrue(s.bins.none { it.starred })
    }

    @Test
    fun `swapping two bins trades their places in custom order`() {
        var s = threeBins()
        s = BinsReducer.swapBins(s, "a", "c")
        assertEquals(listOf("c", "b", "a"), s.custom())
        assertEquals(BinSort.CUSTOM, s.sort)
        s = BinsReducer.swapBins(s, "b", "a")
        assertEquals(listOf("c", "a", "b"), s.custom())
    }

    @Test
    fun `custom order starts from the order on screen`() {
        var s = threeBins()
        s = BinsReducer.markBinViewed(s, "b", now = 50)
        val shown = BinsReducer.sortedBins(s.bins, BinSort.RECENTLY_VIEWED).map { it.id }
        assertEquals(listOf("b", "c", "a"), shown)
        s = BinsReducer.useDisplayedBinOrder(s, shown)
        assertEquals(shown, s.custom())
        s = BinsReducer.swapBins(s, "b", "a")
        assertEquals(listOf("a", "c", "b"), s.custom())
    }

    @Test
    fun `titles swap inside a bin and new ones go to the end`() {
        var s = threeBins()
        s = BinsReducer.addEntry(s, "a", entry("t1"), now = 10)
        s = BinsReducer.addEntry(s, "a", entry("t2"), now = 11)
        s = BinsReducer.addEntry(s, "a", entry("t3"), now = 12)
        s = BinsReducer.swapEntries(s, "a", "movie:t1", "movie:t3")
        val bin = s.bins.first { it.id == "a" }
        assertEquals(listOf("t3", "t2", "t1"), BinsReducer.sortedEntries(bin.entries, BinSort.CUSTOM).map { it.id })
        s = BinsReducer.addEntry(s, "a", entry("t4"), now = 13)
        assertEquals("t4", BinsReducer.sortedEntries(s.bins.first { it.id == "a" }.entries, BinSort.CUSTOM).last().id)
    }

    @Test
    fun `adding a title twice keeps one copy and its place`() {
        var s = threeBins()
        s = BinsReducer.addEntry(s, "a", entry("t1"), now = 10)
        s = BinsReducer.addEntry(s, "a", entry("t1", name = "New name"), now = 99)
        val only = s.bins.first { it.id == "a" }.entries.single()
        assertEquals("New name", only.name)
        assertEquals(10L, only.addedAt)
    }

    @Test
    fun `saved titles from before bins move into a new starred bin`() {
        val s = BinsReducer.reconcile(
            BinsState(), listOf(entry("old1", addedAt = 5), entry("old2", addedAt = 7)), now = 100,
            newBinId = { "saved" }, defaultName = "Saved"
        )
        val saved = s.bins.single()
        assertEquals("Saved", saved.name)
        assertTrue(saved.starred)
        assertEquals(setOf("old1", "old2"), saved.entries.map { it.id }.toSet())
        assertEquals(5L, saved.entries.first { it.id == "old1" }.addedAt)
        assertTrue(s.migrated)
    }

    @Test
    fun `a title saved elsewhere lands in the starred bin, and one removed from the library leaves every bin`() {
        var s = threeBins()
        s = BinsReducer.toggleStar(s, "b")
        s = BinsReducer.addEntry(s, "a", entry("keep"), now = 10)
        s = BinsReducer.addEntry(s, "c", entry("gone"), now = 11)
        s = BinsReducer.reconcile(s, listOf(entry("keep"), entry("fresh")), now = 20, newBinId = { error("no new bin") }, defaultName = "Saved")
        assertEquals(listOf("keep"), s.bins.first { it.id == "a" }.entries.map { it.id })
        assertEquals(listOf("fresh"), s.bins.first { it.id == "b" }.entries.map { it.id })
        assertTrue(s.bins.first { it.id == "c" }.entries.isEmpty())
    }

    @Test
    fun `before the first migration nothing is removed`() {
        var s = BinsState()
        s = BinsReducer.create(s, "a", "A", now = 1)
        s = BinsReducer.addEntry(s, "a", entry("x"), now = 2)
        s = BinsReducer.reconcile(s, emptyList(), now = 3, newBinId = { "n" }, defaultName = "Saved")
        assertEquals(listOf("x"), s.bins.single().entries.map { it.id })
    }

    @Test
    fun `deleting a bin closes the gap in custom order`() {
        var s = threeBins()
        s = BinsReducer.delete(s, "b")
        assertEquals(listOf("a", "c"), s.custom())
        assertEquals(listOf(0, 1), BinsReducer.sortedBins(s.bins, BinSort.CUSTOM).map { it.order })
        assertFalse(s.bins.any { it.id == "b" })
    }

    @Test
    fun `title sorts ignore a leading article`() {
        val entries = listOf(entry("1", "The Bear"), entry("2", "Andor"), entry("3", "Conclave"))
        assertEquals(listOf("Andor", "The Bear", "Conclave"), BinsReducer.sortedEntries(entries, BinSort.TITLE_AZ).map { it.name })
    }

    @Test
    fun `bin names are tidied`() {
        assertEquals("Horror Binge", cleanBinName("  Horror   Binge "))
        assertEquals(MAX_BIN_NAME_LENGTH, cleanBinName("x".repeat(80)).length)
    }
}
