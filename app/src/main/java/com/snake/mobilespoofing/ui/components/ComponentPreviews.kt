package com.snake.mobilespoofing.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.snake.mobilespoofing.core.geo.EndBehavior
import com.snake.mobilespoofing.core.geo.TravelSpeeds
import com.snake.mobilespoofing.core.model.CoordType
import com.snake.mobilespoofing.core.model.FavoriteItem
import com.snake.mobilespoofing.core.model.GeoPoint
import com.snake.mobilespoofing.core.model.SessionErrorReason
import com.snake.mobilespoofing.core.model.SessionState
import com.snake.mobilespoofing.ui.MainUiState
import com.snake.mobilespoofing.ui.SimulationMode
import com.snake.mobilespoofing.ui.theme.MockLocationTheme

private val point = GeoPoint(39.9042, 116.4074, CoordType.WGS84)

@Preview(name = "Status pills", showBackground = true, widthDp = 360)
@Composable
private fun StatusPillsPreview() {
    MockLocationTheme {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusPill(SessionState.Idle)
            StatusPill(SessionState.Running(sessionId = 1L, sessionEpochElapsedMs = 1_000L, lastPointWgs84 = point))
            StatusPill(SessionState.Error(sessionId = 1L, reason = SessionErrorReason.EngineStartFailed))
        }
    }
}

@Preview(name = "Mode and actions", showBackground = true, widthDp = 360, heightDp = 220)
@Composable
private fun ModeAndActionsPreview() {
    MockLocationTheme {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ModeSegmentedControl(selected = SimulationMode.ROUTE, onSelected = {})
            BottomActionBar(canStart = true, canStop = true, onStart = {}, onStop = {})
        }
    }
}

@Preview(name = "Coordinate input", showBackground = true, widthDp = 360, heightDp = 200)
@Composable
private fun CoordinateInputPreview() {
    MockLocationTheme {
        CoordinateInputField(
            onSubmit = { true },
            modifier = Modifier.padding(20.dp),
        )
    }
}

@Preview(name = "Setup mixed", showBackground = true, widthDp = 360, heightDp = 340)
@Composable
private fun SetupMixedPreview() {
    MockLocationTheme {
        SetupChecklist(
            permissionGranted = true,
            canMock = false,
            batteryWhitelisted = false,
            notificationsGranted = false,
            onRequestPermission = {},
            onOpenDeveloperOptions = {},
            onOpenBatterySettings = {},
            onOpenNotificationSettings = {},
            modifier = Modifier.padding(20.dp),
        )
    }
}

@Preview(name = "Setup complete", showBackground = true, widthDp = 360, heightDp = 340)
@Composable
private fun SetupCompletePreview() {
    MockLocationTheme {
        SetupChecklist(
            permissionGranted = true,
            canMock = true,
            batteryWhitelisted = true,
            notificationsGranted = true,
            onRequestPermission = {},
            onOpenDeveloperOptions = {},
            onOpenBatterySettings = {},
            onOpenNotificationSettings = {},
            modifier = Modifier.padding(20.dp),
        )
    }
}

@Preview(name = "Favorites populated", showBackground = true, widthDp = 360, heightDp = 360)
@Composable
private fun FavoritesPopulatedPreview() {
    MockLocationTheme {
        FavoriteSection(
            favorites = listOf(
                FavoriteItem(id = 1, name = "办公室", point = point),
                FavoriteItem(
                    id = 2,
                    name = "测试场",
                    point = GeoPoint(31.2304, 121.4737, CoordType.WGS84),
                ),
            ),
            favoriteName = "",
            onFavoriteNameChange = {},
            canSave = true,
            onSave = {},
            onPick = {},
            onDelete = {},
            modifier = Modifier.padding(20.dp),
        )
    }
}

@Preview(name = "Route controls", showBackground = true, widthDp = 360, heightDp = 440)
@Composable
private fun RouteControlsPreview() {
    MockLocationTheme {
        RouteControls(
            state = MainUiState(
                mode = SimulationMode.ROUTE,
                routeWaypoints = listOf(
                    point,
                    GeoPoint(39.9080, 116.4130, CoordType.WGS84),
                ),
                travelSpeed = TravelSpeeds.CYCLING,
                endBehavior = EndBehavior.LOOP,
            ),
            onSpeedChanged = {},
            onEndBehaviorChanged = {},
            onUndo = {},
            onClear = {},
            modifier = Modifier.padding(20.dp),
        )
    }
}

@Preview(
    name = "Components dark",
    showBackground = true,
    widthDp = 360,
    heightDp = 420,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun ComponentsDarkPreview() {
    MockLocationTheme {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StatusPill(SessionState.Running(sessionId = 1L, sessionEpochElapsedMs = 1_000L, lastPointWgs84 = point))
            ModeSegmentedControl(selected = SimulationMode.STATIC, onSelected = {})
            SetupChecklist(
                permissionGranted = true,
                canMock = false,
                batteryWhitelisted = true,
                notificationsGranted = true,
                onRequestPermission = {},
                onOpenDeveloperOptions = {},
                onOpenBatterySettings = {},
                onOpenNotificationSettings = {},
            )
        }
    }
}
