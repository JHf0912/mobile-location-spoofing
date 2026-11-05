package com.snake.mobilespoofing.ui

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.snake.mobilespoofing.core.geo.EndBehavior
import com.snake.mobilespoofing.core.geo.TravelSpeeds
import com.snake.mobilespoofing.core.model.CoordType
import com.snake.mobilespoofing.core.model.FavoriteItem
import com.snake.mobilespoofing.core.model.GeoPoint
import com.snake.mobilespoofing.core.model.SessionErrorReason
import com.snake.mobilespoofing.core.model.SessionState
import com.snake.mobilespoofing.ui.theme.MockLocationTheme

private val previewPoint = GeoPoint(39.9042, 116.4074, CoordType.WGS84)

// A fixed start keeps previews deterministic. rememberElapsedLabel ignores it during inspection
// and renders a fixed label, so the value itself never reaches the rendered preview.
private const val PREVIEW_START_MS = 0L

private val previewFavorites = listOf(
    FavoriteItem(id = 1, name = "办公室", point = previewPoint),
    FavoriteItem(
        id = 2,
        name = "测试场",
        point = GeoPoint(31.2304, 121.4737, CoordType.WGS84),
    ),
)

@Preview(name = "Idle", showBackground = true, widthDp = 360, heightDp = 820)
@Composable
private fun IdlePreview() {
    PreviewScreen(
        MainUiState(
            selectedPoint = previewPoint,
            permissionGranted = true,
            canMock = true,
            batteryWhitelisted = true,
            notificationsGranted = true,
            favorites = previewFavorites,
        ),
    )
}

@Preview(name = "Running", showBackground = true, widthDp = 360, heightDp = 820)
@Composable
private fun RunningPreview() {
    PreviewScreen(
        MainUiState(
            mode = SimulationMode.ROUTE,
            selectedPoint = previewPoint,
            routeWaypoints = listOf(
                previewPoint,
                GeoPoint(39.9080, 116.4130, CoordType.WGS84),
                GeoPoint(39.9110, 116.4200, CoordType.WGS84),
            ),
            travelSpeed = TravelSpeeds.CYCLING,
            endBehavior = EndBehavior.LOOP,
            session = SessionState.Running(
                sessionId = 1L,
                sessionEpochElapsedMs = PREVIEW_START_MS,
                lastPointWgs84 = previewPoint,
            ),
            permissionGranted = true,
            canMock = true,
            batteryWhitelisted = true,
            notificationsGranted = true,
        ),
    )
}

@Preview(name = "Error", showBackground = true, widthDp = 360, heightDp = 820)
@Composable
private fun ErrorPreview() {
    PreviewScreen(
        MainUiState(
            selectedPoint = previewPoint,
            session = SessionState.Error(
                sessionId = 1L,
                reason = SessionErrorReason.EngineStartFailed,
                terminal = true,
            ),
            permissionGranted = true,
            canMock = true,
            batteryWhitelisted = false,
            notificationsGranted = true,
        ),
    )
}

@Preview(name = "Permission missing", showBackground = true, widthDp = 360, heightDp = 820)
@Composable
private fun PermissionMissingPreview() {
    PreviewScreen(MainUiState())
}

@Preview(
    name = "Dark",
    showBackground = true,
    widthDp = 360,
    heightDp = 820,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun DarkPreview() {
    PreviewScreen(
        MainUiState(
            selectedPoint = previewPoint,
            permissionGranted = true,
            canMock = true,
            batteryWhitelisted = true,
            notificationsGranted = true,
            favorites = previewFavorites,
        ),
    )
}

@Composable
private fun PreviewScreen(state: MainUiState) {
    MockLocationTheme {
        MainScreen(state = state, actions = MainActions())
    }
}
