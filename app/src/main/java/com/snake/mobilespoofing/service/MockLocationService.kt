package com.snake.mobilespoofing.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.snake.mobilespoofing.MainActivity
import com.snake.mobilespoofing.R
import com.snake.mobilespoofing.core.geo.CoordinateTransformer
import com.snake.mobilespoofing.core.geo.StaticMovementSource
import com.snake.mobilespoofing.core.location.MockSessionRunner
import com.snake.mobilespoofing.core.location.MockStatusStore
import com.snake.mobilespoofing.core.location.SessionConfig
import com.snake.mobilespoofing.core.model.CoordType
import com.snake.mobilespoofing.core.model.GeoPoint
import com.snake.mobilespoofing.core.model.MockStatus
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Thin Android shell around [MockSessionRunner].
 *
 * The injection loop itself lives in the runner so it can be tested without a device; this class
 * only deals with intents, the foreground notification and the service lifecycle.
 */
@AndroidEntryPoint
class MockLocationService : LifecycleService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Field injection is populated before onCreate, but not before a property initializer — so
    // these must be lateinit vars used from onCreate onward.
    @Inject lateinit var runner: MockSessionRunner
    @Inject lateinit var statusStore: MockStatusStore

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            // A sticky restart delivers a null intent: no action, no payload. Falling through the
            // `when` here is what used to leave a running service that never called
            // startForeground and never injected anything.
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> runner.stop()
            else -> stopSelf()
        }

        // Never sticky, on every path. Resuming a mock session without the user asking would
        // silently start reporting a fake position again, and on Android 12+ a sticky restart
        // counts as a background foreground-service start with a five-second deadline to call
        // startForeground — failing which the app crashes rather than quietly doing nothing.
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        val latitude = intent.getDoubleExtra(EXTRA_LAT, Double.NaN)
        val longitude = intent.getDoubleExtra(EXTRA_LNG, Double.NaN)
        val coordType = intent.getStringExtra(EXTRA_COORD_TYPE)
            ?.let { raw -> CoordType.entries.firstOrNull { it.name == raw } }
            ?: CoordType.GCJ02

        if (latitude.isNaN() || longitude.isNaN()) {
            statusStore.emit(MockStatus.Error("参数错误：经纬度为空"))
            stopSelf()
            return
        }

        // Both injectors require WGS84, so convert once here at the boundary rather than leaving a
        // datum-tagged point to leak into the engine.
        val target = CoordinateTransformer.toWgs84(GeoPoint(latitude, longitude, coordType))
            .copy(coordType = CoordType.WGS84)

        startForeground(NOTIFY_ID, buildNotification("模拟中"))
        runner.start(
            scope = scope,
            source = StaticMovementSource(target),
            config = SessionConfig(),
            onFinished = { finishSession() },
        )
    }

    private fun finishSession() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        // Cancelling the runner first lets it run its NonCancellable teardown before the scope goes.
        runner.stop()
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(content: String): Notification {
        ensureChannel()
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(content)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun ensureChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Mock Location",
            // IMPORTANCE_LOW is a trap on MIUI/EMUI/ColorOS, which collapse or hide
            // low-importance ongoing notifications — and that removes the user's main way to stop
            // a running session. Default importance, kept silent instead.
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            setSound(null, null)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "mock_location_channel"
        private const val NOTIFY_ID = 1001

        const val ACTION_START = "mock.start"
        const val ACTION_STOP = "mock.stop"
        private const val EXTRA_LAT = "extra_lat"
        private const val EXTRA_LNG = "extra_lng"
        private const val EXTRA_COORD_TYPE = "extra_coord_type"

        fun startIntent(context: Context, point: GeoPoint): Intent {
            return Intent(context, MockLocationService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_LAT, point.latitude)
                putExtra(EXTRA_LNG, point.longitude)
                putExtra(EXTRA_COORD_TYPE, point.coordType.name)
            }
        }

        fun stopIntent(context: Context): Intent {
            return Intent(context, MockLocationService::class.java).apply {
                action = ACTION_STOP
            }
        }
    }
}
