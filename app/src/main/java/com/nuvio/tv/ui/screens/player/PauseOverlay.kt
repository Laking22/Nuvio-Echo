package com.nuvio.tv.ui.screens.player

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nuvio.tv.R
import com.nuvio.tv.ui.util.contentTextDirection
import com.nuvio.tv.ui.util.localizeEpisodeTitle
import kotlinx.coroutines.delay
import java.util.Date

/**
 * Shown once playback has been paused for a while: what's playing, in the hero's quiet style. A small "Paused", the
 * logo, one line of details, the synopsis and how much is left, over a shade in the lower-left corner so the paused
 * picture stays visible. Any key dismisses it (the player handles that).
 */
@Composable
fun PauseOverlay(
    visible: Boolean,
    onClose: () -> Unit,
    title: String,
    logo: String?,
    episodeTitle: String?,
    season: Int?,
    episode: Int?,
    year: String?,
    type: String?,
    description: String?,
    modifier: Modifier = Modifier,
    positionMs: Long = 0L,
    durationMs: Long = 0L,
    showClock: Boolean = true
) {
    PlayerOverlayScaffold(
        visible = visible,
        onDismiss = onClose,
        modifier = modifier,
        captureKeys = false,
        dismissOnBackgroundClick = true,
        overlayTint = Color.Black.copy(alpha = 0.16f),
        drawShade = false,
        topEndContent = {
            if (showClock) PauseOverlayClock(modifier = Modifier.padding(top = 36.dp, end = 52.dp))
        }
    ) {
        // Darkest in the lower-left, behind the text, and clear towards the top right.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to Color.Black.copy(alpha = 0.72f),
                        0.5f to Color.Black.copy(alpha = 0.32f),
                        1f to Color.Transparent
                    )
                )
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.45f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.6f)
                    )
                )
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 64.dp, bottom = 64.dp)
                .fillMaxWidth(0.46f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.Pause,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = stringResource(R.string.pause_label).uppercase(),
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 2.sp,
                    modifier = Modifier.padding(start = 6.dp)
                )
            }
            PauseTitle(title = title, logo = logo)
            val details = pauseDetails(episodeTitle, season, episode, year, type)
            if (details.isNotBlank()) {
                Text(
                    text = details,
                    color = Color.White.copy(alpha = 0.82f),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (!description.isNullOrBlank()) {
                Text(
                    text = description,
                    style = androidx.compose.ui.text.TextStyle(textDirection = description.contentTextDirection()),
                    color = Color.White.copy(alpha = 0.74f),
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (durationMs > 0L) {
                PauseProgress(positionMs = positionMs, durationMs = durationMs)
            }
        }
    }
}

@Composable
private fun PauseTitle(title: String, logo: String?) {
    var logoFailed by remember(logo) { mutableStateOf(false) }
    if (!logo.isNullOrBlank() && !logoFailed) {
        val context = LocalContext.current
        AsyncImage(
            model = remember(logo) { ImageRequest.Builder(context).data(logo).memoryCacheKey(logo).crossfade(true).build() },
            contentDescription = title,
            contentScale = ContentScale.Fit,
            alignment = Alignment.BottomStart,
            onError = { logoFailed = true },
            modifier = Modifier.height(72.dp).widthIn(max = 340.dp)
        )
    } else {
        Text(
            text = title,
            color = Color.White,
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.heightIn(min = 40.dp)
        )
    }
}

/** "2024 · S1 E2 · The Episode": the year, then the episode and its name for a series. */
@Composable
private fun pauseDetails(episodeTitle: String?, season: Int?, episode: Int?, year: String?, type: String?): String {
    val context = LocalContext.current
    val parts = buildList {
        year?.takeIf { it.isNotBlank() }?.let(::add)
        if (type in listOf("series", "tv") && season != null && episode != null) {
            add(stringResource(R.string.season_episode_format, season, episode))
        }
        episodeTitle?.takeIf { it.isNotBlank() }?.let { add(it.localizeEpisodeTitle(context)) }
    }
    return parts.joinToString("  ·  ")
}

/** A thin line of how far in, and the time left beside it. */
@Composable
private fun PauseProgress(positionMs: Long, durationMs: Long) {
    val fraction = (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    val leftMinutes = ((durationMs - positionMs).coerceAtLeast(0L) / 60_000L).toInt()
    val left = if (leftMinutes >= 60) {
        stringResource(R.string.pause_left_hours, leftMinutes / 60, leftMinutes % 60)
    } else {
        stringResource(R.string.pause_left_minutes, leftMinutes.coerceAtLeast(1))
    }
    Row(
        modifier = Modifier.padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(180.dp)
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color.White.copy(alpha = 0.22f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .background(Color.White.copy(alpha = 0.9f))
            )
        }
        Text(
            text = left,
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 13.sp,
            modifier = Modifier.padding(start = 12.dp)
        )
    }
}

@Composable
private fun PauseOverlayClock(modifier: Modifier = Modifier) {
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    val context = LocalContext.current
    val formatter = remember(context) { DateFormat.getTimeFormat(context) }

    LaunchedEffect(Unit) {
        while (true) {
            val current = System.currentTimeMillis()
            nowMillis = current
            delay((60_000L - (current % 60_000L)).coerceAtLeast(1_000L))
        }
    }

    Text(
        text = formatter.format(Date(nowMillis)),
        color = Color.White.copy(alpha = 0.85f),
        fontSize = 28.sp,
        fontWeight = FontWeight.Light,
        modifier = modifier
    )
}
