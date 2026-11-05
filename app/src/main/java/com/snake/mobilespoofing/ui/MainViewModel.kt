package com.snake.mobilespoofing.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.snake.mobilespoofing.R
import com.snake.mobilespoofing.core.data.FavoriteRepository
import com.snake.mobilespoofing.core.geo.CoordinateTransformer
import com.snake.mobilespoofing.core.geo.EndBehavior
import com.snake.mobilespoofing.core.geo.TravelSpeeds
import com.snake.mobilespoofing.core.location.CurrentLocationSource
import com.snake.mobilespoofing.core.location.MockPermissionChecker
import com.snake.mobilespoofing.core.location.MockStatusStore
import com.snake.mobilespoofing.core.location.SessionConfigStore
import com.snake.mobilespoofing.core.model.CoordType
import com.snake.mobilespoofing.core.model.FavoriteItem
import com.snake.mobilespoofing.core.model.GeoPoint
import com.snake.mobilespoofing.core.model.canStartNewSession
import com.snake.mobilespoofing.core.model.isSessionActive
import com.snake.mobilespoofing.domain.StartMockUseCase
import com.snake.mobilespoofing.domain.StopMockUseCase
import com.snake.mobilespoofing.ui.input.CoordinateParseError
import com.snake.mobilespoofing.ui.input.CoordinateParseResult
import com.snake.mobilespoofing.ui.input.parseCoordinateInput
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
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
    private val currentLocationSource: CurrentLocationSource,
    private val sessionConfigStore: SessionConfigStore,
    private val savedStateHandle: SavedStateHandle,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    /** The startup fix is requested at most once per ViewModel, never on every resume. */
    private var defaultLocationRequested = false

    init {
        // Restore before the collectors start: the session collector emits immediately, and a
        // restore afterwards would overwrite whatever it just published.
        restoreEditorState()
        observeSession()
        observeFavorites()
        refreshCapabilities()
    }

    /**
     * Map taps arrive in GCJ02 — that is AMap's datum — and are converted immediately, so every
     * position held in state is WGS84.
     */
    fun onMapPointSelected(latitude: Double, longitude: Double) {
        val wgs84 = CoordinateTransformer.toWgs84(GeoPoint(latitude, longitude, CoordType.GCJ02))
        updateEditorState { state ->
            when (state.mode) {
                SimulationMode.STATIC -> state.copy(selectedPoint = wgs84, message = null)
                SimulationMode.ROUTE -> state.copy(
                    routeWaypoints = state.appendedWaypoint(wgs84),
                    message = null,
                )
            }
        }
    }

    fun onModeChanged(mode: SimulationMode) {
        updateEditorState { it.copy(mode = mode, message = null) }
    }

    /**
     * Applies a typed or pasted coordinate. Plain `lat,lon` is WGS84 (the parser's default);
     * `geo:` forms are WGS84 by definition. This is a separate entry point from
     * [onMapPointSelected], whose coordinates are GCJ02 from AMap.
     *
     * Returns true only when the input was accepted, so the field can keep the text (and the
     * user can fix it) after a rejection.
     */
    fun onCoordinateInput(raw: String): Boolean {
        val point = when (val result = parseCoordinateInput(raw)) {
            is CoordinateParseResult.Ok -> result.point

            is CoordinateParseResult.Invalid -> {
                _uiState.update { it.copy(message = appContext.getString(result.reason.messageRes())) }
                return false
            }
        }

        // A full route must not report success: the field would clear and the user would think
        // the coordinate was added. Keep the text and explain the cap instead.
        val current = _uiState.value
        if (current.mode == SimulationMode.ROUTE &&
            appendRouteWaypoint(current.routeWaypoints, point) is RouteAppendResult.Full
        ) {
            _uiState.update {
                it.copy(
                    message = appContext.getString(
                        R.string.msg_route_full,
                        EditorSnapshot.MAX_ROUTE_WAYPOINTS,
                    ),
                )
            }
            return false
        }

        updateEditorState { state ->
            when (state.mode) {
                SimulationMode.STATIC -> state.copy(selectedPoint = point, message = null)
                SimulationMode.ROUTE -> when (val append = appendRouteWaypoint(state.routeWaypoints, point)) {
                    is RouteAppendResult.Appended -> state.copy(
                        routeWaypoints = append.waypoints,
                        message = null,
                    )
                    // The pre-check above already rejected a full route; keep the state unchanged
                    // if an editor change still races us here.
                    RouteAppendResult.Full -> state
                }
            }
        }
        return true
    }

    private fun CoordinateParseError.messageRes(): Int = when (this) {
        CoordinateParseError.Empty -> R.string.msg_coordinate_empty
        CoordinateParseError.Malformed -> R.string.msg_coordinate_malformed
        CoordinateParseError.OutOfRange -> R.string.msg_coordinate_out_of_range
        CoordinateParseError.NotFinite -> R.string.msg_coordinate_not_finite
    }

    fun onTravelSpeedChanged(speed: TravelSpeeds) {
        updateEditorState { it.copy(travelSpeed = speed) }
    }

    fun onEndBehaviorChanged(behavior: EndBehavior) {
        updateEditorState { it.copy(endBehavior = behavior) }
    }

    fun onUndoLastWaypoint() {
        updateEditorState { state ->
            if (state.routeWaypoints.isEmpty()) state
            else state.copy(routeWaypoints = state.routeWaypoints.dropLast(1))
        }
    }

    fun onClearRoute() {
        updateEditorState { it.copy(routeWaypoints = emptyList(), message = null) }
    }

    fun saveFavorite(name: String, onSaved: () -> Unit) {
        val state = _uiState.value
        if (state.mode != SimulationMode.STATIC) {
            // A route is a list of waypoints; the favorites table stores a single point. Saving
            // here used to store a stale selectedPoint that had nothing to do with the route.
            _uiState.update { it.copy(message = appContext.getString(R.string.msg_route_favorite_unsupported)) }
            return
        }
        val target = state.selectedPoint ?: run {
            _uiState.update { it.copy(message = appContext.getString(R.string.msg_select_point)) }
            return
        }
        viewModelScope.launch {
            try {
                favoriteRepository.save(name, target)
                _uiState.update { it.copy(message = appContext.getString(R.string.msg_favorite_saved)) }
                // The text field is cleared only here: a failed save must keep the typed name so
                // the user can retry without retyping it.
                onSaved()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(message = appContext.getString(R.string.msg_save_failed, e.message.orEmpty()))
                }
            }
        }
    }

    fun removeFavorite(id: Long) {
        viewModelScope.launch {
            try {
                favoriteRepository.delete(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(message = appContext.getString(R.string.msg_delete_failed, e.message.orEmpty()))
                }
            }
        }
    }

    fun pickFavorite(item: FavoriteItem) {
        // Convert at the boundary, exactly like a map tap. A legacy/corrupt row can carry a
        // non-WGS84 datum, and both movement sources require WGS84.
        val wgs84 = CoordinateTransformer.toWgs84(item.point)
        updateEditorState { state ->
            when (state.mode) {
                SimulationMode.STATIC -> state.copy(selectedPoint = wgs84, message = null)
                // In route mode a favorite is a waypoint, not a replacement for the whole route.
                SimulationMode.ROUTE -> state.copy(
                    routeWaypoints = state.appendedWaypoint(wgs84),
                    message = null,
                )
            }
        }
    }

    fun startMock() {
        val state = _uiState.value

        // Re-check every time instead of trusting the values captured when the screen rendered:
        // the mock-location AppOp and the location permission can both change in Settings while
        // this activity is backgrounded. The service re-checks as well, but a stale check here
        // would let the user reach a start path that fails later.
        val hasPermission = hasLocationPermission()
        val canMock = permissionChecker.canMockLocation()
        _uiState.update { it.copy(permissionGranted = hasPermission, canMock = canMock) }

        if (!hasPermission) {
            _uiState.update { it.copy(message = appContext.getString(R.string.msg_permission_required)) }
            return
        }
        if (!canMock) {
            _uiState.update { it.copy(message = appContext.getString(R.string.msg_mock_app_required)) }
            return
        }
        // Any live session including Starting/Degraded/Stopping, and the non-terminal
        // teardown-timeout window, refuses a second start. The collected UI session can lag a
        // just-emitted transition by one dispatch, so the store is the gate authority here.
        val currentSession = statusStore.state.value
        if (!currentSession.canStartNewSession) {
            val messageRes = if (currentSession.isSessionActive) {
                R.string.msg_already_running
            } else {
                // Only the non-terminal teardown window reaches here while inactive: the previous
                // session's providers may still be unregistering.
                R.string.msg_teardown_in_progress
            }
            _uiState.update { it.copy(message = appContext.getString(messageRes)) }
            return
        }

        val spec = buildMovementSpec(state) ?: run {
            _uiState.update {
                it.copy(
                    message = when (state.mode) {
                        SimulationMode.STATIC -> appContext.getString(R.string.msg_select_point)
                        SimulationMode.ROUTE -> appContext.getString(
                            R.string.msg_route_too_short,
                            MainUiState.MIN_ROUTE_WAYPOINTS,
                        )
                    },
                )
            }
            return
        }

        startMockUseCase(spec)
    }

    fun stopMock() {
        // No pre-emptive clear: the stop is not confirmed yet, and an editor edit dispatched in
        // the same frame would immediately re-sync against a still-active store phase anyway.
        // The Stopping -> Idle transition (below) re-syncs the pending spec from the editor once
        // the session has actually ended, so the explicit stop is not undone by a stale sync.
        stopMockUseCase()
    }

    fun refreshCapabilities() {
        val granted = hasLocationPermission()
        val canMock = permissionChecker.canMockLocation()
        val batteryWhitelisted = appContext
            .getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(appContext.packageName) == true
        // A denied POST_NOTIFICATIONS does not stop a foreground service, but the user cannot see
        // its status or its Stop action. The checklist row and the request dialog both cover it.
        val notificationsGranted = NotificationManagerCompat.from(appContext).areNotificationsEnabled()
        _uiState.update {
            it.copy(
                permissionGranted = granted,
                canMock = canMock,
                batteryWhitelisted = batteryWhitelisted,
                notificationsGranted = notificationsGranted,
            )
        }
    }

    /**
     * Seeds the editor with the device's real position once the location permission is granted.
     *
     * Runs at most once per ViewModel. A point the user picked while the fix was in flight wins:
     * the emptiness check is repeated right before applying, not only before the request.
     */
    fun useCurrentLocationAsDefault() {
        if (defaultLocationRequested) return
        val state = _uiState.value
        if (state.selectedPoint != null || state.routeWaypoints.isNotEmpty()) {
            defaultLocationRequested = true
            return
        }
        if (!hasLocationPermission()) {
            // Permission may still be granted later; do not consume the one attempt yet.
            return
        }
        defaultLocationRequested = true

        viewModelScope.launch {
            val point = currentLocationSource.current() ?: return@launch
            val current = _uiState.value
            if (current.selectedPoint != null || current.routeWaypoints.isNotEmpty()) return@launch
            updateEditorState { it.copy(selectedPoint = point, message = null) }
        }
    }

    /**
     * Coarse is enough for a location-type foreground service, and mock injection itself needs no
     * runtime location permission. Checking FINE only rejected users who chose "Approximate".
     */
    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    /**
     * Applies an editor change, then persists the editor fields and re-syncs the pending session
     * spec. Session, favorites and capability updates do not touch either: only the fields listed
     * in [EditorSnapshot] survive process death, and only editor edits change what a later start
     * should run.
     */
    private fun updateEditorState(transform: (MainUiState) -> MainUiState) {
        _uiState.update(transform)
        val state = _uiState.value
        state.toEditorSnapshot().writeTo(savedStateHandle)
        syncPendingSpec(state)
    }

    private fun restoreEditorState() {
        updateEditorState { state -> readEditorSnapshot(savedStateHandle).applyTo(state) }
    }

    private fun syncPendingSpec(state: MainUiState) {
        // The collected UI session can lag a just-dispatched transition; the store is the
        // authority for whether a live session currently owns its spec.
        when (val decision = pendingSpecDecision(state, statusStore.state.value)) {
            PendingSpecDecision.Keep -> Unit
            is PendingSpecDecision.Update -> sessionConfigStore.set(decision.spec)
            PendingSpecDecision.Clear -> sessionConfigStore.clear()
        }
    }

    /**
     * Copies the phase/epoch state verbatim. The timer is derived from the session epoch, so a
     * recreated ViewModel that subscribes late immediately sees the running session's epoch and no
     * longer resets the timer to the first tick it happens to observe.
     *
     * Every observed inactive phase re-syncs the pending spec. `StateFlow` deduplicates equal
     * values, so a conflated transition into Idle must not be missed; active phases Keep the spec
     * the service is already running.
     */
    private fun observeSession() {
        viewModelScope.launch {
            statusStore.state.collect { session ->
                _uiState.update { it.copy(session = session) }
                if (shouldSyncPendingSpecForObservedSession(session)) {
                    syncPendingSpec(_uiState.value)
                }
            }
        }
    }

    /**
     * Subscribes to favorites with bounded retries. A Room error ends the flow, and the old
     * implementation caught it once and let the collector finish: the screen then kept stale rows
     * forever with only a one-off snackbar. The attempt budget is per ViewModel lifetime and is
     * not reset by an emission, so an emit-then-throw flow still reaches the limit; after the last
     * attempt the list is cleared and the error is surfaced, so stale data is never presented as
     * current.
     */
    private fun observeFavorites() {
        viewModelScope.launch {
            collectFavoritesWithRetry(
                source = { favoriteRepository.observeAll() },
                onValue = { favorites ->
                    _uiState.update { it.copy(favorites = favorites) }
                },
                onFailure = { attempt, error ->
                    // Surface the first failure at once; the retries themselves stay quiet so a
                    // transient error does not queue one snackbar per attempt.
                    if (attempt == 1) showFavoritesLoadFailure(error)
                },
                onExhausted = { error ->
                    _uiState.update {
                        it.copy(
                            favorites = emptyList(),
                            message = favoritesLoadFailureMessage(error),
                        )
                    }
                },
            )
        }
    }

    private fun showFavoritesLoadFailure(error: Throwable) {
        _uiState.update { it.copy(message = favoritesLoadFailureMessage(error)) }
    }

    private fun favoritesLoadFailureMessage(error: Throwable): String =
        appContext.getString(R.string.msg_load_favorites_failed, error.message.orEmpty())

    /**
     * The saved-state route cap also bounds the live editor, so a restore never truncates a route
     * the user has just built. Extra taps beyond the cap are ignored.
     */
    private fun MainUiState.appendedWaypoint(point: GeoPoint): List<GeoPoint> =
        when (val append = appendRouteWaypoint(routeWaypoints, point)) {
            is RouteAppendResult.Appended -> append.waypoints
            RouteAppendResult.Full -> routeWaypoints
        }
}
