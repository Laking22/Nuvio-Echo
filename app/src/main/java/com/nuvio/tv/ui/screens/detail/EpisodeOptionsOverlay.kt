package com.nuvio.tv.ui.screens.detail

import android.content.Context
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.transformations
import com.nuvio.tv.domain.model.EpisodeOptionsOverlayStyle
import com.nuvio.tv.ui.util.BlurTransformation

private fun isSelectKey(keyCode: Int): Boolean {
    return keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
}

internal fun shouldShowEpisodeOverlayBackdrop(style: EpisodeOptionsOverlayStyle): Boolean {
    return style != EpisodeOptionsOverlayStyle.NONE
}

internal fun shouldBlurEpisodeOverlayBackdrop(
    style: EpisodeOptionsOverlayStyle,
    blurUnwatchedEpisodes: Boolean,
    isWatched: Boolean
): Boolean {
    return style == EpisodeOptionsOverlayStyle.BLUR ||
        (style == EpisodeOptionsOverlayStyle.ARTWORK && blurUnwatchedEpisodes && !isWatched)
}

private const val OVERLAY_BLUR_MAX_WIDTH_PX = 480

internal fun episodeOverlayBackdropDecodeSize(
    screenWidthPx: Int,
    screenHeightPx: Int,
    blur: Boolean
): Pair<Int, Int> {
    val width = screenWidthPx.coerceAtLeast(1)
    val height = screenHeightPx.coerceAtLeast(1)
    if (!blur) return width to height
    val blurredWidth = (width / 4).coerceIn(1, OVERLAY_BLUR_MAX_WIDTH_PX)
    val blurredHeight = ((height.toLong() * blurredWidth) / width).toInt().coerceAtLeast(1)
    return blurredWidth to blurredHeight
}

internal fun episodeOverlayBackdropMemoryCacheKey(
    url: String,
    widthPx: Int,
    heightPx: Int,
    blur: Boolean
): String {
    return "${url}_${widthPx}x${heightPx}_blur$blur"
}

internal fun episodeOverlayBackdropRequest(
    context: Context,
    url: String,
    screenWidthPx: Int,
    screenHeightPx: Int,
    blur: Boolean = false
): ImageRequest {
    val (widthPx, heightPx) = episodeOverlayBackdropDecodeSize(screenWidthPx, screenHeightPx, blur)
    val cacheKey = episodeOverlayBackdropMemoryCacheKey(url, widthPx, heightPx, blur)
    return ImageRequest.Builder(context)
        .data(url)
        .memoryCacheKey(cacheKey)
        .diskCacheKey(url)
        .crossfade(true)
        .size(width = widthPx, height = heightPx)
        .apply {
            if (blur) transformations(BlurTransformation())
        }
        .build()
}

