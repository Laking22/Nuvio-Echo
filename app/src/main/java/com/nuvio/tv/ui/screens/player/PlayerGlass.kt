@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.player

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.ui.newlook.focusRingColor
import com.nuvio.tv.ui.newlook.glassSurface
import com.nuvio.tv.ui.theme.LocalNuvioColors
import com.nuvio.tv.ui.theme.LocalNuvioExtendedColors
import com.nuvio.tv.ui.theme.LocalNuvioFocusRingStyle
import com.nuvio.tv.ui.theme.LocalThemePalette
import com.nuvio.tv.ui.theme.NuvioColorScheme
import com.nuvio.tv.ui.theme.ThemeColors
import com.nuvio.tv.ui.theme.createFocusRingStyle

/**
 * The player's palette, whatever the Appearance colour: white for what's picked or lit (the progress line, the ticked
 * track, the focused choice) with dark text on it, and see-through glass for its panels, cards and dialogs, as on Home
 * and in Settings. The panels sit over the video, so their glass is dark enough to read on any frame.
 */
private val PlayerGlassPalette = ThemeColors.White.copy(
    secondary = Color(0xFFF2F2F2),
    secondaryVariant = Color.White,
    onSecondary = Color(0xFF151515),
    onSecondaryVariant = Color(0xFF151515),
    accentGradient = listOf(Color.White),
    focusRing = Color.White,
    focusRingGradient = listOf(Color.White),
    focusBackground = Color.White.copy(alpha = 0.16f),
    backgroundElevated = Color(0xC7101012),
    backgroundCard = Color.White.copy(alpha = 0.07f),
    surface = Color.White.copy(alpha = 0.08f),
    surfaceVariant = Color.White.copy(alpha = 0.10f),
    panel = Color(0xC7101012),
    field = Color.White.copy(alpha = 0.08f),
    menu = Color(0xD9121214),
    modal = Color(0xE6101012)
)

/** Wraps the player in [PlayerGlassPalette]. */
@Composable
internal fun PlayerGlassTheme(content: @Composable () -> Unit) {
    val colors = remember { NuvioColorScheme(PlayerGlassPalette) }
    val extended = LocalNuvioExtendedColors.current.copy(
        backgroundElevated = colors.BackgroundElevated,
        backgroundCard = colors.BackgroundCard,
        focusRing = colors.FocusRing,
        focusBackground = colors.FocusBackground
    )
    CompositionLocalProvider(
        LocalThemePalette provides PlayerGlassPalette,
        LocalNuvioColors provides colors,
        LocalNuvioExtendedColors provides extended,
        LocalNuvioFocusRingStyle provides remember { createFocusRingStyle(PlayerGlassPalette) }
    ) {
        MaterialTheme(
            colorScheme = MaterialTheme.colorScheme.copy(
                secondary = colors.Secondary,
                onSecondary = colors.OnSecondary
            ),
            typography = MaterialTheme.typography,
            content = content
        )
    }
}

/** The player's floating buttons (skip intro or outro, next episode) are pills. */
internal val PlayerPillShape = RoundedCornerShape(percent = 50)

/**
 * Liquid glass for the player's floating buttons, the same glass as Home's menu: clear over the video, with a bright
 * rim. Focused, the glass lights up and takes a ring in the Appearance colour ([ring]).
 */
internal fun Modifier.playerGlass(shape: Shape, focused: Boolean, ring: Color): Modifier = this
    .glassSurface(shape)
    .drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val lit = Brush.verticalGradient(
            0f to Color.White.copy(alpha = 0.26f),
            0.5f to Color.White.copy(alpha = 0.16f),
            1f to Color.White.copy(alpha = 0.20f)
        )
        onDrawBehind {
            if (!focused) return@onDrawBehind
            drawOutline(outline, lit)
            drawOutline(outline, ring, style = Stroke(1.5.dp.toPx()))
        }
    }

/** A liquid-glass pill button for the player's prompts; [primary] lights up white while focused. */
@Composable
internal fun PlayerGlassPillButton(
    text: String,
    onClick: () -> Unit,
    primary: Boolean,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }
    val ring = focusRingColor
    Card(
        onClick = onClick,
        shape = CardDefaults.shape(shape = PlayerPillShape),
        colors = CardDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = if (primary) Color.White else Color.Transparent
        ),
        border = CardDefaults.border(border = Border.None, focusedBorder = Border.None),
        scale = CardDefaults.scale(focusedScale = 1.04f),
        modifier = modifier
            .playerGlass(PlayerPillShape, focused && !primary, ring)
            .onFocusChanged { focused = it.isFocused }
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (focused && primary) Color.Black else Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 26.dp, vertical = 13.dp)
        )
    }
}
