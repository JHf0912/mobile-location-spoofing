package com.snake.mobilespoofing.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import com.snake.mobilespoofing.ui.theme.AppRadius
import com.snake.mobilespoofing.ui.theme.AppSize
import com.snake.mobilespoofing.ui.theme.AppSpacing
import com.snake.mobilespoofing.ui.theme.appColors

private data class SetupItem(
    val title: String,
    val done: Boolean,
    val doneText: String,
    val todoText: String,
    val action: () -> Unit,
)

@Composable
fun SetupChecklist(
    permissionGranted: Boolean,
    canMock: Boolean,
    batteryWhitelisted: Boolean,
    notificationsGranted: Boolean,
    onRequestPermission: () -> Unit,
    onOpenDeveloperOptions: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.appColors
    val items = listOf(
        SetupItem(
            title = stringResource(R.string.setup_location),
            done = permissionGranted,
            doneText = stringResource(R.string.setup_location_done),
            todoText = stringResource(R.string.setup_location_todo),
            action = onRequestPermission,
        ),
        SetupItem(
            title = stringResource(R.string.setup_mock_app),
            done = canMock,
            doneText = stringResource(R.string.setup_mock_app_done),
            todoText = stringResource(R.string.setup_mock_app_todo),
            action = onOpenDeveloperOptions,
        ),
        SetupItem(
            title = stringResource(R.string.setup_battery),
            done = batteryWhitelisted,
            doneText = stringResource(R.string.setup_battery_done),
            todoText = stringResource(R.string.setup_battery_todo),
            action = onOpenBatterySettings,
        ),
        SetupItem(
            title = stringResource(R.string.setup_notifications),
            done = notificationsGranted,
            doneText = stringResource(R.string.setup_notifications_done),
            todoText = stringResource(R.string.setup_notifications_todo),
            action = onOpenNotificationSettings,
        ),
    )

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppRadius.control),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.hairline),
    ) {
        Column {
            items.forEachIndexed { index, item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 60.dp)
                        .clickable(role = Role.Button, onClick = item.action)
                        .padding(horizontal = AppSpacing.lg, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(AppSize.checklistBadge)
                            .background(
                                color = if (item.done) colors.accentMint else colors.amberSoft,
                                shape = CircleShape,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (item.done) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = colors.onAccentMint,
                                modifier = Modifier.size(16.dp),
                            )
                        } else {
                            Text(
                                text = "${index + 1}",
                                style = MaterialTheme.typography.labelMedium,
                                color = colors.ink,
                            )
                        }
                    }

                    Spacer(Modifier.width(12.dp))

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(1.dp),
                    ) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = colors.ink,
                        )
                        Text(
                            text = if (item.done) item.doneText else item.todoText,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.muted,
                        )
                    }

                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = stringResource(R.string.setup_open),
                        tint = colors.muted,
                    )
                }

                if (index < items.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 56.dp),
                        color = colors.hairline,
                    )
                }
            }
        }
    }
}
