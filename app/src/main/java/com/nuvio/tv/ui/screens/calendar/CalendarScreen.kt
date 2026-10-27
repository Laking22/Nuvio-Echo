@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.calendar

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.nuvio.tv.ui.screens.detail.selectionSurface
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nuvio.tv.LocalContentFocusRequester
import com.nuvio.tv.R
import com.nuvio.tv.core.calendar.CalendarEpisode
import com.nuvio.tv.core.calendar.CalendarMarker
import com.nuvio.tv.core.calendar.CalendarShow
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.browse.BrowsePill
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.theme.ThemeColors
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * The plain look (the White theme's colours): the colour picked in Appearance only reaches the selection ring, so
 * these screens stay plain glass and grey whatever it is.
 */
private val Plain = ThemeColors.White

private enum class CalendarView { LIST, MONTH }

private val ScreenStart = 48.dp
private val ScreenEnd = 40.dp
private val TopInset = 18.dp
private val FeatureWidth = 340.dp
private val PanelGap = 28.dp
internal val ReminderGreen = Color(0xFF66BB6A)
private const val MONTHS_BACK = 1
private const val MONTHS_AHEAD = 12

@Composable
fun CalendarScreen(
    viewModel: CalendarViewModel = hiltViewModel(),
    onOpenShow: (showId: String, addonBaseUrl: String?, season: Int?, episode: Int?) -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()

    var view by rememberSaveable { mutableStateOf(CalendarView.LIST) }
    var monthOffset by rememberSaveable { mutableIntStateOf(0) }
    var focusedDay by rememberSaveable { mutableStateOf<LocalDate?>(null) }
    var focusedId by remember { mutableStateOf(viewModel.lastFocusedId) }
    var actionsFor by remember { mutableStateOf<CalendarEntry?>(null) }
    var dayDialog by remember { mutableStateOf<LocalDate?>(null) }

    val listPillFocus = remember { FocusRequester() }
    val monthPillFocus = remember { FocusRequester() }
    val firstRowFocus = remember { FocusRequester() }
    val restoreRowFocus = remember { FocusRequester() }
    val dayFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()

    /** Tries the preferred targets for a few frames (the list may still be laying out), then the last one. */
    fun focusAfterLayout(vararg targets: FocusRequester) {
        scope.launch {
            repeat(12) {
                withFrameNanos { }
                for (target in targets.dropLast(1)) {
                    if (runCatching { target.requestFocus() }.getOrDefault(false)) return@launch
                }
            }
            targets.lastOrNull()?.let { runCatching { it.requestFocus() } }
        }
    }

    fun focusContent() {
        when (view) {
            CalendarView.LIST -> focusAfterLayout(restoreRowFocus, firstRowFocus, listPillFocus)
            CalendarView.MONTH -> focusAfterLayout(dayFocus, monthPillFocus)
        }
    }

    // Opening: wait for the first rows (or for the answers, when there are none) before taking focus.
    // Back from a show's page: the row it was opened from.
    val ready = state.entries.isNotEmpty() || (state.loaded && !state.refreshing)
    LaunchedEffect(ready) {
        if (ready) focusContent()
    }

    val entriesById = remember(state.entries) { state.entries.associateBy { it.show.id } }
    val focusedEntry = focusedId?.let { entriesById[it] } ?: state.entries.firstOrNull()
    val month = YearMonth.from(state.today).plusMonths(monthOffset.toLong())

    val contentFocusRequester = LocalContentFocusRequester.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioTheme.colors.Background)
            .focusRequester(contentFocusRequester)
            .focusRestorer()
            .focusGroup()
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = ScreenStart, end = ScreenEnd, top = TopInset)
        ) {
            Box(modifier = Modifier.width(FeatureWidth).fillMaxHeight()) {
                when (view) {
                    CalendarView.LIST -> FeaturePanel(entry = focusedEntry, locale = locale)
                    CalendarView.MONTH -> {
                        val day = focusedDay ?: state.today
                        DayPanel(day = day, entries = state.byDate[day].orEmpty(), today = state.today, locale = locale)
                    }
                }
            }
            Spacer(modifier = Modifier.width(PanelGap))
            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                Header(
                    state = state,
                    view = view,
                    listPillFocus = listPillFocus,
                    monthPillFocus = monthPillFocus,
                    onSelect = { selected ->
                        if (view != selected) {
                            view = selected
                            focusContent()
                        }
                    }
                )
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))
                when (view) {
                    CalendarView.LIST -> CalendarList(
                        state = state,
                        listStateHolder = listState,
                        restoreId = viewModel.lastFocusedId,
                        firstRowFocus = firstRowFocus,
                        restoreRowFocus = restoreRowFocus,
                        locale = locale,
                        onFocused = { entry ->
                            focusedId = entry.show.id
                            viewModel.lastFocusedId = entry.show.id
                        },
                        onClick = { actionsFor = it }
                    )
                    CalendarView.MONTH -> MonthGrid(
                        month = month,
                        state = state,
                        focusedDay = focusedDay,
                        dayFocus = dayFocus,
                        locale = locale,
                        canGoBack = monthOffset > -MONTHS_BACK,
                        canGoForward = monthOffset < MONTHS_AHEAD,
                        onMonth = { delta -> monthOffset = (monthOffset + delta).coerceIn(-MONTHS_BACK, MONTHS_AHEAD) },
                        onDayFocused = { focusedDay = it },
                        onDayClick = { day ->
                            val entries = state.byDate[day].orEmpty()
                            when (entries.size) {
                                0 -> Unit
                                1 -> actionsFor = entries.first()
                                else -> dayDialog = day
                            }
                        }
                    )
                }
            }
        }
    }

    dayDialog?.let { day ->
        DayDialog(
            day = day,
            entries = state.byDate[day].orEmpty(),
            locale = locale,
            onDismiss = { dayDialog = null },
            onPick = { entry ->
                dayDialog = null
                actionsFor = entry
            }
        )
    }

    actionsFor?.let { picked ->
        // Follows the reminder as it's toggled.
        val entry = entriesById[picked.show.id] ?: picked
        ActionsDialog(
            entry = entry,
            locale = locale,
            onDismiss = { actionsFor = null },
            onOpen = {
                actionsFor = null
                viewModel.lastFocusedId = entry.show.id
                onOpenShow(entry.show.id, entry.show.addonBaseUrl, entry.episode?.season, entry.episode?.episode)
            },
            onToggleReminder = {
                actionsFor = null
                viewModel.toggleReminder(entry.show.id)
            }
        )
    }
}

@Composable
private fun Header(
    state: CalendarUiState,
    view: CalendarView,
    listPillFocus: FocusRequester,
    monthPillFocus: FocusRequester,
    onSelect: (CalendarView) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(32.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.calendar_title),
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
            color = NuvioTheme.colors.TextPrimary
        )
        Spacer(modifier = Modifier.width(NuvioTheme.spacing.md))
        Text(
            text = when {
                state.followed == 0 && state.refreshing -> stringResource(R.string.calendar_loading)
                else -> stringResource(R.string.calendar_summary, state.dated, state.followed)
            },
            style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.colors.TextTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            BrowsePill(
                text = stringResource(R.string.calendar_view_list),
                selected = view == CalendarView.LIST,
                modifier = Modifier.widthIn(min = 64.dp).focusRequester(listPillFocus),
                onClick = { onSelect(CalendarView.LIST) }
            )
            BrowsePill(
                text = stringResource(R.string.calendar_view_month),
                selected = view == CalendarView.MONTH,
                modifier = Modifier.widthIn(min = 64.dp).focusRequester(monthPillFocus),
                onClick = { onSelect(CalendarView.MONTH) }
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// List view
// ---------------------------------------------------------------------------------------------

@Composable
private fun CalendarList(
    state: CalendarUiState,
    listStateHolder: LazyListState,
    restoreId: String?,
    firstRowFocus: FocusRequester,
    restoreRowFocus: FocusRequester,
    locale: Locale,
    onFocused: (CalendarEntry) -> Unit,
    onClick: (CalendarEntry) -> Unit
) {
    if (state.entries.isEmpty()) {
        Text(
            text = stringResource(if (state.refreshing || !state.loaded) R.string.calendar_loading else R.string.calendar_empty),
            style = MaterialTheme.typography.bodyLarge,
            color = NuvioTheme.colors.TextSecondary,
            modifier = Modifier.padding(top = NuvioTheme.spacing.xl)
        )
        return
    }
    val groups = remember(state.entries) { state.entries.groupBy { it.group } }
    val firstId = state.entries.first().show.id
    LazyColumn(
        state = listStateHolder,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(bottom = NuvioTheme.spacing.xxl)
    ) {
        for (group in CalendarGroup.entries) {
            val entries = groups[group] ?: continue
            item(key = "h-${group.name}", contentType = "header") {
                Text(
                    text = stringResource(
                        when (group) {
                            CalendarGroup.TODAY -> R.string.calendar_group_today
                            CalendarGroup.WEEK -> R.string.calendar_group_week
                            CalendarGroup.LATER -> R.string.calendar_group_later
                            CalendarGroup.NO_DATE -> R.string.calendar_group_no_date
                        }
                    ).uppercase(locale),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp),
                    color = NuvioTheme.colors.TextTertiary,
                    modifier = Modifier.padding(start = 4.dp, top = NuvioTheme.spacing.sm, bottom = 2.dp)
                )
            }
            items(entries, key = { it.show.id }, contentType = { "row" }) { entry ->
                CalendarRow(
                    entry = entry,
                    locale = locale,
                    modifier = Modifier
                        .then(if (entry.show.id == firstId) Modifier.focusRequester(firstRowFocus) else Modifier)
                        .then(if (entry.show.id == restoreId) Modifier.focusRequester(restoreRowFocus) else Modifier),
                    onFocused = { onFocused(entry) },
                    onClick = { onClick(entry) }
                )
            }
        }
    }
}

@Composable
private fun CalendarRow(
    entry: CalendarEntry,
    locale: Locale,
    modifier: Modifier,
    onFocused: () -> Unit,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val colors = NuvioTheme.colors
    val shape = RoundedCornerShape(NuvioTheme.radii.md)
    val primary = colors.TextPrimary
    val secondary = if (focused) Color.White.copy(alpha = 0.75f) else colors.TextSecondary
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(62.dp)
            .selectionSurface(focused, shape)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 6.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Thumbnail(
            url = entry.show.backdrop ?: entry.show.poster,
            modifier = Modifier.fillMaxHeight().aspectRatio(16f / 9f)
        )
        Spacer(modifier = Modifier.width(NuvioTheme.spacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.show.title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = rowSubtitle(entry, locale),
                style = MaterialTheme.typography.bodySmall,
                color = secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(NuvioTheme.spacing.sm))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = entry.daysAway?.let { relativeDays(it, locale) } ?: stringResource(R.string.calendar_no_date),
                style = MaterialTheme.typography.labelMedium,
                color = if (entry.daysAway == 0L && !focused) Plain.secondary else secondary,
                maxLines = 1
            )
            val marker = entry.episode?.takeIf { entry.date != null }?.marker ?: CalendarMarker.NONE
            if (marker != CalendarMarker.NONE || entry.reminder) {
                Spacer(modifier = Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (marker != CalendarMarker.NONE) {
                        MarkerChip(marker, inverted = focused)
                    }
                    if (entry.reminder) {
                        if (marker != CalendarMarker.NONE) Spacer(modifier = Modifier.width(5.dp))
                        Icon(
                            imageVector = Icons.Filled.NotificationsActive,
                            contentDescription = stringResource(R.string.calendar_reminder_on),
                            tint = if (focused) Color.White else ReminderGreen,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.width(4.dp))
    }
}

// ---------------------------------------------------------------------------------------------
// Month view
// ---------------------------------------------------------------------------------------------

@Composable
private fun MonthGrid(
    month: YearMonth,
    state: CalendarUiState,
    focusedDay: LocalDate?,
    dayFocus: FocusRequester,
    locale: Locale,
    canGoBack: Boolean,
    canGoForward: Boolean,
    onMonth: (Int) -> Unit,
    onDayFocused: (LocalDate) -> Unit,
    onDayClick: (LocalDate) -> Unit
) {
    val firstDayOfWeek = WeekFields.of(locale).firstDayOfWeek
    val gridStart = remember(month, firstDayOfWeek) {
        val first = month.atDay(1)
        val shift = (first.dayOfWeek.value - firstDayOfWeek.value + 7) % 7
        first.minusDays(shift.toLong())
    }
    val days = remember(gridStart) { List(42) { gridStart.plusDays(it.toLong()) } }
    // Focus lands on the day last looked at when it's on this page, else today, else the 1st.
    val anchor = focusedDay?.takeIf { YearMonth.from(it) == month }
        ?: state.today.takeIf { YearMonth.from(it) == month }
        ?: month.atDay(1)

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MonthArrow(Icons.Filled.ChevronLeft, enabled = canGoBack) { onMonth(-1) }
            Text(
                text = month.month.getDisplayName(TextStyle.FULL_STANDALONE, locale)
                    .replaceFirstChar { it.titlecase(locale) } + " " + month.year,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = NuvioTheme.colors.TextPrimary,
                modifier = Modifier.padding(horizontal = NuvioTheme.spacing.md)
            )
            MonthArrow(Icons.Filled.ChevronRight, enabled = canGoForward) { onMonth(1) }
        }
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(7) { i ->
                Text(
                    text = firstDayOfWeek.plus(i.toLong()).getDisplayName(TextStyle.SHORT, locale).uppercase(locale),
                    style = MaterialTheme.typography.labelSmall,
                    color = NuvioTheme.colors.TextTertiary,
                    modifier = Modifier.weight(1f).padding(start = 6.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (week in 0 until 6) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (d in 0 until 7) {
                        val day = days[week * 7 + d]
                        DayCell(
                            day = day,
                            inMonth = YearMonth.from(day) == month,
                            isToday = day == state.today,
                            selected = day == focusedDay,
                            entries = state.byDate[day].orEmpty(),
                            modifier = Modifier
                                .weight(1f)
                                .then(if (day == anchor) Modifier.focusRequester(dayFocus) else Modifier),
                            onFocused = { onDayFocused(day) },
                            onClick = { onDayClick(day) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthArrow(icon: ImageVector, enabled: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val colors = NuvioTheme.colors
    Box(
        modifier = Modifier
            .size(28.dp)
            .selectionSurface(focused, CircleShape)
            .then(if (focused) Modifier else Modifier.border(BorderStroke(NuvioTheme.spacing.hairline, colors.Border), CircleShape))
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = when {
                focused -> Color.White
                enabled -> colors.TextPrimary
                else -> colors.TextTertiary.copy(alpha = 0.4f)
            },
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun DayCell(
    day: LocalDate,
    inMonth: Boolean,
    isToday: Boolean,
    selected: Boolean,
    entries: List<CalendarEntry>,
    modifier: Modifier,
    onFocused: () -> Unit,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val colors = NuvioTheme.colors
    val shape = RoundedCornerShape(NuvioTheme.radii.sm)
    Box(
        modifier = modifier
            .height(52.dp)
            .selectionSurface(
                focused, shape,
                when {
                    selected -> Plain.focusBackground
                    entries.isNotEmpty() -> Plain.backgroundCard
                    else -> Color.Transparent
                }
            )
            .then(
                if (isToday && !focused) Modifier.border(BorderStroke(1.dp, Plain.secondary), shape) else Modifier
            )
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 6.dp, vertical = 5.dp)
    ) {
        Text(
            text = day.dayOfMonth.toString(),
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Medium
            ),
            color = when {
                focused -> Color.White
                isToday -> Plain.secondary
                inMonth -> colors.TextPrimary
                else -> colors.TextTertiary.copy(alpha = 0.5f)
            },
            modifier = Modifier.align(Alignment.TopStart)
        )
        if (entries.isNotEmpty()) {
            Row(
                modifier = Modifier.align(Alignment.BottomStart),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                entries.take(MAX_DOTS).forEach { entry ->
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    focused -> Color.White
                                    entry.reminder -> ReminderGreen
                                    else -> colors.TextSecondary
                                }
                            )
                    )
                }
                if (entries.size > MAX_DOTS) {
                    Text(
                        text = "+${entries.size - MAX_DOTS}",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        color = if (focused) Color.White else colors.TextSecondary
                    )
                }
            }
        }
    }
}

private const val MAX_DOTS = 4

// ---------------------------------------------------------------------------------------------
// Left panels
// ---------------------------------------------------------------------------------------------

/** The focused show, big: art, when, which episode, what kind of episode, and its synopsis. */
@Composable
private fun FeaturePanel(entry: CalendarEntry?, locale: Locale) {
    if (entry == null) return
    val colors = NuvioTheme.colors
    val episode = entry.episode
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(NuvioTheme.radii.lg))
                .background(Plain.backgroundCard)
        ) {
            val url = entry.show.backdrop ?: entry.show.poster
            if (url != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current).data(url).crossfade(true).build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            val marker = episode?.takeIf { entry.date != null }?.marker ?: CalendarMarker.NONE
            if (marker != CalendarMarker.NONE) {
                Box(modifier = Modifier.align(Alignment.TopStart).padding(10.dp)) {
                    MarkerChip(marker, inverted = false, large = true)
                }
            }
        }
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))
        Text(
            text = if (entry.date != null) {
                dayLabel(entry.date, locale) + " · " + relativeDays(entry.daysAway ?: 0L, locale)
            } else {
                stringResource(R.string.calendar_group_no_date)
            }.uppercase(locale),
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
            color = if (entry.date != null) Plain.secondary else colors.TextTertiary,
            maxLines = 1
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = entry.show.title,
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = colors.TextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (episode != null) {
            Spacer(modifier = Modifier.height(2.dp))
            val code = stringResource(R.string.calendar_episode_code, episode.season, episode.episode)
            Text(
                text = if (entry.date != null) {
                    listOfNotNull(code, episode.name).joinToString(" · ")
                } else {
                    stringResource(
                        R.string.calendar_last_episode,
                        listOfNotNull(code, episode.airDate?.let { fullDate(it, locale) }).joinToString(" · ")
                    )
                },
                style = MaterialTheme.typography.bodyLarge,
                color = colors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        val support = supportLine(entry.show, episode.takeIf { entry.date != null })
        if (support.isNotEmpty()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = support,
                style = MaterialTheme.typography.bodySmall,
                color = colors.TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        val overview = episode?.overview?.takeIf { entry.date != null }
        if (overview != null) {
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
            Text(
                text = overview,
                style = MaterialTheme.typography.bodySmall,
                color = colors.TextSecondary,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (entry.reminder) {
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.NotificationsActive,
                    contentDescription = null,
                    tint = ReminderGreen,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.calendar_reminder_on),
                    style = MaterialTheme.typography.labelMedium,
                    color = ReminderGreen
                )
            }
        }
    }
}

/** Month view: the focused day and what airs on it. */
@Composable
private fun DayPanel(day: LocalDate, entries: List<CalendarEntry>, today: LocalDate, locale: Locale) {
    val colors = NuvioTheme.colors
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(NuvioTheme.radii.lg))
                .background(Plain.backgroundCard),
            contentAlignment = Alignment.Center
        ) {
            val url = entries.firstNotNullOfOrNull { it.show.backdrop ?: it.show.poster }
            if (url != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current).data(url).crossfade(true).build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
                )
            }
            Text(
                text = day.dayOfMonth.toString(),
                style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Bold),
                color = if (url != null) Color.White else colors.TextTertiary,
                modifier = Modifier
                    .align(if (url != null) Alignment.BottomStart else Alignment.Center)
                    .padding(if (url != null) 12.dp else 0.dp)
            )
        }
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))
        val label = day.format(DateTimeFormatter.ofPattern("EEEE d MMMM", locale))
        val relative = ChronoUnit.DAYS.between(today, day)
        Text(
            text = (if (relative >= 0) "$label · ${relativeDays(relative, locale)}" else label).uppercase(locale),
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
            color = Plain.secondary,
            maxLines = 1
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = if (entries.isEmpty()) {
                stringResource(R.string.calendar_nothing_this_day)
            } else {
                pluralStringResource(R.plurals.calendar_day_episodes, entries.size, entries.size)
            },
            style = MaterialTheme.typography.titleMedium,
            color = colors.TextPrimary
        )
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
        entries.take(5).forEach { entry ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(if (entry.reminder) ReminderGreen else colors.TextSecondary)
                )
                Spacer(modifier = Modifier.width(NuvioTheme.spacing.sm))
                Text(
                    text = entry.show.title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                entry.episode?.let { ep ->
                    Spacer(modifier = Modifier.width(NuvioTheme.spacing.sm))
                    Text(
                        text = stringResource(R.string.calendar_episode_code, ep.season, ep.episode),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.TextSecondary,
                        maxLines = 1
                    )
                }
            }
        }
        if (entries.size > 5) {
            Text(
                text = "+${entries.size - 5}",
                style = MaterialTheme.typography.bodySmall,
                color = colors.TextTertiary,
                modifier = Modifier.padding(start = 14.dp, top = 2.dp)
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Dialogs
// ---------------------------------------------------------------------------------------------

@Composable
private fun ActionsDialog(
    entry: CalendarEntry,
    locale: Locale,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onToggleReminder: () -> Unit
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        repeat(2) { withFrameNanos { } }
        runCatching { firstFocus.requestFocus() }
    }
    val episode = entry.episode
    NuvioDialog(
        onDismiss = onDismiss,
        title = entry.show.title,
        subtitle = when {
            entry.date != null && episode != null ->
                stringResource(R.string.calendar_episode_code, episode.season, episode.episode) + " · " +
                    dayLabel(entry.date, locale) + " · " + relativeDays(entry.daysAway ?: 0L, locale)
            else -> stringResource(R.string.calendar_no_date)
        },
        width = 460.dp
    ) {
        CalendarActionCard(
            icon = Icons.AutoMirrored.Outlined.OpenInNew,
            title = stringResource(R.string.calendar_open_show),
            modifier = Modifier.focusRequester(firstFocus),
            onClick = onOpen
        )
        // A reminder needs a day to fire on; one already on can always be turned off.
        if (entry.reminder || entry.date != null) {
            CalendarActionCard(
                icon = if (entry.reminder) Icons.Filled.NotificationsActive else Icons.Outlined.NotificationsNone,
                iconTint = if (entry.reminder) ReminderGreen else null,
                title = stringResource(if (entry.reminder) R.string.calendar_turn_off_reminder else R.string.calendar_remind_me),
                description = if (entry.reminder) null else stringResource(R.string.calendar_reminder_hint),
                onClick = onToggleReminder
            )
        }
    }
}

@Composable
private fun DayDialog(
    day: LocalDate,
    entries: List<CalendarEntry>,
    locale: Locale,
    onDismiss: () -> Unit,
    onPick: (CalendarEntry) -> Unit
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        repeat(2) { withFrameNanos { } }
        runCatching { firstFocus.requestFocus() }
    }
    NuvioDialog(
        onDismiss = onDismiss,
        title = day.format(DateTimeFormatter.ofPattern("EEEE d MMMM", locale)).replaceFirstChar { it.titlecase(locale) },
        subtitle = pluralStringResource(R.plurals.calendar_day_episodes, entries.size, entries.size),
        width = 460.dp
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
        ) {
            items(entries, key = { it.show.id }) { entry ->
                val ep = entry.episode
                CalendarActionCard(
                    icon = if (entry.reminder) Icons.Filled.NotificationsActive else null,
                    iconTint = ReminderGreen,
                    title = entry.show.title,
                    description = ep?.let {
                        listOfNotNull(stringResource(R.string.calendar_episode_code, it.season, it.episode), it.name)
                            .joinToString(" · ")
                    },
                    modifier = if (entry == entries.first()) Modifier.focusRequester(firstFocus) else Modifier,
                    onClick = { onPick(entry) }
                )
            }
        }
    }
}

@Composable
internal fun CalendarActionCard(
    icon: ImageVector?,
    title: String,
    modifier: Modifier = Modifier,
    iconTint: Color? = null,
    description: String? = null,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.colors(
            containerColor = Plain.backgroundCard,
            focusedContainerColor = Plain.focusBackground
        ),
        shape = CardDefaults.shape(RoundedCornerShape(10.dp)),
        scale = CardDefaults.scale(focusedScale = 1f)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(NuvioTheme.spacing.lg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint ?: NuvioTheme.colors.TextPrimary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(NuvioTheme.spacing.md))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = NuvioTheme.colors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!description.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Bits
// ---------------------------------------------------------------------------------------------

@Composable
private fun Thumbnail(url: String?, modifier: Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(NuvioTheme.radii.sm))
            .background(Plain.backgroundCard)
    ) {
        if (url != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(url).crossfade(true).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun MarkerChip(marker: CalendarMarker, inverted: Boolean, large: Boolean = false) {
    if (marker == CalendarMarker.NONE) return
    val colors = NuvioTheme.colors
    val label = stringResource(
        when (marker) {
            CalendarMarker.SERIES_PREMIERE -> R.string.calendar_marker_series_premiere
            CalendarMarker.SEASON_PREMIERE -> R.string.calendar_marker_season_premiere
            CalendarMarker.MID_SEASON -> R.string.calendar_marker_mid_season
            else -> R.string.calendar_marker_finale
        }
    )
    Text(
        text = label.uppercase(),
        style = (if (large) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelSmall)
            .copy(fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp, fontSize = if (large) 11.sp else 9.sp),
        color = if (inverted) Plain.secondary else Plain.onSecondary,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (inverted) Plain.onSecondary else Plain.secondary)
            .padding(horizontal = if (large) 7.dp else 5.dp, vertical = if (large) 3.dp else 1.dp)
    )
}

/** "Thu 15 Oct · S2 E8 · HBO", or for a show without a date "Last episode S3 E10 · 12 Mar 2025". */
@Composable
private fun rowSubtitle(entry: CalendarEntry, locale: Locale): String {
    val ep = entry.episode
    val code = ep?.let { stringResource(R.string.calendar_episode_code, it.season, it.episode) }
    return when {
        entry.date != null -> listOfNotNull(dayLabel(entry.date, locale), code, entry.show.network).joinToString(" · ")
        ep != null -> stringResource(
            R.string.calendar_last_episode,
            listOfNotNull(code, ep.airDate?.let { fullDate(it, locale) }).joinToString(" · ")
        )
        else -> entry.show.network.orEmpty()
    }
}

/** "HBO · 46 min · 3 seasons". */
@Composable
private fun supportLine(show: CalendarShow, episode: CalendarEpisode?): String {
    val parts = ArrayList<String>(3)
    show.network?.let { parts += it }
    episode?.runtimeMinutes?.let { parts += stringResource(R.string.calendar_minutes, it) }
    show.seasons?.takeIf { it > 0 }?.let { parts += pluralStringResource(R.plurals.calendar_seasons, it, it) }
    return parts.joinToString(" · ")
}

@Composable
internal fun relativeDays(days: Long, locale: Locale): String {
    val text = when {
        days <= 0L -> stringResource(R.string.calendar_today)
        days == 1L -> stringResource(R.string.calendar_tomorrow)
        days < 14L -> pluralStringResource(R.plurals.calendar_in_days, days.toInt(), days.toInt())
        days < 60L -> (days / 7).toInt().let { pluralStringResource(R.plurals.calendar_in_weeks, it, it) }
        else -> (days / 30).toInt().let { pluralStringResource(R.plurals.calendar_in_months, it, it) }
    }
    return text.replaceFirstChar { it.titlecase(locale) }
}

internal fun dayLabel(date: LocalDate, locale: Locale): String =
    date.format(DateTimeFormatter.ofPattern("EEE d MMM", locale))

private fun fullDate(date: LocalDate, locale: Locale): String =
    date.format(DateTimeFormatter.ofPattern("d MMM yyyy", locale))
