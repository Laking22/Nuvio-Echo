package com.nuvio.tv.ui.newlook.home

import android.graphics.Bitmap
import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.BitmapImage
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/** The tint's strength. */
private const val TINT_STRENGTH = 0.51f

/** Samples the three horizontal colour bands of artwork for a saved, network-independent Home palette. */
internal suspend fun sampleArtworkPalette(context: Context, url: String): List<Int>? = withContext(Dispatchers.IO) {
    if (url.isBlank()) return@withContext null
    val request = ImageRequest.Builder(context)
        .data(url)
        .allowHardware(false)
        .size(coil3.size.Size(48, 27))
        .build()
    val bitmap = ((context.imageLoader.execute(request) as? SuccessResult)?.image as? BitmapImage)?.bitmap
        ?: return@withContext null
    samplePalette(bitmap)
}

private val tintCache = object : LinkedHashMap<String, Color>(64, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Color>?) = size > 120
}

/**
 * The artwork's colour, for the ambient tint. Instead of blurring the full image (expensive on a TV), it reads a
 * 48 px copy and averages it, leaning toward the saturated pixels, so the glow reads like the artwork spilling
 * onto the page. Cached per image.
 */
@Composable
internal fun rememberArtworkTint(url: String?): Color? {
    val context = LocalContext.current
    var color by remember(url) { mutableStateOf(url?.let { synchronized(tintCache) { tintCache[it] } }) }
    LaunchedEffect(url) {
        if (url.isNullOrBlank() || color != null) return@LaunchedEffect
        color = withContext(Dispatchers.IO) {
            val request = ImageRequest.Builder(context)
                .data(url)
                .allowHardware(false)
                .size(coil3.size.Size(48, 27))
                .build()
            val bitmap = ((context.imageLoader.execute(request) as? SuccessResult)?.image as? BitmapImage)?.bitmap
            bitmap?.let(::sampleTint)?.also { synchronized(tintCache) { tintCache[url] = it } }
        }
    }
    return color
}

private fun sampleTint(bitmap: Bitmap): Color? {
    if (bitmap.width <= 0 || bitmap.height <= 0) return null
    val step = max(1, bitmap.width / 24)
    var r = 0f
    var g = 0f
    var b = 0f
    var total = 0f
    val hsv = FloatArray(3)
    for (y in 0 until bitmap.height step step) {
        for (x in 0 until bitmap.width step step) {
            val p = bitmap.getPixel(x, y)
            android.graphics.Color.colorToHSV(p, hsv)
            // Saturated, mid-bright pixels carry the artwork's colour; near-black and washed-out ones don't.
            val weight = 0.15f + hsv[1] * hsv[2] * 2f
            r += android.graphics.Color.red(p) * weight
            g += android.graphics.Color.green(p) * weight
            b += android.graphics.Color.blue(p) * weight
            total += weight
        }
    }
    if (total <= 0f) return null
    val out = FloatArray(3)
    android.graphics.Color.colorToHSV(
        android.graphics.Color.rgb((r / total).toInt(), (g / total).toInt(), (b / total).toInt()),
        out
    )
    // A little more saturated and kept out of the darks, like a CSS blur(56) saturate(1.6).
    out[1] = (out[1] * 1.6f).coerceAtMost(0.85f)
    out[2] = out[2].coerceIn(0.45f, 0.9f)
    return Color(android.graphics.Color.HSVToColor(out))
}

private fun samplePalette(bitmap: Bitmap): List<Int>? {
    if (bitmap.width <= 0 || bitmap.height <= 0) return null
    val hsv = FloatArray(3)
    return (0..2).map { band ->
        val startX = band * bitmap.width / 3
        val endX = (band + 1) * bitmap.width / 3
        var red = 0f
        var green = 0f
        var blue = 0f
        var total = 0f
        val step = max(1, bitmap.width / 24)
        for (y in 0 until bitmap.height step max(1, bitmap.height / 12)) {
            for (x in startX until endX step step) {
                val pixel = bitmap.getPixel(x, y)
                android.graphics.Color.colorToHSV(pixel, hsv)
                val weight = 0.15f + hsv[1] * hsv[2] * 2f
                red += android.graphics.Color.red(pixel) * weight
                green += android.graphics.Color.green(pixel) * weight
                blue += android.graphics.Color.blue(pixel) * weight
                total += weight
            }
        }
        if (total <= 0f) return@map null
        android.graphics.Color.colorToHSV(
            android.graphics.Color.rgb((red / total).toInt(), (green / total).toInt(), (blue / total).toInt()),
            hsv
        )
        hsv[1] = (hsv[1] * 1.55f).coerceAtMost(0.9f)
        hsv[2] = hsv[2].coerceIn(0.38f, 0.78f)
        android.graphics.Color.HSVToColor(hsv) and 0xFFFFFF
    }.takeIf { colors -> colors.size == 3 && colors.none { it == null } }?.filterNotNull()
}

/**
 * The artwork's light spilling around a window or card: [color] blurred out from this box, which the caller places
 * exactly over the window or card. Two Gaussian blurs of the box's rounded rect, a tight bright one and a wide faint
 * one, so the light is strongest at the edge and fades smoothly to nothing about [spread] out, with no step or edge
 * where it ends. Crossfades as the colour changes.
 *
 * The blur is made once per size (see [glowMask]) as a small white image and drawn tinted, scaled up, every frame:
 * one image draw, nothing blurred while the page scrolls or a trailer plays.
 *
 * The box sits on the window, never at a negative offset: a glow box placed up/left of its parent inside a lazy list
 * item was drawn with its cut-out shifted (on a TV and in the JVM preview the glow went missing above
 * the hero window, and left of it with the menu hidden). Drawing outward from a box at the window's own place works.
 */
@Composable
internal fun AmbientGlow(
    color: Color?,
    modifier: Modifier = Modifier,
    spread: Dp = 110.dp,
    radius: Dp = 16.dp,
    visible: Boolean = true
) {
    val target = if (visible && color != null) color.copy(alpha = TINT_STRENGTH) else Color.Transparent
    // Read only while drawing: the 700 ms crossfade redraws the glow, never recomposes it.
    val animated = animateColorAsState(target, tween(700), label = "ambientTint")
    Box(
        modifier = modifier.drawWithCache {
            val spreadPx = spread.toPx()
            val mask = glowMask(size.width, size.height, spreadPx, radius.toPx(), 2.dp.toPx())
            val parts = glowRingParts(mask.width, mask.height, size.width.toInt(), size.height.toInt(), spreadPx.toInt(), radius.roundToPx())
            onDrawBehind {
                val colour = animated.value
                if (colour.alpha <= 0.01f) return@onDrawBehind
                // The mask's two layers overlap rather than add up, so it is lifted a little to make up for it.
                val alpha = (colour.alpha * 1.3f).coerceAtMost(1f)
                val tint = ColorFilter.tint(colour.copy(alpha = 1f), BlendMode.SrcIn)
                // Only the ring around the window: the part under it would be painted and then covered.
                parts.forEach { part ->
                    drawImage(
                        mask,
                        srcOffset = part.srcOffset, srcSize = part.srcSize,
                        dstOffset = part.dstOffset, dstSize = part.dstSize,
                        alpha = alpha, colorFilter = tint, filterQuality = FilterQuality.Low
                    )
                }
            }
        }
    )
}

internal data class GlowPart(val srcOffset: IntOffset, val srcSize: IntSize, val dstOffset: IntOffset, val dstSize: IntSize)

/**
 * The ring of a [maskWidth] × [maskHeight] glow mask around a [width] × [height] box (in pixels, the mask covering it
 * plus [spread] each side): a strip above and below, full width, and one each side between them. Each reaches
 * [inner] into the box, so the box's rounded corners (radius [inner]) still have light behind them. The strips meet
 * edge to edge on screen; in the small mask their edges are rounded, a pixel or so off in a blur nobody sees.
 */
internal fun glowRingParts(maskWidth: Int, maskHeight: Int, width: Int, height: Int, spread: Int, inner: Int): List<GlowPart> {
    if (maskWidth <= 0 || maskHeight <= 0 || width <= 0 || height <= 0 || spread <= 0) return emptyList()
    val r = inner.coerceIn(0, minOf(width, height) / 2)
    val sx = maskWidth.toFloat() / (width + spread * 2)
    val sy = maskHeight.toFloat() / (height + spread * 2)
    // Screen edges of the strips, from the box's top-left, and the same edges in the mask.
    val x1 = r
    val x2 = width - r
    val y1 = r
    val y2 = height - r
    fun mx(x: Int) = ((x + spread) * sx).toInt().coerceIn(0, maskWidth)
    fun my(y: Int) = ((y + spread) * sy).toInt().coerceIn(0, maskHeight)
    return listOf(
        // Above and below, across the whole width.
        GlowPart(IntOffset(0, 0), IntSize(maskWidth, my(y1)), IntOffset(-spread, -spread), IntSize(width + spread * 2, spread + y1)),
        GlowPart(IntOffset(0, my(y2)), IntSize(maskWidth, maskHeight - my(y2)), IntOffset(-spread, y2), IntSize(width + spread * 2, height + spread - y2)),
        // Left and right, between them.
        GlowPart(IntOffset(0, my(y1)), IntSize(mx(x1), my(y2) - my(y1)), IntOffset(-spread, y1), IntSize(spread + x1, y2 - y1)),
        GlowPart(IntOffset(mx(x2), my(y1)), IntSize(maskWidth - mx(x2), my(y2) - my(y1)), IntOffset(x2, y1), IntSize(width + spread - x2, y2 - y1))
    ).filter { it.srcSize.width > 0 && it.srcSize.height > 0 && it.dstSize.width > 0 && it.dstSize.height > 0 }
}

/** Blurred light varies slowly, so a quarter-size image scaled up is indistinguishable and 16 times smaller. */
private const val MASK_SCALE = 0.25f

private val maskCache = object : LinkedHashMap<String, ImageBitmap>(8, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > 6
}

/**
 * The glow's shape for a [width] × [height] box with corner [radius]: white, its alpha the light, covering the box
 * plus [spread] on every side. A near blur hugging the edge plus a far one carrying the light out (their sum falls
 * off like real light, steep then long), drawn once on the CPU at [MASK_SCALE] and cached per size.
 */
internal fun glowMask(width: Float, height: Float, spread: Float, radius: Float, inset: Float): ImageBitmap {
    val key = "${width.toInt()}x${height.toInt()}|${spread.toInt()}|${radius.toInt()}"
    synchronized(maskCache) { maskCache[key] }?.let { return it }
    val s = MASK_SCALE
    val w = ((width + spread * 2) * s).toInt().coerceAtLeast(1)
    val h = ((height + spread * 2) * s).toInt().coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val left = (spread + inset) * s
    val top = (spread + inset) * s
    val right = (spread + width - inset) * s
    val bottom = (spread + height - inset) * s
    val r = radius * s
    fun layer(blur: Float, alpha: Float) = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        this.alpha = (alpha * 255).toInt().coerceIn(0, 255)
        maskFilter = android.graphics.BlurMaskFilter(blur * s, android.graphics.BlurMaskFilter.Blur.NORMAL)
    }
    canvas.drawRoundRect(left, top, right, bottom, r, r, layer(spread * 0.85f, 0.9f))
    canvas.drawRoundRect(left, top, right, bottom, r, r, layer(spread * 0.32f, 1f))
    return bitmap.asImageBitmap().also { synchronized(maskCache) { maskCache[key] = it } }
}
