package com.nuvio.tv.core.player.seekpreview

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream

class MediaChaptersTest {

    private class BytesSource(private val bytes: ByteArray) : RangeSource {
        override val totalLength: Long = bytes.size.toLong()
        override fun read(offset: Long, length: Int): ByteArray {
            if (offset >= bytes.size) return ByteArray(0)
            return bytes.copyOfRange(offset.toInt(), minOf(bytes.size, offset.toInt() + length))
        }
    }

    // --- Matroska ---

    private fun idBytes(id: Long): ByteArray {
        val length = (64 - java.lang.Long.numberOfLeadingZeros(id) + 7) / 8
        return ByteArray(length) { i -> (id shr (8 * (length - 1 - i))).toByte() }
    }

    /** Every size in the 8-byte form, so lengths are easy to work out. */
    private fun el(id: Long, vararg body: ByteArray): ByteArray {
        val data = body.fold(ByteArray(0)) { acc, b -> acc + b }
        val size = ByteArray(8) { i -> if (i == 0) 0x01 else (data.size.toLong() shr (8 * (7 - i))).toByte() }
        return idBytes(id) + size + data
    }

    private fun uint(v: Long): ByteArray = ByteArray(8) { i -> (v shr (8 * (7 - i))).toByte() }

    private fun atom(startMs: Long, title: String, hidden: Boolean = false): ByteArray = el(
        0xB6,
        el(0x91, uint(startMs * 1_000_000L)),
        if (hidden) el(0x98, uint(1)) else ByteArray(0),
        el(0x80, el(0x85, title.toByteArray(Charsets.UTF_8)), el(0x437C, "eng".toByteArray()))
    )

    private val ebmlHeader = el(0x1A45DFA3, el(0x4282, "matroska".toByteArray()))

    private val chapters = el(
        0x1043A770,
        el(0x45B9, el(0x45DB, uint(1)), atom(0, "Prologue"), atom(95_000, "Opening"), atom(185_000, "Part A"), atom(1_300_000, "Ending"), atom(1_390_000, "Hidden", hidden = true))
    )

    @Test
    fun `matroska chapters in the first bytes`() {
        val file = ebmlHeader + el(0x18538067, el(0x1549A966, el(0x2AD7B1, uint(1_000_000))), chapters)
        val list = MatroskaIndexReader.readChapters(BytesSource(file), file)
        assertEquals(listOf("Prologue", "Opening", "Part A", "Ending"), list.map { it.title })
        assertEquals(listOf(0L, 95_000L, 185_000L, 1_300_000L), list.map { it.startMs })
    }

    @Test
    fun `matroska chapters where the seek head points`() {
        // SeekHead, then 300 KB of something, then Chapters: past the first bytes, found by its position.
        val filler = el(0xEC, ByteArray(300 * 1024))
        fun seekHead(chaptersAt: Long) = el(0x114D9B74, el(0x4DBB, el(0x53AB, idBytes(0x1043A770)), el(0x53AC, uint(chaptersAt))))
        val headLength = seekHead(0).size
        val segmentBody = seekHead((headLength + filler.size).toLong()) + filler + chapters
        val file = ebmlHeader + el(0x18538067, segmentBody)
        val head = file.copyOf(MediaIndexReader.HEAD_BYTES)
        val list = MatroskaIndexReader.readChapters(BytesSource(file), head)
        assertEquals(listOf("Prologue", "Opening", "Part A", "Ending"), list.map { it.title })
    }

    // --- MP4 ---

    private fun box(type: String, vararg body: ByteArray): ByteArray {
        val data = body.fold(ByteArray(0)) { acc, b -> acc + b }
        val size = data.size + 8
        return byteArrayOf((size shr 24).toByte(), (size shr 16).toByte(), (size shr 8).toByte(), size.toByte()) +
            type.toByteArray(Charsets.ISO_8859_1) + data
    }

    private fun chpl(vararg chapters: Pair<Long, String>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(1, 0, 0, 0, 0, 0, 0, 0))
        out.write(chapters.size)
        for ((ms, title) in chapters) {
            out.write(uint(ms * 10_000L))
            val t = title.toByteArray(Charsets.UTF_8)
            out.write(t.size)
            out.write(t)
        }
        return box("chpl", out.toByteArray())
    }

    @Test
    fun `mp4 nero chapters`() {
        val file = box("ftyp", "isom".toByteArray(), ByteArray(4)) +
            box("moov", box("mvhd", ByteArray(100)), box("udta", chpl(0L to "Intro", 62_500L to "Episode", 1_250_000L to "Credits")))
        val list = Mp4IndexReader.readChapters(BytesSource(file), file)
        assertEquals(listOf("Intro", "Episode", "Credits"), list.map { it.title })
        assertEquals(listOf(0L, 62_500L, 1_250_000L), list.map { it.startMs })
    }
}
