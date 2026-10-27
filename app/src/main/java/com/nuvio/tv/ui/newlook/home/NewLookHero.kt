package com.nuvio.tv.ui.newlook.home

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.request.ImageRequest
import com.nuvio.tv.R
import com.nuvio.tv.core.home.FeaturedTag
import com.nuvio.tv.ui.components.TrailerPlayer
import com.nuvio.tv.ui.newlook.hairlineRing
import com.nuvio.tv.ui.screens.detail.detailGlassLens

/** One title in the hero carousel, with what the window shows about it. */
internal data class HeroSlide(
    val key: String,
    val title: String,
    val backdrop: String?,
    val logo: String?,
    val typeLabel: String,
    val genre: String?,
    val year: String?,
    val rating: String?,
    val ratingIsImdb: Boolean,
    val description: String?,
    val tag: FeaturedTag?,
    val tagSeason: Int?
)

/**
 * The new look's hero: a rounded window of featured titles (a carousel independent of focus). The current
 * title's backdrop fills it, or its trailer while the window has focus; logo, details, synopsis and the
 * Watch Now / More Info pills bottom-left; the carousel indicator bottom-right.
 */
@Composable
internal fun HeroWindow(
    slides: List<HeroSlide>,
    index: Int,
    focusedButton: Int?,
    watchFocus: FocusRequester,
    moreFocus: FocusRequester,
    trailerUrl: String?,
    trailerAudioUrl: String?,
    muted: Boolean,
    onTrailerEnded: () -> Unit,
    onButtonFocused: (Int) -> Unit,
    onWatchNow: () -> Unit,
    onMoreInfo: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * False for a grand row's card: the pills are drawn (the [focusedButton] one lit) but aren't focusable or
     * clickable, as the card around the window holds focus and Home moves between the pills.
     */
    interactive: Boolean = true,
    /** False drops the window's own drop shadow (a grand card's is clipped by the card around it). */
    shadow: Boolean = true,
    /** True starts the trailer from black ([HeldTrailer]) rather than under a copy of the backdrop. */
    holdTrailerInBlack: Boolean = false,
    /** The hero keeps its own corners; a grand card follows Layout's corner radius. */
    cornerRadius: androidx.compose.ui.unit.Dp = 16.dp,
    /**
     * Edge to edge (Layout → Home Layout): the window fills the screen with no corners, edge or shadow, and its bottom
     * fades out into the page; the text sits [textStart] in and [textBottom] up, clear of the rows over the fade.
     */
    edgeToEdge: Boolean = false,
    textStart: androidx.compose.ui.unit.Dp = 24.dp,
    textBottom: androidx.compose.ui.unit.Dp = 20.dp
) {
    val shape = if (edgeToEdge) androidx.compose.ui.graphics.RectangleShape else RoundedCornerShape(cornerRadius)
    val slide = slides.getOrNull(index)
    var trailerShowing by remember(trailerUrl) { mutableStateOf(false) }
    // Once the trailer has played for a few seconds the synopsis fades away, leaving the logo and details over the
    // video; it comes back when the trailer stops.
    val trailerRunning = trailerShowing && trailerUrl != null && focusedButton != null
    var synopsisHidden by remember(slide?.key) { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(trailerRunning) {
        if (trailerRunning) {
            kotlinx.coroutines.delay(TRAILER_FADE_DELAY_MS)
            synopsisHidden = true
        } else {
            synopsisHidden = false
        }
    }
    // The backdrop on show: the current slide's once it has loaded. Until then the last one stays up, so moving through
    // the carousel never leaves the window empty (with no fill under it, the page showed through while it loaded).
    var shownBackdrop by remember { mutableStateOf(slide?.backdrop) }
    // Layout's card depth waits for the first backdrop, so a window still loading isn't an empty lit box.
    var backdropShown by remember { mutableStateOf(false) }
    // Every slide's backdrop is fetched ahead at the window's size: the carousel moves on while the window is out of
    // sight, and coming back to a slide never shown left it black until the picture came in.
    var windowSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val prefetchContext = LocalContext.current
    androidx.compose.runtime.LaunchedEffect(slides.map { it.backdrop }, windowSize) {
        if (windowSize.width <= 0 || windowSize.height <= 0) return@LaunchedEffect
        slides.mapNotNull { it.backdrop }.distinct().forEach { url ->
            prefetchContext.imageLoader.enqueue(
                ImageRequest.Builder(prefetchContext).data(url).size(windowSize.width, windowSize.height).build()
            )
        }
    }
    // The window's faint edge brightens while it has focus, so it's clear where focus is.
    val ringColor by androidx.compose.animation.animateColorAsState(
        when {
            edgeToEdge -> Color.Transparent
            focusedButton != null -> com.nuvio.tv.ui.newlook.focusRingColor.copy(alpha = 0.3f)
            else -> Color.White.copy(alpha = 0.08f)
        },
        tween(240),
        label = "heroRing"
    )
    Box(
        modifier = modifier
            .then(
                if (shadow && !edgeToEdge) Modifier.shadow(20.dp, shape, ambientColor = Color.Black.copy(alpha = 0.55f), spotColor = Color.Black.copy(alpha = 0.55f))
                else Modifier
            )
            .then(if (edgeToEdge) Modifier.fadeOutBottom() else Modifier)
            .onSizeChanged { windowSize = it }
            .clip(shape)
            .hairlineRing(shape, ringColor)
    ) {
        val nextBackdrop = slide?.backdrop
        if (nextBackdrop != null && nextBackdrop != shownBackdrop) {
            // The next slide's backdrop, loaded out of sight at the window's size; the one on show crossfades to it
            // once it's in (from the memory cache, so at once).
            val context = LocalContext.current
            AsyncImage(
                model = remember(nextBackdrop) { ImageRequest.Builder(context).data(nextBackdrop).build() },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onSuccess = { if (nextBackdrop == slide?.backdrop) shownBackdrop = nextBackdrop },
                onError = { if (nextBackdrop == slide?.backdrop) shownBackdrop = nextBackdrop },
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 0f }
            )
        }
        Crossfade(targetState = shownBackdrop, animationSpec = tween(260), label = "heroBackdrop") { url ->
            if (url != null) {
                val context = LocalContext.current
                AsyncImage(
                    model = remember(url) { ImageRequest.Builder(context).data(url).build() },
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    onSuccess = { backdropShown = true },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        if (holdTrailerInBlack) {
            // A grand card: the backdrop fades to black and holds there until the trailer's first frame is up, as
            // the row cards do (a fresh backdrop cover flashed for the frame it took to load).
            HeldTrailer(active = trailerUrl != null && focusedButton != null, key = trailerUrl) { onFirstFrame ->
                TrailerPlayer(
                    trailerUrl = trailerUrl,
                    trailerAudioUrl = trailerAudioUrl,
                    isPlaying = true,
                    onEnded = onTrailerEnded,
                    onFirstFrameRendered = {
                        trailerShowing = true
                        onFirstFrame()
                    },
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
        } else if (trailerUrl != null && focusedButton != null) {
            val videoLayer = com.nuvio.tv.ui.components.LocalTrailerVideoLayer.current
            TrailerPlayer(
                trailerUrl = trailerUrl,
                trailerAudioUrl = trailerAudioUrl,
                isPlaying = true,
                onEnded = onTrailerEnded,
                onFirstFrameRendered = { trailerShowing = true },
                muted = muted,
                cropToFill = true,
                // Burned-in black bars are cropped, so the picture fills the hero or card it plays in.
                autoCropLetterbox = true,
                focusable = false,
                overscanZoom = com.nuvio.tv.ui.screens.home.MODERN_TRAILER_OVERSCAN_ZOOM,
                modifier = Modifier.fillMaxSize(),
                surfaceView = videoLayer
            )
            // On the video layer the trailer can't fade in: the backdrop covers it until its first frame, then fades.
            if (videoLayer) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = !trailerShowing,
                    enter = androidx.compose.animation.EnterTransition.None,
                    exit = androidx.compose.animation.fadeOut(tween(300))
                ) {
                    slide?.backdrop?.let { url ->
                        val context = LocalContext.current
                        AsyncImage(
                            model = remember(url) { ImageRequest.Builder(context).data(url).build() },
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } ?: Box(modifier = Modifier.fillMaxSize().background(Color(0xFF1C1C1C)))
                }
            }
        }
        // A dark shade from the bottom and from the left so the text stays readable.
        Box(modifier = Modifier.fillMaxSize().drawBehind { drawHeroShade() })
        // Layout's card depth, under the text so the sheen lights the picture, not the words.
        if (!edgeToEdge && backdropShown) CardDepthOverlay(shape, com.nuvio.tv.domain.model.CardDepthSurface.FEATURED)
        if (slide != null) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = textStart, bottom = textBottom)
                    .width(400.dp)
            ) {
                HeroLogo(slide)
                Spacer(modifier = Modifier.height(10.dp))
                DetailsLine(
                    type = slide.typeLabel,
                    genres = listOfNotNull(slide.genre),
                    year = slide.year,
                    runtime = null,
                    rating = slide.rating,
                    ratingIsImdb = slide.ratingIsImdb,
                    color = Color.White,
                    dotColor = Color.White.copy(alpha = 0.6f)
                )
                if (!slide.description.isNullOrBlank()) {
                    androidx.compose.animation.AnimatedVisibility(
                        visible = !synopsisHidden,
                        enter = androidx.compose.animation.fadeIn(tween(400)) + androidx.compose.animation.expandVertically(tween(400)),
                        exit = androidx.compose.animation.fadeOut(tween(700)) + androidx.compose.animation.shrinkVertically(tween(700, delayMillis = 200))
                    ) {
                        Column {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = slide.description,
                                color = Color(0xFFEEEEEE),
                                fontSize = 13.sp,
                                lineHeight = 17.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(if (interactive) 8.dp else 10.dp)) {
                    HeroButton(
                        label = stringResource(R.string.home_watch_now),
                        icon = Icons.Filled.PlayArrow,
                        compact = interactive,
                        focused = focusedButton == 0,
                        focusRequester = watchFocus.takeIf { interactive },
                        onFocused = { onButtonFocused(0) },
                        onClick = onWatchNow
                    )
                    HeroButton(
                        label = stringResource(R.string.home_more_info),
                        icon = null,
                        compact = interactive,
                        focused = focusedButton == 1,
                        focusRequester = moreFocus.takeIf { interactive },
                        onFocused = { onButtonFocused(1) },
                        onClick = onMoreInfo
                    )
                }
            }
            CarouselDots(
                count = slides.size,
                current = index,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = if (edgeToEdge) 40.dp else 16.dp, bottom = textBottom)
            )
        }
    }
}


/** Where an edge-to-edge hero starts fading into the page, and where it's gone (fractions of its height). */
private const val EDGE_FADE_START = 0.58f
private const val EDGE_FADE_END = 0.98f

/**
 * The edge-to-edge hero's bottom melting into the page (and its ambient colour) instead of ending on a line: drawn
 * on its own layer, then cut by a gradient.
 */
private fun Modifier.fadeOutBottom(): Modifier = this
    .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(
            androidx.compose.ui.graphics.Brush.verticalGradient(
                EDGE_FADE_START to Color.Black,
                EDGE_FADE_END to Color.Transparent
            ),
            blendMode = androidx.compose.ui.graphics.BlendMode.DstIn
        )
    }

/** Where the bottom shade starts, and where the left one ends (fractions of the window). */
private const val SHADE_TOP = 0.35f
private const val SHADE_LEFT_END = 0.55f
private const val SHADE_RES = 100

/**
 * The window's shade, the bottom gradient and the left one together (as drawn one over the other), baked once into
 * a small image. Both are straight lines between their stops, so stretched with filtering it draws the same
 * gradients, in one pass instead of two over the whole window.
 */
private val HeroShade: ImageBitmap by lazy {
    fun vertical(y: Float) = when {
        y < SHADE_TOP -> 0f
        y < 0.55f -> 0.25f * (y - SHADE_TOP) / (0.55f - SHADE_TOP)
        else -> 0.25f + (0.78f - 0.25f) * (y - 0.55f) / 0.45f
    }
    fun horizontal(x: Float) = if (x < SHADE_LEFT_END) 0.55f * (1f - x / SHADE_LEFT_END) else 0f
    val pixels = IntArray(SHADE_RES * SHADE_RES) { i ->
        val x = (i % SHADE_RES + 0.5f) / SHADE_RES
        val y = (i / SHADE_RES + 0.5f) / SHADE_RES
        val alpha = 1f - (1f - vertical(y)) * (1f - horizontal(x))
        (alpha * 255f + 0.5f).toInt().coerceIn(0, 255) shl 24
    }
    android.graphics.Bitmap.createBitmap(pixels, SHADE_RES, SHADE_RES, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
}

/**
 * Draws [HeroShade] only where it isn't clear: the bottom part across the window, and the top-left corner (the left
 * shade alone). The top right, a sixth of the window, is left alone.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHeroShade() {
    val w = size.width.toInt()
    val h = size.height.toInt()
    val top = (h * SHADE_TOP).toInt()
    val leftEnd = (w * SHADE_LEFT_END).toInt()
    val srcTop = (SHADE_RES * SHADE_TOP).toInt()
    val srcLeftEnd = (SHADE_RES * SHADE_LEFT_END).toInt()
    drawImage(
        HeroShade,
        srcOffset = IntOffset(0, srcTop), srcSize = IntSize(SHADE_RES, SHADE_RES - srcTop),
        dstOffset = IntOffset(0, top), dstSize = IntSize(w, h - top),
        filterQuality = FilterQuality.Low
    )
    drawImage(
        HeroShade,
        srcOffset = IntOffset(0, 0), srcSize = IntSize(srcLeftEnd, srcTop),
        dstOffset = IntOffset(0, 0), dstSize = IntSize(leftEnd, top),
        filterQuality = FilterQuality.Low
    )
}

@Composable
private fun HeroLogo(slide: HeroSlide, modifier: Modifier = Modifier) {
    var failed by remember(slide.logo) { mutableStateOf(false) }
    if (!slide.logo.isNullOrBlank() && !failed) {
        val context = LocalContext.current
        AsyncImage(
            model = remember(slide.logo) { ImageRequest.Builder(context).data(slide.logo).build() },
            contentDescription = slide.title,
            contentScale = ContentScale.Fit,
            alignment = Alignment.BottomStart,
            onError = { failed = true },
            modifier = modifier.height(56.dp).widthIn(max = 260.dp)
        )
    } else {
        Text(
            text = slide.title.uppercase(),
            color = Color.White,
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier.heightIn(min = 56.dp).padding(top = 8.dp)
        )
    }
}

/**
 * How long a trailer plays before the text over it fades: the hero's synopsis on Home, everything but the logo on
 * the details page.
 */
internal const val TRAILER_FADE_DELAY_MS = 4_000L

/**
 * A pill in the details page's liquid glass: focused, the bright glass lens and a little larger; otherwise a faint
 * clear glass. White text either way. Without a [focusRequester] it's only drawn (a grand card's pills; the card
 * takes the keys). [compact]: the hero's, a little smaller than a grand card's.
 */
@Composable
private fun HeroButton(
    label: String,
    icon: ImageVector?,
    compact: Boolean,
    focused: Boolean,
    focusRequester: FocusRequester?,
    onFocused: () -> Unit,
    onClick: () -> Unit
) {
    val scale by animateFloatAsState(if (focused) 1.04f else 1f, tween(120), label = "heroButtonScale")
    val content = if (focused) Color.White else Color.White.copy(alpha = 0.9f)
    val shape = RoundedCornerShape(if (compact) 14.dp else 16.dp)
    Row(
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .height(if (compact) 28.dp else 32.dp)
            .then(if (focused) Modifier.detailGlassLens(shape) else Modifier.heroGlassIdle(shape))
            .then(
                if (focusRequester == null) Modifier else Modifier
                    .focusRequester(focusRequester)
                    .onFocusChanged { if (it.isFocused) onFocused() }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClick
                    )
            )
            .padding(horizontal = if (compact) 14.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 7.dp)
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(if (compact) 13.dp else 15.dp))
        }
        Text(text = label, color = content, fontSize = if (compact) 12.sp else 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** An unfocused hero pill: a faint dark glass with a thin rim, so it reads over any backdrop without glowing. */
private fun Modifier.heroGlassIdle(shape: RoundedCornerShape): Modifier = this
    .drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val body = Brush.verticalGradient(
            0f to Color(40, 40, 40).copy(alpha = 0.42f),
            1f to Color(16, 16, 16).copy(alpha = 0.48f)
        )
        val rim = Brush.verticalGradient(
            0f to Color.White.copy(alpha = 0.30f),
            0.4f to Color.White.copy(alpha = 0.08f),
            1f to Color.White.copy(alpha = 0.14f)
        )
        onDrawBehind {
            drawOutline(outline, body)
            drawOutline(outline, rim, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
        }
    }

/** A dark capsule; the current title is a short lit bar that flows to its new place, the others dots. */
@Composable
private fun CarouselDots(count: Int, current: Int, modifier: Modifier = Modifier) {
    if (count <= 1) return
    Row(
        modifier = modifier
            .shadow(3.dp, RoundedCornerShape(6.dp))
            .height(12.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(38, 38, 38, 199))
            .padding(horizontal = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        repeat(count) { i ->
            val on = i == current
            val width by animateDpAsState(
                targetValue = if (on) 14.dp else 5.dp,
                animationSpec = spring(dampingRatio = 0.65f, stiffness = 420f),
                label = "dotWidth"
            )
            val alpha by animateFloatAsState(if (on) 1f else 0.32f, tween(320), label = "dotAlpha")
            Box(
                modifier = Modifier
                    .size(width = width, height = 5.dp)
                    .clip(RoundedCornerShape(2.5.dp))
                    .background(Color.White.copy(alpha = alpha))
            )
        }
    }
}


/**
 * A grand row's card: the hero window for one title, at the window's size. The card holds focus and Home moves
 * between its two pills ([button]: 0 Watch Now, 1 More Info). While focused it plays the title's trailer, as the
 * hero does, and [onTrailerEnded] moves on to the next card.
 */
@Composable
internal fun GrandCard(
    slide: HeroSlide,
    focused: Boolean,
    button: Int,
    trailerUrl: String?,
    trailerAudioUrl: String?,
    muted: Boolean,
    dims: NewLookDimens,
    onTrailerEnded: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    // Never focused: the pills are only drawn, so these just satisfy the window's parameters.
    val unusedWatch = remember { FocusRequester() }
    val unusedMore = remember { FocusRequester() }
    val slides = remember(slide) { listOf(slide) }
    NewLookCard(dims.grandWidth, dims.grandHeight, onClick, onLongClick, onFocusChanged, modifier) {
        HeroWindow(
            slides = slides,
            index = 0,
            focusedButton = if (focused) button else null,
            watchFocus = unusedWatch,
            moreFocus = unusedMore,
            trailerUrl = if (focused) trailerUrl else null,
            trailerAudioUrl = if (focused) trailerAudioUrl else null,
            muted = muted,
            onTrailerEnded = onTrailerEnded,
            onButtonFocused = {},
            onWatchNow = onClick,
            onMoreInfo = onClick,
            interactive = false,
            shadow = false,
            holdTrailerInBlack = true,
            cornerRadius = LocalNewLookCardRadius.current,
            modifier = Modifier.fillMaxSize()
        )
    }
}
