@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.browse

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.nuvio.tv.ui.screens.detail.selectionSurface
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.core.image.customPosterFallback
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nuvio.tv.R
import com.nuvio.tv.core.browse.BrowseItemDetails
import com.nuvio.tv.core.browse.BrowseRatingSource
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.components.WatchedMarker
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.theme.ThemeColors
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The plain look (the White theme's colours): the colour picked in Appearance only reaches the selection ring, so
 * these screens stay plain glass and grey whatever it is.
 */
private val Plain = ThemeColors.White

/** Card size shared by the Genres and Search grids: five columns fill the right of the screen. */
internal val BrowseCardWidth = 128.dp
internal val BrowseGridSpacing = 8.dp
internal const val BrowseGridColumns = 5

/** The grid's width for a poster [cardWidth]: five posters and the gaps between them. */
internal fun browseGridWidth(cardWidth: Dp): Dp = cardWidth * BrowseGridColumns + BrowseGridSpacing * (BrowseGridColumns - 1)

/** The widest poster (up to the usual 128 dp) whose grid fits in [width]; posters keep their 2:3 shape. */
internal fun browseCardWidthToFit(width: Dp): Dp =
    ((width - BrowseGridSpacing * (BrowseGridColumns - 1)) / BrowseGridColumns).coerceIn(1.dp, BrowseCardWidth)

internal val BrowseListTextStyle
    @Composable get() = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp)

/**
 * One row of the genre or sub-genre list. Focus puts the glass lens on it; the selection
 * that drives the grid keeps a muted fill once focus moves on.
 */
@Composable
internal fun BrowseListRow(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
    onClick: () -> Unit = {}
) {
    var focused by remember { mutableStateOf(false) }
    val colors = NuvioTheme.colors
    val container = if (selected) Plain.focusBackground else Color.Transparent
    val content = when {
        focused -> Color.White
        selected -> colors.TextPrimary
        else -> colors.TextSecondary
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(28.dp)
            .selectionSurface(focused, RoundedCornerShape(NuvioTheme.radii.sm), container)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = NuvioTheme.spacing.sm),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = text,
            style = BrowseListTextStyle,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Outlined pill used for Movies / Shows / All and for recent searches. */
@Composable
internal fun BrowsePill(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val colors = NuvioTheme.colors
    val shape = RoundedCornerShape(NuvioTheme.radii.md)
    Box(
        modifier = modifier
            .height(28.dp)
            .selectionSurface(focused, shape, if (selected) Plain.focusBackground else Color.Transparent)
            .then(
                if (focused) Modifier else Modifier.border(
                    BorderStroke(NuvioTheme.spacing.hairline, if (selected) Plain.focusRing.copy(alpha = 0.6f) else colors.Border),
                    shape
                )
            )
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = when {
                focused -> Color.White
                selected -> colors.TextPrimary
                else -> colors.TextSecondary
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** "Popular | Action" or "| Trending Today": a title split by an accent bar. */
@Composable
internal fun BrowseHeader(
    title: String?,
    subtitle: String?,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.height(32.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
    ) {
        if (!title.isNullOrEmpty()) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                color = NuvioTheme.colors.TextPrimary,
                maxLines = 1
            )
        }
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(22.dp)
                .clip(RoundedCornerShape(NuvioTheme.radii.xxs))
                .background(Plain.secondary)
        )
        if (!subtitle.isNullOrEmpty()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                color = if (title.isNullOrEmpty()) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Five-column poster grid. Keeps the last focused poster when focus comes back, asks for the next
 * page a few rows before the end, and reports LEFT on the first column so the screen can bring its
 * side column back.
 */
@Composable
internal fun BrowsePosterGrid(
    items: List<MetaPreview>,
    isLoading: Boolean,
    isLoadingMore: Boolean,
    canLoadMore: Boolean,
    gridState: LazyGridState,
    focusRequester: FocusRequester,
    firstItemFocusRequester: FocusRequester,
    restoreIndex: Int,
    restoreFocusRequester: FocusRequester,
    onLoadMore: () -> Unit,
    onItemClick: (MetaPreview) -> Unit,
    onItemFocused: (Int, MetaPreview) -> Unit,
    onLeftFromFirstColumn: () -> Unit,
    modifier: Modifier = Modifier,
    emptyText: String? = null,
    isWatched: (MetaPreview) -> Boolean = { false },
    /**
     * Key cards by position, so swapping in a whole new set of titles (another genre, sort or
     * keyword) updates the cards already on screen instead of building ~20 new ones. Turn off
     * where results stream in and reorder, so focus stays on the same title.
     */
    reuseCardsAcrossSets: Boolean = true,
    /** Poster width (height follows at 2:3); smaller where the screen has less room. */
    cardWidth: Dp = BrowseCardWidth,
    /**
     * More titles are on the way (the next page, or search catalogs still answering): skeleton posters fill out the
     * last row and one more after the titles. They can't take focus.
     */
    loadingTail: Boolean = isLoadingMore
) {
    val cardHeight = cardWidth * 1.5f
    // Item lambdas read these holders, so a new callback from the screen doesn't recompose every
    // visible poster.
    val itemClick = rememberUpdatedState(onItemClick)
    val itemFocused = rememberUpdatedState(onItemFocused)
    val leftFromFirstColumn = rememberUpdatedState(onLeftFromFirstColumn)
    val latestCanLoadMore by rememberUpdatedState(canLoadMore && !isLoadingMore && !isLoading)
    val latestOnLoadMore by rememberUpdatedState(onLoadMore)
    val latestItemCount by rememberUpdatedState(items.size)
    // Where focus is in the grid (-1: not in it). Cards are keyed by position, so when the titles change under a
    // focused card (results streaming in and re-ranking), focus stays put and the new title there is reported.
    var focusedIndex by remember { mutableIntStateOf(-1) }
    LaunchedEffect(items) {
        val index = focusedIndex
        if (reuseCardsAcrossSets && index >= 0) items.getOrNull(index)?.let { itemFocused.value(index, it) }
    }

    LaunchedEffect(gridState) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged()
            .collect { lastVisible ->
                if (lastVisible >= 0 && latestCanLoadMore && lastVisible >= latestItemCount - BrowseGridColumns * 2) {
                    latestOnLoadMore()
                }
            }
    }
    // A short first page can leave nothing to scroll, so ask for more as soon as it lands.
    LaunchedEffect(items.size, canLoadMore, isLoadingMore) {
        if (items.isNotEmpty() && items.size < BrowseGridColumns * 3 && latestCanLoadMore) latestOnLoadMore()
    }

    val skeletonPulse = rememberSkeletonPulse()
    Box(modifier = modifier.width(browseGridWidth(cardWidth))) {
        when {
            items.isEmpty() && isLoading -> BrowseSkeletonGrid(cardWidth = cardWidth, pulse = skeletonPulse)
            items.isEmpty() -> {
                if (emptyText != null) {
                    Text(
                        text = emptyText,
                        style = MaterialTheme.typography.bodyLarge,
                        color = NuvioTheme.colors.TextSecondary,
                        modifier = Modifier.padding(top = NuvioTheme.spacing.xl)
                    )
                }
            }
            else -> {
                val upcomingFormatter = remember { DateTimeFormatter.ofPattern("MMMM d", Locale.getDefault()) }
                val today = remember { LocalDate.now() }
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(BrowseGridColumns),
                    modifier = Modifier
                        .fillMaxSize()
                        // Dimmed while the next set of titles loads behind it.
                        .graphicsLayer { alpha = if (isLoading) 0.4f else 1f }
                        .focusRequester(focusRequester)
                        .focusRestorer()
                        .onFocusChanged { if (!it.hasFocus) focusedIndex = -1 },
                    contentPadding = PaddingValues(top = 6.dp, bottom = NuvioTheme.spacing.xxl, start = 4.dp, end = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(BrowseGridSpacing),
                    verticalArrangement = Arrangement.spacedBy(BrowseGridSpacing)
                ) {
                    itemsIndexed(
                        items = items,
                        key = if (reuseCardsAcrossSets) null else { _, item -> item.id },
                        contentType = { _, _ -> "browse_poster" }
                    ) { index, item ->
                        val upcomingLabel = remember(item.released) {
                            item.released
                                ?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }
                                ?.takeIf { it.isAfter(today) }
                                ?.format(upcomingFormatter)
                        }
                        Box(
                            modifier = Modifier
                                .then(if (index == 0) Modifier.focusRequester(firstItemFocusRequester) else Modifier)
                                .onPreviewKeyEvent { event ->
                                    val native = event.nativeKeyEvent
                                    if (native.action == AndroidKeyEvent.ACTION_DOWN &&
                                        native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_LEFT &&
                                        index % BrowseGridColumns == 0
                                    ) {
                                        leftFromFirstColumn.value()
                                        true
                                    } else {
                                        false
                                    }
                                }
                        ) {
                            BrowsePosterCard(
                                item = item,
                                width = cardWidth,
                                height = cardHeight,
                                isWatched = isWatched(item),
                                focusRequester = if (index == restoreIndex) restoreFocusRequester else null,
                                onClick = { itemClick.value(item) },
                                onFocused = {
                                    focusedIndex = index
                                    itemFocused.value(index, item)
                                }
                            )
                            if (upcomingLabel != null) {
                                UpcomingDateLabel(upcomingLabel, cardWidth)
                            }
                        }
                    }
                    if (loadingTail) {
                        val fill = (BrowseGridColumns - items.size % BrowseGridColumns) % BrowseGridColumns
                        items(fill + BrowseGridColumns, key = { "browse_skeleton_$it" }, contentType = { "browse_skeleton" }) {
                            BrowseSkeletonCard(cardWidth, skeletonPulse)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Poster for the browse grids. Lighter than the shared poster card, whose layered Card and
 * focus animations made swapping a whole grid (a new genre or keyword) cost a long frame on TV
 * hardware. Same theme focus ring and lift; posters appear without a fade so the GPU isn't
 * redrawing twenty crossfades at once.
 */
@Composable
private fun BrowsePosterCard(
    item: MetaPreview,
    width: Dp,
    height: Dp,
    isWatched: Boolean,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
    onFocused: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(NuvioTheme.radii.md)
    val ring = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs)
    val placeholder = Plain.backgroundCard
    val context = LocalContext.current
    val density = LocalDensity.current
    val request = remember(item.poster, item.rawPosterUrl, density, width) {
        ImageRequest.Builder(context)
            .data(item.poster)
            .customPosterFallback(item.poster, item.rawPosterUrl)
            .size(with(density) { width.roundToPx() }, with(density) { height.roundToPx() })
            .crossfade(false)
            .build()
    }
    Box(
        modifier = Modifier
            .size(width, height)
            .graphicsLayer {
                val scale = if (focused) FOCUSED_SCALE else 1f
                scaleX = scale
                scaleY = scale
            }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .clip(shape)
            .background(placeholder)
    ) {
        if (!item.poster.isNullOrBlank()) {
            AsyncImage(
                model = request,
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        if (isWatched) {
            WatchedMarker(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = NuvioTheme.spacing.sm, top = NuvioTheme.spacing.sm)
            )
        }
        if (focused) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .border(ring, shape)
            )
        }
    }
}

private const val FOCUSED_SCALE = 1.04f

@Composable
private fun androidx.compose.foundation.layout.BoxScope.UpcomingDateLabel(text: String, width: Dp) {
    Box(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .width(width)
            .clip(RoundedCornerShape(bottomStart = NuvioTheme.radii.md, bottomEnd = NuvioTheme.radii.md))
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
            .padding(top = NuvioTheme.spacing.md, bottom = NuvioTheme.spacing.xs),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = Color.White
        )
    }
}

/** Outlined placeholders in the grid's shape while the first page loads. */
@Composable
internal fun BrowseSkeletonGrid(rows: Int = 3, cardWidth: Dp = BrowseCardWidth, pulse: State<Float> = rememberSkeletonPulse()) {
    Column(
        modifier = Modifier.padding(start = 4.dp, top = 6.dp),
        verticalArrangement = Arrangement.spacedBy(BrowseGridSpacing)
    ) {
        repeat(rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(BrowseGridSpacing)) {
                repeat(BrowseGridColumns) { BrowseSkeletonCard(cardWidth, pulse) }
            }
        }
    }
}

/** The pulse every skeleton poster on a screen shares (one animation, not one per card). */
@Composable
internal fun rememberSkeletonPulse(): State<Float> =
    androidx.compose.animation.core.rememberInfiniteTransition(label = "browseSkeleton").animateFloat(
        initialValue = 0.45f,
        targetValue = 0.9f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(900, easing = androidx.compose.animation.core.LinearEasing),
            androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "browseSkeletonAlpha"
    )

/** A loading poster: the card colour, softly pulsing, with a hairline edge. Never focusable. */
@Composable
internal fun BrowseSkeletonCard(cardWidth: Dp, pulse: State<Float>) {
    val shape = RoundedCornerShape(NuvioTheme.radii.md)
    val fill = Plain.backgroundCard
    Box(
        modifier = Modifier
            .size(cardWidth, cardWidth * 1.5f)
            .clip(shape)
            .border(BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border), shape)
            // Read while drawing, so the pulse redraws without recomposing.
            .drawBehind { drawRect(fill, alpha = pulse.value) }
    )
}

/**
 * Details for the poster in focus: logo or title, then a frosted card built from a tiny decode
 * of the poster, with year, runtime, rating, genres, overview and cast.
 */
@Composable
internal fun BrowseInfoPanel(
    details: StateFlow<BrowseItemDetails?>,
    modifier: Modifier = Modifier
) {
    // Collected here so a focus move redraws the panel, not the screen around it.
    val current by details.collectAsState()
    BrowseInfoPanelContent(current, modifier)
}

@Composable
private fun BrowseInfoPanelContent(
    details: BrowseItemDetails?,
    modifier: Modifier
) {
    val colors = NuvioTheme.colors
    Column(modifier = modifier.width(PanelWidth)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(104.dp),
            contentAlignment = Alignment.Center
        ) {
            when {
                details == null -> Unit
                !details.logo.isNullOrBlank() -> AsyncImage(
                    model = details.logo,
                    contentDescription = details.title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(88.dp)
                )
                else -> Text(
                    text = details.title,
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                    color = colors.TextPrimary,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))
        val shape = RoundedCornerShape(NuvioTheme.radii.xl)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(shape)
                .background(Color.Black)
                .border(BorderStroke(NuvioTheme.spacing.hairline, colors.Border.copy(alpha = 0.7f)), shape)
        ) {
            PosterMeshBackground(
                mesh = rememberPosterMesh(details?.poster?.takeIf { it.isNotBlank() && LocalPosterColourMesh.current }),
                modifier = Modifier.matchParentSize()
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = 0.32f), Color.Black.copy(alpha = 0.62f))
                        )
                    )
            )
            if (details != null) {
                PanelBody(details)
            }
        }
    }
}

/**
 * The focused poster's most prominent colours as soft overlapping glows on black, biggest first. The colours and
 * their places animate from one poster to the next; every frame of that only redraws this box. Black while the
 * colours load or when there is no poster.
 */
@Composable
private fun PosterMeshBackground(mesh: PosterMesh?, modifier: Modifier) {
    val spec = tween<Color>(durationMillis = 360)
    val spots = List(MeshSpots) { i ->
        val spot = mesh?.spots?.getOrNull(i)
        val color = animateColorAsState(spot?.color ?: Color.Black.copy(alpha = 0f), spec, label = "meshColor$i")
        val center = animateOffsetAsState(
            spot?.let { Offset(it.x, it.y) } ?: MeshRestingPlaces[i],
            tween(durationMillis = 360),
            label = "meshCenter$i"
        )
        val share = animateFloatAsState(spot?.share ?: 0f, tween(durationMillis = 360), label = "meshShare$i")
        Triple(color, center, share)
    }
    Box(
        modifier = modifier.drawBehind {
            val reach = size.maxDimension
            // Most prominent last, so it sits on top of the accents.
            for (i in spots.indices.reversed()) {
                val (color, center, share) = spots[i]
                val c = color.value
                if (c.alpha <= 0.01f) continue
                val origin = Offset(center.value.x * size.width, center.value.y * size.height)
                val radius = reach * (0.45f + 0.6f * share.value.coerceIn(0f, 1f))
                // A radial gradient with radius 0 throws; the panel can be measured at zero size while it fades.
                if (radius <= 0f) continue
                drawCircle(
                    brush = Brush.radialGradient(
                        0f to c,
                        0.45f to c.copy(alpha = c.alpha * 0.7f),
                        1f to c.copy(alpha = 0f),
                        center = origin,
                        radius = radius
                    ),
                    radius = radius,
                    center = origin
                )
            }
        }
    )
}

/** Where a missing colour fades out: the panel's corners and middle. */
private val MeshRestingPlaces = listOf(Offset(0.5f, 0.5f), Offset(0.15f, 0.15f), Offset(0.85f, 0.85f), Offset(0.85f, 0.15f))

internal val PanelWidth = 230.dp

private val KitsuOrange = Color(0xFFF75239)

@Composable
private fun PanelBody(details: BrowseItemDetails) {
    val colors = NuvioTheme.colors
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = NuvioTheme.spacing.lg, vertical = NuvioTheme.spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val metaLine = listOfNotNull(details.year, details.runtime, details.ageRating)
            .filter { it.isNotBlank() }
            .joinToString("  ·  ")
        if (metaLine.isNotEmpty()) {
            Text(
                text = metaLine,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.TextPrimary,
                maxLines = 1
            )
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))
        }
        details.rating?.takeIf { it > 0f }?.let { rating ->
            RatingBadge(rating = rating, source = details.ratingSource)
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))
        }
        if (details.genres.isNotEmpty()) {
            Text(
                text = details.genres.joinToString("  ·  "),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.TextPrimary,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        PanelDivider()
        details.overview?.takeIf { it.isNotBlank() }?.let { overview ->
            Text(
                text = overview,
                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp),
                color = colors.TextPrimary,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (details.cast.isNotEmpty()) {
            PanelDivider()
            Text(
                text = details.cast.joinToString(", "),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                color = colors.TextSecondary,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun PanelDivider() {
    Box(
        modifier = Modifier
            .padding(vertical = NuvioTheme.spacing.lg)
            .width(64.dp)
            .height(1.dp)
            .background(NuvioTheme.colors.TextTertiary.copy(alpha = 0.6f))
    )
}

@Composable
private fun RatingBadge(rating: Float, source: BrowseRatingSource) {
    val colors = NuvioTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
    ) {
        val (label, badgeColor, badgeText) = when (source) {
            BrowseRatingSource.IMDB -> Triple("IMDb", colors.source.imdb, Color.Black)
            BrowseRatingSource.TMDB -> Triple("TMDB", colors.source.tmdb, Color.White)
            BrowseRatingSource.KITSU -> Triple("Kitsu", KitsuOrange, Color.White)
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(NuvioTheme.radii.xs))
                .background(badgeColor)
                .padding(horizontal = 6.dp, vertical = 1.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = badgeText
            )
        }
        Text(
            text = String.format(Locale.US, "%.1f", rating),
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = colors.TextPrimary
        )
    }
}

@Composable
internal fun browseSortLabel(sort: com.nuvio.tv.core.browse.BrowseSort): String = stringResource(
    when (sort) {
        com.nuvio.tv.core.browse.BrowseSort.POPULAR -> R.string.browse_sort_popular
        com.nuvio.tv.core.browse.BrowseSort.LATEST -> R.string.browse_sort_latest
        com.nuvio.tv.core.browse.BrowseSort.MOST_RATED -> R.string.browse_sort_most_rated
    }
)

/** Width of the keyboard column; it doesn't change with its contents, so the grid never shifts. */
internal val BrowseSubColumnWidth: Dp = 200.dp
