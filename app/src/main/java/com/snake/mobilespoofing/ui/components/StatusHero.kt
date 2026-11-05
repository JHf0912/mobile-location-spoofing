package com.snake.mobilespoofing.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.snake.mobilespoofing.R
import com.snake.mobilespoofing.core.model.SessionState
import com.snake.mobilespoofing.degradedReasonRes
import com.snake.mobilespoofing.errorReasonRes
import com.snake.mobilespoofing.ui.MainUiState
import com.snake.mobilespoofing.ui.SimulationMode
import com.snake.mobilespoofing.ui.formatPoint
import com.snake.mobilespoofing.ui.label
import com.snake.mobilespoofing.ui.rememberElapsedLabel
import com.snake.mobilespoofing.ui.rememberMotionEnabled
import com.snake.mobilespoofing.ui.theme.appColors

@Composable
fun StatusHero(
    state: MainUiState,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.appColors
    val session = state.session

    // The last injected point is the most useful position to show; before the first push (and
    // while Stopping/Error, whose states carry no point) fall back to the selected draft.
    val point = when (session) {
        is SessionState.Running -> session.lastPointWgs84
        is SessionState.Degraded -> session.lastPointWgs84
        SessionState.Idle,
        is SessionState.Starting,
        is SessionState.Stopping,
        is SessionState.Error,
        -> null
    } ?: state.selectedPoint
    val coordinates = point?.let { formatPoint(it.latitude, it.longitude) }
        ?: stringResource(R.string.value_dash)

    val titleRes = when (session) {
        SessionState.Idle -> if (state.selectedPoint != null) {
            R.string.status_ready
        } else {
            R.string.status_not_started
        }
        is SessionState.Starting -> R.string.status_starting
        is SessionState.Running -> R.string.status_running
        is SessionState.Degraded -> R.string.status_degraded
        is SessionState.Stopping -> R.string.status_stopping
        is SessionState.Error -> R.string.status_error
    }
    val title = stringResource(titleRes)

    val reasonRes: Int? = when (session) {
        is SessionState.Degraded -> degradedReasonRes(session.reason)
        is SessionState.Error -> errorReasonRes(session.reason)
        SessionState.Idle,
        is SessionState.Starting,
        is SessionState.Running,
        is SessionState.Stopping,
        -> null
    }
    val reasonText = reasonRes?.let { stringResource(it) }
    val reasonColor = if (session is SessionState.Error) colors.danger else colors.muted

    val sessionEpochElapsedMs = when (session) {
        is SessionState.Running -> session.sessionEpochElapsedMs
        is SessionState.Degraded -> session.sessionEpochElapsedMs
        is SessionState.Stopping -> session.sessionEpochElapsedMs
        SessionState.Idle,
        is SessionState.Starting,
        is SessionState.Error,
        -> null
    }
    val elapsed = rememberElapsedLabel(sessionEpochElapsedMs)
    val modeLabel = stringResource(
        if (state.mode == SimulationMode.STATIC) R.string.status_mode_static else R.string.status_mode_route,
    )
    val waypointsLabel = if (state.mode == SimulationMode.ROUTE) {
        stringResource(R.string.status_waypoints, state.routeWaypoints.size)
    } else {
        null
    }
    val speedLabel = if (state.mode == SimulationMode.ROUTE) {
        "${state.travelSpeed.label()} ${state.travelSpeed.kilometersPerHour.toInt()} km/h"
    } else {
        null
    }
    val elapsedLabel = if (elapsed != null) {
        stringResource(R.string.status_elapsed, elapsed)
    } else {
        null
    }
    val meta = listOfNotNull(modeLabel, waypointsLabel, speedLabel, elapsedLabel).joinToString(" · ")

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.hero_overline),
            style = MaterialTheme.typography.labelSmall,
            color = colors.muted,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = if (session is SessionState.Error) colors.danger else colors.ink,
        )
        Spacer(Modifier.height(6.dp))

        if (rememberMotionEnabled()) {
            AnimatedContent(
                targetState = coordinates,
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                label = "coordinates",
            ) { value ->
                CoordinateText(value = value, hasPoint = point != null)
            }
        } else {
            CoordinateText(value = coordinates, hasPoint = point != null)
        }

        Spacer(Modifier.height(6.dp))
        Text(
            text = meta,
            style = MaterialTheme.typography.bodySmall,
            color = colors.muted,
        )
        if (reasonText != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = reasonText,
                style = MaterialTheme.typography.bodySmall,
                color = reasonColor,
            )
        }
    }
}

@Composable
private fun CoordinateText(value: String, hasPoint: Boolean) {
    val colors = MaterialTheme.appColors
    Text(
        text = value,
        style = if (hasPoint) {
            MaterialTheme.typography.displaySmall
        } else {
            MaterialTheme.typography.headlineMedium
        },
        color = if (hasPoint) colors.ink else colors.muted,
    )
}
