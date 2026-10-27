package com.nuvio.tv.core.player

import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Whether a file carries sound, read from its container's track list before anything plays it. Some
 * addon files are video only; Auto quality checks the source it picked so it never moves to one.
 * Only a file that lists its tracks and has none for audio counts as silent: anything that can't be
 * read in time (a playlist, an odd container, a slow server) is [Result.UNKNOWN], and the switch's own
 * check after it starts covers it.
 */
object AudioTrackProbe {

    enum class Result { HAS_AUDIO, NO_AUDIO, UNKNOWN }

    private const val TAG = "AudioTrackProbe"
    private val cache = LruCache<String, Result>(64)

    /**
     * [listsAudio]: the container's own track list (true when it lists an audio track, null when it can't
     * be read). MediaExtractor leaves out audio tracks the device can't decode itself (TrueHD, DTS-HD on
     * many boxes) which the player may still play, so its "no audio" is checked against that first.
     */
    suspend fun probe(
        url: String,
        headers: Map<String, String>,
        timeoutMs: Long,
        listsAudio: (suspend () -> Boolean?)? = null
    ): Result {
        cache.get(url)?.let { return it }
        if (!isProbeable(url)) return Result.UNKNOWN
        // MediaExtractor blocks in native code and can't be interrupted: it reads on its own thread,
        // and past the timeout the answer is "don't know" while that thread finishes on its own.
        val answer = CompletableDeferred<Result>()
        Thread({ answer.complete(read(url, headers)) }, "audio-track-probe").apply { isDaemon = true }.start()
        var result = withTimeoutOrNull(timeoutMs) { answer.await() } ?: Result.UNKNOWN
        if (result == Result.NO_AUDIO && listsAudio != null) {
            when (withTimeoutOrNull(timeoutMs) { listsAudio() }) {
                true -> {
                    Log.i(TAG, "audio track the platform reader left out (a codec it can't decode itself)")
                    result = Result.HAS_AUDIO
                }
                false -> Unit
                // Can't tell: not "silent" on the platform reader's word alone; the switch checks once it plays.
                null -> result = Result.UNKNOWN
            }
        }
        if (result != Result.UNKNOWN) cache.put(url, result)
        return result
    }

    private fun isProbeable(url: String): Boolean {
        val lower = url.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
        // Playlists and manifests list no tracks of their own.
        val path = lower.substringBefore('?').substringBefore('#')
        return !path.endsWith(".m3u8") && !path.endsWith(".m3u") && !path.endsWith(".mpd")
    }

    private fun read(url: String, headers: Map<String, String>): Result {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(url, FrameRateUtils.extractorProbeHeaders(headers))
            var video = false
            var audio = false
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) audio = true
                if (mime.startsWith("video/")) video = true
            }
            when {
                audio -> Result.HAS_AUDIO
                video -> Result.NO_AUDIO
                else -> Result.UNKNOWN
            }
        } catch (e: Throwable) {
            Log.i(TAG, "couldn't read tracks: ${e.javaClass.simpleName}")
            Result.UNKNOWN
        } finally {
            runCatching { extractor.release() }
        }
    }
}
