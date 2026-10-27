package com.nuvio.tv.core.streams

/** One look at playback, taken about once a second while Auto quality is on. */
data class AutoQualitySample(
    val nowMs: Long,
    val positionMs: Long,
    /** Playback should be running: not paused by the viewer. */
    val wantsToPlay: Boolean,
    /** Stalled waiting for data, after the first frame. */
    val stalled: Boolean,
    /** Media buffered ahead of the playhead, in ms; null when the player can't say. */
    val bufferedAheadMs: Long?,
    /** Measured download speed, in bits/s; null when unknown. */
    val throughputBps: Long?
)

sealed class AutoQualityDecision {
    data object Stay : AutoQualityDecision()
    /** [early]: a step down taken ahead of trouble, with plenty still buffered (see [AutoQualityPolicy.Config.earlyDowngrade]). */
    data class Change(val target: StreamQuality, val isUpgrade: Boolean, val early: Boolean = false) : AutoQualityDecision()
}

/** The lowest quality Auto steps down to without having measured the connection. */
private const val LOWEST_UNMEASURED_STEP = 720

/**
 * Decides when Auto quality steps up or down. Down when playback stalls (one long stall, or a few
 * short ones close together), or when the connection is falling behind the playing file with little
 * buffered; up only after a calm stretch with plenty buffered and a connection comfortably faster
 * than the better quality needs. Every change is followed by a settling time, a step down holds off
 * the next step up for longer, and a quality that failed to play is left alone for a while, so it
 * never flips back and forth. Seeks aren't stalls.
 *
 * Pure: the player feeds it [AutoQualitySample]s and acts on the [AutoQualityDecision].
 */
class AutoQualityPolicy(config: Config = Config()) {

    /** Can change between samples (Hot swap on or off); timers and history carry over. */
    var config: Config = config

    data class Config(
        /** After any change (or a new source), how long before judging the new quality. */
        val settleMs: Long = 15_000L,
        /** A stall this long on its own means the quality is too high. */
        val longStallMs: Long = 5_000L,
        /** Stalls shorter than this are ignored (a hiccup, not starvation). */
        val minStallMs: Long = 400L,
        /** This many stalls within [stallWindowMs] mean the quality is too high. */
        val stallsToDowngrade: Int = 2,
        val stallWindowMs: Long = 90_000L,
        /** A stall starting this soon after a seek is the seek's, not the connection's. */
        val seekGraceMs: Long = 3_000L,
        /** Falling behind: less than this buffered while the connection is slower than the file. */
        val lowBufferMs: Long = 5_000L,
        /** Up: no stall for this long ... */
        val calmBeforeUpgradeMs: Long = 120_000L,
        /** ... at least this long since any change ... */
        val upgradeAfterChangeMs: Long = 60_000L,
        /** ... and this long since the last step down ... */
        val upgradeAfterDowngradeMs: Long = 180_000L,
        /** ... with this much buffered ... */
        val healthyBufferMs: Long = 30_000L,
        /** ... and a connection this many times faster than the better quality needs. */
        val upgradeHeadroom: Double = 1.6,
        /** Down to a quality the connection carries with this much to spare. */
        val downgradeHeadroom: Double = 1.3,
        /** A quality that failed to play is skipped for this long. */
        val blockMs: Long = 10 * 60_000L,
        /**
         * Also step down early, while plenty is still buffered: the connection slower than the file for
         * [earlySlowMs] with the buffer shrinking by [earlyBufferDropMs] meanwhile, and less than
         * [earlyBelowBufferMs] buffered (with more, there's time for the connection to recover).
         */
        val earlyDowngrade: Boolean = false,
        val earlySlowMs: Long = 20_000L,
        val earlyBufferDropMs: Long = 2_000L,
        val earlyBelowBufferMs: Long = 60_000L
    )

    companion object {
        val DEFAULT = Config()

        /**
         * With Hot swap on a change barely interrupts playback, so Auto acts sooner: earlier steps up,
         * and steps down taken early, before anything stalls (the hot swap needs buffer to hide them in).
         */
        val SENSITIVE = Config(
            calmBeforeUpgradeMs = 45_000L,
            upgradeAfterChangeMs = 30_000L,
            upgradeAfterDowngradeMs = 90_000L,
            earlyDowngrade = true,
            // Sources that fail are passed over one by one (see the hot swap), so a quality is only
            // blocked once none of its sources plays: back to trying it sooner.
            blockMs = 3 * 60_000L
        )

        /** What a stream of each quality typically needs, in bits/s (web encodes; remuxes run higher). */
        fun typicalBitrateBps(quality: StreamQuality): Long = when (quality) {
            StreamQuality.P2160 -> 20_000_000L
            StreamQuality.P1440 -> 12_000_000L
            StreamQuality.P1080 -> 8_000_000L
            StreamQuality.P720 -> 4_000_000L
            StreamQuality.P480 -> 1_500_000L
            StreamQuality.P360 -> 800_000L
            StreamQuality.P240 -> 400_000L
            StreamQuality.P144 -> 200_000L
        }
    }

    private var lastSample: AutoQualitySample? = null
    private var lastSeekAtMs = Long.MIN_VALUE / 2
    private var lastChangeAtMs = Long.MIN_VALUE / 2
    private var lastDowngradeAtMs = Long.MIN_VALUE / 2
    private var stallStartedAtMs: Long? = null
    private var stallIsSeek = false
    private val stallStarts = ArrayDeque<Long>()
    private var lastStallEndedAtMs = Long.MIN_VALUE / 2
    private var throughputBps: Double? = null
    private var lastRawThroughput: Long? = null
    private val blockedUntil = HashMap<StreamQuality, Long>()
    private var snoozedUntilMs = Long.MIN_VALUE / 2
    /** Since when the connection has been slower than the playing file (null: it isn't), and the buffer then. */
    private var slowSinceMs: Long? = null
    private var bufferAtSlowStartMs = 0L

    private enum class DowngradeNeed { NOW, EARLY }

    /** The measured download speed, smoothed (bits/s); null before any measurement. */
    val throughputEstimateBps: Long? get() = throughputBps?.toLong()

    /** A new source started playing (picked by hand, by Auto, or by the fallback): judge it afresh. */
    fun onSourceChanged(nowMs: Long, wasDowngrade: Boolean = false) {
        lastChangeAtMs = nowMs
        if (wasDowngrade) lastDowngradeAtMs = nowMs
        stallStartedAtMs = null
        stallIsSeek = false
        stallStarts.clear()
        lastSample = null
        slowSinceMs = null
    }

    /** The viewer cancelled a change: no change of any kind for [forMs] (stalls are still noted). */
    fun snooze(nowMs: Long, forMs: Long) {
        snoozedUntilMs = nowMs + forMs
    }

    /** Auto's switch to [quality] found nothing that plays: leave it for a while. */
    fun block(quality: StreamQuality, nowMs: Long) {
        blockedUntil[quality] = nowMs + config.blockMs
    }

    fun evaluate(
        sample: AutoQualitySample,
        current: StreamQuality?,
        available: Collection<StreamQuality>,
        currentBitrateBps: Long?
    ): AutoQualityDecision {
        track(sample)
        if (current == null || !sample.wantsToPlay) return AutoQualityDecision.Stay
        trackSlow(sample, current, currentBitrateBps)
        val now = sample.nowMs
        if (now - lastChangeAtMs < config.settleMs || now < snoozedUntilMs) return AutoQualityDecision.Stay
        val usable = available.filter { (blockedUntil[it] ?: Long.MIN_VALUE) <= now }
        val speed = throughputBps

        val need = downgradeNeed(sample, current, currentBitrateBps)
        if (need != null) {
            val lower = usable.filter { it.ordinal > current.ordinal }.sortedBy { it.ordinal }
            if (lower.isEmpty()) return AutoQualityDecision.Stay
            // The best lower quality the connection carries; without a measurement (or with none that fits), one step
            // down, but not below 720p: below that only a measured connection takes it, so stalls alone don't walk it
            // down to 360p.
            val target = speed?.let { bps -> lower.firstOrNull { typicalBitrateBps(it) * config.downgradeHeadroom <= bps } }
                ?: lower.first().takeIf { it.height >= LOWEST_UNMEASURED_STEP }
                ?: return AutoQualityDecision.Stay
            return AutoQualityDecision.Change(target, isUpgrade = false, early = need == DowngradeNeed.EARLY)
        }

        if (canUpgrade(sample)) {
            val bps = speed ?: return AutoQualityDecision.Stay
            val target = usable
                .filter { it.ordinal < current.ordinal && typicalBitrateBps(it) * config.upgradeHeadroom <= bps }
                .minByOrNull { it.ordinal }
                ?: return AutoQualityDecision.Stay
            return AutoQualityDecision.Change(target, isUpgrade = true)
        }
        return AutoQualityDecision.Stay
    }

    /** Records stalls (seeks aside) and smooths the measured speed. */
    private fun track(sample: AutoQualitySample) {
        val now = sample.nowMs
        val previous = lastSample
        if (previous != null) {
            // The playhead jumped further than playback moves it: a seek.
            val elapsed = (now - previous.nowMs).coerceAtLeast(0L)
            val moved = sample.positionMs - previous.positionMs
            if (moved < -1_000L || moved > elapsed + 3_000L) lastSeekAtMs = now
        }
        lastSample = sample

        if (sample.stalled && sample.wantsToPlay) {
            if (stallStartedAtMs == null) {
                stallStartedAtMs = now
                stallIsSeek = now - lastSeekAtMs <= config.seekGraceMs
            }
        } else {
            val started = stallStartedAtMs
            if (started != null && !stallIsSeek && now - started >= config.minStallMs) {
                stallStarts.addLast(started)
                lastStallEndedAtMs = now
            }
            stallStartedAtMs = null
            stallIsSeek = false
        }
        while (stallStarts.isNotEmpty() && now - stallStarts.first() > config.stallWindowMs) stallStarts.removeFirst()

        val raw = sample.throughputBps?.takeIf { it > 0L }
        if (raw != null && raw != lastRawThroughput) {
            lastRawThroughput = raw
            throughputBps = throughputBps?.let { it * 0.7 + raw * 0.3 } ?: raw.toDouble()
        }
    }

    /** Notes how long the connection has been slower than the playing file, for the early step down. */
    private fun trackSlow(sample: AutoQualitySample, current: StreamQuality, currentBitrateBps: Long?) {
        val speed = throughputBps
        val buffered = sample.bufferedAheadMs
        val needed = (currentBitrateBps ?: typicalBitrateBps(current)).toDouble()
        if (speed == null || buffered == null || sample.stalled || speed >= needed * 0.9) {
            slowSinceMs = null
            return
        }
        if (slowSinceMs == null) {
            slowSinceMs = sample.nowMs
            bufferAtSlowStartMs = buffered
        }
    }

    private fun downgradeNeed(sample: AutoQualitySample, current: StreamQuality, currentBitrateBps: Long?): DowngradeNeed? {
        val now = sample.nowMs
        val ongoing = stallStartedAtMs?.takeIf { !stallIsSeek }?.let { now - it }
        if (ongoing != null && ongoing >= config.longStallMs) return DowngradeNeed.NOW
        val recentStalls = stallStarts.size + if (ongoing != null && ongoing >= config.minStallMs) 1 else 0
        if (recentStalls >= config.stallsToDowngrade) return DowngradeNeed.NOW
        // Falling behind before it stalls: little buffered and the connection slower than the file.
        val buffered = sample.bufferedAheadMs ?: return null
        val speed = throughputBps ?: return null
        val needed = (currentBitrateBps ?: typicalBitrateBps(current)).toDouble()
        if (!sample.stalled && buffered < config.lowBufferMs && speed < needed * 0.9) return DowngradeNeed.NOW
        // Ahead of that: slower than the file for a while, the buffer going down, and not so much
        // buffered that the connection has time to recover.
        val slowSince = slowSinceMs ?: return null
        val early = config.earlyDowngrade &&
            now - slowSince >= config.earlySlowMs &&
            buffered < config.earlyBelowBufferMs &&
            bufferAtSlowStartMs - buffered >= config.earlyBufferDropMs
        return if (early) DowngradeNeed.EARLY else null
    }

    private fun canUpgrade(sample: AutoQualitySample): Boolean {
        val now = sample.nowMs
        if (sample.stalled || stallStartedAtMs != null) return false
        if (now - lastChangeAtMs < config.upgradeAfterChangeMs) return false
        if (now - lastDowngradeAtMs < config.upgradeAfterDowngradeMs) return false
        if (now - lastStallEndedAtMs < config.calmBeforeUpgradeMs) return false
        val buffered = sample.bufferedAheadMs ?: return false
        return buffered >= config.healthyBufferMs
    }
}
