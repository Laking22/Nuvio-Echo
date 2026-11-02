package com.nuvio.tv.ui.newlook

import androidx.compose.animation.core.FloatAnimationSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * How the new look's pages and rows move between items, after Nuvio Legacy:
 * a glide that starts quickly and eases into place over about 400 ms, instead of a 220 ms tween that arrived in a
 * handful of frames. Critically damped, so it never overshoots; as a spring it keeps its speed when the next press
 * comes mid-glide, where a tween started again from a standstill and hitched.
 */
object Glide {
    /** Covers 90% of the way in about 230 ms and settles by about 400 ms. */
    const val STIFFNESS = 280f

    val scroll: SpringSpec<Float> = spring(dampingRatio = 1f, stiffness = STIFFNESS)
}

/**
 * [Glide.scroll] for a row or page that a held key moves faster than the glide settles. A held key moves focus about
 * 20 items a second, and the plain glide trailed it by two to three items: the row's passed cards faded out ahead of
 * it, leaving a gap before the focused card. This firms up once the move runs faster than a single press ever does, or
 * falls more than an item behind, so it trails by about half an item; a single press glides exactly as before. It
 * never overshoots: moving towards its place, it's always firm enough to stop there.
 *
 * Works both ways Compose runs a scroll animation: from where it is each frame (a lazy row bringing focus into view)
 * and as one run from a start (the page's own glide).
 *
 * @param stepPx how far one item is, so "an item behind" and "items a second" mean the same on any row.
 */
class PacedGlide(private val stepPx: Float) : FloatAnimationSpec {
    private fun omega(offset: Float, velocity: Float): Float {
        if (stepPx <= 0f) return BASE_OMEGA
        val speed = abs(velocity) / stepPx
        val boost = ((speed - FREE_SPEED) / BOOST_SPEED).coerceIn(0f, MAX_SPEED_BOOST) +
            (abs(offset) / stepPx - 1f).coerceAtLeast(0f) * BEHIND_BOOST
        val omega = BASE_OMEGA * (1f + boost)
        // Heading for its place faster than this spring would stop: firm up just enough to arrive without passing it.
        return if (offset != 0f && offset * velocity < 0f) maxOf(omega, abs(velocity) / abs(offset)) else omega
    }

    override fun getValueFromNanos(playTimeNanos: Long, initialValue: Float, targetValue: Float, initialVelocity: Float): Float {
        val x0 = initialValue - targetValue
        val w = omega(x0, initialVelocity)
        val t = playTimeNanos / 1_000_000_000f
        return targetValue + (x0 + (initialVelocity + w * x0) * t) * exp(-w * t)
    }

    override fun getVelocityFromNanos(playTimeNanos: Long, initialValue: Float, targetValue: Float, initialVelocity: Float): Float {
        val x0 = initialValue - targetValue
        val w = omega(x0, initialVelocity)
        val t = playTimeNanos / 1_000_000_000f
        return (initialVelocity - w * (initialVelocity + w * x0) * t) * exp(-w * t)
    }

    override fun getDurationNanos(initialValue: Float, targetValue: Float, initialVelocity: Float): Long {
        var t = 0L
        while (t < MAX_DURATION_NANOS) {
            t += STEP_NANOS
            val offset = getValueFromNanos(t, initialValue, targetValue, initialVelocity) - targetValue
            val velocity = getVelocityFromNanos(t, initialValue, targetValue, initialVelocity)
            if (abs(offset) < SETTLED_PX && abs(velocity) < SETTLED_PX_PER_S) return t
        }
        return MAX_DURATION_NANOS
    }

    override fun getEndVelocity(initialValue: Float, targetValue: Float, initialVelocity: Float): Float = 0f

    private companion object {
        /** [Glide.scroll]'s spring: stiffness 280, critically damped. */
        val BASE_OMEGA = sqrt(Glide.STIFFNESS)
        /** Items a second a single press peaks at about 6: below this the glide is left alone. */
        const val FREE_SPEED = 8f
        const val BOOST_SPEED = 4f
        const val MAX_SPEED_BOOST = 3f
        /** Extra firmness for each item it's behind past the first (a single press starts one item away). */
        const val BEHIND_BOOST = 1.5f
        const val SETTLED_PX = 0.5f
        const val SETTLED_PX_PER_S = 8f
        const val STEP_NANOS = 4_000_000L
        const val MAX_DURATION_NANOS = 3_000_000_000L
    }
}
