package com.nuvio.tv.core.player.seekpreview

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

class SeekPreviewIndexTest {

    private class FileSource(file: File) : RangeSource {
        private val raf = RandomAccessFile(file, "r")
        var reads = 0
        var bytes = 0L
        override val totalLength: Long = file.length()
        override fun read(offset: Long, length: Int): ByteArray {
            reads++
            if (offset >= totalLength) return ByteArray(0)
            val n = minOf(length.toLong(), totalLength - offset).toInt()
            val out = ByteArray(n)
            raf.seek(offset)
            raf.readFully(out)
            bytes += n
            return out
        }
    }

    @Test
    fun `nearest picks the closer keyframe`() {
        val table = KeyframeTable(longArrayOf(0, 2_000_000, 4_000_000), LongArray(3), LongArray(3), IntArray(3))
        assertEquals(0, table.nearest(900_000))
        assertEquals(1, table.nearest(1_100_000))
        assertEquals(2, table.nearest(9_000_000))
        assertEquals(0, table.nearest(-5))
    }

    @Test
    fun `length-prefixed NALs become Annex-B behind the parameter sets`() {
        val track = VideoTrackInfo(
            VideoCodec.HEVC, 1920, 1080, 0.0, 10,
            nalLengthSize = 4, parameterSets = byteArrayOf(0, 0, 0, 1, 0x40, 0x01),
        )
        // An IDR slice (type 19) and a Dolby Vision RPU (type 62), which is dropped.
        val sample = byteArrayOf(0, 0, 0, 2, 0x26, 0x01, 0, 0, 0, 2, 0x7C, 0x01)
        val out = AccessUnits.build(track, sample)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x40, 0x01, 0, 0, 0, 1, 0x26, 0x01), out)
    }

    @Test
    fun `parameter sets are found inside a keyframe when the header has none`() {
        val track = VideoTrackInfo(VideoCodec.HEVC, 3840, 2160, 0.0, 10)
        assertTrue(track.needsParameterSets)
        // PPS (34), VPS (32), SPS (33), an IDR slice (19), and the VPS again.
        val unit = byteArrayOf(
            0, 0, 0, 1, 0x44, 0x01, 0x0A,
            0, 0, 1, 0x40, 0x01, 0x0B,
            0, 0, 0, 1, 0x42, 0x01, 0x0C,
            0, 0, 0, 1, 0x26, 0x01, 0x0D,
            0, 0, 0, 1, 0x40, 0x01, 0x0B,
        )
        val sets = AccessUnits.parameterSets(track, unit)
        assertArrayEquals(
            byteArrayOf(0, 0, 0, 1, 0x40, 0x01, 0x0B, 0, 0, 0, 1, 0x42, 0x01, 0x0C, 0, 0, 0, 1, 0x44, 0x01, 0x0A),
            sets,
        )
        val filled = track.withParameterSets(sets)
        assertTrue(!filled.needsParameterSets)
        assertArrayEquals(sets, filled.csd.single())
    }

    @Test
    fun `h264 parameter sets split into SPS and PPS csd`() {
        val track = VideoTrackInfo(VideoCodec.H264, 1920, 1080, 0.0, 8)
        val unit = byteArrayOf(0, 0, 0, 1, 0x67, 0x64, 0, 0, 0, 1, 0x68, 0x01, 0, 0, 0, 1, 0x65, 0x02)
        val filled = track.withParameterSets(AccessUnits.parameterSets(track, unit))
        assertEquals(2, filled.csd.size)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x67, 0x64), filled.csd[0])
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x68, 0x01), filled.csd[1])
    }

    @Test
    fun `header stripping is undone before conversion`() {
        val track = VideoTrackInfo(VideoCodec.VP9, 0, 0, 0.0, 8, strippedHeader = byteArrayOf(9, 8))
        assertArrayEquals(byteArrayOf(9, 8, 7), AccessUnits.build(track, byteArrayOf(7)))
    }

    /**
     * Files made with ffmpeg (see the commit that added this); set SEEK_THUMBS_MEDIA to their folder:
     * h264.mkv (60 s, keyframe every 2 s, with audio), hevc10.mkv (20 s, 10-bit PQ),
     * h264_front.mp4 / h264_end.mp4 (moov before / after mdat), hevc10.mp4.
     */
    private fun media(name: String): File {
        val dir = System.getenv("SEEK_THUMBS_MEDIA")
        assumeTrue("SEEK_THUMBS_MEDIA not set", dir != null)
        return File(dir, name).also { assumeTrue("$name missing", it.isFile) }
    }

    private fun checkFile(name: String, codec: VideoCodec, keyframes: Int, bitDepth: Int, container: Container) {
        val source = FileSource(media(name))
        val index = MediaIndexReader.read(source)
        println("$name: ${index.container} ${index.video.codec} ${index.video.width}x${index.video.height} " +
            "${index.video.bitDepth}-bit transfer=${index.video.transfer} keyframes=${index.keyframes.count} " +
            "indexReads=${source.reads} indexBytes=${source.bytes}")
        assertEquals(container, index.container)
        assertEquals(codec, index.video.codec)
        assertEquals(bitDepth, index.video.bitDepth)
        assertEquals(keyframes, index.keyframes.count)
        for (i in 0 until index.keyframes.count) {
            assertEquals(i * 2_000_000L, index.keyframes.timeUs[i], 50_000.0)
        }
        // Every keyframe is found and starts with a random-access NAL.
        val before = source.bytes
        for (i in 0 until index.keyframes.count) {
            val stored = MediaIndexReader.readKeyframe(index, source, i, 12 * 1024 * 1024)
            assertNotNull("keyframe $i of $name", stored)
            val unit = AccessUnits.build(index.video, stored!!)!!
            val types = nalTypes(unit, codec)
            val randomAccess = if (codec == VideoCodec.H264) 5 in types else types.any { it in 16..21 }
            assertTrue("keyframe $i of $name has NAL types $types", randomAccess)
        }
        println("  keyframe reads: ${(source.bytes - before) / index.keyframes.count} bytes each on average")
    }

    private fun assertEquals(expected: Long, actual: Long, tolerance: Double) {
        assertTrue("expected $expected but was $actual", kotlin.math.abs(expected - actual) <= tolerance)
    }

    private fun nalTypes(annexB: ByteArray, codec: VideoCodec): List<Int> {
        val types = ArrayList<Int>()
        var i = 0
        while (i + 3 < annexB.size) {
            if (annexB[i].toInt() == 0 && annexB[i + 1].toInt() == 0 && annexB[i + 2].toInt() == 1) {
                val h = annexB[i + 3].toInt()
                types += if (codec == VideoCodec.HEVC) (h shr 1) and 0x3F else h and 0x1F
                i += 3
            } else {
                i++
            }
        }
        return types
    }

    @Test fun `mkv h264`() = checkFile("h264.mkv", VideoCodec.H264, 30, 8, Container.MATROSKA)
    @Test fun `mkv hevc 10-bit`() = checkFile("hevc10.mkv", VideoCodec.HEVC, 10, 10, Container.MATROSKA)
    @Test fun `mp4 moov in front`() = checkFile("h264_front.mp4", VideoCodec.H264, 30, 8, Container.MP4)
    @Test fun `mp4 moov at the end`() = checkFile("h264_end.mp4", VideoCodec.H264, 30, 8, Container.MP4)
    @Test fun `mp4 hevc 10-bit`() = checkFile("hevc10.mp4", VideoCodec.HEVC, 10, 10, Container.MP4)

    private fun checkCueless(name: String, codec: VideoCodec, slots: Int) {
        val source = FileSource(media(name))
        val index = MediaIndexReader.read(source)
        assertNotNull("$name should get an estimated index", index.cueless)
        assertEquals(slots, index.keyframes.count)
        for (i in 0 until index.keyframes.count) {
            val stored = MediaIndexReader.readKeyframe(index, source, i, 12 * 1024 * 1024)
            assertNotNull("slot $i of $name", stored)
            val t = index.keyframes.timeUs[i]
            println("$name slot ${i * 10}s -> keyframe at ${t / 1000}ms")
            assertTrue("slot $i of $name landed at $t", kotlin.math.abs(t - i * 10_000_000L) <= 15_000_000L)
            assertEquals("keyframes are every 2 s", 0L, t % 2_000_000L / 1000)
            val types = nalTypes(AccessUnits.build(index.video, stored!!)!!, codec)
            assertTrue(if (codec == VideoCodec.HEVC) types.any { it in 16..21 } else 5 in types)
        }
    }

    @Test fun `mkv without cues`() = checkCueless("nocues.mkv", VideoCodec.H264, 7)
    @Test fun `hevc mkv without cues`() = checkCueless("nocues_hevc.mkv", VideoCodec.HEVC, 3)

    @Test
    fun `large moov reads only the video track`() {
        val file = media("h264_end.mp4")
        val full = Mp4IndexReader.read(FileSource(file), FileSource(file).read(0, MediaIndexReader.HEAD_BYTES))
        val source = FileSource(file)
        val slim = Mp4IndexReader.read(source, source.read(0, MediaIndexReader.HEAD_BYTES), slimAbove = 1024)
        assertArrayEquals(full.keyframes.timeUs, slim.keyframes.timeUs)
        assertArrayEquals(full.keyframes.offset, slim.keyframes.offset)
        assertArrayEquals(full.keyframes.size, slim.keyframes.size)
        assertEquals(full.video.width, slim.video.width)
    }
}
