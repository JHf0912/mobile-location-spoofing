package com.snake.mobilespoofing.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.snake.mobilespoofing.R
import com.snake.mobilespoofing.core.geo.EndBehavior
import com.snake.mobilespoofing.core.geo.TravelSpeeds
import com.snake.mobilespoofing.ui.MainUiState
import com.snake.mobilespoofing.ui.label
import com.snake.mobilespoofing.ui.theme.AppRadius
import com.snake.mobilespoofing.ui.theme.AppSpacing
import com.snake.mobilespoofing.ui.theme.appColors

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RouteControls(
    state: MainUiState,
    onSpeedChanged: (TravelSpeeds) -> Unit,
    onEndBehaviorChanged: (EndBehavior) -> Unit,
    onUndo: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.appColors

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppRadius.control),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.hairline),
    ) {
        Column(
            modifier = Modifier.padding(AppSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.md),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.route_waypoints, state.routeWaypoints.size),
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.ink,
                )
                Box(Modifier.weight(1f))
                IconButton(
                    onClick = onUndo,
                    enabled = state.routeWaypoints.isNotEmpty(),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.route_undo),
                        tint = colors.muted,
                    )
                }
                IconButton(
                    onClick = onClear,
                    enabled = state.routeWaypoints.isNotEmpty(),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.route_clear),
                        tint = colors.muted,
                    )
                }
            }

            Text(
                text = stringResource(R.string.route_speed),
                style = MaterialTheme.typography.labelSmall,
                color = colors.muted,
            )
            FlowRow(
                maxItemsInEachRow = 2,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.sm),
            ) {
                TravelSpeeds.entries.forEach { speed ->
                    ChoiceChip(
                        label = stringResource(
                            R.string.speed_chip,
                            speed.label(),
                            speed.kilometersPerHour.toInt(),
                        ),
                        selected = speed == state.travelSpeed,
                        onClick = { onSpeedChanged(speed) },
                    )
                }
            }

            Text(
                text = stringResource(R.string.route_end_behavior),
                style = MaterialTheme.typography.labelSmall,
                color = colors.muted,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.sm),
            ) {
                EndBehavior.entries.forEach { behavior ->
                    ChoiceChip(
                        label = behavior.label(),
                        selected = behavior == state.endBehavior,
                        onClick = { onEndBehaviorChanged(behavior) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.appColors
    val shape = RoundedCornerShape(AppRadius.chip)

    Box(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .background(if (selected) colors.cyanSoft else colors.surface, shape)
            .border(
                width = 1.dp,
                color = if (selected) colors.accentCyan else colors.hairline,
                shape = shape,
            )
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.RadioButton,
            )
            .padding(horizontal = AppSpacing.md),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) colors.ink else colors.muted,
        )
    }
}
