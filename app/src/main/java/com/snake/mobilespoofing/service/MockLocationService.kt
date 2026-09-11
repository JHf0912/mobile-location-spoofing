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
import com.snake.mobilespoofing.core.geo.toMovementSource
import com.snake.mobilespoofing.core.location.MockSessionRunner
import com.snake.mobilespoofing.core.location.MockStatusStore
import com.snake.mobilespoofing.core.location.SessionConfig
import com.snake.mobilespoofing.core.location.SessionConfigStore
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
    @Inject lateinit var sessionConfigStore: SessionConfigStore

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            // A sticky restart delivers a null intent: no action, no payload. Falling through the
            // `when` here is what used to leave a running service that never called
            // startForeground and never injected anything.
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent.action) {
            ACTION_START -> handleStart()
            ACTION_STOP -> runner.stop()
            else -> stopSelf()
        }

        // Never sticky, on every path. Resuming a mock session without the user asking would
        // silently start reporting a fake position again, and on Android 12+ a sticky restart
        // counts as a background foreground-service start with a five-second deadline to call
        // startForeground — failing which the app crashes rather than quietly doing nothing.
        return START_NOT_STICKY
    }

    private fun handleStart() {
        val spec = sessionConfigStore.pending.value
        if (spec == null) {
            // Nothing to run. This is the expected state after a process restart, since the store
            // does not survive one.
            statusStore.emit(MockStatus.Error("参数错误：未选择要模拟的位置"))
            stopSelf()
            return
        }

        val source = try {
            spec.toMovementSource()
        } catch (e: IllegalArgumentException) {
            // Surfaced rather than substituted: every rejection here means the resulting position
            // would not be what the user asked for.
            statusStore.emit(MockStatus.Error("无法开始：${e.message}"))
            stopSelf()
            return
        }

        startForeground(NOTIFY_ID, buildNotification("模拟中"))
        runner.start(
            scope = scope,
            source = source,
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

        /**
         * Carries only the action. The coordinates live in [SessionConfigStore] because a route is
         * a list of them, which an Intent extra cannot hold without marshalling.
         */
        fun startIntent(context: Context): Intent {
            return Intent(context, MockLocationService::class.java).apply {
                action = ACTION_START
            }
        }

        fun stopIntent(context: Context): Intent {
            return Intent(context, MockLocationService::class.java).apply {
                action = ACTION_STOP
            }
        }
    }
}
