package com.nuvio.tv.core.bins

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import java.util.Locale

/**
 * Bins are the Library: folders the viewer makes and names ("Horror Binge"), each holding titles in an order of
 * their own. One bin can be starred; it's where the details page's Bin button puts a title.
 */
@Immutable
@Serializable
data class BinEntry(
    val id: String,
    val type: String,
    val name: String,
    val poster: String? = null,
    val background: String? = null,
    val logo: String? = null,
    val description: String? = null,
    val releaseInfo: String? = null,
    val imdbRating: Float? = null,
    val genres: List<String> = emptyList(),
    val addonBaseUrl: String? = null,
    val addedAt: Long = 0L,
    val viewedAt: Long = 0L,
    /** Position in the bin's custom order. */
    val order: Int = 0
) {
    val key: String get() = binKey(id, type)
}

@Immutable
@Serializable
data class Bin(
    val id: String,
    val name: String,
    val starred: Boolean = false,
    /** Position in the custom order of bins. */
    val order: Int = 0,
    val createdAt: Long = 0L,
    val viewedAt: Long = 0L,
    val entries: List<BinEntry> = emptyList()
) {
    fun contains(key: String): Boolean = entries.any { it.key == key }
}

@Serializable
data class BinsState(
    val bins: List<Bin> = emptyList(),
    val sortKey: String = BinSort.RECENTLY_VIEWED.key,
    /** Whether the titles saved before bins existed have been moved into one. */
    val migrated: Boolean = false
) {
    val sort: BinSort get() = BinSort.fromKey(sortKey)
    val starred: Bin? get() = bins.firstOrNull { it.starred }
}

enum class BinSort(val key: String) {
    CUSTOM("custom"),
    RECENTLY_VIEWED("recent"),
    ADDED_NEWEST("added_desc"),
    ADDED_OLDEST("added_asc"),
    TITLE_AZ("title_asc"),
    TITLE_ZA("title_desc");

    companion object {
        /** What the Sort pill offers; Custom order has its own button. */
        val Menu = listOf(RECENTLY_VIEWED, ADDED_NEWEST, ADDED_OLDEST, TITLE_AZ, TITLE_ZA)

        fun fromKey(key: String?): BinSort = entries.firstOrNull { it.key == key } ?: RECENTLY_VIEWED
    }
}

fun binKey(id: String, type: String): String = "${type.trim().lowercase(Locale.ROOT)}:$id"

const val MAX_BIN_NAME_LENGTH = 40

/** A bin name as stored: trimmed, inner runs of spaces collapsed, at most [MAX_BIN_NAME_LENGTH] characters. */
fun cleanBinName(raw: String): String = raw.trim().replace(Regex("\\s+"), " ").take(MAX_BIN_NAME_LENGTH).trim()

/** Every change to the bins, as pure functions of the state, so the order, star and sort rules are testable. */
object BinsReducer {

    fun create(state: BinsState, id: String, name: String, now: Long): BinsState {
        val bin = Bin(
            id = id,
            name = name,
            // The first bin is the default until another is starred, so the Bin button works straight away.
            starred = state.bins.isEmpty(),
            order = (state.bins.maxOfOrNull { it.order } ?: -1) + 1,
            createdAt = now
        )
        return state.copy(bins = state.bins + bin)
    }

    fun rename(state: BinsState, binId: String, name: String): BinsState =
        state.updateBin(binId) { it.copy(name = name) }

    fun delete(state: BinsState, binId: String): BinsState =
        state.copy(bins = normalizedBinOrder(state.bins.filterNot { it.id == binId }))

    /** Stars [binId] (only one bin is starred), or clears the star when it's already starred. */
    fun toggleStar(state: BinsState, binId: String): BinsState {
        val turnOn = state.bins.firstOrNull { it.id == binId }?.starred == false
        return state.copy(bins = state.bins.map { it.copy(starred = turnOn && it.id == binId) })
    }

    /** Custom order starts from what's on screen, so entering it never shuffles anything. */
    fun useDisplayedBinOrder(state: BinsState, orderedIds: List<String>): BinsState {
        val position = orderedIds.withIndex().associate { (i, id) -> id to i }
        val rest = state.bins.filterNot { it.id in position }.sortedWith(customBinComparator)
        val ordered = state.bins.filter { it.id in position }.sortedBy { position.getValue(it.id) } + rest
        return state.copy(bins = ordered.mapIndexed { i, bin -> bin.copy(order = i) }, sortKey = BinSort.CUSTOM.key)
    }

    fun swapBins(state: BinsState, a: String, b: String): BinsState {
        if (a == b) return state
        val bins = normalizedBinOrder(state.bins)
        val orderA = bins.firstOrNull { it.id == a }?.order ?: return state
        val orderB = bins.firstOrNull { it.id == b }?.order ?: return state
        return state.copy(
            bins = bins.map {
                when (it.id) {
                    a -> it.copy(order = orderB)
                    b -> it.copy(order = orderA)
                    else -> it
                }
            },
            sortKey = BinSort.CUSTOM.key
        )
    }

    fun useDisplayedEntryOrder(state: BinsState, binId: String, orderedKeys: List<String>): BinsState {
        val position = orderedKeys.withIndex().associate { (i, key) -> key to i }
        return state.updateBin(binId) { bin ->
            val rest = bin.entries.filterNot { it.key in position }.sortedWith(customEntryComparator)
            val ordered = bin.entries.filter { it.key in position }.sortedBy { position.getValue(it.key) } + rest
            bin.copy(entries = ordered.mapIndexed { i, entry -> entry.copy(order = i) })
        }.copy(sortKey = BinSort.CUSTOM.key)
    }

    fun swapEntries(state: BinsState, binId: String, a: String, b: String): BinsState {
        if (a == b) return state
        return state.updateBin(binId) { bin ->
            val entries = normalizedEntryOrder(bin.entries)
            val orderA = entries.firstOrNull { it.key == a }?.order ?: return@updateBin bin
            val orderB = entries.firstOrNull { it.key == b }?.order ?: return@updateBin bin
            bin.copy(
                entries = entries.map {
                    when (it.key) {
                        a -> it.copy(order = orderB)
                        b -> it.copy(order = orderA)
                        else -> it
                    }
                }
            )
        }.copy(sortKey = BinSort.CUSTOM.key)
    }

    /** Adds [entry] to the end of the bin's custom order; a title already there only has its details refreshed. */
    fun addEntry(state: BinsState, binId: String, entry: BinEntry, now: Long): BinsState =
        state.updateBin(binId) { bin ->
            val existing = bin.entries.firstOrNull { it.key == entry.key }
            if (existing != null) {
                bin.copy(entries = bin.entries.map {
                    if (it.key == entry.key) entry.copy(addedAt = existing.addedAt, viewedAt = existing.viewedAt, order = existing.order) else it
                })
            } else {
                val order = (bin.entries.maxOfOrNull { it.order } ?: -1) + 1
                bin.copy(entries = bin.entries + entry.copy(addedAt = now, order = order))
            }
        }

    fun removeEntry(state: BinsState, binId: String, key: String): BinsState =
        state.updateBin(binId) { bin -> bin.copy(entries = normalizedEntryOrder(bin.entries.filterNot { it.key == key })) }

    fun markBinViewed(state: BinsState, binId: String, now: Long): BinsState =
        state.updateBin(binId) { it.copy(viewedAt = now) }

    fun markEntryViewed(state: BinsState, binId: String, key: String, now: Long): BinsState =
        state.updateBin(binId) { bin ->
            bin.copy(entries = bin.entries.map { if (it.key == key) it.copy(viewedAt = now) else it })
        }

    fun setSort(state: BinsState, sort: BinSort): BinsState = state.copy(sortKey = sort.key)

    /**
     * Keeps the bins and the saved library (which the calendar, Nuvio sync and "Add to library" elsewhere use) the
     * same set of titles: a saved title in no bin goes into the starred bin (or a new [defaultName] bin when there
     * are none), and once bins exist, a title removed from the library leaves every bin.
     */
    fun reconcile(
        state: BinsState,
        library: List<BinEntry>,
        now: Long,
        newBinId: () -> String,
        defaultName: String
    ): BinsState {
        val libraryByKey = library.associateBy { it.key }
        var next = state
        if (state.migrated) {
            next = next.copy(bins = next.bins.map { bin ->
                val kept = bin.entries.filter { it.key in libraryByKey }
                if (kept.size == bin.entries.size) bin else bin.copy(entries = normalizedEntryOrder(kept))
            })
        }
        val binned = next.bins.flatMapTo(HashSet()) { bin -> bin.entries.map { it.key } }
        val orphans = library.filterNot { it.key in binned }
        if (orphans.isNotEmpty()) {
            val target = next.starred ?: next.bins.sortedWith(customBinComparator).firstOrNull()
            val targetId = target?.id ?: newBinId().also { id -> next = create(next, id, defaultName, now) }
            orphans.sortedBy { it.addedAt }.forEach { orphan ->
                next = addEntry(next, targetId, orphan, now)
                // Keep when it was saved, not when it was moved into the bin.
                if (orphan.addedAt > 0L) next = next.updateBin(targetId) { bin ->
                    bin.copy(entries = bin.entries.map { if (it.key == orphan.key) it.copy(addedAt = orphan.addedAt) else it })
                }
            }
        }
        return if (next.migrated) next else next.copy(migrated = true)
    }

    fun sortedBins(bins: List<Bin>, sort: BinSort): List<Bin> = when (sort) {
        BinSort.CUSTOM -> bins.sortedWith(customBinComparator)
        BinSort.RECENTLY_VIEWED -> bins.sortedWith(compareByDescending<Bin> { it.viewedAt }.thenByDescending { it.createdAt })
        BinSort.ADDED_NEWEST -> bins.sortedByDescending { it.createdAt }
        BinSort.ADDED_OLDEST -> bins.sortedBy { it.createdAt }
        BinSort.TITLE_AZ -> bins.sortedBy { titleSortKey(it.name) }
        BinSort.TITLE_ZA -> bins.sortedByDescending { titleSortKey(it.name) }
    }

    fun sortedEntries(entries: List<BinEntry>, sort: BinSort): List<BinEntry> = when (sort) {
        BinSort.CUSTOM -> entries.sortedWith(customEntryComparator)
        BinSort.RECENTLY_VIEWED -> entries.sortedWith(compareByDescending<BinEntry> { it.viewedAt }.thenByDescending { it.addedAt })
        BinSort.ADDED_NEWEST -> entries.sortedByDescending { it.addedAt }
        BinSort.ADDED_OLDEST -> entries.sortedBy { it.addedAt }
        BinSort.TITLE_AZ -> entries.sortedBy { titleSortKey(it.name) }
        BinSort.TITLE_ZA -> entries.sortedByDescending { titleSortKey(it.name) }
    }

    private val customBinComparator = compareBy<Bin> { it.order }.thenBy { it.createdAt }
    private val customEntryComparator = compareBy<BinEntry> { it.order }.thenBy { it.addedAt }

    private fun normalizedBinOrder(bins: List<Bin>): List<Bin> {
        val position = bins.sortedWith(customBinComparator).withIndex().associate { (i, bin) -> bin.id to i }
        return bins.map { it.copy(order = position.getValue(it.id)) }
    }

    private fun normalizedEntryOrder(entries: List<BinEntry>): List<BinEntry> {
        val position = entries.sortedWith(customEntryComparator).withIndex().associate { (i, e) -> e.key to i }
        return entries.map { it.copy(order = position.getValue(it.key)) }
    }

    private fun BinsState.updateBin(binId: String, update: (Bin) -> Bin): BinsState =
        copy(bins = bins.map { if (it.id == binId) update(it) else it })

    // "The Bear" sorts under B, the way streaming services order titles.
    private val LEADING_ARTICLE = Regex("^(the|an|a)\\s+", RegexOption.IGNORE_CASE)

    private fun titleSortKey(title: String): String = title.trim().replace(LEADING_ARTICLE, "").lowercase(Locale.ROOT)
}
