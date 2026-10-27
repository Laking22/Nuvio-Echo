package com.nuvio.tv.core.speedtest

import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window
import kotlinx.coroutines.delay

/**
 * One drawn frame: when its vsync was due and how long it took, both in nanoseconds.
 * [deadlineNanos] is the time the system allowed it (Android 12+), else 0.
 */
data class FrameSample(val vsyncNanos: Long, val durationNanos: Long, val deadlineNanos: Long = 0) {
    val endNanos: Long get() = vsyncNanos + durationNanos

    /** Missed its deadline, as gfxinfo counts jank; before Android 12, slower than 16.7 ms. */
    val janky: Boolean get() = durationNanos > (deadlineNanos.takeIf { it > 0 } ?: LEGACY_BUDGET_NANOS)

    private companion object {
        const val LEGACY_BUDGET_NANOS = 16_666_667L
    }
}

data class FrameStats(
    val frames: Int,
    val p50Ms: Double,
    val p90Ms: Double,
    val maxMs: Double,
    /** Share of frames that missed their deadline, as gfxinfo counts jank. */
    val jankyPercent: Double,
    /** Frames over 50 ms: hitches a viewer notices. */
    val stutters: Int
) {
    companion object {
        fun of(samples: List<FrameSample>): FrameStats {
            if (samples.isEmpty()) return FrameStats(0, 0.0, 0.0, 0.0, 0.0, 0)
            val ms = samples.map { it.durationNanos / 1_000_000.0 }.sorted()
            val janky = samples.count { it.janky }
            fun percentile(p: Double) = ms[((ms.size - 1) * p).toInt()]
            return FrameStats(
                frames = ms.size,
                p50Ms = percentile(0.5),
                p90Ms = percentile(0.9),
                maxMs = ms.last(),
                jankyPercent = janky * 100.0 / ms.size,
                stutters = ms.count { it > STUTTER_MS }
            )
        }

        private const val STUTTER_MS = 50.0
    }
}

/**
 * Records every frame the window draws, only between [start] and [stop]. Nothing is attached to
 * the window outside a speed test, so the app pays nothing for it the rest of the time.
 */
class FrameRecorder(private val window: Window) {
    private val samples = ArrayList<FrameSample>()
    private var thread: HandlerThread? = null
    private val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
        val sample = FrameSample(
            vsyncNanos = metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP),
            durationNanos = metrics.getMetric(FrameMetrics.TOTAL_DURATION),
            deadlineNanos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) metrics.getMetric(FrameMetrics.DEADLINE) else 0
        )
        synchronized(samples) { samples += sample }
    }

    /** Must be called on the main thread. */
    fun start() {
        if (thread != null) return
        val handlerThread = HandlerThread("SpeedTestFrames").also { it.start() }
        thread = handlerThread
        window.addOnFrameMetricsAvailableListener(listener, Handler(handlerThread.looper))
    }

    /** Must be called on the main thread. */
    fun stop() {
        val handlerThread = thread ?: return
        runCatching { window.removeOnFrameMetricsAvailableListener(listener) }
        handlerThread.quitSafely()
        thread = null
    }

    fun clear() = synchronized(samples) { samples.clear() }

    fun snapshot(): List<FrameSample> = synchronized(samples) { samples.toList() }

    fun framesSince(nanos: Long): List<FrameSample> = snapshot().filter { it.vsyncNanos >= nanos }

    /**
     * Waits until no frame has been drawn for [quietMs] (the screen has settled), up to
     * [timeoutMs]. Returns when the last frame before the quiet spell finished, in nanoTime.
     */
    suspend fun awaitQuiet(sinceNanos: Long, quietMs: Long, timeoutMs: Long): Long {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            delay(QUIET_POLL_MS)
            val last = framesSince(sinceNanos).maxOfOrNull { it.endNanos } ?: sinceNanos
            if ((System.nanoTime() - last) / 1_000_000 >= quietMs) return last
        }
        return framesSince(sinceNanos).maxOfOrNull { it.endNanos } ?: System.nanoTime()
    }

    private companion object {
        const val QUIET_POLL_MS = 50L
    }
}
