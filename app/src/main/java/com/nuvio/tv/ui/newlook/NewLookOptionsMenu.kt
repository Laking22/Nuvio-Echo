package com.nuvio.tv.ui.newlook

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.nuvio.tv.ui.screens.detail.detailGlassPanel
import com.nuvio.tv.ui.screens.detail.ignoreHeldSelect
import com.nuvio.tv.ui.screens.detail.ndClickable
import com.nuvio.tv.ui.screens.detail.selectionSurface
import kotlinx.coroutines.delay

/** One pill in a [NewLookOptionsMenu]. A [checked] one shows a tick; a disabled one is dimmed and can't be picked. */
internal class NewLookOption(
    val label: String,
    val checked: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit
)

/**
 * The new look's menu for a title held on Home (poster options, Continue Watching, Smart Highlights): a liquid-glass
 * sheet with the title's backdrop and name, then its options as pills, the focused one under the app's glass lens,
 * as in the Library's bin menus. In a dialog window, so it takes focus and Back closes it; the held OK that opened
 * it doesn't pick the first option.
 */
@Composable
internal fun NewLookOptionsMenu(
    title: String,
    subtitle: String?,
    options: List<NewLookOption>,
    onDismiss: () -> Unit,
    image: String? = null,
    initialIndex: Int = 0,
    /** Changed when the menu swaps its options for others (a list of bins), so focus starts over on them. */
    contentKey: Any? = null
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier
                    .width(MenuWidth)
                    .detailGlassPanel(MenuShape, strength = 1.7f)
                    .ignoreHeldSelect()
                    .focusGroup()
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (image != null) {
                    val context = LocalContext.current
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(18.dp))
                    ) {
                        AsyncImage(
                            model = remember(image) { ImageRequest.Builder(context).data(image).build() },
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        // A little shade along the bottom, so the image sits into the glass.
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.35f)))
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                }
                Text(
                    title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 6.dp)
                )
                if (subtitle != null) {
                    Text(
                        subtitle, color = MutedText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 2.dp)
                    )
                }
                Spacer(Modifier.height(6.dp))
                val requesters = remember(contentKey, options.size) { List(options.size) { FocusRequester() } }
                LaunchedEffect(contentKey, options.size) {
                    val target = requesters.getOrNull(initialIndex.coerceIn(0, (options.size - 1).coerceAtLeast(0)))
                        ?: return@LaunchedEffect
                    // The dialog's window needs a moment before anything in it can take focus.
                    repeat(8) {
                        if (runCatching { target.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
                        delay(40)
                    }
                }
                options.forEachIndexed { i, option ->
                    OptionPill(option, requesters[i])
                }
            }
        }
    }
}

@Composable
private fun OptionPill(option: NewLookOption, requester: FocusRequester) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp)
            .selectionSurface(focused, PillShape)
            .ndClickable(focusRequester = requester, onFocusChanged = { focused = it }, onClick = {
                if (option.enabled) option.onClick()
            })
            .alpha(if (option.enabled) 1f else 0.4f)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            option.label, color = Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (option.checked) {
            Text("✓", color = if (focused) Color.White else MutedText, fontSize = 14.sp)
        }
    }
}

private val MenuWidth = 340.dp
private val MenuShape = RoundedCornerShape(26.dp)
private val PillShape = RoundedCornerShape(999.dp)
private val MutedText = Color(0xFF9A9A9A)
