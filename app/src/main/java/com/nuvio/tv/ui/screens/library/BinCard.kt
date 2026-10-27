package com.nuvio.tv.ui.screens.library

import com.nuvio.tv.ui.theme.NuvioTheme
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.format.DateUtils
import android.util.LruCache
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.toBitmap
import com.nuvio.tv.R
import com.nuvio.tv.core.bins.Bin
import com.nuvio.tv.core.bins.BinsReducer
import com.nuvio.tv.core.bins.BinSort
import com.nuvio.tv.ui.screens.detail.ndClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.max

internal val BinShape = RoundedCornerShape(28.dp)

/**
 * A bin: its name, count and when it was last opened on the left, three of its posters fanned on the right, over a
 * blurred mesh of its posters that drifts to the next one every few seconds. The star in the corner is its own focus
 * stop (Up from the bin reaches it) and marks the default bin.
 */
@Composable
internal fun BinCard(
    bin: Bin,
    width: Dp,
    height: Dp,
    index: Int,
    requester: FocusRequester,
    starRequester: FocusRequester,
    picked: Boolean,
    reordering: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onLongClick: (Rect) -> Unit,
    onStar: () -> Unit,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }
    var starFocused by remember { mutableStateOf(false) }
    // Up on the bin arms its star, so it can take focus even though the bin lets go of focus a moment first (without
    // this the move was refused: the star counted as unfocusable for that instant).
    var starArmed by remember { mutableStateOf(false) }
    LaunchedEffect(starArmed) {
        if (starArmed) {
            delay(400)
            if (!starFocused) starArmed = false
        }
    }
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val lifted = focused || picked
    val scale by animateFloatAsState(
        when {
            picked -> 1.05f
            focused -> 1.03f
            else -> 1f
        }, tween(200), label = "binScale"
    )
    // Newest first in the fan and the mesh, whatever order the bin's own page uses.
    val titles = remember(bin.entries) { BinsReducer.sortedEntries(bin.entries, BinSort.ADDED_NEWEST) }
    val posters = remember(titles) { titles.mapNotNull { it.poster?.takeIf(String::isNotBlank) } }
    val count = bin.entries.size

    Box(
        modifier = modifier
            .size(width, height)
            .onGloballyPositioned { bounds = it.boundsInRoot() }
            .graphicsLayer { scaleX = scale; scaleY = scale }
    ) {
        // The bin itself (focus target): everything but the star.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(BinShape)
                .background(Brush.linearGradient(listOf(Color(0xFF242424), Color(0xFF111111))))
                .focusProperties { up = starRequester }
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp) starArmed = true
                    false
                }
                .ndClickable(
                    focusRequester = requester,
                    onFocusChanged = { f -> focused = f; if (f) onFocused() },
                    onLongClick = { onLongClick(bounds) },
                    onClick = onClick
                )
                .semantics { contentDescription = bin.name }
        ) {
            if (posters.isNotEmpty()) BinMesh(posters, seed = index, bright = lifted)
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.horizontalGradient(0f to Color(0xA0000000), 0.45f to Color(0x55000000), 0.75f to Color.Transparent))
            )
            BinFan(posters, focused = lifted, modifier = Modifier.align(Alignment.TopEnd).padding(top = 29.dp, end = 22.dp))
            Column(
                Modifier
                    .padding(start = 21.dp, top = 20.dp, bottom = 18.dp)
                    .width(width * 0.47f)
                    .fillMaxHeight()
            ) {
                Text(bin.name, color = Color.White, fontSize = 19.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(5.dp))
                Text(
                    if (count > 0) pluralStringResource(R.plurals.bins_title_count, count, count) else stringResource(R.string.bins_empty_label),
                    color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp
                )
                if (bin.starred) {
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.bins_default_label).uppercase(), color = Color.White.copy(alpha = 0.85f), fontSize = 10.sp, letterSpacing = 0.8.sp)
                }
                Spacer(Modifier.weight(1f))
                // An empty bin has nothing to say here.
                if (count > 0) {
                    Text(
                        if (bin.viewedAt > 0L) stringResource(R.string.bins_viewed, relativeTime(bin.viewedAt)) else stringResource(R.string.bins_not_viewed),
                        color = Color.White.copy(alpha = 0.5f), fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        // Focus and pick rings sit over the card, outside its clip.
        val ring = when {
            picked -> 3.dp to Color.White
            focused && reordering -> 2.dp to NuvioTheme.colors.FocusRing.copy(alpha = 0.45f)
            focused -> 2.dp to NuvioTheme.colors.FocusRing
            else -> null
        }
        if (ring != null) Box(Modifier.fillMaxSize().border(ring.first, ring.second, BinShape))
        // The star: Up from the bin, Down back to it.
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = (-8).dp, y = 6.dp)
                .size(30.dp)
                // Reachable only by Up from its own bin, so Down from the pills and Left/Right between bins land on
                // bins, never on a star in their corner.
                .focusProperties {
                    canFocus = focused || starFocused || starArmed
                    down = requester
                }
                .ndClickable(
                    focusRequester = starRequester,
                    onFocusChanged = { f -> starFocused = f; if (!f) starArmed = false },
                    onClick = onStar
                )
                .semantics { contentDescription = "star:${bin.name}" },
            contentAlignment = Alignment.Center
        ) {
            BinStar(starred = bin.starred, focused = starFocused, binFocused = focused, modifier = Modifier.size(18.dp))
        }
    }
}

/** Three posters fanned like a hand of cards; they spread when the bin is focused. Dashed outlines when empty. */
@Composable
private fun BinFan(posters: List<String>, focused: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    val spread by animateFloatAsState(if (focused) 1f else 0f, tween(320, easing = FastOutSlowInEasing), label = "binFan")
    Box(modifier.size(width = 116.dp, height = 108.dp)) {
        for (k in 0 until 3) {
            val url = posters.getOrNull(k)
            val baseX = 27.5f * k
            val rotation = when (k) {
                0 -> -9f - 5f * spread
                2 -> 9f + 5f * spread
                else -> 0f
            }
            val dx = when (k) {
                0 -> -11f * spread
                2 -> 11f * spread
                else -> 0f
            }
            val dy = if (k == 1) 9f - 16f * spread else 14f - 5f * spread
            val shape = RoundedCornerShape(7.dp)
            Box(
                Modifier
                    .offset(x = (baseX + dx).dp, y = dy.dp)
                    .size(width = 60.dp, height = 90.dp)
                    .graphicsLayer { rotationZ = rotation }
                    .then(
                        if (url != null) Modifier.clip(shape).background(Color(0xFF222222))
                        else Modifier.drawBehind {
                            drawRoundRect(
                                Color.White.copy(alpha = 0.25f), cornerRadius = CornerRadius(7.dp.toPx()),
                                style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
                            )
                        }
                    )
            ) {
                if (url != null) {
                    AsyncImage(
                        model = remember(url) { ImageRequest.Builder(context).data(url).size(120, 180).crossfade(true).build() },
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

private const val MESH_W = 64
private const val MESH_H = 40
private const val MESH_STEP_MS = 3_600L
private const val MESH_FADE_MS = 1_400

/**
 * The bin's backdrop: four of its posters laid as overlapping patches into a tiny image, blurred on the CPU (it's
 * 64×40, so that's nothing) and drawn stretched over the card, so the GPU only ever scales one small picture. Every
 * few seconds the next poster joins the mix and the new picture fades in over the old one.
 */
@Composable
private fun BoxScope.BinMesh(posters: List<String>, seed: Int, bright: Boolean) {
    val context = LocalContext.current
    var thumbs by remember(posters) { mutableStateOf<List<Bitmap>>(emptyList()) }
    var front by remember(posters) { mutableStateOf<ImageBitmap?>(null) }
    var back by remember(posters) { mutableStateOf<ImageBitmap?>(null) }
    val fade = remember(posters) { Animatable(0f) }
    LaunchedEffect(posters) {
        thumbs = loadThumbs(context, posters.take(MAX_MESH_POSTERS))
        if (thumbs.isEmpty()) return@LaunchedEffect
        front = withContext(Dispatchers.Default) { buildMesh(thumbs, 0) }
        if (thumbs.size < 2) return@LaunchedEffect
        // Staggered, so the bins don't all change at once.
        delay(1_800L + (seed % 6) * 650L)
        var step = 0
        while (true) {
            step++
            back = withContext(Dispatchers.Default) { buildMesh(thumbs, step) }
            fade.snapTo(0f)
            fade.animateTo(1f, tween(MESH_FADE_MS, easing = FastOutSlowInEasing))
            front = back
            back = null
            fade.snapTo(0f)
            delay(MESH_STEP_MS - MESH_FADE_MS)
        }
    }
    val brightness by animateFloatAsState(if (bright) 0.82f else 0.62f, tween(240), label = "binMeshBright")
    val filter = remember(brightness) { ColorFilter.colorMatrix(meshMatrix(saturation = if (brightness > 0.7f) 1.6f else 1.5f, scale = brightness)) }
    Canvas(Modifier.matchParentSize()) {
        val w = size.width.toInt().coerceAtLeast(1)
        val h = size.height.toInt().coerceAtLeast(1)
        // A little past the edges, so the soft border of the blur never shows.
        val over = (max(w, h) * 0.08f).toInt()
        val dstOffset = IntOffset(-over, -over)
        val dstSize = IntSize(w + 2 * over, h + 2 * over)
        front?.let { drawImage(it, dstOffset = dstOffset, dstSize = dstSize, colorFilter = filter, filterQuality = FilterQuality.Low) }
        val b = back
        if (b != null && fade.value > 0f) {
            drawImage(b, dstOffset = dstOffset, dstSize = dstSize, alpha = fade.value, colorFilter = filter, filterQuality = FilterQuality.Low)
        }
    }
}

private const val MAX_MESH_POSTERS = 8

private fun meshMatrix(saturation: Float, scale: Float): ColorMatrix {
    val m = ColorMatrix().apply { setToSaturation(saturation) }
    m.timesAssign(ColorMatrix(floatArrayOf(scale, 0f, 0f, 0f, 0f, 0f, scale, 0f, 0f, 0f, 0f, 0f, scale, 0f, 0f, 0f, 0f, 0f, 1f, 0f)))
    return m
}

/** Where each poster lands in the mesh: x and y as fractions of it; each patch is 68% wide and 90% tall. */
private val PATCHES = listOf(-0.1f to -0.2f, 0.42f to -0.25f, -0.05f to 0.45f, 0.5f to 0.4f)

internal fun buildMesh(thumbs: List<Bitmap>, step: Int): ImageBitmap {
    val out = Bitmap.createBitmap(MESH_W, MESH_H, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(out)
    val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    PATCHES.forEachIndexed { k, (px, py) ->
        val thumb = thumbs[(step + k) % thumbs.size]
        paint.alpha = if (k == 0) 255 else 217
        // The middle of a poster carries its strongest colours.
        val src = android.graphics.Rect(thumb.width / 6, thumb.height / 6, thumb.width * 5 / 6, thumb.height * 3 / 4)
        val dst = RectF(px * MESH_W, py * MESH_H, px * MESH_W + MESH_W * 0.68f, py * MESH_H + MESH_H * 0.9f)
        canvas.drawBitmap(thumb, src, dst, paint)
    }
    val pixels = IntArray(MESH_W * MESH_H)
    out.getPixels(pixels, 0, MESH_W, 0, 0, MESH_W, MESH_H)
    repeat(3) { boxBlur(pixels, MESH_W, MESH_H, radius = 5) }
    out.setPixels(pixels, 0, MESH_W, 0, 0, MESH_W, MESH_H)
    return out.asImageBitmap()
}

/** One horizontal and one vertical box pass (clamped at the edges); three of them come close to a gaussian. */
internal fun boxBlur(pixels: IntArray, w: Int, h: Int, radius: Int) {
    val tmp = IntArray(pixels.size)
    fun pass(src: IntArray, dst: IntArray, lineCount: Int, lineLen: Int, index: (line: Int, i: Int) -> Int) {
        val div = 2 * radius + 1
        for (line in 0 until lineCount) {
            var a = 0; var r = 0; var g = 0; var b = 0
            for (i in -radius..radius) {
                val c = src[index(line, i.coerceIn(0, lineLen - 1))]
                a += c ushr 24; r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF
            }
            for (i in 0 until lineLen) {
                dst[index(line, i)] = ((a / div) shl 24) or ((r / div) shl 16) or ((g / div) shl 8) or (b / div)
                val outC = src[index(line, (i - radius).coerceIn(0, lineLen - 1))]
                val inC = src[index(line, (i + radius + 1).coerceIn(0, lineLen - 1))]
                a += (inC ushr 24) - (outC ushr 24)
                r += ((inC shr 16) and 0xFF) - ((outC shr 16) and 0xFF)
                g += ((inC shr 8) and 0xFF) - ((outC shr 8) and 0xFF)
                b += (inC and 0xFF) - (outC and 0xFF)
            }
        }
    }
    pass(pixels, tmp, h, w) { line, i -> line * w + i }
    pass(tmp, pixels, w, h) { line, i -> i * w + line }
}

private val thumbCache = LruCache<String, Bitmap>(160)

/** Small software copies of the posters (the mesh is drawn on the CPU, which can't read hardware bitmaps). */
private suspend fun loadThumbs(context: Context, urls: List<String>): List<Bitmap> {
    val loader = SingletonImageLoader.get(context)
    return urls.mapNotNull { url ->
        thumbCache.get(url) ?: runCatching {
            val result = loader.execute(
                ImageRequest.Builder(context).data(url).size(32, 48).allowHardware(false).build()
            )
            (result as? SuccessResult)?.image?.toBitmap()?.also { thumbCache.put(url, it) }
        }.getOrNull()
    }
}

private fun relativeTime(epochMs: Long): String =
    DateUtils.getRelativeTimeSpanString(epochMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString().lowercase()
