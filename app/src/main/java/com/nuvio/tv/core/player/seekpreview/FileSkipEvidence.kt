package com.nuvio.tv.core.player.seekpreview

import okhttp3.OkHttpClient
import java.io.IOException

/** One chapter as the file lists it; [endMs] is null when the container gives only starts. */
internal class MediaChapter(val startMs: Long, val endMs: Long?, val title: String)

/**
 * What a file says about itself that skip times can be fitted to, read the way the seek thumbnails read its index:
 * its chapters (MKV Chapters, MP4 Nero `chpl`) and, when asked, its keyframe times. Null for HLS and DASH, or
 * anything that can't be read at all.
 */
internal object FileSkipEvidence {
    /** The index alone: MP4s with a large moov read only the video track. */
    private const val BUDGET_BYTES = 24L * 1024 * 1024

    class Evidence(
        val chapters: List<MediaChapter>,
        /** Keyframe times (ms); null when not asked for, or the file has no index to take them from. */
        val keyframesMs: LongArray?
    )

    fun read(
        client: OkHttpClient,
        url: String,
        headers: Map<String, String>,
        userAgent: String,
        durationMs: Long,
        keyframes: Boolean
    ): Evidence? {
        val source = try {
            HttpRangeSource(client, url, headers, userAgent, BUDGET_BYTES)
        } catch (e: IOException) {
            return null
        }
        return try {
            val head = source.read(0, MediaIndexReader.HEAD_BYTES)
            if (head.size < 16) return null
            // Each part on its own: a file whose video the thumbnails can't decode still has its chapters.
            val chapters = runCatching {
                when {
                    MatroskaIndexReader.looksLikeMatroska(head) -> MatroskaIndexReader.readChapters(source, head)
                    Mp4IndexReader.looksLikeMp4(head) -> Mp4IndexReader.readChapters(source, head)
                    else -> emptyList()
                }
            }.getOrDefault(emptyList())
            val times = if (!keyframes) null else runCatching {
                val index = MediaIndexReader.read(source, durationMs * 1000L, head)
                index.keyframes.timeUs
                    .takeIf { index.cueless == null && it.size >= 2 }
                    ?.let { us -> LongArray(us.size) { us[it] / 1000L } }
            }.getOrNull()
            Evidence(chapters, times)
        } catch (e: IOException) {
            null
        } catch (e: RuntimeException) {
            null
        } finally {
            source.close()
        }
    }
}
