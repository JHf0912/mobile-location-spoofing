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
import com.snake.mobilespoofing.core.location.MockLocationEngine
import com.snake.mobilespoofing.core.location.MockStatusStore
import com.snake.mobilespoofing.core.model.CoordType
import com.snake.mobilespoofing.core.model.Fix
import com.snake.mobilespoofing.core.model.GeoPoint
import com.snake.mobilespoofing.core.model.MockStatus
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MockLocationService : LifecycleService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null

    // Field injection is populated before onCreate runs, but NOT before a property initializer —
    // so these must be `lateinit var` used from onCreate onward, never read in an init block.
    @Inject lateinit var engine: MockLocationEngine
    @Inject lateinit var statusStore: MockStatusStore

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val lat = intent.getDoubleExtra(EXTRA_LAT, Double.NaN)
                val lng = intent.getDoubleExtra(EXTRA_LNG, Double.NaN)
                val coordType = CoordType.valueOf(intent.getStringExtra(EXTRA_COORD_TYPE) ?: CoordType.GCJ02.name)
                if (lat.isNaN() || lng.isNaN()) {
                    statusStore.emit(MockStatus.Error("参数错误：经纬度为空"))
                    return START_NOT_STICKY
                }
                val target = GeoPoint(lat, lng, coordType)
                startForeground(NOTIFY_ID, buildNotification("模拟中"))
                startLoop(target)
            }

            ACTION_STOP -> {
                stopLoop()
            }
        }
        return START_STICKY
    }

    private fun startLoop(point: GeoPoint) {
        loopJob?.cancel()
        loopJob = scope.launch {
            try {
                engine.startMockMode()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                statusStore.emit(MockStatus.Error("无法开启模拟模式：${e.message}"))
                stopLoop()
                return@launch
            }

            while (isActive) {
                // Converted once, and the injectors require WGS84.
                val wgs = CoordinateTransformer.toWgs84(point).copy(coordType = CoordType.WGS84)
                try {
                    engine.push(Fix(point = wgs))
                    statusStore.emit(MockStatus.Running(wgs, System.currentTimeMillis()))
                } catch (e: CancellationException) {
                    // Must be rethrown. runCatching here would swallow cancellation, so a cancel
                    // from stopLoop could be absorbed by an in-flight await() and the loop would
                    // keep injecting after the user pressed stop.
                    throw e
                } catch (e: Exception) {
                    statusStore.emit(MockStatus.Error("注入失败：${e.message}"))
                }
                delay(INJECT_INTERVAL_MS)
            }
        }
    }

    private fun stopLoop() {
        loopJob?.cancel()
        scope.launch {
            // Teardown must happen even though the loop above was just cancelled: a LocationManager
            // test provider outlives this process, so skipping this leaves the device reporting a
            // mock position to every app until it is rebooted.
            try {
                engine.stopMockMode()
            } catch (e: CancellationException) {
                withContext(NonCancellable) { engine.stopMockMode() }
            } catch (_: Exception) {
                // Nothing useful to do — the status reset below still matters.
            }
            statusStore.emit(MockStatus.Idle)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun buildNotification(content: String): Notification {
        ensureChannel()
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(content)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Mock Location",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "mock_location_channel"
        private const val NOTIFY_ID = 1001
        private const val INJECT_INTERVAL_MS = 800L

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
