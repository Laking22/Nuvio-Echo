package com.nuvio.tv.ui.screens.player

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import com.nuvio.tv.R
import com.nuvio.tv.core.intro.PlaybackIntroState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlinx.coroutines.delay

/**
 * Draws the playback intro above the whole app while [PlaybackIntroState] is active.
 * Nothing is composed or drawn when it isn't.
 */
@Composable
fun PlaybackIntroHost() {
    val active by PlaybackIntroState.active.collectAsState()
    val betaSettings by rememberBetaPerformanceSettings()
    if (!active) return
    // The length chosen in Beta speed-ups, fixed for this intro.
    val timeline = remember { IntroTimeline(betaSettings.introLengthSeconds) }
    // Never left up: if the intro can't draw or finish for any reason, it goes shortly after
    // its own length and the app carries on (the loading screen follows, as usual).
    LaunchedEffect(Unit) {
        delay(timeline.totalMs.toLong() + WATCHDOG_EXTRA_MS)
        Log.w(INTRO_TAG, "intro didn't finish by itself; closing it")
        PlaybackIntroState.finish()
    }
    // Black underneath covers the one or two frames before the intro's surface first draws.
    Box(Modifier.fillMaxSize().background(Color.Black))
    // The intro's view goes straight into the window, above the app, not into Compose: a
    // SurfaceView only gets its surface once it's drawn, and inside Compose (AndroidView) it
    // sometimes never was, leaving the intro black and stuck.
    val hostView = LocalView.current
    DisposableEffect(Unit) {
        val content = hostView.rootView.findViewById<ViewGroup>(android.R.id.content)
        val intro = IntroSurfaceView(hostView.context, timeline) { PlaybackIntroState.finish() }
        content?.addView(intro, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        onDispose { content?.removeView(intro) }
    }
}

private const val INTRO_TAG = "NuvioIntro"
private const val WATCHDOG_EXTRA_MS = 2_500L
/** Where "nuvio" ends inside its image (its right edge is clear), and "echo"'s height and gap as parts of the word's. */
private const val WORD_INK_RIGHT = 0.971f
private const val ECHO_HEIGHT = 0.30f
private const val ECHO_GAP = 0.12f

/**
 * The intro renders on its own thread into its own surface, timed by vsync. The app's main
 * thread is busy while a stream starts (creating the player, the decoder, the UI), and a
 * main-thread animation stuttered whenever it stalled; this one doesn't wait on it.
 */
@SuppressLint("ViewConstructor")
private class IntroSurfaceView(
    context: Context,
    private val timeline: IntroTimeline,
    private val onFinished: () -> Unit
) : SurfaceView(context), SurfaceHolder.Callback {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var thread: HandlerThread? = null
    private var renderHandler: Handler? = null
    private var renderer: IntroRenderer? = null
    private var sound: MediaPlayer? = null
    private var startNanos = 0L

    @Volatile
    private var finishedNaturally = false

    @Volatile
    private var running = false

    @Volatile
    private var detached = false

    init {
        setZOrderOnTop(true)
        holder.setFormat(PixelFormat.OPAQUE)
        holder.addCallback(this)
    }

    // Frame pacing, logged once per intro: frames drawn, how many came late, the worst gap.
    private var frames = 0
    private var lateFrames = 0
    private var lastFrameNanos = 0L
    private var worstGapNanos = 0L

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            if (startNanos == 0L) startNanos = frameTimeNanos
            if (lastFrameNanos != 0L) {
                val gap = frameTimeNanos - lastFrameNanos
                if (gap > LATE_FRAME_NANOS) lateFrames++
                if (gap > worstGapNanos) worstGapNanos = gap
            }
            lastFrameNanos = frameTimeNanos
            frames++
            val t = (frameTimeNanos - startNanos) / 1_000_000f
            drawFrame(t)
            if (t >= timeline.totalMs) {
                running = false
                finishedNaturally = true
                Log.i(TAG, "finished: frames=$frames late=$lateFrames worstGapMs=${worstGapNanos / 1_000_000}")
                mainHandler.post(onFinished)
            } else {
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.i(TAG, "surface created")
        val handlerThread = HandlerThread("NuvioIntro", android.os.Process.THREAD_PRIORITY_DISPLAY).also { it.start() }
        thread = handlerThread
        val handler = Handler(handlerThread.looper)
        renderHandler = handler
        // The sound is made on a thread without a Looper so its events go to the main thread.
        // Made on the render thread, its "finished" event went to a thread that had already
        // quit, so the player was never released.
        Thread({
            val player = createSound()
            val posted = handler.post {
                if (detached) {
                    player?.release()
                    return@post
                }
                renderer = IntroRenderer(resources, timeline)
                Log.i(TAG, "rendering (sound ${if (player != null) "ready" else "unavailable"})")
                synchronized(lock) { sound = player }
                running = true
                player?.start()
                Choreographer.getInstance().postFrameCallback(frameCallback)
            }
            if (!posted) player?.release()
        }, "NuvioIntroSound").start()
    }

    private fun createSound(): MediaPlayer? = runCatching {
        MediaPlayer.create(
            context,
            timeline.soundRes,
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
            0
        )
    }.getOrNull()

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // Stop before the surface goes away; the lock waits out a frame that's mid-draw.
        synchronized(lock) { running = false }
        renderHandler?.post { Choreographer.getInstance().removeFrameCallback(frameCallback) }
        thread?.quitSafely()
        thread = null
        renderHandler = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        detached = true
        val player = synchronized(lock) { sound.also { sound = null } } ?: return
        if (finishedNaturally && player.isPlaying) {
            // Let the sound's tail ring out under the film, then release it (on the main thread).
            player.setOnCompletionListener { it.release() }
        } else {
            runCatching { player.stop() }
            player.release()
        }
    }

    private companion object {
        const val TAG = INTRO_TAG
        /** Over 1.5 vsyncs at 60 Hz: a frame was skipped. */
        const val LATE_FRAME_NANOS = 25_000_000L
    }

    private fun drawFrame(t: Float) {
        synchronized(lock) {
            if (!running) return
            val surface = holder.surface ?: return
            if (!surface.isValid) return
            val canvas = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) holder.lockHardwareCanvas() else holder.lockCanvas()
            }.getOrNull() ?: return
            try {
                renderer?.draw(canvas, t)
            } finally {
                runCatching { holder.unlockCanvasAndPost(canvas) }
            }
        }
    }
}

/**
 * Milestones of an intro [seconds] long, in milliseconds. The 5 s intro is as tuned; the others
 * scale every part by seconds / 5, so each keeps its share. The sound follows the same timeline.
 */
internal class IntroTimeline(val seconds: Int) {
    val scale = seconds / 5f
    val landEnd = 350f * scale           // logo lands with an overshoot
    val landOvershoot = 200f * scale
    val wordOutStart = 350f * scale      // logo shrinks left, the word slides out of it
    val wordOutEnd = 1000f * scale
    val wordInStart = 3100f * scale      // "nuvio" holds, then slides back in
    val wordInEnd = 3550f * scale
    val zoomStart = 3550f * scale        // through the logos, faster and faster
    val pulse1 = 4100f * scale
    val pulse2 = 4450f * scale
    val fadeStart = 4650f * scale
    val totalMs = 5000f * scale

    /** This length's sound (scripts/intro/generate_intro_sound.py --length). */
    val soundRes: Int
        get() = when (seconds) {
            3 -> R.raw.intro_3s
            4 -> R.raw.intro_4s
            6 -> R.raw.intro_6s
            else -> R.raw.intro_5s
        }
}

/** Draws one frame of the intro at time [t] (ms). Positions are fractional: nothing snaps. */
private class IntroRenderer(resources: Resources, private val timeline: IntroTimeline) {
    private val density = resources.displayMetrics.density
    private val mark = decode(resources, R.drawable.intro_logo_mark)
    private val word = decode(resources, R.drawable.nuvio_text)
    /** Nuvio Echo's "echo", small at the word's top right; null in builds without it. */
    private val echo = if (resources.getBoolean(R.bool.intro_echo)) decode(resources, R.drawable.intro_echo) else null
    /** The logo in two parts, its body and its white play symbol, so the symbol can fade alone. */
    private val ring = decode(resources, R.drawable.intro_logo_ring).withMipMaps()
    private val play = decode(resources, R.drawable.intro_logo_play).withMipMaps()
    /** A diagonal sheen covering only the ring's body, laid over deeper copies. */
    private val gloss = makeGloss(ring).withMipMaps()

    // Index 0 is sharp; 1.. are smeared by BLUR_SPANS. Filled in off this thread while the logo
    // and word play (the zoom starts 3.5 s in); until then the zoom draws sharp.
    private val ringImages = arrayOfNulls<Bitmap>(BLUR_SPANS.size + 1).also { it[0] = ring }
    private val glossImages = arrayOfNulls<Bitmap>(BLUR_SPANS.size + 1).also { it[0] = gloss }
    private val playImages = arrayOfNulls<Bitmap>(BLUR_SPANS.size + 1).also { it[0] = play }

    @Volatile
    private var blurReady = false

    init {
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            BLUR_SPANS.forEachIndexed { i, span ->
                val ringBlur = radialBlur(ring, span, BLUR_SIZE)
                ringImages[i + 1] = ringBlur.withMipMaps()
                glossImages[i + 1] = makeGloss(ringBlur).withMipMaps()
                playImages[i + 1] = radialBlur(play, span, BLUR_SIZE / 2).withMipMaps()
            }
            blurReady = true
        }, "NuvioIntroBlur").start()
    }

    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val wordPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
        colorFilter = PorterDuffColorFilter(android.graphics.Color.WHITE, PorterDuff.Mode.SRC_IN)
    }
    private val echoPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val streakPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) }
    private val rect = RectF()

    private val logoPx = 200f * density
    private val wordHeightPx = 62f * density
    private val gapPx = 18f * density
    private val shrunk = 0.72f

    fun draw(canvas: Canvas, t: Float) {
        canvas.drawColor(android.graphics.Color.BLACK)
        val cx = canvas.width / 2f
        val cy = canvas.height / 2f
        val fade = if (t < timeline.fadeStart) 1f else 1f - progress(t, timeline.fadeStart, timeline.totalMs)

        if (t < timeline.zoomStart) drawLogoAndWord(canvas, t, cx, cy) else drawZoom(canvas, t, cx, cy, fade)
    }

    // ── Logo lands, word slides out and back ────────────────────────────────────────────────

    private fun drawLogoAndWord(canvas: Canvas, t: Float, cx: Float, cy: Float) {
        val wordWidthPx = wordHeightPx * word.width / word.height
        val groupWidth = logoPx * shrunk + gapPx + wordWidthPx
        val sideBySideX = cx - groupWidth / 2 + logoPx * shrunk / 2

        val overshoot = timeline.landOvershoot
        val landScale = when {
            t < overshoot -> lerp(0.55f, 1.08f, easeOut(t / overshoot))
            t < timeline.landEnd -> lerp(1.08f, 1f, easeInOut((t - overshoot) / (timeline.landEnd - overshoot)))
            else -> 1f
        }
        val split = when {
            t < timeline.wordOutStart -> 0f
            t < timeline.wordOutEnd -> easeInOut(progress(t, timeline.wordOutStart, timeline.wordOutEnd))
            t < timeline.wordInStart -> 1f
            else -> 1f - easeInOut(progress(t, timeline.wordInStart, timeline.wordInEnd))
        }
        // A slow drift while the word is out, so the hold doesn't look frozen.
        val hold = progress(t, timeline.wordOutEnd, timeline.wordInStart)
        val drift = 1f + 0.035f * easeInOut(hold) * split
        val scale = landScale * lerp(1f, shrunk, split) * drift
        val logoX = lerp(cx, sideBySideX, split)
        val size = logoPx * scale

        drawCentered(canvas, mark, logoX, cy, size, imagePaint, 1f)

        if (split > 0f) {
            val logoRight = logoX + size * 0.42f
            val wordLeftRest = logoX + logoPx * shrunk * drift / 2 + gapPx
            val wordLeft = lerp(wordLeftRest - wordWidthPx - gapPx, wordLeftRest, split)
            canvas.save()
            canvas.clipRect(logoRight, 0f, canvas.width.toFloat(), canvas.height.toFloat())
            rect.set(wordLeft, cy - wordHeightPx / 2, wordLeft + wordWidthPx, cy + wordHeightPx / 2)
            wordPaint.alpha = (255 * (split * 1.6f).coerceAtMost(1f)).toInt()
            canvas.drawBitmap(word, null, rect, wordPaint)
            echo?.let { drawEcho(canvas, it, t, wordLeft + wordWidthPx * WORD_INK_RIGHT, cy - wordHeightPx / 2) }
            canvas.restore()
        }
    }

    /**
     * "echo" at the word's top right ([inkRight], [top]: the word's letters), a third of its height. It fades in as
     * the word finishes coming out, then rides back in with it, inside the same clip.
     */
    private fun drawEcho(canvas: Canvas, echo: Bitmap, t: Float, inkRight: Float, top: Float) {
        val fadeIn = easeInOut(progress(t, timeline.wordOutEnd - 150f * timeline.scale, timeline.wordOutEnd + 250f * timeline.scale))
        if (fadeIn <= 0f) return
        val height = wordHeightPx * ECHO_HEIGHT
        val left = inkRight + wordHeightPx * ECHO_GAP
        rect.set(left, top, left + height * echo.width / echo.height, top + height)
        echoPaint.alpha = (wordPaint.alpha * fadeIn).toInt()
        canvas.drawBitmap(echo, null, rect, echoPaint)
    }

    // ── Through the logos ───────────────────────────────────────────────────────────────────

    /** Logos passed by [t]: a slow start, then faster and faster. */
    private fun depthAt(t: Float): Float =
        ZOOM_LEVELS * timeline.scale * progress(t, timeline.zoomStart, timeline.totalMs).pow(ZOOM_CURVE)

    // The logos on screen this frame, nearest (biggest) first; drawn from the far end.
    private val layerK = IntArray(LAYERS_MAX)
    private val layerSize = FloatArray(LAYERS_MAX)

    /**
     * Moving through logos that stand one behind another, each [NEST_RATIO] the size of the one
     * in front, faster and faster. Nearer logos are drawn in front, so you look through each
     * one's opening at the ones behind and pass through its glass; each stays until it has flown
     * out past the corners; the far ones fade into the dark; each logo further in is clearer and
     * glossier than the one in front. Logos are smeared by how far the zoom moves in a frame
     * (pre-blurred copies, crossfaded), so it reads as motion rather than separate frames.
     */
    private fun drawZoom(canvas: Canvas, t: Float, cx: Float, cy: Float, fade: Float) {
        val levels = depthAt(t)
        pickBlur((levels - depthAt(t - FRAME_MS)) * BLUR_FRAMES)
        val camera = (1f / NEST_RATIO).pow(levels)
        val appear = progress(t, timeline.zoomStart, timeline.zoomStart + 150f * timeline.scale)
        // Two pulses of a coloured streak, timed with the sound's swells.
        val pulse = gaussian(t, timeline.pulse1, 120f * timeline.scale) * 0.8f +
            gaussian(t, timeline.pulse2, 130f * timeline.scale)
        val screen = minOf(canvas.width, canvas.height).toFloat()
        val minPx = 6f * density

        var count = 0
        for (k in maxOf(0, levels.toInt() - 8)..levels.toInt() + 12) {
            val size = logoPx * camera * NEST_RATIO.pow(k)
            if (size < minPx) break
            if (size / screen >= REACH || count == LAYERS_MAX) continue   // flown past
            layerK[count] = k
            layerSize[count] = size
            count++
        }
        for (i in count - 1 downTo 0) {
            val k = layerK[i]
            val size = layerSize[i]
            val pass = 1f - smoothstep(REACH * 0.75f, REACH, size / screen)
            // Only the copies inside the starting logo fade in.
            val inner = if (k == 0) 1f else appear
            // How far behind the logo at the starting size it stands: the distance fades to dark.
            val behind = ln(logoPx / size) / ln(1f / NEST_RATIO)
            val fog = 1f - smoothstep(FOG_DEPTH * 0.4f, FOG_DEPTH, behind)
            val glass = 1f - exp(-k / GLASS_RATE)
            val seen = inner * pass * fade * fog
            val body = lerp(1f, GLASS_BODY, glass) * seen
            val shine = lerp(0f, GLASS_SHINE, glass) * seen
            // New logos fade in from the vanishing point rather than popping in.
            val tiny = smoothstep(minPx, minPx * 3f, size)
            drawBlurred(canvas, ringImages, cx, cy, size, body * tiny)
            if (shine > 0.01f) drawBlurred(canvas, glossImages, cx, cy, size, shine * tiny)
            // Every logo keeps its play symbol until it grows past the starting size, so one
            // always sits in the middle and none vanishes at once.
            val play = (1f - smoothstep(logoPx, logoPx * 2.4f, size)) * lerp(1f, 0.75f, glass) * seen
            if (play > 0.01f) drawBlurred(canvas, playImages, cx, cy, size, play * tiny)
        }
        if (pulse > 0.02f) drawStreak(canvas, cy, pulse * 0.6f * fade)
    }

    // Which pre-blurred images this frame crossfades between, set by [pickBlur].
    private var blurLow = 0
    private var blurHigh = 0
    private var blurMix = 0f

    /** Chooses images smeared by about [span] levels of zoom (0 = sharp). */
    private fun pickBlur(span: Float) {
        blurLow = 0; blurHigh = 0; blurMix = 0f
        if (!blurReady || span <= 0.02f) return
        var lowSpan = 0f
        for (i in BLUR_SPANS.indices) {
            val highSpan = BLUR_SPANS[i]
            if (span <= highSpan) {
                blurHigh = i + 1
                blurMix = (span - lowSpan) / (highSpan - lowSpan)
                return
            }
            blurLow = i + 1
            lowSpan = highSpan
        }
        blurHigh = blurLow
    }

    private fun drawBlurred(canvas: Canvas, images: Array<Bitmap?>, x: Float, y: Float, size: Float, alpha: Float) {
        val low = images[blurLow] ?: return
        if (blurMix < 0.98f) drawCentered(canvas, low, x, y, size, imagePaint, alpha * (1f - blurMix))
        if (blurMix > 0.02f) images[blurHigh]?.let { drawCentered(canvas, it, x, y, size, imagePaint, alpha * blurMix) }
    }

    private fun drawStreak(canvas: Canvas, cy: Float, strength: Float) {
        val height = 3f * density * (1f + strength)
        // Coloured only: no white core, so the centre doesn't flash.
        streakPaint.shader = LinearGradient(
            0f, 0f, canvas.width.toFloat(), 0f,
            intArrayOf(0, argb(0.3f * strength, 0x4FD8FF), argb(0.45f * strength, 0x8A6CFF), argb(0.3f * strength, 0xD45BFF), 0),
            floatArrayOf(0f, 0.32f, 0.5f, 0.68f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, cy - height / 2, canvas.width.toFloat(), cy + height / 2, streakPaint)
    }

    private fun drawCentered(canvas: Canvas, bitmap: Bitmap, x: Float, y: Float, size: Float, paint: Paint, alpha: Float) {
        if (alpha <= 0f || size <= 0f) return
        val half = size / 2
        rect.set(x - half, y - half, x + half, y + half)
        paint.alpha = (255 * alpha.coerceIn(0f, 1f)).toInt()
        canvas.drawBitmap(bitmap, null, rect, paint)
    }

    private companion object {
        // Tuned in scripts/intro/lab (same values there).
        /** Each logo is this fraction of the size of the one in front of it (the spacing). */
        const val NEST_RATIO = 0.42f
        /** Logos passed in the 5 s intro (other lengths in proportion, at the same speed). */
        const val ZOOM_LEVELS = 19.5f
        /** The zoom's speed slope: 1 = steady; higher starts slower and rushes at the end. */
        const val ZOOM_CURVE = 2.7f
        /** How long a "shutter" the smear covers, in frames. */
        const val BLUR_FRAMES = 2f
        const val FRAME_MS = 1000f / 60f
        /** Logos passed in one frame that each pre-blurred image is smeared over. */
        val BLUR_SPANS = floatArrayOf(0.12f, 0.35f, 0.8f)
        /** Side of the pre-blurred images, in pixels; they're soft anyway. */
        const val BLUR_SIZE = 512
        /** Logos over which the glass mostly sets in; it keeps clearing beyond that. */
        const val GLASS_RATE = 3f
        /** Opacity and sheen the logos approach, furthest in. */
        const val GLASS_BODY = 0.15f
        const val GLASS_SHINE = 0.9f
        /** Logos stay until they're this many screens wide: flown out past the corners. */
        const val REACH = 10f
        /** How far down the tunnel you see before the logos fade to dark, in logos. */
        const val FOG_DEPTH = 6.5f
        /** Logos drawn at most per frame. */
        const val LAYERS_MAX = 21

        /**
         * [source] averaged over a zoom of [span] levels that ends at full size (it grew during
         * the frame), at [side]×[side]. Made once per intro, off the render thread.
         */
        fun radialBlur(source: Bitmap, span: Float, side: Int): Bitmap {
            val out = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            // Enough copies that neighbours overlap even at the widest smear.
            val samples = (span * 40).toInt().coerceIn(8, 32)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
                alpha = (255f / samples).toInt().coerceAtLeast(1)
            }
            val r = RectF()
            for (i in 0 until samples) {
                val half = side * NEST_RATIO.pow(span * i / (samples - 1)) / 2
                r.set(side / 2f - half, side / 2f - half, side / 2f + half, side / 2f + half)
                c.drawBitmap(source, null, r, paint)
            }
            return out
        }

        /** Lets the GPU filter small copies smoothly instead of shimmering. */
        fun Bitmap.withMipMaps(): Bitmap = apply { setHasMipMap(true) }


        /** White sheen from the top-left, kept to the logo's opaque pixels. */
        fun makeGloss(source: Bitmap): Bitmap {
            val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            c.drawBitmap(source, 0f, 0f, null)
            val sheen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
                shader = LinearGradient(
                    0f, 0f, source.width.toFloat(), source.height.toFloat(),
                    intArrayOf(
                        argb(0.9f, 0xFFFFFF), argb(0.35f, 0xFFFFFF), argb(0f, 0xFFFFFF),
                        argb(0f, 0xFFFFFF), argb(0.18f, 0xFFFFFF)
                    ),
                    floatArrayOf(0f, 0.32f, 0.5f, 0.82f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            c.drawRect(0f, 0f, source.width.toFloat(), source.height.toFloat(), sheen)
            return out
        }

        fun decode(resources: Resources, id: Int): Bitmap =
            BitmapFactory.decodeResource(resources, id, BitmapFactory.Options().apply { inScaled = false })

        fun argb(alpha: Float, rgb: Int): Int = ((255 * alpha.coerceIn(0f, 1f)).toInt() shl 24) or rgb
        fun lerp(a: Float, b: Float, f: Float) = a + (b - a) * f
        fun progress(t: Float, start: Float, end: Float) = ((t - start) / (end - start)).coerceIn(0f, 1f)
        fun easeOut(x: Float) = 1f - (1f - x.coerceIn(0f, 1f)).pow(3)
        fun easeInOut(x: Float) = (0.5f - 0.5f * cos(PI * x.coerceIn(0f, 1f))).toFloat()
        fun gaussian(t: Float, at: Float, width: Float) = exp(-((t - at) / width).pow(2))
        fun smoothstep(a: Float, b: Float, x: Float): Float {
            val f = ((x - a) / (b - a)).coerceIn(0f, 1f)
            return f * f * (3 - 2 * f)
        }
    }
}
