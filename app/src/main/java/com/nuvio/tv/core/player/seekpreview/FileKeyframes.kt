package com.nuvio.tv.core.player.seekpreview

import okhttp3.OkHttpClient
import java.io.IOException

/**
 * What a file's own index says, read the way the seek thumbnails read it (MKV Tracks and Cues, MP4 moov):
 * its keyframe times, for picking a spot where a source started there has nothing to decode before its
 * first picture, and whether it lists an audio track at all (any codec, unlike Android's MediaExtractor,
 * which leaves out tracks it can't decode itself). Null for HLS and DASH, or anything that can't be read.
 */
internal object FileKeyframes {
    /** The index alone: MP4s with a large moov read only the video track. */
    private const val BUDGET_BYTES = 24L * 1024 * 1024

    class Summary(
        /** Keyframe times (µs); null for a Matroska file without Cues (its keyframes would only be estimates). */
        val keyframesUs: LongArray?,
        /** Null when only the video track was read (a large MP4 moov). */
        val hasAudio: Boolean?
    )

    fun read(
        client: OkHttpClient,
        url: String,
        headers: Map<String, String>,
        userAgent: String,
        durationMs: Long
    ): Summary? {
        val source = try {
            HttpRangeSource(client, url, headers, userAgent, BUDGET_BYTES)
        } catch (e: IOException) {
            return null
        }
        return try {
            val index = MediaIndexReader.read(source, durationMs * 1000L)
            Summary(
                keyframesUs = index.keyframes.timeUs.takeIf { index.cueless == null && it.size >= 2 },
                hasAudio = index.hasAudio
            )
        } catch (e: IOException) {
            null
        } catch (e: RuntimeException) {
            null
        } finally {
            source.close()
        }
    }
}
