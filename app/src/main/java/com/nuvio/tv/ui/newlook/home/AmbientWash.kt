package com.nuvio.tv.ui.newlook.home

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import coil3.BitmapImage
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** The artwork is read this small, blurred, then drawn across the screen: a blur of the full image would cost the TV. */
private const val WASH_READ_WIDTH = 64
private const val WASH_READ_HEIGHT = 36
/** Box blur passes and radius at that size: three passes make it Gaussian, about an eighth of the screen wide. */
private const val WASH_BLUR_PASSES = 3
private const val WASH_BLUR_RADIUS = 5
/** Kept at this size after the blur, so the GPU's bilinear stretch to the screen shows no steps. */
private const val WASH_KEEP_WIDTH = 192
private const val WASH_KEEP_HEIGHT = 108
/**
 * The wash's average brightness (HSV value) before the fade: dark artwork is lifted to it, so a night scene still
 * colours the page; bright artwork is brought down to it, so the rows stay readable. Lifted at most [WASH_MAX_LIFT].
 */
private const val WASH_MEAN_VALUE = 0.40f
private const val WASH_MAX_LIFT = 3f
/** The brightness at the top of the page and at the bottom, where the rows sit (fractions of the above). */
private const val WASH_TOP_LIGHT = 1.0f
private const val WASH_BOTTOM_LIGHT = 0.62f
private const val WASH_SATURATION = 1.25f
private const val WASH_CROSSFADE_MS = 1_200

private val washCache = object : LinkedHashMap<String, ImageBitmap>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > 12
}

/**
 * Home's ambient background: the carousel's current artwork, blurred until only its colours are left and darkened
 * toward the rows, behind the whole page. It follows the carousel, crossfading as the slides change. The darkening is
 * baked into the small image, so each frame draws one stretched image and nothing else.
 */
@Composable
internal fun AmbientPageWash(url: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var shown by remember { mutableStateOf<ImageBitmap?>(url?.let { synchronized(washCache) { washCache[it] } }) }
    var previous by remember { mutableStateOf<ImageBitmap?>(null) }
    val fade = remember { Animatable(1f) }
    // The menu's frosted glass takes its colours from what's on screen; leaving, this wash is no longer there.
    androidx.compose.runtime.DisposableEffect(Unit) {
        com.nuvio.tv.ui.newlook.AmbientWashState.staticHomePaletteActive = false
        com.nuvio.tv.ui.newlook.AmbientWashState.lastPalette = emptyList()
        shown?.let {
            com.nuvio.tv.ui.newlook.AmbientWashState.image = it
            com.nuvio.tv.ui.newlook.AmbientWashState.lastImage = it
        }
        onDispose { if (com.nuvio.tv.ui.newlook.AmbientWashState.image === shown) com.nuvio.tv.ui.newlook.AmbientWashState.image = null }
    }
    LaunchedEffect(url) {
        com.nuvio.tv.ui.newlook.AmbientWashState.lastPalette = emptyList()
        if (url.isNullOrBlank()) return@LaunchedEffect
        val next = synchronized(washCache) { washCache[url] } ?: withContext(Dispatchers.IO) {
            val request = ImageRequest.Builder(context)
                .data(url)
                .allowHardware(false)
                .size(coil3.size.Size(WASH_READ_WIDTH, WASH_READ_HEIGHT))
                .build()
            val bitmap = ((context.imageLoader.execute(request) as? SuccessResult)?.image as? BitmapImage)?.bitmap
            bitmap?.let(::washOf)?.also { synchronized(washCache) { washCache[url] = it } }
        } ?: return@LaunchedEffect
        if (next === shown) return@LaunchedEffect
        previous = shown
        shown = next
        com.nuvio.tv.ui.newlook.AmbientWashState.image = next
        com.nuvio.tv.ui.newlook.AmbientWashState.lastImage = next
        if (com.nuvio.tv.ui.newlook.AmbientWashState.lastUrl != url) {
            com.nuvio.tv.ui.newlook.AmbientWashState.lastUrl = url
            context.getSharedPreferences(AMBIENT_PREFS, android.content.Context.MODE_PRIVATE).edit()
                .putString(AMBIENT_LAST_URL_KEY, url).apply()
        }
        fade.snapTo(0f)
        fade.animateTo(1f, tween(WASH_CROSSFADE_MS))
        previous = null
    }
    Box(
        modifier = modifier.drawBehind {
            val dst = IntSize(size.width.roundToInt(), size.height.roundToInt())
            // Read only here: the crossfade redraws the page's background, it never recomposes it.
            val alpha = fade.value
            previous?.takeIf { alpha < 1f }?.let {
                drawImage(it, IntOffset.Zero, IntSize(it.width, it.height), IntOffset.Zero, dst, filterQuality = FilterQuality.Low)
            }
            shown?.let {
                drawImage(it, IntOffset.Zero, IntSize(it.width, it.height), IntOffset.Zero, dst, alpha = alpha, filterQuality = FilterQuality.Low)
            }
        }
    )
}

/** Home's saved static theme: a lightweight gradient made from the sampled artwork palette. */
@Composable
internal fun StaticHomePaletteWash(palette: List<Int>, modifier: Modifier = Modifier) {
    val colors = remember(palette) {
        palette.take(3).map { Color(0xFF000000L or it.toLong()) }
            .ifEmpty { listOf(Color(0xFF33343A), Color(0xFF25262B), Color(0xFF17181C)) }
    }
    androidx.compose.runtime.DisposableEffect(colors) {
        com.nuvio.tv.ui.newlook.AmbientWashState.staticHomePaletteActive = true
        com.nuvio.tv.ui.newlook.AmbientWashState.lastPalette = palette.take(3)
        com.nuvio.tv.ui.newlook.AmbientWashState.image = null
        onDispose {
            com.nuvio.tv.ui.newlook.AmbientWashState.staticHomePaletteActive = false
            com.nuvio.tv.ui.newlook.AmbientWashState.lastPalette = emptyList()
        }
    }
    Box(
        modifier = modifier.drawBehind {
            drawRect(
                brush = Brush.linearGradient(
                    colors = colors,
                    start = androidx.compose.ui.geometry.Offset.Zero,
                    end = androidx.compose.ui.geometry.Offset(size.width, size.height * 0.55f)
                )
            )
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.12f),
                    0.42f to Color.Black.copy(alpha = 0.32f),
                    1f to Color.Black.copy(alpha = 0.82f)
                )
            )
        }
    )
}

private const val AMBIENT_PREFS = "new_look_home"
private const val AMBIENT_LAST_URL_KEY = "ambient_last_url"

/**
 * The artwork the ambient colour last showed, this session or the last one: Who's watching opens before Home, so it
 * wears the colour Home last had.
 */
@Composable
internal fun rememberLastAmbientUrl(): String? {
    val context = LocalContext.current
    return remember {
        com.nuvio.tv.ui.newlook.AmbientWashState.lastUrl
            ?: context.getSharedPreferences(AMBIENT_PREFS, android.content.Context.MODE_PRIVATE).getString(AMBIENT_LAST_URL_KEY, null)
    }
}

/** [source] blurred, a little more saturated and darkened toward the bottom, at [WASH_KEEP_WIDTH] × [WASH_KEEP_HEIGHT]. */
private fun washOf(source: Bitmap): ImageBitmap? {
    if (source.width <= 0 || source.height <= 0) return null
    val small = if (source.width == WASH_READ_WIDTH && source.height == WASH_READ_HEIGHT) source
    else Bitmap.createScaledBitmap(source, WASH_READ_WIDTH, WASH_READ_HEIGHT, true)
    val w = small.width
    val h = small.height
    val pixels = IntArray(w * h)
    small.getPixels(pixels, 0, w, 0, 0, w, h)
    val r = FloatArray(w * h) { ((pixels[it] shr 16) and 0xFF).toFloat() }
    val g = FloatArray(w * h) { ((pixels[it] shr 8) and 0xFF).toFloat() }
    val b = FloatArray(w * h) { (pixels[it] and 0xFF).toFloat() }
    repeat(WASH_BLUR_PASSES) {
        for (channel in arrayOf(r, g, b)) {
            boxBlur(channel, w, h, WASH_BLUR_RADIUS, horizontal = true)
            boxBlur(channel, w, h, WASH_BLUR_RADIUS, horizontal = false)
        }
    }
    val hsv = FloatArray(3)
    var valueSum = 0f
    for (i in 0 until w * h) {
        android.graphics.Color.RGBToHSV(r[i].toInt(), g[i].toInt(), b[i].toInt(), hsv)
        valueSum += hsv[2]
    }
    val gain = (WASH_MEAN_VALUE / (valueSum / (w * h)).coerceAtLeast(0.01f)).coerceAtMost(WASH_MAX_LIFT)
    for (y in 0 until h) {
        val light = WASH_TOP_LIGHT + (WASH_BOTTOM_LIGHT - WASH_TOP_LIGHT) * (y / (h - 1f))
        for (x in 0 until w) {
            val i = y * w + x
            android.graphics.Color.RGBToHSV(r[i].toInt(), g[i].toInt(), b[i].toInt(), hsv)
            hsv[1] = (hsv[1] * WASH_SATURATION).coerceAtMost(0.9f)
            hsv[2] = (hsv[2] * gain).coerceAtMost(0.75f) * light
            pixels[i] = android.graphics.Color.HSVToColor(hsv)
        }
    }
    val blurred = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    return Bitmap.createScaledBitmap(blurred, WASH_KEEP_WIDTH, WASH_KEEP_HEIGHT, true).asImageBitmap()
}

/** One box blur of [radius] along rows or columns of a [w] × [h] channel, edges held. */
private fun boxBlur(channel: FloatArray, w: Int, h: Int, radius: Int, horizontal: Boolean) {
    val lines = if (horizontal) h else w
    val length = if (horizontal) w else h
    val line = FloatArray(length)
    val span = radius * 2 + 1
    for (l in 0 until lines) {
        fun at(k: Int) = if (horizontal) l * w + k else k * w + l
        for (k in 0 until length) line[k] = channel[at(k)]
        var sum = 0f
        for (k in -radius..radius) sum += line[k.coerceIn(0, length - 1)]
        for (k in 0 until length) {
            channel[at(k)] = sum / span
            sum += line[(k + radius + 1).coerceAtMost(length - 1)] - line[(k - radius).coerceAtLeast(0)]
        }
    }
}
