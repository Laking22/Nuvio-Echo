@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nuvio.tv.ui.theme.NuvioTheme

/*
 * The player's pop-out card: subtitles, and audio and quality, open in one liquid-glass card in the bottom right,
 * over the video. Its head holds the card's choices (language pills, or Audio | Quality tabs) with a white gear on the
 * right for the extras; its body lists what can be picked, one row each.
 */

private val PopCardWidth = 360.dp
private val PopCardRadius = 28.dp
private val PopCardMargin = 32.dp
private val PopCardPadding = 14.dp
private val PopCardShape = RoundedCornerShape(PopCardRadius)
private val PopRowShape = RoundedCornerShape(14.dp)

/** The tallest a card's list gets before it scrolls, so the card stays clear of the top of the screen. */
internal val PopListMaxHeight = 330.dp

/**
 * The glass's dark tint, deep enough that the card reads over any frame. The video under it isn't blurred: that needs
 * a copy of the frame read back from the video surface, which froze this TV's display (Amlogic) before.
 */
private const val PopGlassDarkness = 0.82f
private const val PopWhiteHaze = 0.06f

@OptIn(ExperimentalAnimationApi::class)
@Composable
internal fun PlayerPopCard(
    visible: Boolean,
    modifier: Modifier = Modifier,
    width: Dp = PopCardWidth,
    head: @Composable RowScope.() -> Unit,
    body: @Composable ColumnScope.() -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(200)),
        exit = fadeOut(tween(160)),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithCache {
                    // Darkens towards the card's corner only, so the picture stays in view.
                    val shade = Brush.linearGradient(
                        0.4f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.5f),
                        start = Offset.Zero,
                        end = Offset(size.width, size.height)
                    )
                    onDrawBehind { drawRect(shade) }
                }
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(PopCardMargin)
                    .animateEnterExit(
                        enter = scaleIn(
                            animationSpec = tween(260),
                            initialScale = 0.86f,
                            transformOrigin = TransformOrigin(1f, 1f)
                        ),
                        exit = scaleOut(
                            animationSpec = tween(160),
                            targetScale = 0.94f,
                            transformOrigin = TransformOrigin(1f, 1f)
                        )
                    )
                    .width(width)
                    .popGlass()
                    .padding(PopCardPadding)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    content = head
                )
                body()
            }
        }
    }
}

/** The card's glass: a dark body under a faint white haze, a bright rim, and a highlight along the top edge. */
private fun Modifier.popGlass(): Modifier = this
    .shadow(
        elevation = 18.dp,
        shape = PopCardShape,
        clip = false,
        ambientColor = Color.Black.copy(alpha = 0.45f),
        spotColor = Color.Black.copy(alpha = 0.45f)
    )
    .drawWithCache {
        val outline = PopCardShape.createOutline(size, layoutDirection, this)
        val haze = Brush.verticalGradient(
            0f to Color.White.copy(alpha = PopWhiteHaze),
            1f to Color.White.copy(alpha = PopWhiteHaze * 0.5f)
        )
        val sheen = Brush.verticalGradient(
            0f to Color.White.copy(alpha = 0.14f),
            0.18f to Color.Transparent,
            0.82f to Color.Transparent,
            1f to Color.White.copy(alpha = 0.06f)
        )
        val rim = Color.White.copy(alpha = 0.16f)
        val edge = Brush.horizontalGradient(
            0.18f to Color.Transparent,
            0.5f to Color.White.copy(alpha = 0.75f),
            0.82f to Color.Transparent
        )
        val edgeY = 1.dp.toPx()
        onDrawBehind {
            drawOutline(outline, Color(12, 12, 16).copy(alpha = PopGlassDarkness))
            drawOutline(outline, haze)
            drawOutline(outline, sheen)
            drawOutline(outline, rim, style = Stroke(1.dp.toPx()))
            drawLine(edge, Offset(0f, edgeY), Offset(size.width, edgeY), strokeWidth = 1.dp.toPx())
        }
    }

/** The white gear in the card's head: opens the extras. White, and turned, while they're open. */
@Composable
internal fun PopGearButton(
    open: Boolean,
    onClick: () -> Unit,
    contentDescription: String?,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }
    val turn by animateFloatAsState(if (open) 60f else 0f, tween(300), label = "gear")
    Box(
        modifier = modifier
            .size(34.dp)
            .onFocusChanged { focused = it.isFocused }
            .clip(CircleShape)
            .background(
                when {
                    open -> Color.White
                    focused -> Color.White.copy(alpha = 0.26f)
                    else -> Color.White.copy(alpha = 0.08f)
                }
            )
            .then(
                if (focused) Modifier.drawWithCache {
                    onDrawBehind {
                        drawCircle(Color.White, radius = size.minDimension / 2f - 1.dp.toPx(), style = Stroke(2.dp.toPx()))
                    }
                } else Modifier
            )
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Settings,
            contentDescription = contentDescription,
            tint = if (open) Color(0xFF111111) else Color.White,
            modifier = Modifier.size(20.dp).rotate(turn)
        )
    }
}

/** A pill in the card's head: a language, or a section tab. White while picked; lit and ringed while focused. */
@Composable
internal fun PopPill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    count: Int? = null,
    focusRequester: FocusRequester? = null,
    onFocused: () -> Unit = {}
) {
    var focused by remember { mutableStateOf(false) }
    val dark = Color(0xFF111111)
    Row(
        modifier = modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .clip(CircleShape)
            .background(
                when {
                    focused -> Color.White
                    selected -> Color.White.copy(alpha = 0.22f)
                    else -> Color.White.copy(alpha = 0.07f)
                }
            )
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = when {
                focused -> dark
                selected -> Color.White
                else -> Color.White.copy(alpha = 0.7f)
            },
            maxLines = 1
        )
        if (count != null && count > 0) {
            Text(
                text = count.toString(),
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                color = (if (focused) dark else Color.White).copy(alpha = 0.6f)
            )
        }
    }
}

/** Small grey words above a card's list. */
@Composable
internal fun PopHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        color = Color.White.copy(alpha = 0.55f),
        modifier = modifier.padding(start = 4.dp, end = 4.dp, bottom = 8.dp)
    )
}

/**
 * One row of a card's list: white with dark words while [selected] (the ticked track or quality), lit glass with a
 * white ring while focused.
 */
@Composable
internal fun PopRow(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    content: @Composable RowScope.(primary: Color, secondary: Color) -> Unit
) {
    Card(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.colors(
            containerColor = if (selected) NuvioTheme.colors.Secondary else Color.Transparent,
            focusedContainerColor = if (selected) NuvioTheme.colors.Secondary else Color.White.copy(alpha = 0.12f)
        ),
        shape = CardDefaults.shape(PopRowShape),
        border = CardDefaults.border(
            border = Border(border = BorderStroke(NuvioTheme.spacing.xxs, Color.Transparent), shape = PopRowShape),
            focusedBorder = Border(border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), shape = PopRowShape)
        ),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        val primary = if (selected) NuvioTheme.colors.OnSecondary else Color.White
        val secondary = if (selected) NuvioTheme.colors.OnSecondary.copy(alpha = 0.72f) else Color.White.copy(alpha = 0.55f)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            content(primary, secondary)
        }
    }
}

/** A row's main words, with smaller ones after them ("1080p Full HD") and an optional line below. */
@Composable
internal fun RowScope.PopRowText(
    title: String,
    primary: Color,
    secondary: Color,
    inline: String? = null,
    below: String? = null
) {
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (!inline.isNullOrBlank()) {
                Text(text = inline, fontSize = 11.sp, color = secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (!below.isNullOrBlank()) {
            Text(text = below, fontSize = 11.sp, color = secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** What sits at a row's end: a tick while selected, else a small note ("Playing", "9 sources"). */
@Composable
internal fun PopRowEnd(selected: Boolean, note: String?, secondary: Color) {
    if (selected) {
        Icon(
            imageVector = Icons.Default.Check,
            contentDescription = null,
            tint = NuvioTheme.colors.OnSecondary,
            modifier = Modifier.padding(start = 8.dp).size(20.dp)
        )
    } else if (!note.isNullOrBlank()) {
        Text(
            text = note,
            fontSize = 11.sp,
            color = secondary,
            maxLines = 1,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

/** A row of the extras: its name (and a line of help) on the left, its control on the right. */
@Composable
internal fun PopSettingRow(
    label: String,
    modifier: Modifier = Modifier,
    helper: String? = null,
    control: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, fontSize = 13.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!helper.isNullOrBlank()) {
                Text(text = helper, fontSize = 10.sp, lineHeight = 12.sp, color = Color.White.copy(alpha = 0.5f), maxLines = 2)
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            content = control
        )
    }
}

/** The value between a stepper's − and +. */
@Composable
internal fun PopStepperValue(text: String, width: Dp = 68.dp) {
    Box(
        modifier = Modifier
            .width(width)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .padding(vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, fontSize = 12.sp, color = Color.White, maxLines = 1)
    }
}
