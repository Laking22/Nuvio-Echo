package com.nuvio.tv.ui.screens.detail

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * The new details page's liquid glass, after the Home sidebar's lens (see com.nuvio.tv.ui.newlook.glassLens) but a
 * little brighter, as approved in the design preview: a light translucent body, a rim brightest at the top, a soft
 * shadow. Plain layered drawing with no blur, so it costs the same on every TV.
 */

/** The pill (or circle) that marks what's focused. */
internal fun Modifier.detailGlassLens(shape: Shape, lifted: Boolean = true): Modifier = this
    .then(
        if (lifted) Modifier.shadow(elevation = 6.dp, shape = shape, clip = false,
            ambientColor = Color.Black.copy(alpha = 0.35f), spotColor = Color.Black.copy(alpha = 0.35f))
        else Modifier
    )
    .drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val body = Brush.verticalGradient(
            0f to Color.White.copy(alpha = 0.24f),
            0.55f to Color.White.copy(alpha = 0.10f),
            1f to Color.White.copy(alpha = 0.15f)
        )
        val rim = Brush.verticalGradient(
            0f to Color.White.copy(alpha = 0.58f),
            0.3f to Color.White.copy(alpha = 0.10f),
            0.7f to Color.White.copy(alpha = 0.05f),
            1f to Color.White.copy(alpha = 0.22f)
        )
        val inner = Brush.verticalGradient(
            0f to Color.White.copy(alpha = 0.32f),
            0.08f to Color.Transparent
        )
        onDrawBehind {
            drawOutline(outline, body)
            drawOutline(outline, inner, style = Stroke(1.dp.toPx()))
            drawOutline(outline, rim, style = Stroke(1.dp.toPx()))
        }
    }

/**
 * The app's one selection look (hero buttons, the details page, Search, Genres, Calendar, Library): the glass lens
 * while focused, [idle] otherwise. Text on it stays white.
 */
internal fun Modifier.selectionSurface(focused: Boolean, shape: Shape, idle: Color = Color.Transparent): Modifier =
    if (focused) detailGlassLens(shape) else clip(shape).background(idle)

/**
 * A larger glass sheet (the open synopsis, the episode hold menu): darker so text on it reads over any backdrop,
 * with a glint along the top. [strength] scales the body's opacity.
 */
internal fun Modifier.detailGlassPanel(shape: Shape, strength: Float = 1f): Modifier = this
    .shadow(elevation = 16.dp, shape = shape, clip = false,
        ambientColor = Color.Black.copy(alpha = 0.45f), spotColor = Color.Black.copy(alpha = 0.45f))
    .drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        fun a(v: Float) = (v * strength).coerceIn(0f, 1f)
        val body = Brush.verticalGradient(
            0f to Color(58, 58, 58).copy(alpha = a(0.52f)),
            0.5f to Color(32, 32, 32).copy(alpha = a(0.46f)),
            1f to Color(20, 20, 20).copy(alpha = a(0.54f))
        )
        val rim = Brush.verticalGradient(
            0f to Color.White.copy(alpha = 0.58f),
            0.3f to Color.White.copy(alpha = 0.10f),
            0.7f to Color.White.copy(alpha = 0.05f),
            1f to Color.White.copy(alpha = 0.22f)
        )
        val left = size.width * 0.08f
        val right = size.width * 0.92f
        val glint = Brush.horizontalGradient(
            listOf(Color.Transparent, Color.White.copy(alpha = 0.7f), Color.Transparent), startX = left, endX = right
        )
        onDrawBehind {
            drawOutline(outline, body)
            drawOutline(outline, rim, style = Stroke(1.dp.toPx()))
            drawLine(glint, Offset(left, 1.dp.toPx()), Offset(right, 1.dp.toPx()), strokeWidth = 1.4.dp.toPx())
        }
    }

/** Line icons drawn to match the design (24-unit paths, 2-unit strokes, round ends), outlined or filled. */
internal enum class DetailGlyph { THUMB_UP, THUMBS_UP_DOUBLE, THUMB_DOWN, STAR }

private const val THUMB_BODY =
    "M15 5.88 14 10h5.83a2 2 0 0 1 1.92 2.56l-2.33 8A2 2 0 0 1 17.5 22H4a2 2 0 0 1-2-2v-8a2 2 0 0 1 2-2h2.76" +
        "a2 2 0 0 0 1.79-1.11L12 2a3.13 3.13 0 0 1 3 3.88Z"
private const val THUMB_CUFF = "M7 10v12"
private const val THUMB_DOWN_BODY =
    "M9 18.12 10 14H4.17a2 2 0 0 1-1.92-2.56l2.33-8A2 2 0 0 1 6.5 2H20a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2h-2.76" +
        "a2 2 0 0 0-1.79 1.11L12 22a3.13 3.13 0 0 1-3-3.88Z"
private const val THUMB_DOWN_CUFF = "M17 14V2"
private const val STAR_PATH =
    "M11.525 2.295a.53.53 0 0 1 .95 0l2.31 4.679a2.123 2.123 0 0 0 1.595 1.16l5.166.756a.53.53 0 0 1 .294.904" +
        "l-3.736 3.638a2.123 2.123 0 0 0-.611 1.878l.882 5.14a.53.53 0 0 1-.771.56l-4.618-2.428a2.122 2.122 0 0 0-1.973 0" +
        "L6.396 21.01a.53.53 0 0 1-.77-.56l.881-5.139a2.122 2.122 0 0 0-.611-1.879L2.16 9.795a.53.53 0 0 1 .294-.906" +
        "l5.165-.755a2.122 2.122 0 0 0 1.597-1.16z"

private fun parse(d: String): Path = PathParser().parsePathString(d).toPath()

private class GlyphPaths {
    val thumbBody = parse(THUMB_BODY)
    val thumbCuff = parse(THUMB_CUFF)
    val downBody = parse(THUMB_DOWN_BODY)
    val downCuff = parse(THUMB_DOWN_CUFF)
    val star = parse(STAR_PATH)
}

/** p' = p * scale + (tx, ty), in the Canvas's pixels. */
private fun scaled(source: Path, scale: Float, tx: Float, ty: Float): Path = Path().apply {
    addPath(source)
    transform(Matrix().apply {
        this[0, 0] = scale
        this[1, 1] = scale
        this[3, 0] = tx
        this[3, 1] = ty
    })
}

@Composable
internal fun DetailGlyphIcon(glyph: DetailGlyph, filled: Boolean, color: Color, size: Dp, modifier: Modifier = Modifier) {
    val paths = remember { GlyphPaths() }
    Canvas(modifier.size(size)) {
        val unit = this.size.minDimension / 24f
        fun DrawScope.glyph(body: Path, cuff: Path?, scale: Float, tx: Float, ty: Float, strokeUnits: Float) {
            val b = scaled(body, unit * scale, tx * unit, ty * unit)
            val stroke = Stroke(width = strokeUnits * unit * scale, cap = StrokeCap.Round, join = StrokeJoin.Round)
            if (filled) drawPath(b, color)
            drawPath(b, color, style = stroke)
            if (cuff != null) drawPath(scaled(cuff, unit * scale, tx * unit, ty * unit), color, style = stroke)
        }
        when (glyph) {
            DetailGlyph.THUMB_UP -> glyph(paths.thumbBody, paths.thumbCuff, 1f, 0f, 0f, 2f)
            DetailGlyph.THUMB_DOWN -> glyph(paths.downBody, paths.downCuff, 1f, 0f, 0f, 2f)
            DetailGlyph.STAR -> glyph(paths.star, null, 1f, 0f, 0f, 2f)
            DetailGlyph.THUMBS_UP_DOUBLE -> {
                // Two thumbs at 0.8 scale; the back one is cut away around the front one so their outlines never
                // cross. The cut is the front thumb grown about its centre (about 14.5, 13.1).
                val gap = scaled(paths.thumbBody, unit * 0.96f, 3.1f * unit, 1.6f * unit)
                clipPath(gap, clipOp = ClipOp.Difference) {
                    glyph(paths.thumbBody, paths.thumbCuff, 0.8f, -2f, -1.5f, 2.5f)
                }
                glyph(paths.thumbBody, paths.thumbCuff, 0.8f, 5f, 3.5f, 2.5f)
            }
        }
    }
}
