package com.nuvio.tv.ui.newlook

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.dp
import com.nuvio.tv.domain.model.AppTheme
import com.nuvio.tv.ui.theme.NuvioTheme

/** The focus hairline of the new look: 1 px near-silver, right on the edge. */
internal val HairlineColor = Color(0xC7E8E8E8)

/** Quiet focus (Appearance → Look & feel): a white edge, a slight lift and a soft shadow instead of a coloured ring. */
val LocalQuietFocus = androidx.compose.runtime.staticCompositionLocalOf { false }

/**
 * The new look's focus ring colour: the colour picked in Settings → Appearance. White, the default, keeps the
 * near-silver hairline, as Quiet focus does whatever the colour.
 */
internal val focusRingColor: Color
    @Composable @ReadOnlyComposable
    get() = if (LocalQuietFocus.current || NuvioTheme.currentTheme == AppTheme.WHITE) HairlineColor else NuvioTheme.colors.FocusRing

/**
 * Liquid glass at full transparency: no frosting and no blur, so it's plain layered
 * drawing that costs the same on every TV. A gray body taken from the page gradient, a rim that's brightest on the
 * top and bottom curves and nearly clear down the sides, specular glints on those edges, and a soft drop shadow.
 * Nothing is clipped, so a lens inside can bulge past the sides.
 *
 * The glints are straight lines 1 dp in from the top and bottom, so they only suit a wide shape: on the narrow
 * sidebar the rounded ends curve away from them and they showed as white patches past the edge ([glints] off).
 */
internal fun Modifier.glassSurface(shape: Shape, glints: Boolean = true): Modifier = this
    .shadow(
        elevation = 14.dp,
        shape = shape,
        clip = false,
        ambientColor = Color.Black.copy(alpha = 0.38f),
        spotColor = Color.Black.copy(alpha = 0.38f)
    )
    .drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        // A gray tint from the (near-black) page gradient, so the glass sits in the page.
        val body = Brush.verticalGradient(
            0f to Color(48, 48, 48, 82),
            0.5f to Color(30, 30, 30, 66),
            1f to Color(20, 20, 20, 77)
        )
        val rim = Brush.verticalGradient(
            0f to Color(225, 225, 225, 107),
            0.16f to Color(200, 200, 200, 26),
            0.5f to Color(160, 160, 160, 13),
            0.84f to Color(200, 200, 200, 20),
            1f to Color(210, 210, 210, 77)
        )
        val glow = Brush.verticalGradient(
            0f to Color.White.copy(alpha = 0.10f),
            0.08f to Color.White.copy(alpha = 0.03f),
            0.92f to Color.White.copy(alpha = 0.03f),
            1f to Color.White.copy(alpha = 0.06f)
        )
        val stroke = 1.2.dp.toPx()
        val glintStroke = 1.4.dp.toPx()
        val left = size.width * 0.22f
        val right = size.width * 0.78f
        val topGlint = Brush.horizontalGradient(
            listOf(Color.Transparent, Color.White.copy(alpha = 0.7f), Color.Transparent),
            startX = left, endX = right
        )
        val bottomGlint = Brush.horizontalGradient(
            listOf(Color.Transparent, Color.White.copy(alpha = 0.4f), Color.Transparent),
            startX = left - size.width * 0.05f, endX = right - size.width * 0.05f
        )
        onDrawBehind {
            drawOutline(outline, body)
            drawOutline(outline, glow, style = Stroke(3.dp.toPx()))
            drawOutline(outline, rim, style = Stroke(stroke))
            if (!glints) return@onDrawBehind
            drawLine(topGlint, Offset(left, 1.dp.toPx()), Offset(right, 1.dp.toPx()), strokeWidth = glintStroke)
            drawLine(
                bottomGlint,
                Offset(left - size.width * 0.05f, size.height - 1.dp.toPx()),
                Offset(right - size.width * 0.05f, size.height - 1.dp.toPx()),
                strokeWidth = glintStroke
            )
        }
    }

/** How opaque the menu's glass is (Layout → Menu & Navigation): [MENU_GLASS_LOW], [MENU_GLASS_MID] or [MENU_GLASS_HIGH]. */
val LocalMenuGlass = androidx.compose.runtime.staticCompositionLocalOf { MENU_GLASS_LOW }
const val MENU_GLASS_LOW = 0
const val MENU_GLASS_MID = 1
const val MENU_GLASS_HIGH = 2

/**
 * The blurred artwork Home's ambient colour is showing (see [com.nuvio.tv.ui.newlook.home.AmbientPageWash]), for the
 * menu's glass to frost with. Null when none is on screen.
 */
internal object AmbientWashState {
    var image by androidx.compose.runtime.mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
    /** The artwork the ambient colour last showed; kept after leaving, so Settings and Who's watching can wear it. */
    @Volatile
    var lastUrl: String? = null
}

/**
 * Frosts the menu's glass at Mid and High: fills it with the part of the ambient wash right behind it (the page's
 * colours, already blurred) plus a light veil, which reads as frosted glass without blurring anything live. Off the
 * wash (another screen, or Ambient colour off) it's a plain gray body instead. Low draws nothing: the clear glass.
 * Goes before [glassSurface], so the glass's rim and glints sit on top.
 */
@Composable
internal fun Modifier.menuFrost(shape: Shape): Modifier {
    val level = LocalMenuGlass.current
    if (level == MENU_GLASS_LOW) return this
    val root = androidx.compose.ui.platform.LocalView.current.rootView
    var origin by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(Offset.Zero) }
    val strength = if (level == MENU_GLASS_HIGH) 0.92f else 0.5f
    return this
        .onGloballyPositioned { origin = it.positionInWindow() }
        .drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            val path = androidx.compose.ui.graphics.Path().apply { addOutline(outline) }
            onDrawBehind {
                val wash = AmbientWashState.image
                val rootWidth = root.width
                val rootHeight = root.height
                clipPath(path) {
                    if (wash != null && rootWidth > 0 && rootHeight > 0) {
                        // The wash covers the whole window: the part behind the glass, scaled to the wash's size.
                        val sx = wash.width / rootWidth.toFloat()
                        val sy = wash.height / rootHeight.toFloat()
                        val src = androidx.compose.ui.unit.IntOffset((origin.x * sx).toInt(), (origin.y * sy).toInt())
                        val srcSize = androidx.compose.ui.unit.IntSize(
                            (size.width * sx).toInt().coerceIn(1, wash.width - src.x.coerceIn(0, wash.width - 1)),
                            (size.height * sy).toInt().coerceIn(1, wash.height - src.y.coerceIn(0, wash.height - 1))
                        )
                        drawImage(
                            wash,
                            srcOffset = androidx.compose.ui.unit.IntOffset(src.x.coerceIn(0, wash.width - 1), src.y.coerceIn(0, wash.height - 1)),
                            srcSize = srcSize,
                            dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt()),
                            alpha = strength,
                            filterQuality = androidx.compose.ui.graphics.FilterQuality.Low
                        )
                        drawRect(Color.White, alpha = 0.14f * strength)
                    } else {
                        drawRect(Color(52, 52, 52), alpha = 0.85f * strength)
                    }
                }
            }
        }
}

/** The lens that sits on the current or focused menu item: a gray glass droplet with a bright inner rim. */
internal fun Modifier.glassLens(shape: Shape): Modifier = this
    .shadow(
        elevation = 6.dp,
        shape = shape,
        clip = false,
        ambientColor = Color.Black.copy(alpha = 0.25f),
        spotColor = Color.Black.copy(alpha = 0.25f)
    )
    .drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val fill = Color(70, 70, 70, 56)
        val rim = Brush.verticalGradient(
            0f to Color.White.copy(alpha = 0.38f),
            0.35f to Color.White.copy(alpha = 0.06f),
            0.7f to Color.White.copy(alpha = 0.04f),
            1f to Color.White.copy(alpha = 0.12f)
        )
        val inner = Brush.radialGradient(
            listOf(Color.White.copy(alpha = 0.07f), Color.Transparent),
            center = Offset(size.width * 0.5f, size.height * 0.5f),
            radius = size.maxDimension * 0.6f
        )
        onDrawBehind {
            drawOutline(outline, fill)
            drawOutline(outline, inner)
            drawOutline(outline, rim, style = Stroke(1.dp.toPx()))
        }
    }

/** The new look's focus ring: a 1 px hairline drawn on the shape's edge. */
internal fun Modifier.hairlineRing(shape: Shape, color: Color = HairlineColor): Modifier = this
    .drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        onDrawWithContent {
            drawContent()
            drawOutline(outline, color, style = Stroke(1.dp.toPx()))
        }
    }
