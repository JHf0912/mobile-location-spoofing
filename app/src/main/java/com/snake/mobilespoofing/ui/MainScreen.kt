package com.snake.mobilespoofing.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.snake.mobilespoofing.R
import com.snake.mobilespoofing.core.geo.EndBehavior
import com.snake.mobilespoofing.core.geo.TravelSpeeds
import com.snake.mobilespoofing.core.model.FavoriteItem
import com.snake.mobilespoofing.ui.components.AppTopBar
import com.snake.mobilespoofing.ui.components.BottomActionBar
import com.snake.mobilespoofing.ui.components.BoundaryNote
import com.snake.mobilespoofing.ui.components.CoordinateInputField
import com.snake.mobilespoofing.ui.components.FavoriteSection
import com.snake.mobilespoofing.ui.components.LiveDiagnosticsCard
import com.snake.mobilespoofing.ui.components.MapCard
import com.snake.mobilespoofing.ui.components.ModeSegmentedControl
import com.snake.mobilespoofing.ui.components.RouteControls
import com.snake.mobilespoofing.ui.components.SectionHeader
import com.snake.mobilespoofing.ui.components.SetupChecklist
import com.snake.mobilespoofing.ui.components.StatusHero
import com.snake.mobilespoofing.ui.theme.AppSpacing
import com.snake.mobilespoofing.ui.theme.appColors

/** All UI events in one object, so [MainScreen] stays stateless and previews stay cheap. */
data class MainActions(
    val onMapPointSelected: (Double, Double) -> Unit = { _, _ -> },
    /** Returns true when the coordinate was accepted, so the field knows whether to clear. */
    val onCoordinateInput: (String) -> Boolean = { false },
    val onModeChanged: (SimulationMode) -> Unit = {},
    val onTravelSpeedChanged: (TravelSpeeds) -> Unit = {},
    val onEndBehaviorChanged: (EndBehavior) -> Unit = {},
    val onUndoWaypoint: () -> Unit = {},
    val onClearRoute: () -> Unit = {},
    /**
     * Saves the named point. The callback runs only after the repository write succeeded, so the
     * text field can clear without losing a name whose save failed.
     */
    val onSaveFavorite: (String, () -> Unit) -> Unit = { _, _ -> },
    val onRemoveFavorite: (Long) -> Unit = {},
    val onPickFavorite: (FavoriteItem) -> Unit = {},
    val onStart: () -> Unit = {},
    val onStop: () -> Unit = {},
    val onRequestPermissions: () -> Unit = {},
    val onOpenDeveloperOptions: () -> Unit = {},
    val onOpenBatterySettings: () -> Unit = {},
    val onOpenNotificationSettings: () -> Unit = {},
)

@Composable
fun MainScreen(
    state: MainUiState,
    actions: MainActions,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val colors = MaterialTheme.appColors
    var favoriteName by rememberSaveable { mutableStateOf("") }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = colors.background,
        topBar = { AppTopBar(state.session) },
        bottomBar = {
            BottomActionBar(
                canStart = state.canStartSession,
                canStop = state.canStop,
                onStart = actions.onStart,
                onStop = actions.onStop,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(
                start = AppSpacing.xl,
                end = AppSpacing.xl,
                top = AppSpacing.xl,
                bottom = AppSpacing.huge,
            ),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.xxl),
        ) {
            item(key = "hero") {
                StaggeredEntrance(index = 0) {
                    StatusHero(state)
                }
            }

            item(key = "location") {
                StaggeredEntrance(index = 1) {
                    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.md)) {
                        SectionHeader(
                            title = stringResource(R.string.section_location),
                            subtitle = stringResource(
                                if (state.mode == SimulationMode.STATIC) {
                                    R.string.section_location_hint_static
                                } else {
                                    R.string.section_location_hint_route
                                },
                            ),
                        )
                        ModeSegmentedControl(
                            selected = state.mode,
                            onSelected = actions.onModeChanged,
                        )
                        MapCard(
                            state = state,
                            onPointSelected = { latLng ->
                                actions.onMapPointSelected(latLng.latitude, latLng.longitude)
                            },
                        )
                        CoordinateInputField(onSubmit = actions.onCoordinateInput)
                    }
                }
            }

            if (state.mode == SimulationMode.ROUTE) {
                item(key = "route") {
                    StaggeredEntrance(index = 2) {
                        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.md)) {
                            SectionHeader(title = stringResource(R.string.section_route))
                            RouteControls(
                                state = state,
                                onSpeedChanged = actions.onTravelSpeedChanged,
                                onEndBehaviorChanged = actions.onEndBehaviorChanged,
                                onUndo = actions.onUndoWaypoint,
                                onClear = actions.onClearRoute,
                            )
                        }
                    }
                }
            }

            item(key = "setup") {
                StaggeredEntrance(index = 3) {
                    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.md)) {
                        SectionHeader(
                            title = stringResource(R.string.section_setup),
                            subtitle = stringResource(R.string.section_setup_hint),
                        )
                        SetupChecklist(
                            permissionGranted = state.permissionGranted,
                            canMock = state.canMock,
                            batteryWhitelisted = state.batteryWhitelisted,
                            notificationsGranted = state.notificationsGranted,
                            onRequestPermission = actions.onRequestPermissions,
                            onOpenDeveloperOptions = actions.onOpenDeveloperOptions,
                            onOpenBatterySettings = actions.onOpenBatterySettings,
                            onOpenNotificationSettings = actions.onOpenNotificationSettings,
                        )
                    }
                }
            }

            item(key = "diagnostics") {
                StaggeredEntrance(index = 4) {
                    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.md)) {
                        SectionHeader(
                            title = stringResource(R.string.section_diagnostics),
                            subtitle = stringResource(R.string.section_diagnostics_hint),
                        )
                        LiveDiagnosticsCard(state)
                    }
                }
            }

            item(key = "boundary-note") {
                StaggeredEntrance(index = 5) {
                    BoundaryNote()
                }
            }

            item(key = "favorites") {
                StaggeredEntrance(index = 6) {
                    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.md)) {
                        SectionHeader(title = stringResource(R.string.section_favorites))
                        FavoriteSection(
                            favorites = state.favorites,
                            favoriteName = favoriteName,
                            onFavoriteNameChange = { favoriteName = it },
                            canSave = state.mode == SimulationMode.STATIC && state.selectedPoint != null,
                            onSave = { name ->
                                // Clear the field only once the save succeeded; on failure the
                                // typed name stays so the user can retry it.
                                actions.onSaveFavorite(name) { favoriteName = "" }
                            },
                            onPick = actions.onPickFavorite,
                            onDelete = actions.onRemoveFavorite,
                        )
                    }
                }
            }

            item(key = "bottom-space") {
                Spacer(Modifier.height(AppSpacing.sm))
            }
        }
    }
}
