package com.nuvio.tv.ui.newlook.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.ui.newlook.NewLookOption
import com.nuvio.tv.ui.newlook.NewLookOptionsMenu

/**
 * Holding a title in a Smart Highlight: take the title out of it, hide the highlight for a while, block it for good,
 * or (when Gemini wrote its name) block just that name. "More options" opens the usual poster options.
 */
@Composable
internal fun SmartHighlightOptionsDialog(
    itemTitle: String,
    rowTitle: String,
    aiNamed: Boolean,
    onDismiss: () -> Unit,
    onNotThisTitle: () -> Unit,
    onSnooze: () -> Unit,
    onBlockName: () -> Unit,
    onBlockHighlight: () -> Unit,
    onMoreOptions: () -> Unit,
    image: String? = null
) {
    NewLookOptionsMenu(
        title = itemTitle,
        subtitle = stringResource(R.string.smart_highlight_dialog_subtitle, rowTitle),
        image = image,
        options = listOfNotNull(
            NewLookOption(stringResource(R.string.smart_highlight_not_this_title), onClick = onNotThisTitle),
            NewLookOption(stringResource(R.string.smart_highlight_snooze, rowTitle), onClick = onSnooze),
            if (aiNamed) NewLookOption(stringResource(R.string.smart_highlight_block_name, rowTitle), onClick = onBlockName) else null,
            NewLookOption(stringResource(R.string.smart_highlight_block), onClick = onBlockHighlight),
            NewLookOption(stringResource(R.string.smart_highlight_more_options), onClick = onMoreOptions)
        ),
        onDismiss = onDismiss
    )
}
