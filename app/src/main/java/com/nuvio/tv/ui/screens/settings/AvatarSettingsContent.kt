@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Border
import coil3.compose.AsyncImage
import com.nuvio.tv.data.remote.supabase.AvatarCatalogItem
import kotlinx.coroutines.launch
import com.nuvio.tv.R
import com.nuvio.tv.data.local.AddCustomAvatarResult
import com.nuvio.tv.ui.components.AvatarPickerGrid
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add

@Composable
internal fun AvatarSettingsContent(
    onBack: () -> Unit,
    viewModel: AvatarSettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var pendingRemoval by remember { mutableStateOf<AvatarCatalogItem?>(null) }
    val addFocus = remember { FocusRequester() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    fun focusAdd() {
        scope.launch {
            repeat(2) { withFrameNanos { } }
            runCatching { addFocus.requestFocus() }
        }
    }

    BackHandler { onBack() }
    LaunchedEffect(Unit) { focusAdd() }

    // Scrolls as one page, so the catalog gets its full height below Your avatars instead of a sliver.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.profile_avatars),
            subtitle = stringResource(R.string.profile_avatars_subtitle)
        )
        SettingsGroupCard(
            modifier = Modifier.fillMaxWidth(),
            title = stringResource(R.string.profile_avatars_yours),
            subtitle = stringResource(R.string.profile_avatars_yours_hint)
        ) {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = NuvioTheme.spacing.xs, vertical = NuvioTheme.spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item(key = "add") {
                    AvatarTile(
                        label = stringResource(R.string.profile_avatars_add),
                        imageUrl = null,
                        onClick = { showAddDialog = true },
                        modifier = Modifier.focusRequester(addFocus)
                    )
                }
                items(state.custom, key = AvatarCatalogItem::id) { avatar ->
                    AvatarTile(
                        label = avatar.displayName,
                        imageUrl = avatar.imageUrl,
                        onClick = { pendingRemoval = avatar }
                    )
                }
            }
        }
        SettingsGroupCard(
            modifier = Modifier.fillMaxWidth(),
            title = stringResource(R.string.profile_avatars_available)
        ) {
            when {
                state.loading && state.catalog.isEmpty() -> Text(
                    text = stringResource(R.string.supporters_loading),
                    color = NuvioTheme.colors.TextSecondary,
                    style = MaterialTheme.typography.bodyMedium
                )
                state.catalog.isEmpty() -> Text(
                    text = stringResource(R.string.profile_avatars_empty),
                    color = NuvioTheme.colors.TextSecondary,
                    style = MaterialTheme.typography.bodyMedium
                )
                // Browse only: a profile picks its avatar when it's edited, from this catalog plus the ones above.
                else -> AvatarPickerGrid(
                    avatars = state.catalog,
                    selectedAvatarId = null,
                    onAvatarSelected = { },
                    modifier = Modifier.fillMaxWidth().heightIn(max = 430.dp)
                )
            }
        }
    }

    if (showAddDialog) {
        AddAvatarUrlDialog(
            onDismiss = { showAddDialog = false },
            onSave = { url, onResult -> viewModel.addAvatar(url, onResult) },
            onAdded = { showAddDialog = false }
        )
    }

    pendingRemoval?.let { avatar ->
        NuvioDialog(
            onDismiss = { pendingRemoval = null },
            title = stringResource(R.string.profile_avatars_remove_title),
            subtitle = stringResource(R.string.profile_avatars_remove_subtitle, avatar.displayName)
        ) {
            SettingsDialogActionRow {
                SettingsDialogActionButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = { pendingRemoval = null }
                )
                SettingsDialogActionButton(
                    text = stringResource(R.string.action_remove),
                    primary = true,
                    onClick = {
                        viewModel.removeAvatar(avatar.id)
                        pendingRemoval = null
                        // The removed tile had focus; Add is always there to land on.
                        focusAdd()
                    }
                )
            }
        }
    }
}

/** A round avatar (or the Add tile, when [imageUrl] is null) with its name, on Settings' glass. */
@Composable
private fun AvatarTile(
    label: String,
    imageUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(20.dp)
    Card(
        onClick = onClick,
        modifier = modifier
            .width(112.dp)
            .onFocusChanged { focused = it.isFocused }
            .settingsFocusGlass(focused, shape),
        colors = CardDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.Transparent
        ),
        border = CardDefaults.border(border = Border.None, focusedBorder = Border.None),
        shape = CardDefaults.shape(shape),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(SettingsGlassFill)
                    .border(
                        BorderStroke(1.dp, Color.White.copy(alpha = if (focused) 0.55f else 0.14f)),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (imageUrl == null) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(30.dp)
                    )
                } else {
                    AsyncImage(
                        model = imageUrl,
                        contentDescription = label,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = if (focused) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun AddAvatarUrlDialog(
    onDismiss: () -> Unit,
    onSave: (String, (AddCustomAvatarResult) -> Unit) -> Unit,
    onAdded: () -> Unit
) {
    var value by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<AddCustomAvatarResult?>(null) }
    var inputFocused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        repeat(2) { withFrameNanos { } }
        runCatching { focusRequester.requestFocus() }
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.profile_avatars_url_title),
        subtitle = stringResource(R.string.profile_avatars_url_subtitle),
        width = 680.dp,
        suppressFirstKeyUp = false
    ) {
        Card(
            onClick = { focusRequester.requestFocus() },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { inputFocused = it.isFocused || it.hasFocus },
            colors = CardDefaults.colors(
                containerColor = SettingsGlassFill,
                focusedContainerColor = SettingsGlassFill
            ),
            border = CardDefaults.border(
                border = Border(BorderStroke(1.dp, Color.White.copy(alpha = if (inputFocused) 0.5f else 0.12f)), shape = RoundedCornerShape(12.dp)),
                focusedBorder = Border(BorderStroke(1.dp, Color.White.copy(alpha = 0.5f)), shape = RoundedCornerShape(12.dp))
            ),
            shape = CardDefaults.shape(RoundedCornerShape(12.dp)),
            scale = CardDefaults.scale(focusedScale = 1f)
        ) {
            Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                BasicTextField(
                    value = value,
                    onValueChange = { value = it; error = null },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onPreviewKeyEvent { event ->
                            event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER &&
                                event.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN
                        },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { keyboardController?.hide() }),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = NuvioTheme.colors.TextPrimary),
                    cursorBrush = SolidColor(if (inputFocused) Color.White else Color.Transparent),
                    decorationBox = { innerTextField ->
                        if (value.isBlank()) {
                            Text(
                                text = stringResource(R.string.profile_avatars_url_placeholder),
                                style = MaterialTheme.typography.bodyMedium,
                                color = NuvioTheme.colors.TextTertiary
                            )
                        }
                        innerTextField()
                    }
                )
            }
        }
        if (error != null) {
            Text(
                text = stringResource(
                    when (error) {
                        AddCustomAvatarResult.INVALID -> R.string.profile_avatars_invalid_url
                        AddCustomAvatarResult.DUPLICATE -> R.string.profile_avatars_duplicate_url
                        else -> R.string.profile_avatars_invalid_url
                    }
                ),
                color = Color(0xFFFF9898),
                style = MaterialTheme.typography.bodySmall
            )
        }
        SettingsDialogActionRow {
            SettingsDialogActionButton(
                text = stringResource(R.string.action_cancel),
                onClick = onDismiss
            )
            SettingsDialogActionButton(
                text = stringResource(R.string.action_save),
                primary = true,
                onClick = {
                    keyboardController?.hide()
                    onSave(value) { result ->
                        if (result == AddCustomAvatarResult.ADDED) onAdded() else error = result
                    }
                }
            )
        }
    }
}
