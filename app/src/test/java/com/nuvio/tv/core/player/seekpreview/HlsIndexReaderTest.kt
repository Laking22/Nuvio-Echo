package com.nuvio.tv.core.player.seekpreview

import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * HLS made with ffmpeg from the files described in [SeekPreviewIndexTest], served over HTTP:
 * hls/ts, hls/fmp4, hls/single (byte ranges), hls/hevc (10-bit) and hls/master.m3u8 (with an audio-only rendition).
 */
class HlsIndexReaderTest {
    private val server = MockWebServer()
    private var ranges = true

    @After
    fun tearDown() = server.shutdown()

    private fun serve(): HttpUrl {
        val dir = System.getenv("SEEK_THUMBS_MEDIA")?.let { File(it, "hls") }
        assumeTrue("SEEK_THUMBS_MEDIA/hls missing", dir?.isDirectory == true)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val file = File(dir, request.requestUrl!!.encodedPath.trimStart('/'))
                if (!file.isFile) return MockResponse().setResponseCode(404)
                val bytes = file.readBytes()
                val type = if (file.name.endsWith(".m3u8")) "application/vnd.apple.mpegurl" else "video/mp2t"
                val range = request.getHeader("Range")?.let { Regex("bytes=(\\d+)-(\\d+)").find(it) }
                if (ranges && range != null && !file.name.endsWith(".m3u8")) {
                    val start = range.groupValues[1].toInt()
                    val end = minOf(range.groupValues[2].toInt(), bytes.size - 1)
                    return MockResponse().setResponseCode(206)
                        .setHeader("Content-Range", "bytes $start-$end/${bytes.size}")
                        .setHeader("Content-Type", type)
                        .setBody(Buffer().write(bytes, start, end - start + 1))
                }
                return MockResponse().setHeader("Content-Type", type).setBody(Buffer().write(bytes))
            }
        }
        server.start()
        return server.url("/")
    }

    private fun check(path: String, codec: VideoCodec, segments: Int, segmentUs: Long) {
        val base = serve()
        val fetcher = SegmentFetcher(OkHttpClient(), emptyMap(), "test", 400L * 1024 * 1024)
        val index = HlsIndexReader.read(fetcher, base.resolve(path)!!)
        println("$path: ${index.video.codec} ${index.video.width}x${index.video.height} ${index.video.bitDepth}-bit " +
            "segments=${index.keyframes.count} fmp4=${index.segments!!.fmp4TrackId >= 0} playlistBytes=${fetcher.bytesUsed}")
        assertEquals(codec, index.video.codec)
        assertEquals(segments, index.keyframes.count)
        assertEquals(segmentUs, index.keyframes.timeUs[1])
        val before = fetcher.bytesUsed
        for (i in 0 until index.keyframes.count) {
            val stored = MediaIndexReader.readKeyframe(index, RangeSourceUnused, i, 12 * 1024 * 1024)
            assertNotNull("keyframe $i of $path", stored)
            val unit = AccessUnits.build(index.video, stored!!)!!
            val types = AccessUnits.nalUnits(unit, codec == VideoCodec.HEVC).map { it.type }
            assertTrue("keyframe $i of $path has $types", if (codec == VideoCodec.HEVC) types.any { it in 16..21 } else 5 in types)
        }
        println("  ${(fetcher.bytesUsed - before) / index.keyframes.count} bytes per keyframe")
    }

    private object RangeSourceUnused : RangeSource {
        override fun read(offset: Long, length: Int): ByteArray = error("HLS reads its own segments")
        override val totalLength: Long = -1
    }

    @Test fun `ts segments`() = check("ts/index.m3u8", VideoCodec.H264, 15, 4_000_000)
    @Test fun `fmp4 segments`() = check("fmp4/index.m3u8", VideoCodec.H264, 15, 4_000_000)
    @Test fun `single file with byte ranges`() = check("single/index.m3u8", VideoCodec.H264, 15, 4_000_000)
    @Test fun `hevc 10-bit ts`() = check("hevc/index.m3u8", VideoCodec.HEVC, 5, 4_000_000)
    @Test fun `master picks a video rendition`() = check("master.m3u8", VideoCodec.H264, 15, 4_000_000)

    @Test
    fun `segments from a server that ignores Range`() {
        ranges = false
        check("ts/index.m3u8", VideoCodec.H264, 15, 4_000_000)
    }
}
