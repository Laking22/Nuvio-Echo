@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.newlook.home

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import com.nuvio.tv.domain.model.CardDepthSurface
import com.nuvio.tv.ui.components.nuvioCardDepth
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.nuvio.tv.core.image.customPosterFallback
import coil3.request.crossfade
import androidx.compose.ui.layout.layout
import androidx.compose.runtime.derivedStateOf
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.TrailerPlayer
import com.nuvio.tv.ui.newlook.focusRingColor

internal val CardBackground = Color(0xFF222222)
private val OpenEasing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
private val ImdbYellow = Color(0xFFF5C518)
private val TmdbTeal = Color(0xFF01B4E4)

/**
 * A focusable card with the new look's 1 px hairline (in the Appearance colour) instead of the thick white ring. A null [width] leaves
 * the width to [modifier] (the poster sets it from its open animation in the layout phase).
 */
@Composable
internal fun NewLookCard(
    width: Dp?,
    height: Dp,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    onFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    radius: Dp = LocalNewLookCardRadius.current,
    /** Which of Layout's card depth switches covers this card; null draws none. */
    depth: CardDepthSurface? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val shape = RoundedCornerShape(radius)
    val ring = focusRingColor
    val quiet = com.nuvio.tv.ui.newlook.LocalQuietFocus.current
    // Whether the card's artwork is in (see [Artwork]; every card with depth draws its picture through it).
    val artwork = remember { mutableStateOf(false) }
    Card(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier
            .then(if (width != null) Modifier.size(width, height) else Modifier.height(height))
            .onFocusChanged { onFocusChanged(it.isFocused) },
        shape = CardDefaults.shape(shape),
        // Clear, not grey: while a row slides, a card whose artwork is still loading showed as a dark box for a frame
        // or two before its picture came in. Now the artwork just fades in where the card is.
        colors = CardDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.Transparent),
        // Quiet focus lifts the card a little and lays a soft shadow under it, with the white hairline.
        scale = CardDefaults.scale(focusedScale = if (quiet) QUIET_FOCUS_LIFT else 1f, pressedScale = 0.98f),
        border = CardDefaults.border(
            border = Border.None,
            focusedBorder = Border(BorderStroke(1.dp, ring), shape = shape),
            pressedBorder = Border(BorderStroke(1.dp, ring), shape = shape)
        ),
        glow = if (quiet) CardDefaults.glow(focusedGlow = QuietFocusShadow, pressedGlow = QuietFocusShadow) else CardDefaults.glow()
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            androidx.compose.runtime.CompositionLocalProvider(LocalArtworkShown provides artwork) { content() }
            // The depth's lit edge and sheen wait for the artwork: over a card still loading (a row sliding in new
            // cards) they drew a faint empty box for a frame or two.
            if (depth != null && artwork.value) CardDepthOverlay(shape, depth)
        }
    }
}

/** How much a focused card grows with Quiet focus: enough to read as lifted, not enough to crowd its row. */
private const val QUIET_FOCUS_LIFT = 1.035f

private val QuietFocusShadow = androidx.tv.material3.Glow(elevationColor = Color.Black.copy(alpha = 0.6f), elevation = 14.dp)

/** A card's "artwork is in" flag, which [Artwork] sets once its picture (or its title, with none) is up. */
internal val LocalArtworkShown = androidx.compose.runtime.compositionLocalOf<androidx.compose.runtime.MutableState<Boolean>?> { null }

/** Layout's corner radius for Home's cards, set once for the page by Home. */
internal val LocalNewLookCardRadius = androidx.compose.runtime.staticCompositionLocalOf { 12.dp }

/** Layout's card depth (edge light and top sheen) over a card's content, when it's on for [surface]. */
@Composable
internal fun BoxScope.CardDepthOverlay(shape: androidx.compose.ui.graphics.Shape, surface: CardDepthSurface) {
    val style = com.nuvio.tv.ui.components.LocalCardDepthStyle.current
    if (style.isEnabledFor(surface)) {
        Box(Modifier.matchParentSize().nuvioCardDepth(shape = shape, surface = surface, style = style))
    }
}

@Composable
internal fun Artwork(
    url: String?,
    modifier: Modifier = Modifier,
    fallbackTitle: String? = null,
    /** The art a custom poster replaced, shown if the custom one fails to load. */
    fallbackUrl: String? = null
) {
    val shown = LocalArtworkShown.current
    if (url.isNullOrBlank()) {
        androidx.compose.runtime.SideEffect { shown?.value = true }
        if (fallbackTitle != null) {
            Box(modifier = modifier.background(CardBackground).padding(10.dp), contentAlignment = Alignment.Center) {
                Text(text = fallbackTitle, color = Color(0xFFB3B3B3), fontSize = 13.sp, textAlign = TextAlign.Center, maxLines = 3)
            }
        }
        return
    }
    val context = LocalContext.current
    // Built once per image, not on every recomposition of the card.
    val request = remember(url, fallbackUrl) {
        ImageRequest.Builder(context).data(url).customPosterFallback(url, fallbackUrl).crossfade(200).build()
    }
    // Tells the card when its picture is up (the card's depth waits for it). A card showing two pictures (a poster
    // under its backdrop) counts as in once the first is.
    AsyncImage(
        model = request,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        onSuccess = { shown?.value = true },
        onError = { shown?.value = true },
        modifier = modifier
    )
}

/** Logo bottom-left, or the title as bold text when there's no logo. */
@Composable
internal fun BoxScope.CardLogo(
    logo: String?,
    title: String,
    maxWidthFraction: Float,
    maxHeightFraction: Float,
    cardWidth: Dp,
    cardHeight: Dp,
    inset: Dp,
    textSize: Float
) {
    var failed by remember(logo) { mutableStateOf(false) }
    if (!logo.isNullOrBlank() && !failed) {
        val context = LocalContext.current
        val request = remember(logo) { ImageRequest.Builder(context).data(logo).crossfade(200).build() }
        AsyncImage(
            model = request,
            contentDescription = title,
            contentScale = ContentScale.Fit,
            alignment = Alignment.BottomStart,
            onError = { failed = true },
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = inset, bottom = inset)
                .widthIn(max = cardWidth * maxWidthFraction)
                .heightIn(max = cardHeight * maxHeightFraction)
        )
    } else {
        Text(
            text = title,
            color = Color.White,
            fontSize = textSize.sp,
            fontWeight = FontWeight.ExtraBold,
            lineHeight = (textSize * 1.1f).sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.6f), blurRadius = 10f)),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = inset, bottom = inset - 2.dp)
                .widthIn(max = cardWidth * 0.7f)
        )
    }
}

/**
 * The shade under a card's title: clear for the top 45%, then down to black at 65% at the bottom. Drawn only over
 * the part that isn't clear.
 */
private fun Modifier.bottomShade(): Modifier = drawWithCache {
    val top = size.height * 0.45f
    val brush = Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.65f), startY = top, endY = size.height)
    onDrawBehind { drawRect(brush, topLeft = androidx.compose.ui.geometry.Offset(0f, top), size = androidx.compose.ui.geometry.Size(size.width, size.height - top)) }
}

/**
 * Continue Watching: always wide, with the title (and the episode, for a show) bottom-left and the progress as a
 * bar along the card's bottom edge, rounded off by the card's corners.
 */
@Composable
internal fun ContinueWatchingCard(
    image: String?,
    title: String,
    episode: String?,
    progress: Float?,
    dims: NewLookDimens,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val hasProgress = progress != null && progress > 0f
    NewLookCard(dims.cwWidth, dims.cwHeight, onClick, onLongClick, onFocusChanged, modifier, depth = CardDepthSurface.CONTINUE_WATCHING) {
        Artwork(image, Modifier.fillMaxSize(), fallbackTitle = title)
        Box(modifier = Modifier.fillMaxSize().bottomShade())
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 14.dp, end = 14.dp, bottom = if (hasProgress) 14.dp else 12.dp)
        ) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.8f), blurRadius = 8f))
            )
            if (!episode.isNullOrBlank()) {
                Text(
                    text = episode,
                    color = Color.White.copy(alpha = 0.78f),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.8f), blurRadius = 8f))
                )
            }
        }
        if (hasProgress) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(Color.White.copy(alpha = 0.28f))
            ) {
                Box(modifier = Modifier.fillMaxHeight().fillMaxWidth(progress!!.coerceIn(0f, 1f)).background(Color.White))
            }
        }
    }
}

/**
 * A poster that opens out while focused: it widens to the open width over ~280 ms and crossfades to the
 * title's backdrop with its logo, then closes back to a poster when focus leaves.
 */
@Composable
internal fun PosterCard(
    poster: String?,
    /** The art a custom [poster] replaced, shown if the custom one fails to load. */
    posterFallback: String? = null,
    backdrop: String?,
    logo: String?,
    title: String,
    watched: Boolean,
    open: Boolean,
    dims: NewLookDimens,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    /** The title's trailer, played in the opened card; null while it's closed or has none. */
    trailerUrl: String? = null,
    trailerAudioUrl: String? = null,
    muted: Boolean = true
) {
    // A regular row stays on its card when the trailer ends: the backdrop comes back.
    var ended by remember(trailerUrl) { mutableStateOf(false) }
    val playing = open && trailerUrl != null && !ended
    // Both animations are read in the layout and draw phases, so each frame of the opening re-measures the card but
    // doesn't recompose it.
    val width = animateDpAsState(
        targetValue = if (open) dims.posterOpenWidth else dims.posterWidth,
        animationSpec = tween(280, easing = OpenEasing),
        label = "posterOpen"
    )
    // As stock Nuvio does: the poster goes the moment the card starts opening, so it's never seen cropped and blown
    // up to the wide shape; the card is black until the backdrop has loaded and faded in. Closing fades the backdrop
    // out quickly and the poster back in once the card is narrow again.
    val openAlpha = animateFloatAsState(
        if (open) 1f else 0f, if (open) snap() else tween(160), label = "posterOpenAlpha"
    )
    val posterAlpha = animateFloatAsState(
        if (open) 0f else 1f, if (open) snap() else tween(220, delayMillis = 120), label = "posterAlpha"
    )
    val showOpen by remember { derivedStateOf { openAlpha.value > 0f } }
    NewLookCard(
        null, dims.posterHeight, onClick, onLongClick, onFocusChanged,
        modifier.layout { measurable, constraints ->
            val w = width.value.roundToPx()
            val placeable = measurable.measure(constraints.copy(minWidth = w, maxWidth = w))
            layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        },
        depth = CardDepthSurface.POSTERS
    ) {
        Artwork(
            poster ?: backdrop, Modifier.fillMaxSize().graphicsLayer { alpha = posterAlpha.value },
            fallbackTitle = title, fallbackUrl = posterFallback
        )
        if (showOpen) {
            Box(modifier = Modifier.fillMaxSize().graphicsLayer { alpha = openAlpha.value }.background(Color.Black)) {
                Artwork(backdrop ?: poster, Modifier.fillMaxSize())
                HeldTrailer(active = playing, key = trailerUrl) { onFirstFrame ->
                    TrailerPlayer(
                        trailerUrl = trailerUrl,
                        trailerAudioUrl = trailerAudioUrl,
                        isPlaying = true,
                        onEnded = { ended = true },
                        onFirstFrameRendered = onFirstFrame,
                        muted = muted,
                        cropToFill = true,
                        // Burned-in black bars are cropped, so the picture fills the hero or card it plays in.
                        autoCropLetterbox = true,
                        focusable = false,
                        overscanZoom = com.nuvio.tv.ui.screens.home.MODERN_TRAILER_OVERSCAN_ZOOM,
                        modifier = Modifier.fillMaxSize(),
                        surfaceView = com.nuvio.tv.ui.components.LocalTrailerVideoLayer.current
                    )
                }
                Box(modifier = Modifier.fillMaxSize().background(
                    Brush.verticalGradient(0f to Color.Transparent, 0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.6f))
                ))
                CardLogo(logo, title, 0.55f, 0.4f, dims.posterOpenWidth, dims.posterHeight, 14.dp, 20f)
            }
        }
        if (watched) WatchedMark(Modifier.align(Alignment.TopEnd).padding(8.dp))
    }
}

@Composable
internal fun WatchedMark(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.size(22.dp).clip(CircleShape).background(Color.White),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Filled.Check, contentDescription = null, tint = Color.Black, modifier = Modifier.size(14.dp))
    }
}

/** A highlighted row's card: always wide, the backdrop with the logo; the focused one can play its trailer. */
@Composable
internal fun HighlightCard(
    backdrop: String?,
    logo: String?,
    title: String,
    trailerUrl: String?,
    trailerAudioUrl: String?,
    playing: Boolean,
    muted: Boolean,
    dims: NewLookDimens,
    onTrailerEnded: () -> Unit,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    onFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    chipLabel: String? = null,
    overlay: @Composable BoxScope.() -> Unit = {}
) {
    NewLookCard(dims.highlightWidth, dims.highlightHeight, onClick, onLongClick, onFocusChanged, modifier, depth = CardDepthSurface.FEATURED) {
        Artwork(backdrop, Modifier.fillMaxSize(), fallbackTitle = title)
        overlay()
        HeldTrailer(active = playing && trailerUrl != null, key = trailerUrl) { onFirstFrame ->
            TrailerPlayer(
                trailerUrl = trailerUrl,
                trailerAudioUrl = trailerAudioUrl,
                isPlaying = true,
                onEnded = onTrailerEnded,
                onFirstFrameRendered = onFirstFrame,
                muted = muted,
                cropToFill = true,
                // Burned-in black bars are cropped, so the picture fills the hero or card it plays in.
                autoCropLetterbox = true,
                focusable = false,
                overscanZoom = com.nuvio.tv.ui.screens.home.MODERN_TRAILER_OVERSCAN_ZOOM,
                modifier = Modifier.fillMaxSize(),
                surfaceView = com.nuvio.tv.ui.components.LocalTrailerVideoLayer.current
            )
        }
        Box(modifier = Modifier.fillMaxSize().background(
            Brush.verticalGradient(0f to Color.Transparent, 0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.62f))
        ))
        CardLogo(logo, title, 0.5f, 0.34f, dims.highlightWidth, dims.highlightHeight, 16.dp, 22f)
        if (chipLabel != null) {
            SoundChip(
                text = chipLabel,
                icon = Icons.Filled.PlayArrow,
                modifier = Modifier.align(Alignment.TopEnd).padding(10.dp)
            )
        }
    }
}

@Composable
internal fun SoundChip(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier, height: Dp = 22.dp) {
    Row(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(Color(0x99121212))
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Icon(icon, contentDescription = null, tint = Color(0xFFDDDDDD), modifier = Modifier.size(12.dp))
        Text(text = text, color = Color(0xFFDDDDDD), fontSize = 10.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/**
 * A ranked row's big number, drawn behind the card. Outline under an opaque fill (so the font's overlapping inner
 * contours never show as stray lines), then a soft gray gradient on top, in the page's grays.
 */
@Composable
internal fun RankNumber(rank: Int, dims: NewLookDimens, focused: Boolean, modifier: Modifier = Modifier) {
    val fontSize = dims.rankFontSize.sp
    val twoDigits = rank >= 10
    val outline by androidx.compose.animation.animateColorAsState(
        if (focused) Color(240, 240, 240, 153) else Color(215, 215, 215, 77),
        tween(240),
        label = "rankOutline"
    )
    val base = TextStyle(
        fontSize = fontSize,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = if (twoDigits) (-0.09).em else (-0.06).em,
        lineHeight = fontSize,
        textAlign = TextAlign.End
    )
    // The number is taller than the card it stands behind (1.18 × its height), so it measures unbounded instead of
    // being squeezed into the card's height, and drops by the font's descent so the digits stand on the card's bottom
    // edge (the line box ends that far below the baseline). Squeezed, with a guessed drop, the digits were cut flat
    // and stood off the edge (seen in the JVM preview).
    var descentPx by remember { mutableIntStateOf(0) }
    Box(
        modifier = modifier
            .offset { IntOffset(0, descentPx) }
            .wrapContentSize(Alignment.BottomEnd, unbounded = true)
            .graphicsLayer {
                if (twoDigits) {
                    scaleX = 0.72f
                    transformOrigin = TransformOrigin(1f, 1f)
                }
            }
    ) {
        Text(
            text = rank.toString(),
            style = base.copy(color = outline, drawStyle = Stroke(width = 6.4f)),
            maxLines = 1,
            softWrap = false,
            onTextLayout = { descentPx = (it.size.height - it.lastBaseline).toInt() }
        )
        Text(text = rank.toString(), style = base.copy(color = Color(0xFF171717)), maxLines = 1, softWrap = false)
        Text(
            text = rank.toString(),
            style = base.copy(
                brush = Brush.verticalGradient(
                    0f to Color(92, 92, 92, 191),
                    0.55f to Color(44, 44, 44, 115),
                    1f to Color(14, 14, 14, 0)
                )
            ),
            maxLines = 1,
            softWrap = false
        )
    }
}

/** Still (no animation, so nothing redraws while it waits), lighter at the top like a card catching light. */
private val PlaceholderShade = Brush.verticalGradient(listOf(Color(0xFF222222), Color(0xFF161616)))

/**
 * A loading row's card: a softly pulsing skeleton that can take focus, so moving down onto a row that hasn't
 * loaded (or never will) lands on it and the next Down goes on past it. OK does nothing.
 */
@Composable
internal fun SkeletonCard(
    width: Dp,
    height: Dp,
    onFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val pulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "skeleton")
    val alpha = pulse.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            tween(900, easing = androidx.compose.animation.core.LinearEasing),
            androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "skeletonAlpha"
    )
    NewLookCard(width, height, onClick = {}, onLongClick = null, onFocusChanged = onFocusChanged, modifier = modifier) {
        // Read while drawing, so the pulse redraws the card without recomposing it.
        Box(modifier = Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha.value }.background(PlaceholderShade))
    }
}

/** "Show • Drama, Mystery • 2022 • 55m • IMDb 8.7", under a row's focused card. */
@Composable
internal fun DetailsLine(
    type: String?,
    genres: List<String>,
    year: String?,
    runtime: String?,
    rating: String?,
    ratingIsImdb: Boolean,
    modifier: Modifier = Modifier,
    color: Color = Color(0xFFCFCFCF),
    dotColor: Color = Color(0xFF777777)
) {
    val parts = listOfNotNull(
        type?.takeIf { it.isNotBlank() },
        genres.take(2).joinToString(", ").takeIf { it.isNotBlank() },
        year?.takeIf { it.isNotBlank() },
        runtime?.takeIf { it.isNotBlank() }
    )
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        parts.forEachIndexed { index, part ->
            if (index > 0) Dot(dotColor)
            Text(text = part, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
        if (!rating.isNullOrBlank()) {
            if (parts.isNotEmpty()) Dot(dotColor)
            RatingBadge(ratingIsImdb)
            Text(text = " $rating", color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

@Composable
private fun Dot(color: Color) {
    Text(text = "•", color = color, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 6.dp))
}

@Composable
internal fun RatingBadge(imdb: Boolean) {
    Text(
        text = if (imdb) "IMDb" else "TMDB",
        color = if (imdb) Color.Black else Color.White,
        fontSize = 9.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = (-0.02).em,
        modifier = Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(if (imdb) ImdbYellow else TmdbTeal)
            .padding(horizontal = 3.dp, vertical = 1.dp)
    )
}

internal fun formatRuntime(minutes: Int?): String? {
    if (minutes == null || minutes <= 0) return null
    val h = minutes / 60
    val m = minutes % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

/**
 * A collection folder. Highlighted, it takes the highlighted card size with the folder's logo (or title)
 * bottom-left; either way its animation (focus GIF or hero video, muted and looping) plays only while focused.
 */
@Composable
internal fun CollectionTile(
    item: com.nuvio.tv.ui.screens.home.ModernCarouselItem,
    width: Dp,
    height: Dp,
    focused: Boolean,
    onClick: () -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false
) {
    val folder = item.payload as? com.nuvio.tv.ui.screens.home.ModernPayload.CollectionFolder
    val gif = folder?.takeIf { it.focusGifEnabled }?.focusGifUrl?.takeIf { it.isNotBlank() }
    val video = folder?.heroVideoUrl?.takeIf { it.isNotBlank() }
    val cover = if (highlighted) item.heroPreview.backdrop ?: item.imageUrl else item.imageUrl ?: item.heroPreview.backdrop
    NewLookCard(
        width, height, onClick, null, onFocusChanged, modifier,
        depth = if (highlighted) CardDepthSurface.FEATURED else CardDepthSurface.POSTERS
    ) {
        Artwork(
            cover, Modifier.fillMaxSize(), fallbackTitle = item.title.ifBlank { null },
            fallbackUrl = item.metaPreview?.rawPosterUrl.takeIf { !highlighted }
        )
        if (focused && gif != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(gif).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else if (focused && video != null) {
            var loop by remember { mutableStateOf(0) }
            androidx.compose.runtime.key(loop) {
                TrailerPlayer(
                    trailerUrl = video,
                    isPlaying = true,
                    onEnded = { loop++ },
                    muted = true,
                    cropToFill = true,
                    // Burned-in black bars are cropped, so the picture fills the hero or card it plays in.
                    autoCropLetterbox = true,
                    focusable = false,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        if (item.title.isNotBlank() || !folder?.titleLogoUrl.isNullOrBlank()) {
            Box(modifier = Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to Color.Transparent, 0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.6f))
            ))
            if (highlighted) {
                CardLogo(folder?.titleLogoUrl, item.title, 0.5f, 0.34f, width, height, 16.dp, 22f)
            } else if (item.title.isNotBlank()) {
                Text(
                    text = item.title,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.6f), blurRadius = 8f)),
                    modifier = Modifier.align(Alignment.BottomStart).padding(12.dp)
                )
            }
        }
        if (highlighted && (gif != null || video != null)) {
            SoundChip(
                text = stringResource(R.string.home_animated_chip),
                icon = Icons.Filled.PlayArrow,
                modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).graphicsLayer { alpha = if (focused) 1f else 0.85f }
            )
        }
    }
}

/**
 * Starts a card's trailer without a flash: the artwork fades to black, the player starts behind that black, and the
 * black lifts once the trailer's first frame is on screen. (Covering the player with a fresh copy of the backdrop
 * flashed white or black for the frame that copy took to load, then showed the backdrop again before the trailer.)
 *
 * The player only runs while the card is still. On the video layer the picture is a separate surface the system
 * places on its own, and one started while its card was sliding into place (a row moving on to its next card, the
 * page scrolling to the row) was left out of line with the card: a smaller, offset copy inside it and a black block
 * beside it. If the card moves while its trailer plays, the black comes back and the trailer starts again once the
 * card has stopped.
 */
@Composable
internal fun BoxScope.HeldTrailer(active: Boolean, key: Any?, player: @Composable (onFirstFrame: () -> Unit) -> Unit) {
    // One value for the black, keyed to the trailer: a new trailer (or the same one again) always starts from the
    // artwork, never from a black still lifting from the last one.
    val dark = remember(key) { androidx.compose.animation.core.Animatable(0f) }
    var started by remember(key) { mutableStateOf(false) }
    var firstFrame by remember(key) { mutableStateOf(false) }
    // Where the card is and how big (a poster card widens as it opens).
    var position by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    var still by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(position) {
        still = false
        kotlinx.coroutines.delay(CARD_STILL_MS)
        still = true
    }
    androidx.compose.runtime.LaunchedEffect(active, key) {
        if (active) {
            dark.animateTo(1f, tween(260))
            started = true
            // A trailer that never starts (a dead link) mustn't leave the card black: the artwork comes back.
            kotlinx.coroutines.delay(TRAILER_HOLD_CAP_MS)
            if (!firstFrame) dark.animateTo(0f, tween(320))
        } else {
            started = false
            firstFrame = false
            dark.snapTo(0f)
        }
    }
    androidx.compose.runtime.LaunchedEffect(firstFrame) {
        if (firstFrame) dark.animateTo(0f, tween(320))
    }
    // Moved while playing: back to black until the trailer is up again in the card's new place.
    androidx.compose.runtime.LaunchedEffect(still) {
        if (!still && firstFrame) {
            firstFrame = false
            dark.snapTo(1f)
        }
    }
    Box(Modifier.matchParentSize().onGloballyPositioned {
        position = androidx.compose.ui.geometry.Rect(it.positionInWindow(), it.size.toSize())
    })
    if (active && started && still) player { firstFrame = true }
    if (active) {
        Box(Modifier.matchParentSize().graphicsLayer { alpha = dark.value }.background(Color.Black))
    }
}

/** How long a card must hold its place before its trailer starts (see [HeldTrailer]). */
private const val CARD_STILL_MS = 160L

/** How long [HeldTrailer] holds black for a trailer that hasn't shown a frame yet. */
private const val TRAILER_HOLD_CAP_MS = 8_000L
