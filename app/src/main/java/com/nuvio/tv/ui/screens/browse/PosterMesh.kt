package com.nuvio.tv.ui.screens.browse

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import coil3.BitmapImage
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** One colour of a [PosterMesh], centred where it sits on the poster (0..1 across and down). */
@Immutable
internal data class MeshSpot(val color: Color, val x: Float, val y: Float, val share: Float)

/** A poster's most prominent colours, most prominent first, for the info panel's background. */
@Immutable
internal data class PosterMesh(val spots: List<MeshSpot>)

internal const val MeshSpots = 4
internal const val MeshSampleWidth = 24
internal const val MeshSampleHeight = 36

/** Beta speed-ups' "Colour mesh behind details" switch; off leaves the panel black and reads no colours. */
internal val LocalPosterColourMesh = staticCompositionLocalOf { true }

private val meshCache = object : LinkedHashMap<String, PosterMesh>(64, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PosterMesh>?) = size > 200
}

/**
 * The poster's colour mesh, read from a 24 × 36 software copy off the main thread (a few hundred pixels, so it costs
 * about what the blurred copy it replaces did). Cached per poster; null until it is ready or if the poster fails.
 */
@Composable
internal fun rememberPosterMesh(url: String?): PosterMesh? {
    val context = LocalContext.current
    var mesh by remember(url) { mutableStateOf(url?.let { synchronized(meshCache) { meshCache[it] } }) }
    LaunchedEffect(url) {
        if (url.isNullOrBlank() || mesh != null) return@LaunchedEffect
        mesh = withContext(Dispatchers.IO) {
          runCatching {
            val request = ImageRequest.Builder(context)
                .data(url)
                .allowHardware(false)
                .size(MeshSampleWidth, MeshSampleHeight)
                .memoryCacheKey("browse_panel_mesh:$url")
                .build()
            val bitmap = ((context.imageLoader.execute(request) as? SuccessResult)?.image as? BitmapImage)?.bitmap
            bitmap?.let(::meshFromBitmap)?.also { synchronized(meshCache) { meshCache[url] = it } }
          }.getOrElse {
            if (it is kotlinx.coroutines.CancellationException) throw it
            null
          }
        }
    }
    return mesh
}

private fun meshFromBitmap(bitmap: Bitmap): PosterMesh? {
    val width = bitmap.width
    val height = bitmap.height
    if (width <= 0 || height <= 0) return null
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
    return posterMesh(pixels, width, height)
}

/**
 * Groups the pixels into coarse colour buckets (8 levels per channel) and keeps the [maxSpots] biggest buckets that
 * look different from each other. Size is mostly area, nudged toward saturated colours so a dark poster's accent
 * still makes it in. Each spot's colour is its bucket's average, placed at the bucket's centre of mass.
 */
internal fun posterMesh(argb: IntArray, width: Int, height: Int, maxSpots: Int = MeshSpots): PosterMesh? {
    if (width <= 0 || height <= 0 || argb.size < width * height) return null
    val buckets = HashMap<Int, Bucket>()
    for (y in 0 until height) {
        for (x in 0 until width) {
            val p = argb[y * width + x]
            if ((p ushr 24) < 128) continue
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val hi = max(r, max(g, b))
            val lo = min(r, min(g, b))
            val saturation = if (hi == 0) 0f else (hi - lo) / hi.toFloat()
            val weight = 1f + 1.5f * saturation * (hi / 255f)
            val key = ((r shr 5) shl 6) or ((g shr 5) shl 3) or (b shr 5)
            buckets.getOrPut(key) { Bucket() }.add(r, g, b, x + 0.5f, y + 0.5f, weight)
        }
    }
    val total = buckets.values.sumOf { it.weight.toDouble() }.toFloat()
    if (total <= 0f) return null
    val picked = ArrayList<Bucket>(maxSpots)
    for (bucket in buckets.values.sortedByDescending { it.weight }) {
        if (picked.size == maxSpots) break
        if (picked.none { it.distanceTo(bucket) < MinSpotDistance }) picked += bucket
    }
    return PosterMesh(
        picked.map { bucket ->
            MeshSpot(
                color = bucket.color(),
                x = bucket.sumX / bucket.weight / width,
                y = bucket.sumY / bucket.weight / height,
                share = bucket.weight / total
            )
        }
    )
}

/** Colours closer than this (RGB distance) are treated as one. */
private const val MinSpotDistance = 56f

private class Bucket {
    var r = 0f
    var g = 0f
    var b = 0f
    var sumX = 0f
    var sumY = 0f
    var weight = 0f

    fun add(red: Int, green: Int, blue: Int, x: Float, y: Float, w: Float) {
        r += red * w
        g += green * w
        b += blue * w
        sumX += x * w
        sumY += y * w
        weight += w
    }

    fun color() = Color(red = r / weight / 255f, green = g / weight / 255f, blue = b / weight / 255f)

    fun distanceTo(other: Bucket): Float {
        val dr = r / weight - other.r / other.weight
        val dg = g / weight - other.g / other.weight
        val db = b / weight - other.b / other.weight
        return sqrt(dr * dr + dg * dg + db * db)
    }
}
