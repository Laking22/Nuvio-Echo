package com.nuvio.tv.ui.screens.detail

import com.nuvio.tv.ui.theme.NuvioTheme

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.TraktCommentReview
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.ui.util.contentTextDirection
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalComposeUiApi::class, ExperimentalTvMaterial3Api::class)
@Composable
fun CommentOverlay(
    review: TraktCommentReview,
    episode: Video?,
    canNavigatePrevious: Boolean,
    canNavigateNext: Boolean,
    isLoadingNext: Boolean,
    transitionDirection: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onDismiss: () -> Unit
) {
    val primaryFocusRequester = remember { FocusRequester() }
    val mainContentFocusRequester = remember { FocusRequester() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF070707),
                            Color(0xFF101010),
                            Color(0xFF151515)
                        )
                    )
                )
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) {
                        false
                    } else {
                        when (event.key) {
                            Key.DirectionLeft -> {
                                if (canNavigatePrevious) {
                                    onPrevious()
                                    true
                                } else {
                                    false
                                }
                            }
                            Key.DirectionRight -> {
                                if (canNavigateNext) {
                                    if (!isLoadingNext) onNext()
                                    true
                                } else {
                                    false
                                }
                            }
                            else -> false
                        }
                    }
                }
                .padding(horizontal = NuvioTheme.spacing.xl, vertical = 10.dp)
        ) {
            AnimatedContent(
                targetState = review,
                transitionSpec = {
                    when {
                        transitionDirection > 0 -> {
                            slideInHorizontally(initialOffsetX = { it / 5 }) + fadeIn() togetherWith
                                slideOutHorizontally(targetOffsetX = { -it / 5 }) + fadeOut()
                        }
                        transitionDirection < 0 -> {
                            slideInHorizontally(initialOffsetX = { -it / 5 }) + fadeIn() togetherWith
                                slideOutHorizontally(targetOffsetX = { it / 5 }) + fadeOut()
                        }
                        else -> fadeIn() togetherWith fadeOut()
                    }
                },
                label = "comment_overlay_transition",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(end = 10.dp)
            ) {
                currentReview ->
                CommentOverlayContent(
                    review = currentReview,
                    episode = episode,
                    primaryFocusRequester = primaryFocusRequester,
                    mainContentFocusRequester = mainContentFocusRequester,
                    isLoadingNext = isLoadingNext
                )
            }

            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .width(168.dp)
                    .padding(top = 6.dp, end = NuvioTheme.spacing.xs)
                    .focusRequester(primaryFocusRequester)
                    .focusable()
                    .focusProperties {
                        down = mainContentFocusRequester
                    },
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.trakt_logo_wordmark),
                    contentDescription = stringResource(R.string.cd_trakt_logo),
                    modifier = Modifier.width(168.dp),
                    colorFilter = ColorFilter.tint(Color.White.copy(alpha = 0.92f))
                )
                Text(
                    text = stringResource(R.string.detail_comments_back_hint),
                    modifier = Modifier.padding(start = 18.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.34f)
                )
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun CommentOverlayContent(
    review: TraktCommentReview,
    episode: Video?,
    primaryFocusRequester: FocusRequester,
    mainContentFocusRequester: FocusRequester,
    isLoadingNext: Boolean
) {
    val commentScrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()
    var isSpoilerRevealed by rememberSaveable(review.id) { mutableStateOf(!review.hasSpoilerContent) }
    val commentText = if (review.hasSpoilerContent && !isSpoilerRevealed) {
        stringResource(R.string.detail_comments_spoiler_hidden)
    } else {
        review.comment
    }
    val commentStyle = readerCommentStyle(commentText.length)
    val formattedCommentDate = remember(review.createdAt, review.updatedAt) {
        formatCommentTimestamp(review.createdAt, review.updatedAt)
    }
    val overlayLabels = buildList {
        episode?.let { add(selectedEpisodeLabel(it)) }
        if (review.review) add(stringResource(R.string.detail_comments_badge_review))
        if (review.hasSpoilerContent) add(stringResource(R.string.detail_comments_badge_spoiler))
        review.rating?.let { add(stringResource(R.string.detail_comments_badge_rating, it)) }
        formattedCommentDate?.let { add(it) }
    }

    LaunchedEffect(review.id) {
        mainContentFocusRequester.requestFocus()
        withFrameNanos { }
        commentScrollState.scrollTo(0)
        withFrameNanos { }
        commentScrollState.scrollTo(0)
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = stringResource(R.string.detail_comments_title),
            style = MaterialTheme.typography.titleMedium,
            color = Color.White.copy(alpha = 0.82f)
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = review.authorDisplayName,
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold
                )
                review.authorUsername
                    ?.takeIf { it.isNotBlank() }
                    ?.let { username ->
                        Text(
                            text = "@$username",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.White.copy(alpha = 0.62f)
                        )
                    }
                if (overlayLabels.isNotEmpty()) {
                    OverlayMetaRow(labels = overlayLabels)
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(commentScrollState)
                    .focusRequester(mainContentFocusRequester)
                    .focusable()
                    .focusProperties {
                        up = primaryFocusRequester
                    }
                    .onPreviewKeyEvent { event ->
                        when {
                            event.type != KeyEventType.KeyDown -> false
                            event.key == Key.DirectionDown && commentScrollState.value < commentScrollState.maxValue -> {
                                coroutineScope.launch {
                                    commentScrollState.animateScrollTo(
                                        (commentScrollState.value + 260).coerceAtMost(commentScrollState.maxValue)
                                    )
                                }
                                true
                            }
                            event.key == Key.DirectionUp && commentScrollState.value > 0 -> {
                                coroutineScope.launch {
                                    commentScrollState.animateScrollTo(
                                        (commentScrollState.value - 260).coerceAtLeast(0)
                                    )
                                }
                                true
                            }
                            !isSpoilerRevealed && (
                                event.key == Key.DirectionCenter ||
                                    event.key == Key.Enter ||
                                    event.key == Key.NumPadEnter
                                ) -> {
                                isSpoilerRevealed = true
                                true
                            }
                            else -> false
                        }
                    },
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    text = commentText,
                    style = commentStyle.copy(textDirection = commentText.contentTextDirection()),
                    color = Color.White,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = stringResource(R.string.detail_comments_likes, review.likes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.56f)
                )
                if (review.hasSpoilerContent && !isSpoilerRevealed) {
                    Text(
                        text = stringResource(R.string.detail_comments_reveal_spoiler_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.62f)
                    )
                }
                if (isLoadingNext) {
                    Text(
                        text = "...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.42f)
                    )
                }
            }
        }
    }
}

@Composable
private fun OverlayMetaRow(labels: List<String>) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        labels.forEachIndexed { index, label ->
            if (index > 0) {
                Box(
                    modifier = Modifier
                        .size(NuvioTheme.spacing.xs)
                        .background(Color.White.copy(alpha = 0.42f), CircleShape)
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.72f)
            )
        }
    }
}

@Composable
private fun readerCommentStyle(length: Int): TextStyle {
    val typography = MaterialTheme.typography
    return when {
        length <= 160 -> typography.displaySmall.copy(fontSize = 40.sp, lineHeight = 48.sp)
        length <= 280 -> typography.headlineLarge.copy(fontSize = 30.sp, lineHeight = 38.sp)
        length <= 420 -> typography.headlineMedium.copy(fontSize = 24.sp, lineHeight = 31.sp)
        length <= 650 -> typography.titleLarge.copy(fontSize = 20.sp, lineHeight = 26.sp)
        length <= 900 -> typography.titleMedium.copy(fontSize = 18.sp, lineHeight = 23.sp)
        else -> typography.bodyLarge.copy(fontSize = 16.sp, lineHeight = 21.sp)
    }
}

private fun formatCommentTimestamp(createdAt: String?, updatedAt: String?): String? {
    val rawTimestamp = createdAt?.trim()?.takeIf { it.isNotBlank() }
        ?: updatedAt?.trim()?.takeIf { it.isNotBlank() }
        ?: return null

    val instant = runCatching {
        if (rawTimestamp.all { it.isDigit() }) {
            val epoch = rawTimestamp.toLong()
            val epochMillis = if (epoch < 100_000_000_000L) epoch * 1000L else epoch
            Instant.ofEpochMilli(epochMillis)
        } else {
            runCatching { Instant.parse(rawTimestamp) }.getOrElse {
                runCatching { OffsetDateTime.parse(rawTimestamp).toInstant() }.getOrElse {
                    LocalDateTime.parse(rawTimestamp).atZone(ZoneId.systemDefault()).toInstant()
                }
            }
        }
    }.getOrNull() ?: return null

    val formatter = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault())
    return formatter.format(instant.atZone(ZoneId.systemDefault()))
}

private fun commentMaxLines(length: Int): Int = when {
    length <= 160 -> 7
    length <= 280 -> 10
    length <= 420 -> 13
    length <= 650 -> 17
    length <= 900 -> 22
    else -> 28
}

private fun selectedEpisodeLabel(video: Video): String {
    val season = video.season ?: 0
    val episode = video.episode ?: 0
    return "S${season.toString().padStart(2, '0')}E${episode.toString().padStart(2, '0')}"
}

