package com.nuvio.tv.ui.screens.library

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MenuDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.cloud.CloudLibraryFile
import com.nuvio.tv.core.cloud.CloudLibraryItem
import com.nuvio.tv.core.cloud.CloudLibraryItemType
import com.nuvio.tv.core.cloud.CloudLibraryPlaybackInfo
import com.nuvio.tv.data.local.PlayerPreference
import com.nuvio.tv.ui.components.EmptyScreenState
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.screens.stream.PlayerChoiceDialog
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The Library's Cloud tab: the files in the connected debrid services, filtered by service, type and a search, and
 * played (or opened externally) from here. [header] is the Library's title and tab row, scrolled with the list.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun CloudLibraryContent(
    viewModel: CloudLibraryViewModel,
    header: @Composable () -> Unit,
    upFocusRequester: FocusRequester,
    onPlaybackResolved: (CloudLibraryPlaybackInfo) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    val activityContext = LocalContext.current
    val scope = rememberCoroutineScope()
    var expandedPicker by remember { mutableStateOf<String?>(null) }
    var activeItem by remember { mutableStateOf<CloudLibraryItem?>(null) }
    var pendingChoice by remember { mutableStateOf<CloudLibraryPlaybackInfo?>(null) }

    LaunchedEffect(state.cloudLibrary.isEnabled, state.cloudLibrary.isLoaded, state.settingsVersion) {
        viewModel.ensureLoaded()
    }

    val route: (CloudLibraryPlaybackInfo) -> Unit = { info ->
        scope.launch {
            when (viewModel.playerPreference()) {
                PlayerPreference.INTERNAL -> onPlaybackResolved(info)
                PlayerPreference.EXTERNAL -> viewModel.launchExternally(info, activityContext)
                PlayerPreference.ASK_EVERY_TIME -> pendingChoice = info
            }
        }
    }

    Box(modifier) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)
        ) {
            item(key = "header") { header() }
            item(key = "selectors") {
                CloudLibrarySelectorsRow(
                    providerOptions = state.providers,
                    typeOptions = state.types,
                    selectedProviderId = state.selectedProviderId,
                    selectedType = state.selectedType,
                    upFocusRequester = upFocusRequester,
                    expandedPicker = expandedPicker,
                    onExpandedChange = { picker, expand -> expandedPicker = if (expand) picker else null },
                    onSelectProvider = { viewModel.selectProvider(it); expandedPicker = null },
                    onSelectType = { viewModel.selectType(it); expandedPicker = null }
                )
            }
            item(key = "search") { CloudLibrarySearchRow(query = state.searchQuery, onQueryChange = viewModel::search) }
            when {
                state.cloudLibrary.isRefreshing && state.visibleItems.isEmpty() -> item(key = "loading") { CloudLibraryLoadingState() }
                !state.cloudLibrary.isEnabled -> item(key = "disabled") {
                    EmptyScreenState(
                        title = stringResource(R.string.cloud_library_disabled_title),
                        subtitle = stringResource(R.string.cloud_library_disabled_message),
                        icon = Icons.Default.BookmarkBorder,
                        height = 260.dp
                    )
                }
                !state.cloudLibrary.hasConnectedProvider -> item(key = "connect") {
                    EmptyScreenState(
                        title = stringResource(R.string.cloud_library_connect_title),
                        subtitle = stringResource(R.string.cloud_library_connect_message),
                        icon = Icons.Default.BookmarkBorder,
                        height = 260.dp
                    )
                }
                state.visibleItems.isEmpty() -> item(key = "empty") {
                    EmptyScreenState(
                        title = stringResource(R.string.cloud_library_empty_title),
                        subtitle = stringResource(R.string.cloud_library_empty_message),
                        icon = Icons.Default.BookmarkBorder,
                        height = 260.dp
                    )
                }
            }
            items(state.visibleItems, key = { it.stableKey }) { item ->
                CloudLibraryCard(
                    item = item,
                    isResolving = state.resolvingFileKey?.startsWith(item.stableKey) == true,
                    onClick = {
                        val playable = item.playableFiles
                        when (playable.size) {
                            0 -> viewModel.onNoPlayableFiles()
                            1 -> viewModel.resolvePlayback(item, playable.first(), route)
                            else -> activeItem = item
                        }
                    }
                )
            }
        }

        activeItem?.let { item ->
            CloudFilePickerDialog(
                item = item,
                resolvingFileKey = state.resolvingFileKey,
                onPlay = { file ->
                    viewModel.resolvePlayback(item, file) { info ->
                        activeItem = null
                        route(info)
                    }
                },
                onDismiss = { activeItem = null }
            )
        }

        pendingChoice?.let { info ->
            PlayerChoiceDialog(
                onInternalSelected = { pendingChoice = null; onPlaybackResolved(info) },
                onExternalSelected = {
                    pendingChoice = null
                    scope.launch { viewModel.launchExternally(info, activityContext) }
                },
                onDismiss = { pendingChoice = null }
            )
        }

        BinsToast(state.message, Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp))
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CloudLibrarySelectorsRow(
    providerOptions: List<FilterOption>,
    typeOptions: List<FilterOption>,
    selectedProviderId: String?,
    selectedType: CloudLibraryItemType?,
    upFocusRequester: FocusRequester,
    expandedPicker: String?,
    onExpandedChange: (String, Boolean) -> Unit,
    onSelectProvider: (String?) -> Unit,
    onSelectType: (CloudLibraryItemType?) -> Unit
) {
    val allLabel = stringResource(R.string.cloud_library_provider_all)
    val typeAllLabel = stringResource(R.string.cloud_library_type_all)
    val selectedProviderLabel = providerOptions.firstOrNull { it.key == selectedProviderId }?.label ?: allLabel
    val selectedTypeLabel = selectedType?.localizedLabel() ?: typeAllLabel

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
    ) {
        LibraryDropdownPicker(
            modifier = Modifier.weight(1f),
            upFocusRequester = upFocusRequester,
            title = stringResource(R.string.cloud_library_select_provider),
            value = selectedProviderLabel,
            selectedValue = selectedProviderId ?: "__all__",
            expanded = expandedPicker == "cloud_provider",
            options = listOf(LibraryOption(allLabel, "__all__")) + providerOptions.map {
                LibraryOption("${it.label} (${it.count})", it.key)
            },
            onExpandedChange = { onExpandedChange("cloud_provider", it) },
            onSelect = { option ->
                onSelectProvider(if (option.value == "__all__") null else option.value)
            }
        )

        LibraryDropdownPicker(
            modifier = Modifier.weight(1f),
            upFocusRequester = upFocusRequester,
            title = stringResource(R.string.cloud_library_select_type),
            value = selectedTypeLabel,
            selectedValue = selectedType?.name ?: "__all__",
            expanded = expandedPicker == "cloud_type",
            options = listOf(LibraryOption(typeAllLabel, "__all__")) + typeOptions.map {
                LibraryOption("${it.label} (${it.count})", it.key)
            },
            onExpandedChange = { onExpandedChange("cloud_type", it) },
            onSelect = { option ->
                onSelectType(CloudLibraryItemType.entries.firstOrNull { it.name == option.value })
            }
        )
    }
}

@Composable
private fun CloudLibraryItemType.localizedLabel(): String =
    when (this) {
        CloudLibraryItemType.Torrent -> stringResource(R.string.cloud_library_type_torrents)
        CloudLibraryItemType.Usenet -> stringResource(R.string.cloud_library_type_usenet)
        CloudLibraryItemType.WebDownload -> stringResource(R.string.cloud_library_type_web)
        CloudLibraryItemType.File -> stringResource(R.string.cloud_library_type_files)
    }

/**
 * Free-text filter over the already-loaded cloud library. Purely local: it never triggers a
 * provider request, it just narrows [CloudUiState.visibleItems]. Filtering is applied on
 * every keystroke, so results narrow live as you type.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CloudLibrarySearchRow(
    query: String,
    onQueryChange: (String) -> Unit
) {
    var editing by remember { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val fieldFocusRequester = remember { FocusRequester() }
    val editorFocusRequester = remember { FocusRequester() }

    LaunchedEffect(editing) {
        if (editing) {
            editorFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        Surface(
            onClick = { editing = true },
            modifier = Modifier
                .weight(1f)
                .focusRequester(fieldFocusRequester),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = NuvioTheme.colors.BackgroundCard,
                focusedContainerColor = NuvioTheme.colors.BackgroundCard
            ),
            border = ClickableSurfaceDefaults.border(
                border = Border(
                    border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border),
                    shape = RoundedCornerShape(NuvioTheme.radii.md)
                ),
                focusedBorder = Border(
                    border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                    shape = RoundedCornerShape(NuvioTheme.radii.md)
                )
            ),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md)),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
        ) {
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = NuvioTheme.spacing.lg, vertical = 14.dp)
                    .focusRequester(editorFocusRequester)
                    .focusProperties { canFocus = editing }
                    .onFocusChanged {
                        if (!it.isFocused && editing) {
                            editing = false
                            keyboardController?.hide()
                        }
                    }
                    .onPreviewKeyEvent { event ->
                        val native = event.nativeKeyEvent
                        if (native.action != AndroidKeyEvent.ACTION_DOWN) {
                            return@onPreviewKeyEvent false
                        }
                        val direction = when (native.keyCode) {
                            AndroidKeyEvent.KEYCODE_DPAD_UP -> FocusDirection.Up
                            AndroidKeyEvent.KEYCODE_DPAD_DOWN -> FocusDirection.Down
                            else -> return@onPreviewKeyEvent false
                        }
                        editing = false
                        keyboardController?.hide()
                        focusManager.moveFocus(direction)
                        true
                    },
                readOnly = !editing,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = {
                        editing = false
                        keyboardController?.hide()
                        fieldFocusRequester.requestFocus()
                    }
                ),
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = NuvioTheme.colors.TextPrimary,
                                            textDirection = TextDirection.Content
                ),
                cursorBrush = SolidColor(
                    if (editing) NuvioTheme.colors.FocusRing else Color.Transparent
                ),
                decorationBox = { innerTextField ->
                    if (query.isEmpty()) {
                        Text(
                            text = stringResource(R.string.cloud_library_search_label),
                            style = MaterialTheme.typography.bodyMedium,
                            color = NuvioTheme.colors.TextTertiary
                        )
                    }
                    innerTextField()
                }
            )
        }

        if (query.isNotEmpty()) {
            Button(
                onClick = {
                    onQueryChange("")
                    editing = false
                },
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundCard,
                    contentColor = NuvioTheme.colors.TextPrimary
                )
            ) {
                Text(stringResource(R.string.cloud_library_search_clear))
            }
        }
    }
}

@Composable
private fun CloudLibraryLoadingState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(260.dp),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        LoadingIndicator()
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = stringResource(R.string.library_syncing_library),
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.colors.TextSecondary
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CloudLibraryCard(
    item: CloudLibraryItem,
    isResolving: Boolean,
    onClick: () -> Unit
) {
    val fileLine = cloudLibraryFileLine(item)
    Card(
        onClick = { if (!isResolving) onClick() },
        modifier = Modifier.fillMaxWidth(),
        shape = CardDefaults.shape(RoundedCornerShape(10.dp)),
        colors = CardDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = NuvioTheme.colors.FocusBackground
        ),
        border = CardDefaults.border(
            border = Border(border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border), shape = RoundedCornerShape(10.dp)),
            focusedBorder = Border(border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), shape = RoundedCornerShape(10.dp))
        ),
        scale = CardDefaults.scale(focusedScale = 1.02f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.titleSmall,
                color = NuvioTheme.colors.TextPrimary,
                fontWeight = FontWeight.SemiBold
            )
            fileLine?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextSecondary
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = cloudLibraryMetadata(item),
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextTertiary
                )
                Text(
                    text = if (isResolving) {
                        stringResource(R.string.cloud_library_opening)
                    } else {
                        cloudLibraryPlayableLabel(item)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (item.playableFiles.isEmpty()) NuvioTheme.colors.TextTertiary else NuvioTheme.colors.Primary
                )
            }
        }
    }
}

@Composable
private fun cloudLibraryFileLine(item: CloudLibraryItem): String? =
    when (val count = item.playableFiles.size) {
        0 -> stringResource(R.string.cloud_library_no_playable_files)
        1 -> item.playableFiles.first().name.takeIf { it != item.name }
        else -> stringResource(R.string.cloud_library_playable_file_count, count)
    }

@Composable
private fun cloudLibraryPlayableLabel(item: CloudLibraryItem): String =
    when (val count = item.playableFiles.size) {
        0 -> stringResource(R.string.cloud_library_no_playable_files)
        1 -> stringResource(R.string.cloud_library_one_playable_file)
        else -> stringResource(R.string.cloud_library_playable_file_count, count)
    }

@Composable
private fun cloudLibraryMetadata(item: CloudLibraryItem): String {
    val parts = listOfNotNull(
        item.providerName.takeIf { it.isNotBlank() },
        item.type.localizedLabel(),
        item.status?.takeIf { it.isNotBlank() } ?: stringResource(R.string.cloud_library_status_ready),
        formatCloudSize(item.sizeBytes)
    )
    return parts.joinToString(" • ")
}

private fun formatCloudSize(sizeBytes: Long?): String? {
    val bytes = sizeBytes ?: return null
    if (bytes <= 0L) return null
    val gb = bytes / 1_000_000_000.0
    return if (gb >= 1.0) {
        String.format(java.util.Locale.US, "%.1f GB", gb)
    } else {
        val mb = bytes / 1_000_000.0
        String.format(java.util.Locale.US, "%.0f MB", mb)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CloudFilePickerDialog(
    item: CloudLibraryItem,
    resolvingFileKey: String?,
    onPlay: (CloudLibraryFile) -> Unit,
    onDismiss: () -> Unit
) {
    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.cloud_library_file_picker_title),
        subtitle = item.name,
        width = 860.dp,
        suppressFirstKeyUp = false
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
        ) {
            items(item.playableFiles, key = { it.stableKey }) { file ->
                val resolving = resolvingFileKey == "${item.stableKey}:${file.stableKey}"
                Card(
                    onClick = { if (!resolving) onPlay(file) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.colors(
                        containerColor = NuvioTheme.colors.BackgroundCard,
                        focusedContainerColor = NuvioTheme.colors.FocusBackground
                    ),
                    shape = CardDefaults.shape(RoundedCornerShape(10.dp)),
                    scale = CardDefaults.scale(focusedScale = 1f)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
                    ) {
                        Text(
                            text = file.name,
                            style = MaterialTheme.typography.bodyLarge,
                            color = NuvioTheme.colors.TextPrimary,
                            fontWeight = FontWeight.SemiBold
                        )
                        formatCloudSize(file.sizeBytes)?.let { size ->
                            Text(
                                text = size,
                                style = MaterialTheme.typography.bodySmall,
                                color = NuvioTheme.colors.TextSecondary
                            )
                        }
                        Text(
                            text = stringResource(R.string.cloud_library_play_file),
                            style = MaterialTheme.typography.labelMedium,
                            color = NuvioTheme.colors.Primary
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun LibraryDropdownPicker(
    modifier: Modifier = Modifier,
    upFocusRequester: FocusRequester? = null,
    title: String,
    value: String,
    selectedValue: String?,
    expanded: Boolean,
    options: List<LibraryOption>,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (LibraryOption) -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    var anchorSize by remember { mutableStateOf(IntSize.Zero) }
    // Seed focused option with the current selection so reopen highlights the right row
    // even before focus lands (fixes #2848 / incomplete #2507 race on TV).
    var focusedOptionValue by remember(expanded) {
        mutableStateOf(if (expanded) selectedValue else null)
    }
    val selectedItemFocusRequester = remember { FocusRequester() }
    val selectedBringIntoViewRequester = remember { BringIntoViewRequester() }

    // Popup content attaches focus targets a few frames after expand. A fixed 50ms
    // delay was flaky on TV and left focus on the first item for non-top selections.
    LaunchedEffect(expanded, selectedValue) {
        if (!expanded || selectedValue == null) return@LaunchedEffect
        var focused = selectedItemFocusRequester.requestFocusAfterFrames(frames = 3)
        var attempt = 0
        while (!focused && attempt < 6) {
            delay(32)
            focused = runCatching { selectedItemFocusRequester.requestFocus() }.getOrDefault(false)
            attempt++
        }
        if (!focused) return@LaunchedEffect
        runCatching { selectedBringIntoViewRequester.bringIntoView() }
        // Material DropdownMenu may still move initial focus to the first item after
        // the popup settles; re-assert once so long lists keep the real selection.
        delay(48)
        if (runCatching { selectedItemFocusRequester.requestFocus() }.getOrDefault(false)) {
            runCatching { selectedBringIntoViewRequester.bringIntoView() }
        }
    }

    Box(modifier = modifier) {
        Card(
            onClick = { onExpandedChange(!expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (upFocusRequester != null) {
                        Modifier.focusProperties { up = upFocusRequester }
                    } else {
                        Modifier
                    }
                )
                .onSizeChanged { anchorSize = it }
                .onFocusChanged { isFocused = it.isFocused },
            shape = CardDefaults.shape(shape = RoundedCornerShape(14.dp)),
            colors = CardDefaults.colors(
                containerColor = NuvioTheme.colors.BackgroundCard,
                focusedContainerColor = NuvioTheme.colors.FocusBackground
            ),
            border = CardDefaults.border(
                border = androidx.tv.material3.Border(
                    border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border),
                    shape = RoundedCornerShape(14.dp)
                ),
                focusedBorder = androidx.tv.material3.Border(
                    border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                    shape = RoundedCornerShape(14.dp)
                )
            ),
            scale = CardDefaults.scale(
                focusedScale = 1.0f,
                pressedScale = 1.0f
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall,
                    color = NuvioTheme.colors.TextTertiary
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = value,
                        style = MaterialTheme.typography.titleMedium,
                        color = NuvioTheme.colors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Icon(
                        imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (expanded) stringResource(R.string.cd_collapse, title) else stringResource(R.string.cd_expand, title),
                        tint = if (isFocused) NuvioTheme.colors.FocusRing else NuvioTheme.colors.TextSecondary
                    )
                }
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = {
                focusedOptionValue = null
                onExpandedChange(false)
            },
            modifier = Modifier
                .width(with(LocalDensity.current) { anchorSize.width.toDp() })
                .heightIn(max = 320.dp),
            shape = RoundedCornerShape(14.dp),
            containerColor = NuvioTheme.colors.BackgroundCard,
            tonalElevation = NuvioTheme.spacing.none,
            shadowElevation = NuvioTheme.spacing.sm,
            border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border)
        ) {
            options.forEach { option ->
                val isSelected = option.value == selectedValue
                val isOptionFocused = option.value == focusedOptionValue
                val itemTextColor = when {
                    isOptionFocused -> NuvioTheme.colors.OnSecondary
                    isSelected -> NuvioTheme.colors.TextPrimary
                    else -> NuvioTheme.colors.TextPrimary
                }
                val itemBackgroundColor = when {
                    isOptionFocused -> NuvioTheme.colors.Secondary
                    isSelected -> NuvioTheme.colors.FocusBackground
                    else -> Color.Transparent
                }

                DropdownMenuItem(
                    modifier = Modifier
                        .then(
                            if (isSelected) {
                                Modifier
                                    .focusRequester(selectedItemFocusRequester)
                                    .bringIntoViewRequester(selectedBringIntoViewRequester)
                            } else {
                                Modifier
                            }
                        )
                        .padding(horizontal = 6.dp, vertical = NuvioTheme.spacing.xxs)
                        .background(
                            color = itemBackgroundColor,
                            shape = RoundedCornerShape(10.dp)
                        )
                        .onFocusChanged { state ->
                            val hasFocus = state.isFocused || state.hasFocus
                            focusedOptionValue = when {
                                hasFocus -> option.value
                                focusedOptionValue == option.value -> null
                                else -> focusedOptionValue
                            }
                        },
                    text = {
                        Text(
                            text = option.label,
                            color = itemTextColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    onClick = { onSelect(option) },
                    colors = MenuDefaults.itemColors(
                        textColor = itemTextColor,
                        disabledTextColor = NuvioTheme.colors.TextDisabled
                    )
                )
            }
        }
    }
}

private data class LibraryOption(
    val label: String,
    val value: String
)
