package com.nuvio.tv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.ui.newlook.NewLookOption
import com.nuvio.tv.ui.newlook.NewLookOptionsMenu
import com.nuvio.tv.ui.screens.home.ContinueWatchingItem

/** URLs that failed to load — skip them immediately on next recomposition. */
internal val brokenImageUrls = java.util.Collections.synchronizedSet(mutableSetOf<String>())

/** Holding a Continue Watching card: the new look's glass menu of what to do with it. */
@Composable
fun ContinueWatchingOptionsDialog(
    item: ContinueWatchingItem,
    onDismiss: () -> Unit,
    onRemove: () -> Unit,
    onDetails: () -> Unit,
    onStartFromBeginning: () -> Unit = {},
    showPlayManually: Boolean = false,
    onPlayManually: () -> Unit = {}
) {
    val isPlayEnabled = LocalPlaybackAvailability.current.canStream(item)
    val title = when (item) {
        is ContinueWatchingItem.InProgress -> item.progress.name
        is ContinueWatchingItem.NextUp -> item.info.name
    }
    val image = when (item) {
        is ContinueWatchingItem.InProgress -> item.progress.backdrop ?: item.progress.poster
        is ContinueWatchingItem.NextUp -> item.info.thumbnail ?: item.info.backdrop ?: item.info.poster
    }
    NewLookOptionsMenu(
        title = title,
        subtitle = stringResource(R.string.cw_dialog_subtitle),
        image = image,
        options = listOfNotNull(
            NewLookOption(stringResource(R.string.cw_action_go_to_details), onClick = onDetails),
            if (showPlayManually && isPlayEnabled) NewLookOption(stringResource(R.string.play_manually), onClick = onPlayManually) else null,
            if (item is ContinueWatchingItem.InProgress && isPlayEnabled) {
                NewLookOption(stringResource(R.string.cw_action_start_from_beginning), onClick = onStartFromBeginning)
            } else {
                null
            },
            NewLookOption(stringResource(R.string.cw_action_remove), onClick = onRemove)
        ),
        onDismiss = onDismiss
    )
}
