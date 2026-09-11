package com.snake.mobilespoofing.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.snake.mobilespoofing.core.data.FavoriteRepository
import com.snake.mobilespoofing.core.geo.CoordinateTransformer
import com.snake.mobilespoofing.core.geo.EndBehavior
import com.snake.mobilespoofing.core.geo.MovementSpec
import com.snake.mobilespoofing.core.geo.TravelSpeeds
import com.snake.mobilespoofing.core.location.MockPermissionChecker
import com.snake.mobilespoofing.core.location.MockStatusStore
import com.snake.mobilespoofing.core.model.CoordType
import com.snake.mobilespoofing.core.model.FavoriteItem
import com.snake.mobilespoofing.core.model.GeoPoint
import com.snake.mobilespoofing.domain.StartMockUseCase
import com.snake.mobilespoofing.domain.StopMockUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class MainViewModel @Inject constructor(
    private val favoriteRepository: FavoriteRepository,
    private val startMockUseCase: StartMockUseCase,
    private val stopMockUseCase: StopMockUseCase,
    private val statusStore: MockStatusStore,
    private val permissionChecker: MockPermissionChecker,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        observeStatus()
        observeFavorites()
        refreshCapabilities()
    }

    /**
     * Map taps arrive in GCJ02 — that is AMap's datum — and are converted immediately, so every
     * position held in state is WGS84.
     */
    fun onMapPointSelected(latitude: Double, longitude: Double) {
        val wgs84 = CoordinateTransformer.toWgs84(GeoPoint(latitude, longitude, CoordType.GCJ02))
        _uiState.update { state ->
            when (state.mode) {
                SimulationMode.STATIC -> state.copy(selectedPoint = wgs84, message = null)
                SimulationMode.ROUTE -> state.copy(
                    routeWaypoints = state.routeWaypoints + wgs84,
                    message = null,
                )
            }
        }
    }

    fun onModeChanged(mode: SimulationMode) {
        _uiState.update { it.copy(mode = mode, message = null) }
    }

    fun onTravelSpeedChanged(speed: TravelSpeeds) {
        _uiState.update { it.copy(travelSpeed = speed) }
    }

    fun onEndBehaviorChanged(behavior: EndBehavior) {
        _uiState.update { it.copy(endBehavior = behavior) }
    }

    fun onUndoLastWaypoint() {
        _uiState.update { state ->
            if (state.routeWaypoints.isEmpty()) state
            else state.copy(routeWaypoints = state.routeWaypoints.dropLast(1))
        }
    }

    fun onClearRoute() {
        _uiState.update { it.copy(routeWaypoints = emptyList(), message = null) }
    }

    fun saveFavorite(name: String) {
        val target = _uiState.value.selectedPoint ?: run {
            _uiState.update { it.copy(message = "请先选择位置") }
            return
        }
        viewModelScope.launch {
            favoriteRepository.save(name, target)
            _uiState.update { it.copy(message = "已收藏") }
        }
    }

    fun removeFavorite(id: Long) {
        viewModelScope.launch { favoriteRepository.delete(id) }
    }

    fun pickFavorite(item: FavoriteItem) {
        _uiState.update { it.copy(selectedPoint = item.point, message = null) }
    }

    fun startMock() {
        val state = _uiState.value
        if (!state.permissionGranted) {
            _uiState.update { it.copy(message = "请先授予定位权限") }
            return
        }
        if (!state.canMock) {
            _uiState.update { it.copy(message = "请在开发者选项中将本应用设为模拟位置应用") }
            return
        }

        val spec = buildSpec(state) ?: run {
            _uiState.update {
                it.copy(
                    message = when (state.mode) {
                        SimulationMode.STATIC -> "请先在地图上选点"
                        SimulationMode.ROUTE -> "路线至少需要 ${MainUiState.MIN_ROUTE_WAYPOINTS} 个途经点"
                    },
                )
            }
            return
        }

        startMockUseCase(spec)
    }

    private fun buildSpec(state: MainUiState): MovementSpec? = when (state.mode) {
        SimulationMode.STATIC -> state.selectedPoint?.let(MovementSpec::Static)

        SimulationMode.ROUTE ->
            if (state.routeWaypoints.size >= MainUiState.MIN_ROUTE_WAYPOINTS) {
                MovementSpec.RouteSpec(
                    waypointsWgs84 = state.routeWaypoints,
                    speedMetersPerSecond = state.travelSpeed.metersPerSecond,
                    endBehavior = state.endBehavior,
                    closedLoop = state.endBehavior == EndBehavior.LOOP,
                )
            } else {
                null
            }
    }

    fun stopMock() {
        stopMockUseCase()
    }

    fun refreshCapabilities() {
        val hasLocationPermission = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val canMock = permissionChecker.canMockLocation()
        _uiState.update { it.copy(permissionGranted = hasLocationPermission, canMock = canMock) }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    private fun observeStatus() {
        viewModelScope.launch {
            statusStore.status.collect { status ->
                _uiState.update { it.copy(status = status) }
            }
        }
    }

    private fun observeFavorites() {
        viewModelScope.launch {
            favoriteRepository.observeAll().collect { favorites ->
                _uiState.update { it.copy(favorites = favorites) }
            }
        }
    }
}
