package com.nuvio.tv.ui.screens.calendar

import com.nuvio.tv.ui.screens.player.rememberBetaPerformanceSettings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.nuvio.tv.R
import com.nuvio.tv.core.calendar.CalendarDue
import com.nuvio.tv.core.calendar.CalendarRepository
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.navigation.Screen
import com.nuvio.tv.ui.newlook.NewLookNotifications
import com.nuvio.tv.ui.theme.NuvioTheme
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.Locale

@EntryPoint
@InstallIn(SingletonComponent::class)
interface CalendarEntryPoint {
    fun calendarRepository(): CalendarRepository
    fun profileManager(): ProfileManager
}

/** Leaves Home's first frames and its own focus work alone before looking. */
private const val CHECK_DELAY_MS = 2_500L

/**
 * "Remind me", delivered: the first time Home shows after the app starts (and after a profile switch), the
 * reminded shows whose new episode is out are listed in a dialog, once per episode. A TV has no notification
 * shade worth the name, so Home is where they'd be seen.
 */
@Composable
fun CalendarReminderHost(navController: NavHostController) {
    val beta by rememberBetaPerformanceSettings()
    val appContext = LocalContext.current.applicationContext
    val entryPoint = remember(appContext) {
        EntryPointAccessors.fromApplication(appContext, CalendarEntryPoint::class.java)
    }
    val repository = entryPoint.calendarRepository()
    val profileId by entryPoint.profileManager().activeProfileId.collectAsState()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val onHome = backStackEntry?.destination?.route == Screen.Home.route
    val scope = rememberCoroutineScope()

    var checkedProfile by remember { mutableIntStateOf(-1) }
    var due by remember { mutableStateOf<List<CalendarDue>>(emptyList()) }
    // The new look announces them as notifications instead of a dialog (unless notifications are off).
    val asNotifications = beta.notificationStyle != com.nuvio.tv.data.local.NotificationStyle.OFF
    val context = LocalContext.current

    LaunchedEffect(onHome, profileId) {
        if (!onHome || checkedProfile == profileId) return@LaunchedEffect
        delay(CHECK_DELAY_MS)
        // Leaving Home cancels this; it's tried again the next time Home shows.
        val found = try {
            repository.dueReminders()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        checkedProfile = profileId
        if (!asNotifications) {
            due = found
            return@LaunchedEffect
        }
        found.forEach { item ->
            NewLookNotifications.post(
                NewLookNotifications.Kind.REMINDER,
                context.getString(R.string.notif_reminder_title),
                context.getString(R.string.notif_reminder_text, item.show.title, episodeCode(context, item))
            )
        }
        withContext(NonCancellable) { repository.markNotified(found) }
        val today = runCatching { repository.airingToday() }.getOrDefault(emptyList())
        today.firstOrNull()?.let { first ->
            val code = episodeCode(context, first)
            NewLookNotifications.post(
                NewLookNotifications.Kind.CALENDAR,
                context.getString(R.string.notif_new_episode_title),
                if (today.size == 1) context.getString(R.string.notif_new_episode_text, first.show.title, code)
                else context.resources.getQuantityString(R.plurals.notif_new_episode_more, today.size - 1, first.show.title, code, today.size - 1)
            )
        }
    }

    if (due.isEmpty() || !onHome) return
    val shown = due
    fun close() {
        due = emptyList()
        scope.launch { withContext(NonCancellable) { repository.markNotified(shown) } }
    }

    DueDialog(
        due = shown,
        onDismiss = { close() },
        onOpen = { item ->
            close()
            navController.navigate(
                Screen.Detail.createRoute(
                    itemId = item.show.id,
                    itemType = "series",
                    addonBaseUrl = item.show.addonBaseUrl,
                    returnFocusSeason = item.episode.season,
                    returnFocusEpisode = item.episode.episode
                )
            )
        }
    )
}

private fun episodeCode(context: android.content.Context, due: CalendarDue): String =
    context.getString(R.string.calendar_episode_code, due.episode.season, due.episode.episode)

@Composable
private fun DueDialog(
    due: List<CalendarDue>,
    onDismiss: () -> Unit,
    onOpen: (CalendarDue) -> Unit
) {
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val today = LocalDate.now()
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        repeat(2) { withFrameNanos { } }
        runCatching { firstFocus.requestFocus() }
    }
    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.calendar_due_title),
        subtitle = stringResource(R.string.calendar_due_subtitle),
        width = 480.dp
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
        ) {
            items(due, key = { it.show.id }) { item ->
                val ep = item.episode
                val date = ep.airDate
                val code = stringResource(R.string.calendar_episode_code, ep.season, ep.episode)
                val whenOut = if (date == null || !date.isBefore(today)) {
                    stringResource(R.string.calendar_due_today)
                } else {
                    stringResource(R.string.calendar_due_since, dayLabel(date, locale))
                }
                CalendarActionCard(
                    icon = Icons.Filled.NotificationsActive,
                    iconTint = ReminderGreen,
                    title = item.show.title,
                    description = listOfNotNull(code, ep.name, whenOut).joinToString(" · "),
                    modifier = if (item == due.first()) Modifier.focusRequester(firstFocus) else Modifier,
                    onClick = { onOpen(item) }
                )
            }
        }
        CalendarActionCard(
            icon = null,
            title = stringResource(R.string.calendar_due_dismiss),
            onClick = onDismiss
        )
    }
}
