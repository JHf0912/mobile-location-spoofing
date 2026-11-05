package com.snake.mobilespoofing.qs

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.snake.mobilespoofing.MainActivity
import com.snake.mobilespoofing.R
import com.snake.mobilespoofing.core.location.MockPermissionChecker
import com.snake.mobilespoofing.core.location.MockStatusStore
import com.snake.mobilespoofing.core.location.SessionConfigStore
import com.snake.mobilespoofing.core.model.SessionPhase
import com.snake.mobilespoofing.core.model.canStartNewSession
import com.snake.mobilespoofing.core.model.isSessionActive
import com.snake.mobilespoofing.service.MockLocationService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Quick Settings tile: start or stop a session from the notification shade.
 *
 * Worth having because a running session's stop affordance otherwise lives inside the app the user
 * is *not* looking at, and because the notification itself can be hidden by OEM battery savers.
 *
 * Every decision is derived from the phase store, never from `runner.isRunning`: Starting and
 * Stopping are visible windows that a boolean cannot represent.
 *
 * Note that this feature is essentially absent from the open-source projects in this space — the
 * popular ones are either closed source or do not ship one.
 */
@AndroidEntryPoint
class MockLocationTileService : TileService() {

    /** Holds the last spec so a tile tap can restart it without opening the app. */
    @Inject lateinit var sessionConfigStore: SessionConfigStore

    @Inject lateinit var statusStore: MockStatusStore

    @Inject lateinit var permissionChecker: MockPermissionChecker

    private var listeningJob: Job? = null

    /** Last phase rendered into the system tile; see the collector in [onStartListening]. */
    private var lastRenderedPhase: SessionPhase? = null

    /** Called when the shade opens, so the tile reflects state changed elsewhere. */
    override fun onStartListening() {
        super.onStartListening()
        // Seed from the same snapshot that was rendered: two reads could record a phase the tile
        // never actually drew and then skip the collector's correction (G6).
        lastRenderedPhase = refreshTile()
        // The phase can change while the shade is open (start/stop, engine failure, teardown);
        // observing turns a stale tile into a live one without polling. Only phase changes are
        // rendered: a route session publishes a new Running point every second, and the tile has
        // nothing new to say about it.
        listeningJob?.cancel()
        listeningJob = CoroutineScope(Dispatchers.Main.immediate).launch {
            statusStore.state.collect { session ->
                if (session.phase != lastRenderedPhase) {
                    lastRenderedPhase = refreshTile() ?: session.phase
                }
            }
        }
    }

    override fun onStopListening() {
        listeningJob?.cancel()
        listeningJob = null
        lastRenderedPhase = null
        super.onStopListening()
    }

    override fun onDestroy() {
        // Belt and braces: a tile that is torn down without onStopListening must not leave a
        // collector attached to the process-wide store.
        listeningJob?.cancel()
        listeningJob = null
        lastRenderedPhase = null
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()

        val session = statusStore.state.value
        if (session.isSessionActive) {
            // Includes Starting/Degraded/Stopping: the service serializes the stop, and a tile
            // that only reacted to a "running" boolean left those windows unusable.
            startServiceSafely(MockLocationService.stopIntent(this), R.string.tile_stop_failed)
        } else if (!session.canStartNewSession) {
            // Non-terminal Error: the previous teardown is still unregistering providers. Starting
            // now would overlap it, so open the app instead (it shows the teardown reason).
            openApp()
            return
        } else if (sessionConfigStore.pending.value == null) {
            // Nothing to resume — most likely the first run, or the process was restarted and the
            // store is empty. Send the user somewhere they can choose a position rather than
            // starting a session with nothing to inject.
            openApp()
            return
        } else if (!hasLocationPermission()) {
            // Android 14+ rejects a location-type foreground service without a while-in-use
            // permission. Starting it from here used to throw SecurityException on the main thread.
            toast(getString(R.string.tile_permission_required))
            openApp()
            return
        } else if (!permissionChecker.canMockLocation()) {
            toast(getString(R.string.tile_mock_app_required))
            openApp()
            return
        } else {
            // A tile click grants a temporary background-start allowlist, which is what makes a
            // foreground-service start legal from here.
            startForegroundServiceSafely(MockLocationService.startIntent(this))
        }

        lastRenderedPhase = refreshTile() ?: lastRenderedPhase
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun startForegroundServiceSafely(intent: Intent) {
        try {
            startForegroundService(intent)
        } catch (e: Exception) {
            toast(getString(R.string.tile_start_failed, e.message.orEmpty()))
        }
    }

    private fun startServiceSafely(intent: Intent, failureRes: Int) {
        try {
            startService(intent)
        } catch (e: Exception) {
            toast(getString(failureRes, e.message.orEmpty()))
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    /** Renders the current state and returns the phase it rendered, or null when there is no tile. */
    private fun refreshTile(): SessionPhase? {
        val tile = qsTile ?: return null
        val session = statusStore.state.value
        val phase = session.phase
        // Starting/Running/Degraded/Stopping all mean a session exists; Idle and a terminal Error
        // mean the tile's next tap starts one.
        val active = session.isSessionActive

        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.app_name)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(
                when (phase) {
                    SessionPhase.Idle, SessionPhase.Error -> R.string.tile_subtitle_idle
                    SessionPhase.Starting, SessionPhase.Running -> R.string.tile_subtitle_running
                    SessionPhase.Degraded -> R.string.tile_subtitle_degraded
                    SessionPhase.Stopping -> R.string.tile_subtitle_stopping
                },
            )
        }
        tile.updateTile()
        return phase
    }

    // `startActivityAndCollapse(PendingIntent)` was added in API 34; before that only the Intent
    // overload exists. Calling the PendingIntent one on API 26–33 is a NoSuchMethodError.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pendingIntent = PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                startActivityAndCollapse(pendingIntent)
            } else {
                // Deprecated from API 34, but the only overload that exists below 34.
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        } catch (_: Exception) {
            // The shade may already be collapsing; opening the app is best effort.
        }
    }
}
