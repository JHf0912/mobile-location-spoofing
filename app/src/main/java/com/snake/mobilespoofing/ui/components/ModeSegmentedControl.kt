package com.snake.mobilespoofing.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.snake.mobilespoofing.R
import com.snake.mobilespoofing.ui.SimulationMode
import com.snake.mobilespoofing.ui.theme.AppRadius
import com.snake.mobilespoofing.ui.theme.appColors

private val SegmentShape = RoundedCornerShape(13.dp)
private val SegmentGap = 4.dp

/**
 * iOS-style segmented control: one white thumb slides between two labels.
 *
 * Deliberately has **no ripple**. Material's default pressed indication paints the whole segment
 * gray, and because the thumb is part of that segment the pill itself flashes gray on every tap —
 * it reads as "disabled", not as feedback. The thumb movement is the feedback here, backed by a
 * subtle label scale while the finger is down and a haptic tick when the selection actually
 * changes. Tapping the already-selected segment does nothing at all.
 */
@Composable
fun ModeSegmentedControl(
    selected: SimulationMode,
    onSelected: (SimulationMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.appColors
    val modes = SimulationMode.entries
    val selectedIndex = modes.indexOf(selected).coerceAtLeast(0)
    val haptics = LocalHapticFeedback.current

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(AppRadius.control))
            .background(colors.surfaceMuted)
            .padding(SegmentGap),
    ) {
        val segmentWidth = (maxWidth - SegmentGap) / modes.size.toFloat()
        val indicatorOffset by animateDpAsState(
            targetValue = (segmentWidth + SegmentGap) * selectedIndex.toFloat(),
            // Spring, not tween: the extra settle at the end makes the thumb feel physical instead
            // of like a linear fade. It also keeps a re-tap mid-animation continuous.
            animationSpec = spring(dampingRatio = 0.9f, stiffness = 600f),
            label = "modeIndicatorOffset",
        )

        Box(
            modifier = Modifier
                .offset(x = indicatorOffset)
                .width(segmentWidth)
                .fillMaxHeight()
                .shadow(elevation = 2.dp, shape = SegmentShape, clip = false)
                .clip(SegmentShape)
                .background(colors.surfaceElevated),
        )

        Row(
            modifier = Modifier
                .fillMaxSize()
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(SegmentGap),
        ) {
            modes.forEach { mode ->
                val isSelected = mode == selected
                val interactionSource = remember { MutableInteractionSource() }
                val pressed by interactionSource.collectIsPressedAsState()
                // Press feedback lives on the label, never on the thumb: scaling the text a few
                // percent is visible but cannot turn the pill gray.
                val labelScale by animateFloatAsState(
                    targetValue = if (pressed) 0.94f else 1f,
                    animationSpec = tween(durationMillis = 120),
                    label = "modeLabelScale",
                )
                val labelColor by animateColorAsState(
                    targetValue = if (isSelected) colors.ink else colors.muted,
                    animationSpec = tween(durationMillis = 160),
                    label = "modeLabel",
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .selectable(
                            selected = isSelected,
                            interactionSource = interactionSource,
                            indication = null,
                            role = Role.Tab,
                            onClick = {
                                if (!isSelected) {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onSelected(mode)
                                }
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(
                            if (mode == SimulationMode.STATIC) R.string.mode_static else R.string.mode_route,
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        color = labelColor,
                        modifier = Modifier.graphicsLayer {
                            scaleX = labelScale
                            scaleY = labelScale
                        },
                    )
                }
            }
        }
    }
}
