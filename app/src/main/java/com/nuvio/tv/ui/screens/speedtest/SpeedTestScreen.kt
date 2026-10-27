@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.speedtest

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.navigation.Screen
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
fun SpeedTestScreen(
    navController: NavHostController,
    viewModel: SpeedTestViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val activity = LocalContext.current as? Activity
    val startFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) { runCatching { startFocus.requestFocus() } }
    BackHandler(enabled = state.running) { viewModel.cancel() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioTheme.colors.Background)
            .padding(horizontal = NuvioTheme.spacing.xxl, vertical = NuvioTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
    ) {
        Text(
            text = stringResource(R.string.speed_test_title),
            style = MaterialTheme.typography.headlineMedium,
            color = NuvioTheme.colors.TextPrimary
        )
        Text(
            text = stringResource(
                when {
                    state.running -> R.string.speed_test_running
                    state.finished -> R.string.speed_test_finished
                    else -> R.string.speed_test_intro
                },
                state.totalMs?.let { "%.1f".format(it / 1000.0) } ?: ""
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.colors.TextSecondary
        )
        Button(
            onClick = {
                if (state.running) {
                    viewModel.cancel()
                } else if (activity != null) {
                    viewModel.start(
                        SpeedTestDriver(
                            activity = activity,
                            navigate = { route -> navController.navigate(route) },
                            returnToTest = { navController.popBackStack(Screen.SpeedTest.route, inclusive = false) }
                        )
                    )
                }
            },
            modifier = Modifier.focusRequester(startFocus),
            colors = ButtonDefaults.colors(
                containerColor = NuvioTheme.colors.BackgroundCard,
                contentColor = NuvioTheme.colors.TextPrimary,
                focusedContainerColor = NuvioTheme.colors.Secondary,
                focusedContentColor = NuvioTheme.colors.OnSecondary
            )
        ) {
            Text(
                stringResource(
                    when {
                        state.running -> R.string.speed_test_stop
                        state.finished -> R.string.speed_test_run_again
                        else -> R.string.speed_test_start
                    }
                )
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = NuvioTheme.spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
        ) {
            items(state.steps, key = { it.id }) { step -> SpeedTestStepRow(step) }
        }
    }
}

@Composable
private fun SpeedTestStepRow(step: SpeedTestStep) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .background(NuvioTheme.colors.BackgroundCard, shape)
            .then(if (focused) Modifier.border(NuvioTheme.focusRing.border(2.dp), shape) else Modifier)
            .padding(horizontal = NuvioTheme.spacing.lg, vertical = NuvioTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(step.id.titleRes),
                style = MaterialTheme.typography.titleMedium,
                color = NuvioTheme.colors.TextPrimary
            )
            Spacer(Modifier.width(NuvioTheme.spacing.md))
            Text(
                text = when (step.status) {
                    SpeedTestStatus.PENDING -> stringResource(R.string.speed_test_status_pending)
                    SpeedTestStatus.RUNNING -> stringResource(R.string.speed_test_status_running)
                    else -> step.headline.orEmpty()
                },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = when (step.status) {
                    SpeedTestStatus.FAILED -> NuvioTheme.colors.Error
                    SpeedTestStatus.DONE -> NuvioTheme.colors.Secondary
                    else -> NuvioTheme.colors.TextTertiary
                }
            )
        }
        step.details.forEach { line ->
            Text(
                text = line,
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextSecondary
            )
        }
    }
}
