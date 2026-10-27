package com.nuvio.tv.ui.screens.detail

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.nuvio.tv.R
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.NotificationStyle
import com.nuvio.tv.data.local.ReleaseRemindersStore
import com.nuvio.tv.ui.navigation.Screen
import com.nuvio.tv.ui.newlook.NewLookNotifications
import com.nuvio.tv.ui.screens.player.rememberBetaPerformanceSettings
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ReleaseReminderEntryPoint {
    fun releaseRemindersStore(): ReleaseRemindersStore
    fun profileManager(): ProfileManager
}

/** Leaves Home's first frames alone before looking (as the Calendar's reminders do). */
private const val CHECK_DELAY_MS = 3_000L

/**
 * Delivers the details page's "Remind me": the first time Home shows after the app starts (and after a profile
 * switch), each episode or movie whose day has come is announced once, then forgotten.
 */
@Composable
fun ReleaseReminderHost(navController: NavHostController) {
    val beta by rememberBetaPerformanceSettings()
    val context = LocalContext.current
    val entryPoint = remember(context) {
        EntryPointAccessors.fromApplication(context.applicationContext, ReleaseReminderEntryPoint::class.java)
    }
    val store = entryPoint.releaseRemindersStore()
    val profileId by entryPoint.profileManager().activeProfileId.collectAsState()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val onHome = backStackEntry?.destination?.route == Screen.Home.route
    var checkedProfile by remember { mutableIntStateOf(-1) }

    LaunchedEffect(onHome, profileId) {
        if (!onHome || checkedProfile == profileId) return@LaunchedEffect
        delay(CHECK_DELAY_MS)
        val due = try {
            store.due()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        checkedProfile = profileId
        if (due.isEmpty()) return@LaunchedEffect
        val asNotifications = beta.notificationStyle != NotificationStyle.OFF
        due.forEach { r ->
            val text = if (r.season != null && r.episode != null) {
                context.getString(R.string.notif_reminder_text, r.title, context.getString(R.string.nd_episode_code, r.season, r.episode))
            } else {
                context.getString(R.string.nd_reminder_movie_text, r.title)
            }
            if (asNotifications) {
                NewLookNotifications.post(NewLookNotifications.Kind.REMINDER, context.getString(R.string.notif_reminder_title), text)
            } else {
                Toast.makeText(context, text, Toast.LENGTH_LONG).show()
            }
        }
        withContext(NonCancellable) { store.remove(due.map { it.key }) }
    }
}
