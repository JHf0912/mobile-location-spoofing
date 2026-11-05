package com.snake.mobilespoofing.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.amap.api.maps.model.LatLng
import com.snake.mobilespoofing.R
import com.snake.mobilespoofing.ui.MainUiState
import com.snake.mobilespoofing.ui.map.AMapPicker
import com.snake.mobilespoofing.ui.theme.AppRadius
import com.snake.mobilespoofing.ui.theme.AppSize
import com.snake.mobilespoofing.ui.theme.appColors

@Composable
fun MapCard(
    state: MainUiState,
    onPointSelected: (LatLng) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showFullScreen by rememberSaveable { mutableStateOf(false) }
    val colors = MaterialTheme.appColors

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(AppSize.mapHeight),
        shape = RoundedCornerShape(AppRadius.card),
        color = colors.surfaceMuted,
        border = BorderStroke(1.dp, colors.hairline),
    ) {
        AMapPicker(
            waypointsWgs84 = state.routeWaypoints,
            selectedPointWgs84 = state.selectedPoint,
            onPointClick = onPointSelected,
            gesturesEnabled = false,
            onPreviewClick = { showFullScreen = true },
            modifier = Modifier.fillMaxSize(),
        )
    }

    if (showFullScreen) {
        MapPickerDialog(
            state = state,
            onPointSelected = onPointSelected,
            onDismiss = { showFullScreen = false },
        )
    }
}

@Composable
private fun MapPickerDialog(
    state: MainUiState,
    onPointSelected: (LatLng) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.appColors

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = colors.background,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.map_dialog_title),
                            style = MaterialTheme.typography.titleLarge,
                            color = colors.ink,
                        )
                        Text(
                            text = stringResource(R.string.map_dialog_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.muted,
                        )
                    }
                    TextButton(onClick = onDismiss) {
                        Text(
                            text = stringResource(R.string.map_dialog_done),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }

                AMapPicker(
                    waypointsWgs84 = state.routeWaypoints,
                    selectedPointWgs84 = state.selectedPoint,
                    onPointClick = onPointSelected,
                    gesturesEnabled = true,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(AppRadius.card)),
                )
            }
        }
    }
}
