package com.nuvio.tv.core.player.seekpreview

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.roundToInt

/**
 * Decodes single keyframes into small bitmaps: MediaCodec renders into a SurfaceTexture, and a shader
 * box-filters it down (tone-mapping HDR) into an offscreen buffer that is read back. A software decoder
 * is used when it can take the stream; otherwise a hardware one (TV boxes usually allow several at once), never a
 * secure or tunnelled one. Not thread-safe: one thread owns it, since it holds an EGL context.
 */
internal class KeyframeDecoder : Closeable {
    private var gl: GlThumbRenderer? = null
    private var codec: MediaCodec? = null
    private var codecTrack: VideoTrackInfo? = null
    var codecName: String? = null
        private set
    /** True when the last decode found no decoder that can take the stream at all. */
    var noDecoder = false
        private set
    /** True when the last decode failed only because the device had no video memory free for a decoder. */
    var busy = false
        private set
    /** A decoder start given up on is still waiting for video memory; no other is tried until it ends. */
    @Volatile private var lateStartPending = false
    private var needsFlush = false
    /** False while the decoder may still be working through the last end of stream. */
    private var codecIdle = true
    private var nextPtsUs = 1_000_000L
    private var candidatesFor: VideoTrackInfo? = null
    private var candidates: List<String> = emptyList()
    private var softwareCandidates: Set<String> = emptySet()
    /** The open decoder is a hardware one, which shares the chip with playback. */
    private var codecIsHardware = false
    /** The codec in use is a hardware one (shared with playback); false with none or a software one. */
    val isHardware: Boolean get() = codecName != null && codecIsHardware
    private val brokenCodecs = HashSet<String>()

    // From the decoder's output format.
    private var frameWidth = 0
    private var frameHeight = 0
    private var outputTransfer = 0

    /** One keyframe access unit to a bitmap fitting [maxWidth] x [maxHeight]; null when it can't be decoded. */
    fun decode(track: VideoTrackInfo, accessUnit: ByteArray, maxWidth: Int, maxHeight: Int): Bitmap? {
        val renderer = gl ?: GlThumbRenderer().also { gl = it }
        noDecoder = false
        busy = false
        val rendered = try {
            val c = ensureCodec(track, renderer, largeInput = null) ?: return null
            var fed = feed(c, accessUnit, renderer)
            if (fed == Fed.INPUT_TOO_SMALL) {
                // An unusually large keyframe: start a decoder with input buffers that fit it.
                releaseCodec()
                val big = ensureCodec(track, renderer, largeInput = accessUnit.size + accessUnit.size / 4) ?: return null
                fed = feed(big, accessUnit, renderer)
            }
            fed == Fed.RENDERED
        } catch (e: Exception) {
            Log.w(TAG, "decode failed on $codecName: ${e.javaClass.simpleName}")
            false
        }
        // A decoder that failed, or never finished its end of stream, may be wedged: start afresh next time.
        if (!rendered || !codecIdle) releaseCodec()
        if (!rendered) return null
        val aspect = when {
            track.displayAspect > 0.0 -> track.displayAspect
            frameWidth > 0 && frameHeight > 0 -> frameWidth.toDouble() / frameHeight
            track.width > 0 && track.height > 0 -> track.width.toDouble() / track.height
            else -> 16.0 / 9.0
        }.coerceIn(0.5, 4.0)
        var outWidth = maxWidth
        var outHeight = (maxWidth / aspect).roundToInt()
        if (outHeight > maxHeight) {
            outHeight = maxHeight
            outWidth = (maxHeight * aspect).roundToInt()
        }
        val transfer = when (track.transfer.takeIf { it == 16 || it == 18 } ?: outputTransfer) {
            16, MediaFormat.COLOR_TRANSFER_ST2084 -> GlThumbRenderer.TRANSFER_PQ
            18, MediaFormat.COLOR_TRANSFER_HLG -> GlThumbRenderer.TRANSFER_HLG
            else -> GlThumbRenderer.TRANSFER_SDR
        }
        val peak = (track.peakNits.takeIf { it in 400..10_000 } ?: 1000).toFloat()
        return renderer.render(outWidth and 1.inv(), outHeight and 1.inv(), frameWidth, frameHeight, transfer, peak)
    }

    private enum class Fed { RENDERED, FAILED, INPUT_TOO_SMALL }

    private fun feed(c: MediaCodec, accessUnit: ByteArray, renderer: GlThumbRenderer): Fed {
        codecIdle = false
        if (needsFlush) {
            c.flush()
            needsFlush = false
        }
        val pts = nextPtsUs
        nextPtsUs += 1_000_000L
        val inIndex = c.dequeueInputBuffer(INPUT_TIMEOUT_US)
        if (inIndex < 0) return Fed.FAILED
        val input = c.getInputBuffer(inIndex) ?: return Fed.FAILED
        if (input.capacity() < accessUnit.size) return Fed.INPUT_TOO_SMALL
        input.clear()
        input.put(accessUnit)
        c.queueInputBuffer(inIndex, 0, accessUnit.size, pts, MediaCodec.BUFFER_FLAG_KEY_FRAME)
        needsFlush = true
        // Decoders that hold frames for reordering must be pushed to hand this one out. Software ones get an end
        // of stream. Hardware ones get the keyframe again, since a new IDR outputs the pictures before it: on
        // Amlogic chips an end of stream sometimes never drains, and stopping the decoder then blocks the chip
        // for ~1.6 s, freezing the playing video.
        val nudgeWithKeyframe = codecIsHardware
        var eosQueued = false
        var nudges = 0
        var nextNudgeAt = 0L
        val info = MediaCodec.BufferInfo()
        val deadline = SystemClock.elapsedRealtime() + DECODE_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (nudgeWithKeyframe) {
                if (nudges < MAX_NUDGES && SystemClock.elapsedRealtime() >= nextNudgeAt) {
                    val nudgeIndex = c.dequeueInputBuffer(0)
                    if (nudgeIndex >= 0) {
                        val nudge = c.getInputBuffer(nudgeIndex) ?: return Fed.FAILED
                        nudge.clear()
                        nudge.put(accessUnit)
                        nudges++
                        c.queueInputBuffer(nudgeIndex, 0, accessUnit.size, pts + nudges * 40_000L, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                        nextNudgeAt = SystemClock.elapsedRealtime() + NUDGE_INTERVAL_MS
                    }
                }
            } else if (!eosQueued) {
                val eosIndex = c.dequeueInputBuffer(0)
                if (eosIndex >= 0) {
                    c.queueInputBuffer(eosIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    eosQueued = true
                }
            }
            val out = c.dequeueOutputBuffer(info, OUTPUT_POLL_US)
            when {
                out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> readOutputFormat(c.outputFormat)
                out >= 0 -> {
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    val render = info.size != 0 && info.presentationTimeUs == pts
                    c.releaseOutputBuffer(out, render)
                    if (render) {
                        if (frameWidth == 0) readOutputFormat(c.outputFormat)
                        val shown = renderer.awaitFrame(pts * 1000L, FRAME_WAIT_MS)
                        // Flushing an Amlogic decoder that is still draining to end of stream breaks it
                        // (every later frame failed), so let the drain finish first. The
                        // keyframe copies left in a hardware decoder are simply flushed next time.
                        codecIdle = eos || !eosQueued || drainToEndOfStream(c, info)
                        return if (shown) Fed.RENDERED else Fed.FAILED
                    }
                    if (eos) {
                        codecIdle = true
                        return Fed.FAILED
                    }
                }
            }
        }
        return Fed.FAILED
    }

    /** True once the decoder reports end of stream (within [EOS_WAIT_MS]). */
    private fun drainToEndOfStream(c: MediaCodec, info: MediaCodec.BufferInfo): Boolean {
        val deadline = SystemClock.elapsedRealtime() + EOS_WAIT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val out = c.dequeueOutputBuffer(info, OUTPUT_POLL_US)
            if (out >= 0) {
                c.releaseOutputBuffer(out, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return true
            }
        }
        return false
    }

    private fun readOutputFormat(format: MediaFormat) {
        val width = format.intOr(MediaFormat.KEY_WIDTH, 0)
        val height = format.intOr(MediaFormat.KEY_HEIGHT, 0)
        val left = format.intOr("crop-left", -1)
        val right = format.intOr("crop-right", -1)
        val top = format.intOr("crop-top", -1)
        val bottom = format.intOr("crop-bottom", -1)
        frameWidth = if (left >= 0 && right > left) right - left + 1 else width
        frameHeight = if (top >= 0 && bottom > top) bottom - top + 1 else height
        outputTransfer = format.intOr(MediaFormat.KEY_COLOR_TRANSFER, 0)
    }

    private fun ensureCodec(track: VideoTrackInfo, renderer: GlThumbRenderer, largeInput: Int?): MediaCodec? {
        codec?.let { if (codecTrack === track && largeInput == null) return it }
        releaseCodec()
        if (candidatesFor !== track) {
            val (software, others) = decoderCandidates(track)
            candidates = software + others
            softwareCandidates = software.toSet()
            candidatesFor = track
            brokenCodecs.clear()
        }
        val format = MediaFormat.createVideoFormat(
            track.codec.mime, track.width.takeIf { it > 0 } ?: 1920, track.height.takeIf { it > 0 } ?: 1080,
        ).apply {
            track.csd.forEachIndexed { i, bytes -> setByteBuffer("csd-$i", ByteBuffer.wrap(bytes)) }
            if (largeInput != null) setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, largeInput)
            setInteger(MediaFormat.KEY_PRIORITY, 1)
        }
        for (name in candidates) {
            if (name in brokenCodecs) continue
            val started = startCodec(name, format, renderer.surface)
            if (started == null) {
                // Busy is temporary (the memory may free up); a decoder that refused the stream is not.
                if (!busy) brokenCodecs += name
                continue
            }
            codec = started
            codecName = name
            codecIsHardware = name !in softwareCandidates
            codecTrack = track
            needsFlush = false
            frameWidth = 0
            frameHeight = 0
            outputTransfer = 0
            return started
        }
        noDecoder = !busy
        return null
    }

    /**
     * Creates and starts [name] on a helper thread, giving up after [START_TIMEOUT_MS]. The TV's codec
     * resource manager makes a hardware decoder wait while video memory is short (a 4K stream playing,
     * a screen recorder); a decoder that turns up after that is released at once so the player never
     * queues behind it.
     */
    private fun startCodec(name: String, format: MediaFormat, surface: Surface): MediaCodec? {
        if (lateStartPending) {
            busy = true
            return null
        }
        val lock = Object()
        var result: MediaCodec? = null
        var done = false
        var abandoned = false
        Thread({
            var created: MediaCodec? = null
            try {
                created = MediaCodec.createByCodecName(name)
                created.configure(format, surface, null, 0)
                created.start()
            } catch (e: Exception) {
                Log.w(TAG, "decoder $name unavailable: ${e.javaClass.simpleName}")
                runCatching { created?.release() }
                created = null
            }
            val keep = synchronized(lock) {
                done = true
                if (!abandoned) result = created
                lock.notifyAll()
                !abandoned
            }
            if (!keep) {
                if (created != null) {
                    Log.w(TAG, "decoder $name started too late; released")
                    runCatching { created.stop() }
                    runCatching { created.release() }
                }
                lateStartPending = false
            }
        }, "SeekThumbsCodec").start()
        synchronized(lock) {
            val deadline = SystemClock.elapsedRealtime() + START_TIMEOUT_MS
            while (!done) {
                val left = deadline - SystemClock.elapsedRealtime()
                if (left <= 0) {
                    abandoned = true
                    lateStartPending = true
                    busy = true
                    Log.w(TAG, "decoder $name busy (no video memory free); not waiting")
                    return null
                }
                lock.wait(left)
            }
            return result
        }
    }

    /**
     * Software decoders that can take the stream, and then hardware ones. For 10-bit, hardware decoders that
     * don't list a 10-bit profile come last: some H.264 decoders play Hi10P without listing it.
     */
    private fun decoderCandidates(track: VideoTrackInfo): Pair<List<String>, List<String>> {
        val mime = track.codec.mime
        val software = ArrayList<String>()
        val hardware = ArrayList<String>()
        val unlisted = ArrayList<String>()
        for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
            if (info.isEncoder || info.supportedTypes.none { it.equals(mime, ignoreCase = true) }) continue
            if (Build.VERSION.SDK_INT >= 29 && info.isAlias) continue
            val name = info.name
            if (name.endsWith(".secure")) continue
            val caps = runCatching { info.getCapabilitiesForType(mime) }.getOrNull() ?: continue
            if (caps.isFeatureRequired(MediaCodecInfo.CodecCapabilities.FEATURE_SecurePlayback) ||
                caps.isFeatureRequired(MediaCodecInfo.CodecCapabilities.FEATURE_TunneledPlayback)
            ) continue
            val video = caps.videoCapabilities ?: continue
            if (track.width > 0 && track.height > 0 &&
                !video.isSizeSupported(track.width, track.height) && !video.isSizeSupported(track.height, track.width)
            ) continue
            val isSoftware = if (Build.VERSION.SDK_INT >= 29) info.isSoftwareOnly
            else name.startsWith("OMX.google.") || name.startsWith("c2.android.")
            if (track.bitDepth > 8 && caps.profileLevels.none { it.profile in highBitDepthProfiles(track.codec) }) {
                if (!isSoftware) unlisted += name
                continue
            }
            if (isSoftware) software += name else hardware += name
        }
        return software to hardware + unlisted
    }

    private fun highBitDepthProfiles(codec: VideoCodec): Set<Int> = when (codec) {
        VideoCodec.HEVC -> setOf(
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10,
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus,
        )
        VideoCodec.AV1 -> setOf(
            MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10,
            MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10,
            MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10Plus,
        )
        VideoCodec.VP9 -> setOf(
            MediaCodecInfo.CodecProfileLevel.VP9Profile2,
            MediaCodecInfo.CodecProfileLevel.VP9Profile3,
            MediaCodecInfo.CodecProfileLevel.VP9Profile2HDR,
            MediaCodecInfo.CodecProfileLevel.VP9Profile3HDR,
        )
        VideoCodec.H264 -> setOf(MediaCodecInfo.CodecProfileLevel.AVCProfileHigh10)
    }

    /** Frees the decoder (and its hardware slot) but keeps the GL context for the next request. */
    fun releaseCodec() {
        val c = codec ?: return
        codec = null
        codecTrack = null
        codecName = null
        codecIsHardware = false
        needsFlush = false
        codecIdle = true
        runCatching { c.stop() }
        runCatching { c.release() }
    }

    override fun close() {
        releaseCodec()
        gl?.close()
        gl = null
    }

    private fun MediaFormat.intOr(key: String, fallback: Int): Int =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(fallback) else fallback

    private companion object {
        const val TAG = "SeekThumbs"
        const val INPUT_TIMEOUT_US = 200_000L
        const val OUTPUT_POLL_US = 10_000L
        const val DECODE_TIMEOUT_MS = 2_500L
        const val FRAME_WAIT_MS = 500L
        const val START_TIMEOUT_MS = 1_500L
        const val EOS_WAIT_MS = 400L
        /** Hardware decoders: keyframe copies queued (one at a time, this far apart) until the frame comes out. */
        const val MAX_NUDGES = 3
        const val NUDGE_INTERVAL_MS = 150L
    }
}

/** An EGL context with a SurfaceTexture for the decoder and an offscreen target read back as a bitmap. */
internal class GlThumbRenderer : Closeable {
    private val display: EGLDisplay
    private val context: EGLContext
    private val pbuffer: EGLSurface
    private val oesTexture: Int
    private val program: Int
    private val surfaceTexture: SurfaceTexture
    val surface: Surface
    private var framebuffer = 0
    private var targetTexture = 0
    private var targetWidth = 0
    private var targetHeight = 0
    private var pixels: ByteBuffer? = null
    private val texMatrix = FloatArray(16)
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(QUAD.size * 4).order(ByteOrder.nativeOrder())
        .asFloatBuffer().apply { put(QUAD).position(0) }

    init {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize" }
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        EGL14.eglChooseConfig(
            display,
            intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE,
            ),
            0, configs, 0, 1, count, 0,
        )
        check(count[0] > 0) { "no EGL config" }
        context = EGL14.eglCreateContext(
            display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext" }
        pbuffer = EGL14.eglCreatePbufferSurface(display, configs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        check(EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)) { "eglMakeCurrent" }

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        oesTexture = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        surfaceTexture = SurfaceTexture(oesTexture)
        surface = Surface(surfaceTexture)
        program = buildProgram()
    }

    /** Latches decoded frames until the one stamped [timestampNs] arrives. */
    fun awaitFrame(timestampNs: Long, timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            surfaceTexture.updateTexImage()
            if (surfaceTexture.timestamp == timestampNs) return true
            SystemClock.sleep(2)
        }
        return false
    }

    /** Draws the latched frame at [width] x [height] and reads it back. */
    fun render(width: Int, height: Int, frameWidth: Int, frameHeight: Int, transfer: Int, peakNits: Float): Bitmap {
        ensureTarget(width, height)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glUseProgram(program)
        surfaceTexture.getTransformMatrix(texMatrix)
        val position = GLES20.glGetAttribLocation(program, "aPosition")
        val coord = GLES20.glGetAttribLocation(program, "aCoord")
        quad.position(0)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(position)
        quad.position(2)
        GLES20.glVertexAttribPointer(coord, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(coord)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTexMatrix"), 1, false, texMatrix, 0)
        // Taps spread over the source pixels that fall in one output pixel (a box filter, not a point sample).
        val sourceWidth = frameWidth.takeIf { it > 0 } ?: (width * 4)
        val sourceHeight = frameHeight.takeIf { it > 0 } ?: (height * 4)
        val spanX = (sourceWidth.toFloat() / width).coerceAtLeast(1f)
        val spanY = (sourceHeight.toFloat() / height).coerceAtLeast(1f)
        GLES20.glUniform2f(
            GLES20.glGetUniformLocation(program, "uStep"),
            kotlin.math.abs(texMatrix[0]) * spanX / (4f * sourceWidth),
            kotlin.math.abs(texMatrix[5]) * spanY / (4f * sourceHeight),
        )
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uTransfer"), transfer.toFloat())
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uPeak"), peakNits / SDR_WHITE_NITS)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTexture"), 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        val buffer = pixels?.takeIf { it.capacity() == width * height * 4 }
            ?: ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder()).also { pixels = it }
        buffer.position(0)
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        buffer.position(0)
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(buffer) }
    }

    private fun ensureTarget(width: Int, height: Int) {
        if (width == targetWidth && height == targetHeight && framebuffer != 0) return
        if (framebuffer != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
            GLES20.glDeleteTextures(1, intArrayOf(targetTexture), 0)
        }
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        targetTexture = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, targetTexture)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glGenFramebuffers(1, ids, 0)
        framebuffer = ids[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, targetTexture, 0)
        check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE) { "framebuffer" }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        targetWidth = width
        targetHeight = height
    }

    private fun buildProgram(): Int {
        fun shader(type: Int, source: String): Int {
            val id = GLES20.glCreateShader(type)
            GLES20.glShaderSource(id, source)
            GLES20.glCompileShader(id)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "shader: ${GLES20.glGetShaderInfoLog(id)}" }
            return id
        }
        val id = GLES20.glCreateProgram()
        GLES20.glAttachShader(id, shader(GLES20.GL_VERTEX_SHADER, VERTEX))
        GLES20.glAttachShader(id, shader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT))
        GLES20.glLinkProgram(id)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(id, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "link: ${GLES20.glGetProgramInfoLog(id)}" }
        return id
    }

    override fun close() {
        surface.release()
        surfaceTexture.release()
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, pbuffer)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglReleaseThread()
        EGL14.eglTerminate(display)
    }

    companion object {
        const val TRANSFER_SDR = 0
        const val TRANSFER_PQ = 1
        const val TRANSFER_HLG = 2
        /** Diffuse white for HDR content, in nits (ITU-R BT.2408). */
        private const val SDR_WHITE_NITS = 203f

        // x, y, u, v. Drawn upside down so glReadPixels (bottom row first) yields a top-first bitmap.
        private val QUAD = floatArrayOf(
            -1f, 1f, 0f, 0f,
            1f, 1f, 1f, 0f,
            -1f, -1f, 0f, 1f,
            1f, -1f, 1f, 1f,
        )

        private const val VERTEX = """
            attribute vec4 aPosition;
            attribute vec4 aCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vCoord;
            void main() {
                gl_Position = aPosition;
                vCoord = (uTexMatrix * aCoord).xy;
            }
        """

        private const val FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            uniform samplerExternalOES uTexture;
            uniform vec2 uStep;
            uniform float uTransfer;
            uniform float uPeak;
            varying vec2 vCoord;

            vec3 pqToLinear(vec3 e) {
                vec3 p = pow(max(e, 0.0), vec3(1.0 / 78.84375));
                return pow(max(p - 0.8359375, 0.0) / max(18.8515625 - 18.6875 * p, 1e-6), vec3(1.0 / 0.1593017578125));
            }

            vec3 hlgToLinear(vec3 e) {
                vec3 low = e * e / 3.0;
                vec3 high = (exp((e - 0.55991073) / 0.17883277) + 0.28466892) / 12.0;
                return mix(low, high, step(0.5, e));
            }

            void main() {
                vec3 c = vec3(0.0);
                for (int i = 0; i < 4; i++) {
                    for (int j = 0; j < 4; j++) {
                        c += texture2D(uTexture, vCoord + vec2(float(i) - 1.5, float(j) - 1.5) * uStep).rgb;
                    }
                }
                c /= 16.0;
                if (uTransfer > 0.5) {
                    vec3 lin;
                    if (uTransfer < 1.5) {
                        lin = pqToLinear(c) * (10000.0 / 203.0);
                    } else {
                        lin = hlgToLinear(c);
                        float y = dot(lin, vec3(0.2627, 0.678, 0.0593));
                        lin *= pow(max(y, 1e-6), 0.2) * (1000.0 / 203.0);
                    }
                    // BT.2020 to BT.709 primaries, then extended Reinhard on the brightest channel.
                    lin = max(mat3(1.6605, -0.1246, -0.0182, -0.5876, 1.1329, -0.1006, -0.0728, -0.0083, 1.1187) * lin, 0.0);
                    float m = max(max(lin.r, lin.g), lin.b);
                    if (m > 0.0) lin *= (1.0 + m / (uPeak * uPeak)) / (1.0 + m);
                    c = pow(clamp(lin, 0.0, 1.0), vec3(1.0 / 2.2));
                }
                gl_FragColor = vec4(c, 1.0);
            }
        """
    }
}
