package com.nuvio.tv.core.player.seekpreview

import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/** A Range read reached an HLS playlist (served without a telling extension): read the stream as HLS. */
internal class HlsPlaylistFound : IOException("HLS playlist")

/** A Range read reached a DASH manifest (served without a telling extension): read the stream as DASH. */
internal class DashManifestFound : IOException("DASH manifest")

/**
 * Exact byte ranges of one stream through the player's HTTP client, so keep-alive connections, DNS and
 * certificates are shared with playback. Servers that ignore Range are refused rather than downloaded,
 * except for small files ([plainPrefix]: HLS segments), where the start of the whole response is read
 * and the rest dropped. Never logs the URL or headers.
 */
internal class HttpRangeSource(
    private val client: OkHttpClient,
    url: HttpUrl,
    private val headers: Map<String, String>,
    private val userAgent: String,
    /** Most this stream may download for thumbnails. */
    private val budgetBytes: Long,
    /** Bytes downloaded so far; shared by the sources of one stream (an HLS stream's segments). */
    private val used: AtomicLong = AtomicLong(),
    private val plainPrefix: Boolean = false,
) : RangeSource, Closeable {
    constructor(client: OkHttpClient, url: String, headers: Map<String, String>, userAgent: String, budgetBytes: Long) :
        this(client, url.toHttpUrlOrNull() ?: throw UnsupportedMediaException("not an HTTP URL"), headers, userAgent, budgetBytes)

    /** The URL as given (before any redirect). */
    val url: HttpUrl = url

    // After the first response this is the final URL, so redirects aren't followed again for every read.
    @Volatile private var target: HttpUrl = url
    // Every request in flight: thumbnails may read several keyframes at once.
    private val calls = java.util.concurrent.ConcurrentHashMap.newKeySet<Call>()
    @Volatile private var closed = false

    @Volatile override var totalLength: Long = -1L
        private set

    /** Longest any one request may take, start to finish (0: the client's own timeouts). */
    @Volatile var callTimeoutMs = 0L

    val bytesUsed: Long get() = used.get()

    override fun read(offset: Long, length: Int): ByteArray {
        var out = readOnce(offset, length)
        // Some servers cap how much one range returns; ask again for the rest.
        while (out.size in 1 until length) {
            val more = readOnce(offset + out.size, length - out.size)
            if (more.isEmpty()) break
            out += more
        }
        return out
    }

    private fun readOnce(offset: Long, length: Int): ByteArray {
        if (closed) throw IOException("closed")
        if (length <= 0) return ByteArray(0)
        val total = totalLength
        if (total in 0..offset) return ByteArray(0)
        val end = if (total > 0) minOf(offset + length - 1, total - 1) else offset + length - 1
        val want = (end - offset + 1).toInt()
        if (used.addAndGet(want.toLong()) > budgetBytes) throw UnsupportedMediaException("thumbnail download budget used up")

        val request = Request.Builder().url(target).apply {
            headers.forEach { (name, value) ->
                if (name.isNotBlank() && !name.equals("Range", true) && !name.equals("Accept-Encoding", true)) header(name, value)
            }
            if (headers.keys.none { it.equals("User-Agent", true) }) header("User-Agent", userAgent)
            header("Range", "bytes=$offset-$end")
            header("Accept-Encoding", "identity")
        }.build()
        val c = client.newCall(request)
        callTimeoutMs.takeIf { it > 0 }?.let { c.timeout().timeout(it, java.util.concurrent.TimeUnit.MILLISECONDS) }
        calls += c
        if (closed) c.cancel()
        try {
            return readResponse(c, offset, want)
        } finally {
            calls -= c
        }
    }

    private fun readResponse(c: Call, offset: Long, want: Int): ByteArray {
        c.execute().use { response ->
            if (response.code == 416) {
                Regex("bytes \\*/(\\d+)").find(response.header("Content-Range").orEmpty())
                    ?.groupValues?.get(1)?.toLongOrNull()?.let { totalLength = it }
                return ByteArray(0)
            }
            if (response.code == 200 && plainPrefix && offset <= MAX_PLAIN_SKIP) {
                return readPlainPrefix(response, offset, want).also { used.addAndGet((it.size - want).toLong()) }
            }
            if (response.code == 200 && response.header("Content-Type").orEmpty().contains("mpegurl", ignoreCase = true)) {
                throw HlsPlaylistFound()
            }
            if (response.code == 200 && response.header("Content-Type").orEmpty().contains("dash+xml", ignoreCase = true)) {
                throw DashManifestFound()
            }
            // A 200 would be the whole file: never read it.
            if (response.code != 206) {
                // The whole file, or the refusals some servers give range requests (seen: 400, 428). Others (403, 404,
                // 5xx) are this link or this moment, not the server's way: a plain failure.
                if (response.code in 200..299 || response.code == 400 || response.code == 428) {
                    throw RangesRefusedException(response.code)
                }
                throw IOException("HTTP ${response.code}")
            }
            val encoding = response.header("Content-Encoding")
            if (encoding != null && !encoding.equals("identity", true)) throw UnsupportedMediaException("encoded range")
            val range = Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)").find(response.header("Content-Range").orEmpty())
                ?: throw UnsupportedMediaException("no Content-Range")
            if (range.groupValues[1].toLong() != offset) throw UnsupportedMediaException("wrong range")
            range.groupValues[3].toLongOrNull()?.let { totalLength = it }
            target = response.request.url
            val count = (range.groupValues[2].toLong() - offset + 1).toInt().coerceAtMost(want)
            used.addAndGet((count - want).toLong())   // the budget was charged for the request; keep what came
            val body = response.body ?: throw IOException("no body")
            val out = ByteArray(count)
            body.byteStream().use { input ->
                var read = 0
                while (read < count) {
                    if (closed) throw IOException("closed")
                    val n = input.read(out, read, count - read)
                    if (n < 0) throw EOFException("short range")
                    read += n
                }
            }
            return out
        }
    }

    /** A server that ignored Range sent the whole file: skip to [offset], keep [want] bytes, drop the rest. */
    private fun readPlainPrefix(response: okhttp3.Response, offset: Long, want: Int): ByteArray {
        val encoding = response.header("Content-Encoding")
        if (encoding != null && !encoding.equals("identity", true)) throw UnsupportedMediaException("encoded segment")
        response.body?.contentLength()?.takeIf { it >= 0 }?.let { totalLength = it }
        target = response.request.url
        val body = response.body ?: throw IOException("no body")
        val out = java.io.ByteArrayOutputStream(want)
        body.byteStream().use { input ->
            var skip = offset
            val chunk = ByteArray(16 * 1024)
            while (skip > 0) {
                if (closed) throw IOException("closed")
                val n = input.read(chunk, 0, minOf(chunk.size.toLong(), skip).toInt())
                if (n < 0) return ByteArray(0)
                skip -= n
            }
            while (out.size() < want) {
                if (closed) throw IOException("closed")
                val n = input.read(chunk, 0, minOf(chunk.size, want - out.size()))
                if (n < 0) break
                out.write(chunk, 0, n)
            }
        }
        return out.toByteArray()
    }

    /** Stops the reads in progress, if any; later reads still work. */
    fun cancel() {
        calls.forEach { it.cancel() }
    }

    override fun close() {
        closed = true
        calls.forEach { it.cancel() }
    }

    private companion object {
        /** Furthest a plain (non-Range) response is skipped into: segments are small, files are not. */
        const val MAX_PLAIN_SKIP = 8L * 1024 * 1024
    }
}
