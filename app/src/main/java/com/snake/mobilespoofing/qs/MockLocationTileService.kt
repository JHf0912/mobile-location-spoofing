package com.snake.mobilespoofing.qs

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.snake.mobilespoofing.MainActivity
import com.snake.mobilespoofing.R
import com.snake.mobilespoofing.core.location.MockStatusStore
import com.snake.mobilespoofing.core.location.SessionConfigStore
import com.snake.mobilespoofing.core.model.MockStatus
import com.snake.mobilespoofing.service.MockLocationService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Quick Settings tile: start or stop a session from the notification shade.
 *
 * Worth having because a running session's stop affordance otherwise lives inside the app the user
 * is *not* looking at, and because the notification itself can be hidden by OEM battery savers.
 *
 * Note that this feature is essentially absent from the open-source projects in this space — the
 * popular ones are either closed source or do not ship one.
 */
@AndroidEntryPoint
class MockLocationTileService : TileService() {

    @Inject lateinit var statusStore: MockStatusStore

    /** Holds the last spec so a tile tap can restart it without opening the app. */
    @Inject lateinit var sessionConfigStore: SessionConfigStore

    /** Called when the shade opens, so the tile reflects state changed elsewhere. */
    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()

        if (isRunning()) {
            startService(MockLocationService.stopIntent(this))
        } else if (sessionConfigStore.pending.value == null) {
            // Nothing to resume — most likely the first run, or the process was restarted and the
            // store is empty. Send the user somewhere they can choose a position rather than
            // starting a session with nothing to inject.
            openApp()
            return
        } else {
            // A tile click grants a temporary background-start allowlist, which is what makes a
            // foreground-service start legal from here.
            startForegroundService(MockLocationService.startIntent(this))
        }

        refreshTile()
    }

    private fun isRunning(): Boolean = statusStore.status.value is MockStatus.Running

    private fun refreshTile() {
        val tile = qsTile ?: return
        val running = isRunning()

        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.app_name)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(if (running) R.string.tile_subtitle_running else R.string.tile_subtitle_idle)
        }
        tile.updateTile()
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(intent)
        } else {
            // Only the PendingIntent overload exists before API 34, and minSdk here is 26.
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            @Suppress("DEPRECATION")
            startActivityAndCollapse(pendingIntent)
        }
    }
}
