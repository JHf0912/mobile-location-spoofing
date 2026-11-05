package com.snake.mobilespoofing.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.snake.mobilespoofing.R
import com.snake.mobilespoofing.ui.theme.AppRadius
import com.snake.mobilespoofing.ui.theme.AppSize
import com.snake.mobilespoofing.ui.theme.AppSpacing
import com.snake.mobilespoofing.ui.theme.appColors

@Composable
fun BottomActionBar(
    canStart: Boolean,
    canStop: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.appColors

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = colors.surface,
        shadowElevation = 12.dp,
    ) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            HorizontalDivider(color = colors.hairline)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.xl, vertical = AppSpacing.md),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
            ) {
                Button(
                    onClick = onStart,
                    enabled = canStart,
                    modifier = Modifier
                        .weight(1f)
                        .height(AppSize.primaryButton),
                    shape = RoundedCornerShape(AppRadius.control),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.primary,
                        contentColor = colors.onPrimary,
                        disabledContainerColor = colors.surfaceMuted,
                        disabledContentColor = colors.muted,
                    ),
                ) {
                    Text(
                        text = stringResource(R.string.action_start),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }

                OutlinedButton(
                    onClick = onStop,
                    enabled = canStop,
                    modifier = Modifier
                        .weight(1f)
                        .height(AppSize.primaryButton),
                    shape = RoundedCornerShape(AppRadius.control),
                    border = BorderStroke(1.dp, colors.hairline),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = colors.ink,
                        disabledContentColor = colors.muted,
                    ),
                ) {
                    Text(
                        text = stringResource(R.string.action_stop),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}
