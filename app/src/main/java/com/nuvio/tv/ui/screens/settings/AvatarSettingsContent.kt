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
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
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
    var selectedAvatarId by remember { mutableStateOf<String?>(null) }
    val addFocus = remember { FocusRequester() }

    BackHandler { onBack() }
    LaunchedEffect(Unit) {
        repeat(2) { withFrameNanos { } }
        runCatching { addFocus.requestFocus() }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.profile_avatars),
            subtitle = stringResource(R.string.profile_avatars_subtitle)
        )
        SettingsGroupCard(
            modifier = Modifier.fillMaxWidth().weight(1f),
            title = stringResource(R.string.profile_avatars_available)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                AddAvatarButton(
                    focusRequester = addFocus,
                    onClick = { showAddDialog = true }
                )
            }
            if (state.loading && state.avatars.isEmpty()) {
                Text(
                    text = stringResource(R.string.supporters_loading),
                    color = NuvioTheme.colors.TextSecondary,
                    style = MaterialTheme.typography.bodyMedium
                )
            } else if (state.avatars.isEmpty()) {
                Text(
                    text = stringResource(R.string.profile_avatars_empty),
                    color = NuvioTheme.colors.TextSecondary,
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                AvatarPickerGrid(
                    avatars = state.avatars,
                    selectedAvatarId = selectedAvatarId,
                    onAvatarSelected = { avatar -> selectedAvatarId = avatar.id },
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
}

@Composable
private fun AddAvatarButton(
    focusRequester: FocusRequester,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(18.dp)
    Card(
        onClick = onClick,
        modifier = Modifier
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.isFocused },
        colors = CardDefaults.colors(
            containerColor = if (focused) NuvioTheme.colors.FocusBackground else NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = NuvioTheme.colors.FocusBackground
        ),
        shape = CardDefaults.shape(shape),
        scale = CardDefaults.scale(focusedScale = 1.04f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(if (focused) NuvioTheme.colors.Primary else Color(0xFF36363A)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
            Text(
                text = stringResource(R.string.profile_avatars_add_url),
                color = NuvioTheme.colors.TextPrimary,
                style = MaterialTheme.typography.labelLarge
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
                containerColor = NuvioTheme.colors.BackgroundCard,
                focusedContainerColor = NuvioTheme.colors.BackgroundCard
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
                    cursorBrush = SolidColor(if (inputFocused) NuvioTheme.colors.Primary else Color.Transparent),
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundCard,
                    contentColor = NuvioTheme.colors.TextPrimary
                )
            ) { Text(stringResource(R.string.action_cancel)) }
            Button(
                onClick = {
                    keyboardController?.hide()
                    onSave(value) { result ->
                        if (result == AddCustomAvatarResult.ADDED) onAdded() else error = result
                    }
                },
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.Primary,
                    contentColor = Color.White
                )
            ) { Text(stringResource(R.string.action_save)) }
        }
    }
}
