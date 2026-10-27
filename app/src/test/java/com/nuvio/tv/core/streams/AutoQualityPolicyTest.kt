package com.nuvio.tv.core.streams

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoQualityPolicyTest {

    private val all = listOf(StreamQuality.P2160, StreamQuality.P1080, StreamQuality.P720, StreamQuality.P480)

    private fun sample(
        now: Long,
        position: Long = now,
        stalled: Boolean = false,
        buffered: Long? = 60_000L,
        speed: Long? = null,
        playing: Boolean = true
    ) = AutoQualitySample(now, position, playing, stalled, buffered, speed)

    /** Plays [policy] from [from] to [to] ms one second at a time; the last decision. */
    private fun run(
        policy: AutoQualityPolicy,
        from: Long,
        to: Long,
        current: StreamQuality = StreamQuality.P1080,
        frozenPositionAt: Long? = null,
        stalledWhile: (Long) -> Boolean = { false },
        speed: Long? = null,
        buffered: Long? = 60_000L
    ): AutoQualityDecision {
        var decision: AutoQualityDecision = AutoQualityDecision.Stay
        var t = from
        while (t <= to) {
            val stalled = stalledWhile(t)
            decision = policy.evaluate(
                sample(t, position = frozenPositionAt ?: t, stalled = stalled, buffered = buffered, speed = speed),
                current, all, null
            )
            if (decision != AutoQualityDecision.Stay) return decision
            t += 1_000L
        }
        return decision
    }

    @Test
    fun `a long stall steps down one quality`() {
        val policy = AutoQualityPolicy()
        policy.onSourceChanged(0L)
        // Plays fine, then stalls from 30 s on (the playhead stops).
        val decision = run(policy, 1_000L, 40_000L, stalledWhile = { it >= 30_000L })
        assertEquals(AutoQualityDecision.Change(StreamQuality.P720, isUpgrade = false), decision)
    }

    @Test
    fun `two short stalls close together step down, to what the connection carries`() {
        val policy = AutoQualityPolicy()
        policy.onSourceChanged(0L)
        val stalls = setOf(20_000L, 21_000L, 40_000L, 41_000L)
        val decision = run(policy, 1_000L, 60_000L, stalledWhile = { it in stalls }, speed = 1_000_000L)
        // 1 Mbps carries none of the lower qualities with room to spare: one step down.
        assertEquals(AutoQualityDecision.Change(StreamQuality.P720, isUpgrade = false), decision)

        val fast = AutoQualityPolicy()
        fast.onSourceChanged(0L)
        val toFit = run(fast, 1_000L, 60_000L, current = StreamQuality.P2160, stalledWhile = { it in stalls }, speed = 12_000_000L)
        assertEquals(AutoQualityDecision.Change(StreamQuality.P1080, isUpgrade = false), toFit)
    }

    @Test
    fun `stalls alone don't step below 720p`() {
        val policy = AutoQualityPolicy()
        policy.onSourceChanged(0L)
        // At 720p with 480p on offer and no measured speed, a long stall doesn't drop it to 480p.
        val decision = run(policy, 1_000L, 40_000L, current = StreamQuality.P720, stalledWhile = { it >= 30_000L })
        assertEquals(AutoQualityDecision.Stay, decision)
    }

    @Test
    fun `a stall right after a seek is not counted`() {
        val policy = AutoQualityPolicy()
        policy.onSourceChanged(0L)
        var decision: AutoQualityDecision = AutoQualityDecision.Stay
        for (t in 1_000L..30_000L step 1_000L) {
            // At 20 s the viewer jumps ahead 10 minutes, and it buffers for 8 s.
            val position = if (t < 20_000L) t else t + 600_000L
            val stalled = t in 20_000L..28_000L
            decision = policy.evaluate(sample(t, position = position, stalled = stalled), StreamQuality.P1080, all, null)
            if (decision != AutoQualityDecision.Stay) break
        }
        assertEquals(AutoQualityDecision.Stay, decision)
    }

    @Test
    fun `steps up only after a calm stretch with plenty buffered and a fast connection`() {
        val policy = AutoQualityPolicy()
        policy.onSourceChanged(0L)
        // Fast enough for 4K with room (20 Mbps x 1.6 = 32 Mbps), but too soon after the change.
        assertEquals(AutoQualityDecision.Stay, run(policy, 1_000L, 59_000L, speed = 40_000_000L))
        assertEquals(
            AutoQualityDecision.Change(StreamQuality.P2160, isUpgrade = true),
            run(policy, 60_000L, 70_000L, speed = 40_000_000L)
        )
    }

    @Test
    fun `no step up without enough buffered or a fast enough connection`() {
        val slow = AutoQualityPolicy().apply { onSourceChanged(0L) }
        assertEquals(AutoQualityDecision.Stay, run(slow, 1_000L, 200_000L, speed = 20_000_000L))
        val thin = AutoQualityPolicy().apply { onSourceChanged(0L) }
        assertEquals(AutoQualityDecision.Stay, run(thin, 1_000L, 200_000L, speed = 40_000_000L, buffered = 10_000L))
        val unknown = AutoQualityPolicy().apply { onSourceChanged(0L) }
        assertEquals(AutoQualityDecision.Stay, run(unknown, 1_000L, 200_000L, speed = null))
    }

    @Test
    fun `a step down holds off the next step up`() {
        val policy = AutoQualityPolicy()
        policy.onSourceChanged(0L, wasDowngrade = true)
        // 60 s after a change would do, but a step down waits 3 minutes.
        assertEquals(AutoQualityDecision.Stay, run(policy, 1_000L, 170_000L, current = StreamQuality.P720, speed = 40_000_000L))
        assertEquals(
            AutoQualityDecision.Change(StreamQuality.P2160, isUpgrade = true),
            run(policy, 181_000L, 190_000L, current = StreamQuality.P720, speed = 40_000_000L)
        )
    }

    @Test
    fun `a blocked quality is skipped`() {
        val blocked = AutoQualityPolicy().apply { onSourceChanged(0L); block(StreamQuality.P2160, 0L) }
        // 4K is the only better quality, and it failed to play: stay.
        assertEquals(AutoQualityDecision.Stay, run(blocked, 1_000L, 120_000L, speed = 40_000_000L))
        // Ten minutes on it may be tried again.
        assertEquals(
            AutoQualityDecision.Change(StreamQuality.P2160, isUpgrade = true),
            run(blocked, 600_000L, 610_000L, speed = 40_000_000L)
        )
    }

    @Test
    fun `after the viewer cancels a change nothing changes for a while`() {
        val policy = AutoQualityPolicy().apply { onSourceChanged(0L) }
        // Would step up at 60 s, but the viewer cancelled at 59 s: five minutes of quiet.
        policy.snooze(59_000L, 5 * 60_000L)
        assertEquals(AutoQualityDecision.Stay, run(policy, 60_000L, 350_000L, speed = 40_000_000L))
        assertEquals(
            AutoQualityDecision.Change(StreamQuality.P2160, isUpgrade = true),
            run(policy, 360_000L, 370_000L, speed = 40_000_000L)
        )
    }

    @Test
    fun `nothing changes while paused`() {
        val policy = AutoQualityPolicy()
        policy.onSourceChanged(0L)
        var decision: AutoQualityDecision = AutoQualityDecision.Stay
        for (t in 1_000L..30_000L step 1_000L) {
            decision = policy.evaluate(sample(t, position = 1_000L, stalled = true, playing = false), StreamQuality.P1080, all, null)
        }
        assertEquals(AutoQualityDecision.Stay, decision)
    }

    @Test
    fun `falling behind with little buffered steps down before it stalls`() {
        val policy = AutoQualityPolicy()
        policy.onSourceChanged(0L)
        // A 1080p file of 10 Mbps over a 6 Mbps connection with 3 s buffered: down to 720p, which it carries.
        var decision: AutoQualityDecision = AutoQualityDecision.Stay
        for (t in 1_000L..20_000L step 1_000L) {
            decision = policy.evaluate(sample(t, buffered = 3_000L, speed = 6_000_000L), StreamQuality.P1080, all, 10_000_000L)
            if (decision != AutoQualityDecision.Stay) break
        }
        assertEquals(AutoQualityDecision.Change(StreamQuality.P720, isUpgrade = false), decision)
    }

    /** Plays a draining buffer: [startBuffered] ms at [from], losing [drainPerSecond] ms every second. */
    private fun runDraining(
        policy: AutoQualityPolicy,
        from: Long,
        to: Long,
        startBuffered: Long,
        drainPerSecond: Long,
        speed: Long
    ): AutoQualityDecision {
        var t = from
        while (t <= to) {
            val buffered = startBuffered - (t - from) / 1_000L * drainPerSecond
            val decision = policy.evaluate(sample(t, buffered = buffered, speed = speed), StreamQuality.P1080, all, null)
            if (decision != AutoQualityDecision.Stay) return decision
            t += 1_000L
        }
        return AutoQualityDecision.Stay
    }

    @Test
    fun `sensitive mode steps down early while the buffer is still there`() {
        // 6 Mbps against 1080p's 8: slower than the file, the buffer shrinking from 50 s.
        val sensitive = AutoQualityPolicy(AutoQualityPolicy.SENSITIVE).apply { onSourceChanged(0L) }
        val decision = runDraining(sensitive, 1_000L, 60_000L, startBuffered = 50_000L, drainPerSecond = 400L, speed = 6_000_000L)
        assertEquals(AutoQualityDecision.Change(StreamQuality.P720, isUpgrade = false, early = true), decision)

        // The usual mode waits for a stall or an almost empty buffer.
        val usual = AutoQualityPolicy().apply { onSourceChanged(0L) }
        assertEquals(
            AutoQualityDecision.Stay,
            runDraining(usual, 1_000L, 60_000L, startBuffered = 50_000L, drainPerSecond = 400L, speed = 6_000_000L)
        )
    }

    @Test
    fun `sensitive mode leaves a slow connection alone while the buffer holds`() {
        val sensitive = AutoQualityPolicy(AutoQualityPolicy.SENSITIVE).apply { onSourceChanged(0L) }
        assertEquals(
            AutoQualityDecision.Stay,
            runDraining(sensitive, 1_000L, 60_000L, startBuffered = 50_000L, drainPerSecond = 0L, speed = 6_000_000L)
        )
    }
}
