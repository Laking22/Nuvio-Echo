package com.nuvio.tv.ui.newlook

/**
 * When Left at a screen's left edge opens the sidebar. Quick presses only ever move focus: a fresh press opens it
 * only after a pause since the previous Left, and a held Left (key repeats) opens it once it has failed to move
 * focus twice in a row. Before, any Left whose focus search ended in the sidebar or came up empty
 * opened it, so rapid presses across a row that was still scrolling landed in the menu.
 *
 * Asked at most once per key press ([mayOpen] keeps its answer until the next [onLeftDown]): both the sidebar's
 * onEnter and the scaffold's "nothing to move to" path ask it for the same press.
 */
internal class SidebarLeftGate(private val restMs: Long = REST_MS) {
    private var lastLeftAt = Long.MIN_VALUE / 2
    private var previousLeftAt = Long.MIN_VALUE / 2
    private var repeatCount = 0
    private var heldMisses = 0
    private var decision: Boolean? = null

    /** Every Left key-down, seen before the screen handles it. */
    fun onLeftDown(timeMs: Long, repeatCount: Int) {
        previousLeftAt = lastLeftAt
        lastLeftAt = timeMs
        this.repeatCount = repeatCount
        decision = null
    }

    /** A Left moved focus within the screen. */
    fun onMoved() {
        heldMisses = 0
    }

    /** This press found nothing left of focus but the sidebar. True when the sidebar should open. */
    fun mayOpen(): Boolean = decision ?: decide().also { decision = it }

    private fun decide(): Boolean {
        if (repeatCount > 0) {
            heldMisses++
            return heldMisses >= HELD_MISSES
        }
        heldMisses = 0
        return lastLeftAt - previousLeftAt >= restMs
    }

    companion object {
        const val REST_MS = 300L
        const val HELD_MISSES = 2
    }
}
