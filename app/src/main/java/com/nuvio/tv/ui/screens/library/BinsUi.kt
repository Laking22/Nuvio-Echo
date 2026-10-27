package com.nuvio.tv.ui.screens.library

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.detail.detailGlassPanel
import com.nuvio.tv.ui.screens.detail.ignoreHeldSelect
import com.nuvio.tv.ui.screens.detail.ndClickable
import com.nuvio.tv.ui.screens.detail.selectionSurface
import com.nuvio.tv.ui.screens.detail.trapDpadFocus
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal val BinsPillShape = RoundedCornerShape(999.dp)
private val PillIdle = Color(0xFF1E1E1E)
private val PillOn = Color(0xFF2E2E2E)
internal val BinsMuted = Color(0xFF9A9A9A)

/** Two slightly curved arrows, one up and one down, as one icon: the Custom order button. */
internal val CustomOrderIcon: ImageVector by lazy {
    ImageVector.Builder("binsCustomOrder", 24.dp, 24.dp, 24f, 24f).apply {
        listOf("M8 20C6.4 15 6.4 9 8 4", "M4.9 7.1 8 4l3.1 3.1", "M16 4c1.6 5 1.6 11 0 16", "M12.9 16.9 16 20l3.1-3.1").forEach { d ->
            addPath(
                pathData = PathParser().parsePathString(d).toNodes(),
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            )
        }
    }.build()
}

private val StarPath by lazy {
    PathParser().parsePathString("M12 3.2l2.6 5.4 5.9.8-4.3 4.1 1 5.9L12 16.6l-5.2 2.8 1-5.9-4.3-4.1 5.9-.8z").toPath()
}

/**
 * A bin's star. Starred it's solid white, otherwise a faded outline. Focused, only the star itself lights up (full
 * white, a touch larger); nothing around it glows.
 */
@Composable
internal fun BinStar(starred: Boolean, focused: Boolean, binFocused: Boolean, modifier: Modifier = Modifier) {
    val fillAlpha by animateFloatAsState(
        when {
            !starred -> 0f
            focused -> 1f
            else -> 0.9f
        }, tween(200), label = "binStarFill"
    )
    val outlineAlpha by animateFloatAsState(
        when {
            starred -> 0f
            focused -> 1f
            binFocused -> 0.55f
            else -> 0.35f
        }, tween(200), label = "binStarOutline"
    )
    val grow by animateFloatAsState(if (focused) 1.18f else 1f, tween(200), label = "binStarGrow")
    Canvas(modifier) {
        val s = size.minDimension / 24f * grow
        translate((size.width - 24f * s) / 2f, (size.height - 24f * s) / 2f) {
            scale(s, s, pivot = Offset.Zero) {
                if (fillAlpha > 0f) drawPath(StarPath, Color.White.copy(alpha = fillAlpha))
                if (outlineAlpha > 0f) {
                    drawPath(StarPath, Color.White.copy(alpha = outlineAlpha), style = Stroke(width = 1.6f, join = StrokeJoin.Round))
                }
            }
        }
    }
}

/** A text pill on the Library's top row: white while focused, a lighter grey when it's the current tab. */
@Composable
internal fun BinsTextPill(
    label: String,
    selected: Boolean,
    requester: FocusRequester,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: (@Composable (Color) -> Unit)? = null
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.04f else 1f, tween(160), label = "binsPillScale")
    Row(
        modifier = modifier
            .androidxScale(scale)
            .height(36.dp)
            .selectionSurface(focused, BinsPillShape, if (selected) PillOn else PillIdle)
            .ndClickable(focusRequester = requester, onFocusChanged = { focused = it }, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val color = Color.White
        if (content != null) content(color) else Text(label, color = color, fontSize = 15.sp, maxLines = 1)
    }
}

/** The Sort pill: "Sort" and the current order, with a chevron. */
@Composable
internal fun BinsSortPill(value: String, requester: FocusRequester, onClick: () -> Unit, onBounds: (Rect) -> Unit, modifier: Modifier = Modifier) {
    BinsTextPill(label = value, selected = false, requester = requester, onClick = onClick, modifier = modifier.boundsReporter(onBounds)) { color ->
        Text(stringResource(R.string.bins_sort), color = BinsMuted, fontSize = 12.sp)
        Spacer(Modifier.width(7.dp))
        Text(value, color = color, fontSize = 15.sp, maxLines = 1)
        Spacer(Modifier.width(4.dp))
        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
    }
}

/**
 * A round icon pill that opens up to show its name while focused (or while [armed]). Its width animates, so the
 * pills beside it glide out of the way instead of jumping.
 */
@Composable
internal fun BinsIconPill(
    icon: ImageVector,
    label: String,
    requester: FocusRequester,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    armed: Boolean = false
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.04f else 1f, tween(160), label = "binsIconPillScale")
    val color = Color.White
    Row(
        modifier = modifier
            .androidxScale(scale)
            .height(36.dp)
            .selectionSurface(focused, BinsPillShape, if (armed) PillOn else PillIdle)
            .then(if (armed && !focused) Modifier.border(1.5.dp, Color.White, BinsPillShape) else Modifier)
            .ndClickable(focusRequester = requester, onFocusChanged = { focused = it }, onClick = onClick)
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = label, tint = color, modifier = Modifier.size(18.dp))
        AnimatedVisibility(
            visible = focused || armed,
            enter = expandHorizontally(tween(280, easing = FastOutSlowInEasing), expandFrom = Alignment.Start) + fadeIn(tween(200, delayMillis = 60)),
            exit = shrinkHorizontally(tween(240, easing = FastOutSlowInEasing), shrinkTowards = Alignment.Start) + fadeOut(tween(120))
        ) {
            Text(label, color = color, fontSize = 15.sp, maxLines = 1, modifier = Modifier.padding(start = 7.dp, end = 6.dp))
        }
    }
}

internal val PlusIcon: ImageVector get() = Icons.Filled.Add

private fun Modifier.androidxScale(scale: Float): Modifier = graphicsLayer { scaleX = scale; scaleY = scale }

/** Reports where this sits in the window, for anchoring a menu to it. */
internal fun Modifier.boundsReporter(onBounds: (Rect) -> Unit): Modifier = onGloballyPositioned { onBounds(it.boundsInRoot()) }

internal data class BinMenuItem(val label: String, val checked: Boolean = false, val onClick: () -> Unit)

/**
 * A pill menu under (or above) [anchor], in the screen's own coordinates. [openedByHold] swallows the held OK that
 * opened it, so the release doesn't pick the first item.
 */
@Composable
internal fun BinPillMenu(
    anchor: Rect,
    title: String?,
    items: List<BinMenuItem>,
    initialIndex: Int,
    openedByHold: Boolean,
    onDismiss: () -> Unit,
    width: Dp = 240.dp
) {
    val requesters = remember(items.size) { List(items.size) { FocusRequester() } }
    BackHandler { onDismiss() }
    LaunchedEffect(Unit) {
        val target = requesters.getOrNull(initialIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))) ?: return@LaunchedEffect
        repeat(6) {
            if (runCatching { target.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
            delay(40)
        }
    }
    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .layout { measurable, constraints ->
                    val p = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                    val margin = 16.dp.roundToPx()
                    val gap = 8.dp.roundToPx()
                    var y = anchor.bottom.toInt() + gap
                    if (y + p.height > constraints.maxHeight - margin) y = anchor.top.toInt() - gap - p.height
                    y = y.coerceIn(margin, (constraints.maxHeight - margin - p.height).coerceAtLeast(margin))
                    val x = anchor.left.toInt().coerceIn(margin, (constraints.maxWidth - margin - p.width).coerceAtLeast(margin))
                    layout(constraints.maxWidth, constraints.maxHeight) { p.place(x, y) }
                }
                .widthIn(min = width)
                .detailGlassPanel(RoundedCornerShape(20.dp), strength = 1.6f)
                .then(if (openedByHold) Modifier.ignoreHeldSelect() else Modifier)
                .trapDpadFocus()
                .focusGroup()
                .padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            if (title != null) {
                Text(
                    title, color = BinsMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 4.dp)
                )
            }
            items.forEachIndexed { i, item ->
                var focused by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier
                        .widthIn(min = width - 12.dp)
                        .height(38.dp)
                        .selectionSurface(focused, BinsPillShape)
                        .ndClickable(focusRequester = requesters[i], onFocusChanged = { focused = it }, onClick = item.onClick)
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        item.label, color = Color.White, fontSize = 15.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)
                    )
                    if (item.checked) {
                        Spacer(Modifier.width(18.dp))
                        Text("✓", color = if (focused) Color.White else BinsMuted, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

/**
 * "Name this bin": a text box, then Cancel and Create under it. [onConfirm] returns null when done, or why not
 * (shown under the box).
 */
@Composable
internal fun BinNameDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onConfirm: suspend (String) -> String?,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    val fieldFocus = remember { FocusRequester() }
    val confirmFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()

    fun confirm() {
        if (busy) return
        busy = true
        scope.launch {
            val problem = onConfirm(text)
            busy = false
            if (problem == null) {
                keyboard?.hide()
                onDismiss()
            } else {
                error = problem
                runCatching { fieldFocus.requestFocus() }
            }
        }
    }

    LaunchedEffect(Unit) {
        repeat(6) {
            if (runCatching { fieldFocus.requestFocus() }.getOrDefault(false)) {
                // The dialog's window needs focus before the keyboard will come up (asked at once, it didn't).
                delay(250)
                keyboard?.show()
                return@LaunchedEffect
            }
            delay(40)
        }
    }

    NuvioDialog(onDismiss = onDismiss, title = title, width = 460.dp) {
        androidx.compose.material3.OutlinedTextField(
            value = text,
            onValueChange = { text = it.take(com.nuvio.tv.core.bins.MAX_BIN_NAME_LENGTH); error = null },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(fieldFocus)
                .onFocusChanged { if (!it.isFocused) editing = false }
                .onPreviewKeyEvent { event ->
                    val native = event.nativeKeyEvent
                    // OK on the box always brings the keyboard back, even when it was dismissed with Back.
                    if (native.action == AndroidKeyEvent.ACTION_DOWN && isSelect(native.keyCode)) {
                        editing = true
                        keyboard?.show()
                    }
                    false
                },
            readOnly = !editing,
            singleLine = true,
            placeholder = { androidx.compose.material3.Text(stringResource(R.string.bins_name_hint)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                editing = false
                keyboard?.hide()
                runCatching { confirmFocus.requestFocus() }
            }),
            textStyle = androidx.compose.material3.LocalTextStyle.current.copy(textDirection = TextDirection.Content, fontSize = 18.sp),
            shape = RoundedCornerShape(14.dp),
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedTextColor = NuvioTheme.colors.TextPrimary,
                unfocusedTextColor = NuvioTheme.colors.TextPrimary,
                focusedContainerColor = Color(0xFF262626),
                unfocusedContainerColor = Color(0xFF262626),
                focusedBorderColor = Color.White,
                unfocusedBorderColor = Color(0xFF3A3A3A),
                focusedPlaceholderColor = Color(0xFF6F6F6F),
                unfocusedPlaceholderColor = Color(0xFF6F6F6F),
                cursorColor = Color.White
            )
        )
        if (error != null) {
            Text(error.orEmpty(), color = Color(0xFFE0A27A), fontSize = 13.sp)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
            DialogPill(stringResource(R.string.action_cancel), null, onClick = { keyboard?.hide(); onDismiss() })
            DialogPill(confirmLabel, confirmFocus, onClick = ::confirm, primary = true)
        }
    }
}

@Composable
private fun DialogPill(label: String, requester: FocusRequester?, onClick: () -> Unit, primary: Boolean = false) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .height(40.dp)
            .selectionSurface(focused, BinsPillShape, if (primary) Color(0xFF3A3A3A) else PillOn)
            .ndClickable(focusRequester = requester, onFocusChanged = { focused = it }, onClick = onClick)
            .padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Color.White, fontSize = 15.sp)
    }
}

/** A short message in a pill at the bottom of the screen. */
@Composable
internal fun BinsToast(message: String?, modifier: Modifier = Modifier) {
    androidx.compose.animation.AnimatedVisibility(
        visible = message != null,
        enter = fadeIn(tween(180)),
        exit = fadeOut(tween(220)),
        modifier = modifier
    ) {
        var last by remember { mutableStateOf("") }
        if (message != null) last = message
        Text(
            last,
            color = Color.White,
            fontSize = 14.sp,
            modifier = Modifier
                .clip(BinsPillShape)
                .background(Color(0xFF1D1D1D))
                .border(1.dp, Color(0xFF333333), BinsPillShape)
                .padding(horizontal = 18.dp, vertical = 10.dp)
        )
    }
}

private fun isSelect(keyCode: Int): Boolean =
    keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER || keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER

/**
 * The details page's hold-down list: every bin as a pill (a check on the ones holding the title), then "New bin",
 * which asks for a name and puts the title straight in. [anchor] is in this overlay's coordinates; null centres it.
 */
@Composable
internal fun BinPicker(
    anchor: Rect?,
    title: String,
    bins: List<com.nuvio.tv.core.bins.Bin>,
    holding: Set<String>,
    openedByHold: Boolean,
    onPick: (String) -> Unit,
    onCreate: suspend (String) -> String?,
    onDismiss: () -> Unit
) {
    var naming by remember { mutableStateOf(false) }
    if (naming) {
        BinNameDialog(
            title = stringResource(R.string.bins_name_title),
            initial = "",
            confirmLabel = stringResource(R.string.bins_create_button),
            onConfirm = onCreate,
            onDismiss = { naming = false; onDismiss() }
        )
        return
    }
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val menuWidth = 240.dp
        val place = anchor ?: with(androidx.compose.ui.platform.LocalDensity.current) {
            val left = (maxWidth / 2 - menuWidth / 2).toPx()
            val top = (maxHeight * 0.28f).toPx()
            Rect(left, top - 8.dp.toPx(), left + menuWidth.toPx(), top - 8.dp.toPx())
        }
        val items = bins.map { bin ->
            BinMenuItem(
                label = if (bin.starred) "★  ${bin.name}" else bin.name,
                checked = bin.id in holding,
                onClick = { onPick(bin.id) }
            )
        } + BinMenuItem("+  " + stringResource(R.string.nd_bin_new)) { naming = true }
        BinPillMenu(
            anchor = place,
            title = title,
            items = items,
            initialIndex = bins.indexOfFirst { it.starred }.coerceAtLeast(0),
            openedByHold = openedByHold,
            onDismiss = onDismiss,
            width = menuWidth
        )
    }
}
