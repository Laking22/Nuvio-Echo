package com.nuvio.tv.core.player.seekpreview

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Seek thumbnails kept on the device, one file per cut of a title (a film, or one episode; a theatrical and an
 * extended cut are kept apart by their length), so a title played again shows them from the start. Each file holds
 * the cut's length, then thumbnails as (time, JPEG). The folder is held to [MAX_TOTAL_BYTES], the titles used longest
 * ago going first. Used on the thumbnail thread only.
 */
internal class SeekPreviewDisk(private val dir: File) {
    private var writer: DataOutputStream? = null
    private var file: File? = null
    private var fileLengthMs = 0L

    /**
     * Opens [title]'s file for the cut [lengthMs] long (within [lengthSlackMs]) and returns its thumbnails by time
     * (ms), up to [maxBytes]; later [append]s go to it. A cut seen for the first time gets a new, empty file.
     */
    fun open(title: String, lengthMs: Long, lengthSlackMs: Long, maxBytes: Long): Map<Long, ByteArray> {
        close()
        val bucket = lengthMs / BUCKET_MS
        // A cut whose length sits by a bucket's edge may have been filed in the next one.
        for (candidate in listOf(bucket, bucket - 1, bucket + 1)) {
            val f = fileFor(title, candidate)
            if (!f.isFile) continue
            val frames = read(f, lengthMs, lengthSlackMs, maxBytes) ?: continue
            file = f
            f.setLastModified(System.currentTimeMillis())
            return frames
        }
        file = fileFor(title, bucket)
        fileLengthMs = lengthMs
        return emptyMap()
    }

    /** Thumbnails in [f], or null when it's for another cut (or unreadable). */
    private fun read(f: File, lengthMs: Long, lengthSlackMs: Long, maxBytes: Long): Map<Long, ByteArray>? {
        val frames = HashMap<Long, ByteArray>()
        return try {
            DataInputStream(BufferedInputStream(FileInputStream(f), 64 * 1024)).use { input ->
                if (input.readInt() != MAGIC) throw IllegalStateException("not a thumbnail file")
                val keptLength = input.readLong()
                if (kotlin.math.abs(keptLength - lengthMs) > lengthSlackMs) return null
                fileLengthMs = keptLength
                var bytes = 0L
                while (bytes < maxBytes) {
                    val time = try {
                        input.readLong()
                    } catch (e: EOFException) {
                        break
                    }
                    val size = input.readInt()
                    if (time < 0 || size <= 0 || size > MAX_FRAME_BYTES) break   // a record cut short: keep what came before
                    val jpeg = ByteArray(size)
                    input.readFully(jpeg)
                    if (frames.put(time, jpeg) == null) bytes += size
                }
            }
            frames
        } catch (e: EOFException) {
            frames
        } catch (e: Exception) {
            Log.i(TAG, "kept thumbnails dropped: ${e.message}")
            f.delete()
            null
        }
    }

    /** Adds one thumbnail to the open file, starting it (with the cut's length) if it's new. */
    fun append(timeMs: Long, jpeg: ByteArray) {
        val f = file ?: return
        runCatching {
            val out = writer ?: run {
                dir.mkdirs()
                val fresh = !f.isFile || f.length() == 0L
                DataOutputStream(BufferedOutputStream(FileOutputStream(f, true), 32 * 1024)).also {
                    if (fresh) {
                        it.writeInt(MAGIC)
                        it.writeLong(fileLengthMs)
                    }
                    writer = it
                }
            }
            out.writeLong(timeMs)
            out.writeInt(jpeg.size)
            out.write(jpeg)
            out.flush()
        }.onFailure {
            Log.w(TAG, "couldn't keep a thumbnail (${it.javaClass.simpleName})")
            closeWriter()
        }
    }

    fun close() {
        closeWriter()
        file = null
        fileLengthMs = 0L
    }

    private fun closeWriter() {
        runCatching { writer?.close() }
        writer = null
    }

    /** Removes the titles used longest ago while the folder is over [MAX_TOTAL_BYTES]. */
    fun trim() {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(SUFFIX) }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= MAX_TOTAL_BYTES) break
            if (f == file) continue
            total -= f.length()
            f.delete()
        }
    }

    private fun fileFor(title: String, bucket: Long): File {
        val digest = MessageDigest.getInstance("SHA-1").digest("$title|$bucket|$FILE_VERSION".toByteArray(Charsets.UTF_8))
        return File(dir, digest.joinToString("") { "%02x".format(it) } + SUFFIX)
    }

    private companion object {
        const val TAG = "SeekThumbs"
        const val MAGIC = 0x4E535032   // "NSP2": one file per cut
        const val SUFFIX = ".thumbs"
        const val MAX_FRAME_BYTES = 512 * 1024
        /**
         * Bumped when files from earlier builds can't be trusted; they're then left to [trim]. 4: frames from other
         * episodes' copies of the same length had got in.
         */
        const val FILE_VERSION = 4
        /** Cuts are filed by length in steps of this. */
        const val BUCKET_MS = 2L * 60_000L
        /** About 15–40 titles' worth. */
        const val MAX_TOTAL_BYTES = 150L * 1024 * 1024
    }
}
