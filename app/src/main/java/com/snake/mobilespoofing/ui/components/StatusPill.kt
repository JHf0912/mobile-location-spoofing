package com.snake.mobilespoofing.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.snake.mobilespoofing.R
import com.snake.mobilespoofing.core.model.SessionPhase
import com.snake.mobilespoofing.core.model.SessionState
import com.snake.mobilespoofing.ui.rememberMotionEnabled
import com.snake.mobilespoofing.ui.theme.AppSize
import com.snake.mobilespoofing.ui.theme.appColors

private enum class StatusTone { Idle, Starting, Running, Degraded, Stopping, Error }

private fun SessionState.tone(): StatusTone = when (this) {
    SessionState.Idle -> StatusTone.Idle
    is SessionState.Starting -> StatusTone.Starting
    is SessionState.Running -> StatusTone.Running
    is SessionState.Degraded -> StatusTone.Degraded
    is SessionState.Stopping -> StatusTone.Stopping
    is SessionState.Error -> StatusTone.Error
}

@Composable
private fun toneColor(tone: StatusTone): Color {
    val colors = MaterialTheme.appColors
    return when (tone) {
        StatusTone.Idle, StatusTone.Stopping -> colors.muted
        StatusTone.Starting -> colors.accentCyan
        // Degraded is still a running session (audit F8): amber, not the terminal danger tone.
        StatusTone.Running, StatusTone.Degraded -> colors.accentAmber
        StatusTone.Error -> colors.danger
    }
}

@Composable
private fun toneLabel(tone: StatusTone): String = stringResource(
    when (tone) {
        StatusTone.Idle -> R.string.status_idle
        StatusTone.Starting -> R.string.status_starting
        StatusTone.Running -> R.string.status_running
        StatusTone.Degraded -> R.string.status_degraded
        StatusTone.Stopping -> R.string.status_stopping
        StatusTone.Error -> R.string.status_error
    },
)

@Composable
fun StatusPill(session: SessionState, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.appColors
    val tone = session.tone()
    val color = toneColor(tone)

    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = color.copy(alpha = 0.14f),
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.28f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(color = color, pulsing = session.phase == SessionPhase.Running)
            Text(
                text = toneLabel(tone),
                modifier = Modifier.padding(start = 6.dp),
                style = MaterialTheme.typography.labelMedium,
                color = colors.ink,
            )
        }
    }
}

@Composable
fun StatusDot(
    color: Color,
    pulsing: Boolean,
    modifier: Modifier = Modifier,
) {
    val motionEnabled = rememberMotionEnabled()
    val alpha = if (pulsing && motionEnabled) {
        val transition = rememberInfiniteTransition(label = "statusDot")
        transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 800, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "statusDotAlpha",
        ).value
    } else {
        1f
    }

    Box(
        modifier = modifier
            .size(AppSize.statusDot)
            .graphicsLayer { this.alpha = alpha }
            .background(color, CircleShape),
    )
}
