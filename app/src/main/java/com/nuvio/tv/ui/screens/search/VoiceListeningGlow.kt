package com.nuvio.tv.ui.screens.search

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * While voice search listens, light in the app's colour streams down from the top of the screen, as the TV launcher's
 * voice search does: soft at the top edge and gone about a third of the way down, a little stronger and further as
 * the voice gets louder. Drawn over the whole app by [VoiceListeningGlowHost].
 */
object VoiceListeningGlow {
    internal var listening by mutableStateOf(false)
        private set
    internal var level by mutableFloatStateOf(0f)
        private set

    fun update(listening: Boolean, level: Float) {
        this.listening = listening
        this.level = if (listening) level.coerceIn(0f, 1f) else 0f
    }
}

/**
 * Keeps [VoiceListeningGlow] showing [listening] for as long as the calling screen is shown. Both are read outside
 * composition, so the voice level changing doesn't recompose the screen.
 */
@Composable
fun VoiceListeningGlowSync(listening: () -> Boolean, level: () -> Float) {
    LaunchedEffect(Unit) {
        snapshotFlow { listening() to level() }.collect { (on, loudness) -> VoiceListeningGlow.update(on, loudness) }
    }
    DisposableEffect(Unit) {
        onDispose { VoiceListeningGlow.update(false, 0f) }
    }
}

@Composable
fun VoiceListeningGlowHost() {
    val listening = VoiceListeningGlow.listening
    // How far down the light reaches, as a share of the screen: it comes down from the top and goes back up.
    val reach = remember { Animatable(0f) }
    LaunchedEffect(listening) {
        reach.animateTo(
            targetValue = if (listening) 1f else 0f,
            animationSpec = tween(if (listening) 520 else 360, easing = GlowEasing)
        )
    }
    val level by animateFloatAsState(VoiceListeningGlow.level, tween(140), label = "voiceGlowLevel")
    if (reach.value <= 0f && !listening) return
    val accent = NuvioTheme.colors.Secondary
    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawBehind {
                val shown = reach.value
                val depth = size.height * GLOW_DEPTH * shown * (1f + level * 0.18f)
                // Light streaming down from the top edge, no line of its own: soft at the top and gone well before the
                // middle (the first, stronger version read as a bar).
                val strength = (0.34f + level * 0.14f) * shown
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to accent.copy(alpha = strength),
                        0.3f to accent.copy(alpha = strength * 0.5f),
                        0.65f to accent.copy(alpha = strength * 0.15f),
                        1f to Color.Transparent,
                        endY = depth
                    ),
                    size = Size(size.width, depth)
                )
            }
    )
}

/** How far down the light reaches, as a share of the screen's height. */
private const val GLOW_DEPTH = 0.3f
private val GlowEasing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
