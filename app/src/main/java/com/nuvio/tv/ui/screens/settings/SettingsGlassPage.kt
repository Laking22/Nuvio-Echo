package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.nuvio.tv.ui.newlook.home.AmbientPageWash
import com.nuvio.tv.ui.newlook.home.rememberLastAmbientUrl
import com.nuvio.tv.ui.screens.home.HeroBackdropState
import com.nuvio.tv.ui.theme.LocalNuvioColors
import com.nuvio.tv.ui.theme.LocalNuvioExtendedColors
import com.nuvio.tv.ui.theme.LocalNuvioFocusRingStyle
import com.nuvio.tv.ui.theme.NuvioColorScheme
import com.nuvio.tv.ui.theme.NuvioExtendedColors
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.theme.ThemeColorPalette
import com.nuvio.tv.ui.theme.createFocusRingStyle

/**
 * A full page reached from Settings (Addons, Plugins, Home catalogs, Collections) in Settings' own look: the colours of
 * the last title Home showed, blurred under a dim veil, and the page on one glass panel. Inside, the theme's solid
 * surfaces become faint white layers and its accent becomes white, so every card, button and switch on these pages
 * matches the glass without each being redrawn. Dialogs keep an opaque body so they read over the page.
 */
@Composable
internal fun SettingsGlassPage(content: @Composable () -> Unit) {
    val colors = remember { NuvioColorScheme(GlassPalette, primary = Color.White) }
    val focusRing = remember { createFocusRingStyle(GlassPalette) }
    val extended = remember(colors) {
        NuvioExtendedColors(
            backgroundElevated = colors.BackgroundElevated,
            backgroundCard = colors.BackgroundCard,
            textSecondary = colors.TextSecondary,
            textTertiary = colors.TextTertiary,
            focusRing = colors.FocusRing,
            focusBackground = colors.FocusBackground,
            rating = colors.Rating
        )
    }
    Box(modifier = Modifier.fillMaxSize().background(NuvioTheme.colors.Background)) {
        AmbientPageWash(
            url = rememberLastAmbientUrl() ?: HeroBackdropState.lastDisplayedUrl,
            modifier = Modifier.fillMaxSize()
        )
        Box(modifier = Modifier.fillMaxSize().background(PageDim))
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = NuvioTheme.spacing.xxl, vertical = NuvioTheme.spacing.xl)
                .settingsGlass(RoundedCornerShape(SettingsContainerRadius), SettingsGlassLevel.PANEL)
        ) {
            CompositionLocalProvider(
                LocalNuvioColors provides colors,
                LocalNuvioExtendedColors provides extended,
                LocalNuvioFocusRingStyle provides focusRing
            ) {
                content()
            }
        }
    }
}

/** As Settings dims its wash. */
private val PageDim = Color.Black.copy(alpha = 0.55f)

private val GlassPalette = ThemeColorPalette(
    secondary = Color(0xFFE6E6E6),
    secondaryVariant = Color(0xFFBDBDBD),
    onSecondary = Color(0xFF111111),
    onSecondaryVariant = Color(0xFF111111),
    focusRing = Color(0xD9FFFFFF),
    focusBackground = Color.White.copy(alpha = 0.16f),
    // The page shows through: the glass panel is the background.
    background = Color.Transparent,
    // Opaque, for dialogs and text fields, which sit over anything.
    backgroundElevated = Color(0xFF202023),
    backgroundCard = Color.White.copy(alpha = 0.06f),
    surface = Color.White.copy(alpha = 0.06f),
    surfaceVariant = Color.White.copy(alpha = 0.10f),
    panel = Color.White.copy(alpha = 0.05f),
    field = Color.White.copy(alpha = 0.08f),
    menu = Color(0xFF202023),
    modal = Color(0xFF202023)
)
