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
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * DASH made with ffmpeg's dash muxer from the files described in [SeekPreviewIndexTest], served over HTTP:
 * dash/timeline (SegmentTemplate + SegmentTimeline, a 720p copy and a 360p re-encode, plus audio),
 * dash/number (SegmentTemplate with a fixed duration), dash/list (one file, SegmentList byte ranges),
 * dash/sidx (one file, SegmentBase with a sidx), dash/hevc (10-bit) and dash/time ($Time$, r=-1, prefixed XML).
 * The manifest-only tests need no files.
 */
class DashIndexReaderTest {
    private val server = MockWebServer()
    private val manifests = HashMap<String, String>()

    @After
    fun tearDown() = server.shutdown()

    private fun serve(media: Boolean = true): HttpUrl {
        val dir = System.getenv("SEEK_THUMBS_MEDIA")?.let { File(it, "dash") }
        if (media) assumeTrue("SEEK_THUMBS_MEDIA/dash missing", dir?.isDirectory == true)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath.trimStart('/')
                manifests[path]?.let { return MockResponse().setHeader("Content-Type", "application/dash+xml").setBody(it) }
                val file = File(dir ?: return MockResponse().setResponseCode(404), path)
                if (!file.isFile) return MockResponse().setResponseCode(404)
                val bytes = file.readBytes()
                val type = if (file.name.endsWith(".mpd")) "application/dash+xml" else "video/mp4"
                val range = request.getHeader("Range")?.let { Regex("bytes=(\\d+)-(\\d+)").find(it) }
                if (range != null && !file.name.endsWith(".mpd")) {
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

    private fun check(path: String, codec: VideoCodec, segments: Int, segmentUs: Long, width: Int) {
        val base = serve()
        val fetcher = SegmentFetcher(OkHttpClient(), emptyMap(), "test", 400L * 1024 * 1024)
        val index = DashIndexReader.read(fetcher, base.resolve(path)!!)
        println("$path: ${index.video.codec} ${index.video.width}x${index.video.height} ${index.video.bitDepth}-bit " +
            "segments=${index.keyframes.count} manifestAndInitBytes=${fetcher.bytesUsed}")
        assertEquals(Container.DASH, index.container)
        assertEquals(codec, index.video.codec)
        assertEquals(width, index.video.width)
        assertEquals(segments, index.keyframes.count)
        assertTrue("second segment at ${index.keyframes.timeUs[1]}", kotlin.math.abs(segmentUs - index.keyframes.timeUs[1]) <= 2_000)
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
        override fun read(offset: Long, length: Int): ByteArray = error("DASH reads its own segments")
        override val totalLength: Long = -1
    }

    @Test fun `template with timeline picks the lightest video`() = check("timeline/manifest.mpd", VideoCodec.H264, 15, 4_000_000, 640)
    @Test fun `template with a fixed duration`() = check("number/manifest.mpd", VideoCodec.H264, 15, 4_000_000, 1280)
    @Test fun `segment list byte ranges`() = check("list/manifest.mpd", VideoCodec.H264, 15, 4_000_000, 1280)
    @Test fun `segment base with sidx`() = check("sidx/manifest.mpd", VideoCodec.H264, 15, 4_000_000, 1280)
    @Test fun `hevc 10-bit`() = check("hevc/manifest.mpd", VideoCodec.HEVC, 5, 3_916_000, 1920)
    @Test fun `time addressing repeated to the period end`() = check("time/manifest.mpd", VideoCodec.H264, 15, 4_000_000, 640)

    private fun refused(manifest: String, reason: String) {
        manifests["m.mpd"] = manifest
        val base = serve(media = false)
        try {
            DashIndexReader.read(SegmentFetcher(OkHttpClient(), emptyMap(), "test", 1024 * 1024), base.resolve("m.mpd")!!)
            fail("expected refusal: $reason")
        } catch (e: UnsupportedMediaException) {
            assertEquals(reason, e.message)
        }
    }

    private fun mpd(type: String = "static", set: String = "", rep: String = "", periods: Int = 1): String {
        val period = """<Period><AdaptationSet mimeType="video/mp4" codecs="avc1.64001f">$set
            <SegmentTemplate timescale="1000" duration="4000" initialization="i.mp4" media="${'$'}Number${'$'}.m4s"/>
            <Representation id="1" bandwidth="1000" width="1280" height="720">$rep</Representation>
            </AdaptationSet></Period>"""
        return """<?xml version="1.0"?><MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="$type"
            mediaPresentationDuration="PT60S">${period.repeat(periods)}</MPD>"""
    }

    @Test fun `live is refused`() = refused(mpd(type = "dynamic"), "live DASH")
    @Test fun `multi-period is refused`() = refused(mpd(periods = 2), "multi-period DASH")
    @Test fun `encrypted is refused`() =
        refused(mpd(set = """<ContentProtection schemeIdUri="urn:mpeg:dash:mp4protection:2011" value="cenc"/>"""), "encrypted DASH")
    @Test fun `webm is refused`() = refused(mpd().replace("video/mp4", "video/webm"), "DASH container video/webm")
    @Test fun `not xml is refused`() = refused("#EXTM3U", "bad DASH manifest")

    @Test
    fun `an MPD read as a file is recognised`() {
        val head = mpd().toByteArray() + ByteArray(64)
        val source = object : RangeSource {
            override fun read(offset: Long, length: Int) = head.copyOfRange(offset.toInt(), minOf(head.size, offset.toInt() + length))
            override val totalLength = head.size.toLong()
        }
        try {
            MediaIndexReader.read(source)
            fail("expected DashManifestFound")
        } catch (_: DashManifestFound) {
        }
    }
}
