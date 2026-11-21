package com.snake.mobilespoofing.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.service.quicksettings.TileService
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.snake.mobilespoofing.MainActivity
import com.snake.mobilespoofing.R
import com.snake.mobilespoofing.SpoofingApp
import com.snake.mobilespoofing.core.geo.toMovementSource
import com.snake.mobilespoofing.core.location.MockPermissionChecker
import com.snake.mobilespoofing.core.location.MockSessionRunner
import com.snake.mobilespoofing.core.location.MockStatusStore
import com.snake.mobilespoofing.core.location.SessionConfig
import com.snake.mobilespoofing.core.location.SessionConfigStore
import com.snake.mobilespoofing.core.model.SessionErrorReason
import com.snake.mobilespoofing.core.model.SessionPhase
import com.snake.mobilespoofing.core.model.SessionState
import com.snake.mobilespoofing.core.model.canStartNewSession
import com.snake.mobilespoofing.degradedReasonText
import com.snake.mobilespoofing.errorReasonText
import com.snake.mobilespoofing.qs.MockLocationTileService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Thin Android shell around [MockSessionRunner].
 *
 * The injection loop itself lives in the runner so it can be tested without a device; this class
 * only deals with intents, the foreground notification and the service lifecycle. It publishes
 * only the pre-run `Starting` phase — the runner owns every later phase transition.
 */
@AndroidEntryPoint
class MockLocationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Runs the bounded teardown join from [onDestroy] off the main thread.
     *
     * Deliberately not [scope]: that scope is cancelled at the end of `onDestroy`, and joining the
     * (NonCancellable) teardown from a `runBlocking` on the main thread used to stall the UI for up
     * to [ON_DESTROY_JOIN_TIMEOUT_MS]. The scope is released when its single bounded job completes,
     * so it does not dangle past `onDestroy`.
     */
    private val teardownScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Field injection is populated before onCreate, but not before a property initializer — so
    // these must be lateinit vars used from onCreate onward.
    @Inject lateinit var runner: MockSessionRunner
    @Inject lateinit var statusStore: MockStatusStore
    @Inject lateinit var sessionConfigStore: SessionConfigStore
    @Inject lateinit var permissionChecker: MockPermissionChecker

    private var wakeLock: PowerManager.WakeLock? = null
    private var notificationJob: Job? = null
    private var lastNotificationText: String? = null
    private var lastTilePhase: SessionPhase? = null

    /**
     * True once this instance has called startForeground. A refused start on an instance that is
     * already foreground owes the system nothing more; a refused start on a freshly created
     * instance (startForegroundService) must still end the five-second obligation.
     */
    @Volatile
    private var foregroundStarted = false

    /**
     * Set once the finish path has passed its ownership check. A new ACTION_START that begins
     * concurrently must not start a session into a service that is already going away.
     */
    @Volatile
    private var finishing = false

    /**
     * Serializes the start-acceptance decision against the finish-ownership decision (G3).
     *
     * Without it, a completion could read Idle and pass the ownership check while a new start is
     * between beginStart() and its Starting emission, and then stop the service under that start.
     * Lock order: this lock, then the ServiceStartGuard's own lock; never the reverse.
     */
    private val startDecisionLock = Any()

    /**
     * Serializes the start window against stops. A duplicate ACTION_START is refused before any
     * preflight check; a stop or a preflight failure makes every later gate in this service
     * instance refuse to start the runner.
     */
    private val startGuard = ServiceStartGuard()

    private var startGateJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        // Once per service, not once per notification rebuild: the channel is immutable and
        // recreating it on every status change is wasted binder traffic.
        ensureChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            // A sticky restart delivers a null intent: no action, no payload. Falling through the
            // `when` here is what used to leave a running service that never called
            // startForeground and never injected anything.
            stopWithoutStart()
            return START_NOT_STICKY
        }

        when (intent.action) {
            ACTION_START -> handleStart()
            ACTION_STOP -> handleStop()
            else -> stopWithoutStart()
        }

        // Never sticky, on every path. Resuming a mock session without the user asking would
        // silently start reporting a fake position again, and on Android 12+ a sticky restart
        // counts as a background foreground-service start with a five-second deadline to call
        // startForeground — failing which the app crashes rather than quietly doing nothing.
        return START_NOT_STICKY
    }

    /** Ends an intent that can never start a session, without leaving a gated start behind. */
    private fun stopWithoutStart() {
        startGuard.requestStop()
        startGateJob?.cancel()
        stopSelf()
    }

    private fun handleStart() {
        // Claim the start window and decide in the same critical section as the finish path
        // (G3): a completion either sees this start in flight, or this start sees its finishing
        // flag. The runner's sessionId/join stays the fallback for direct callers.
        var claimedStart = false
        var refusedWithoutForeground = false
        val refusal: SessionState? = synchronized(startDecisionLock) {
            // Before any preflight: a duplicate start must not run checks that could fail, call
            // stopSelf(), and leave the first start to launch a session after the service decided
            // to stop. beginStart() also refuses a start after a stop was requested.
            if (!startGuard.beginStart()) {
                // R1: an instance that is already stopping and never reached startForeground must
                // end this startForegroundService obligation. An in-flight first start is left
                // alone: it will call startForeground itself.
                refusedWithoutForeground = !foregroundStarted && startGuard.isStopping
                null
            } else {
                claimedStart = true
                val current = statusStore.state.value
                // A live session (Starting/Running/Degraded/Stopping), the teardown-timeout
                // window (non-terminal Error), or a finish already in progress all refuse.
                if (finishing || !current.canStartNewSession) current else null
            }
        }
        if (!claimedStart) {
            if (refusedWithoutForeground) {
                postErrorNotification(getString(R.string.notification_stopping))
                requestTileRefresh()
                stopSelf()
            }
            return
        }
        if (refusal != null) {
            refuseStart(refusal)
            return
        }

        val spec = sessionConfigStore.pending.value
        if (spec == null) {
            // Nothing to run. This is the expected state after a process restart, since the store
            // does not survive one.
            failEarly(SessionErrorReason.MissingSpec)
            return
        }

        if (!hasLocationPermission()) {
            failEarly(SessionErrorReason.PermissionDenied)
            return
        }
        if (!permissionChecker.canMockLocation()) {
            failEarly(SessionErrorReason.MockAppNotSelected)
            return
        }

        // Starting is published after the cheap checks but before startForeground and before the
        // notification collector attaches. Replacing a previous terminal Error here is what stops
        // that old error text from flashing on the new start: the collector's first value is
        // already Starting, matching the running text startForeground is about to post.
        statusStore.emit(
            SessionState.Starting(
                sessionId = runner.nextSessionId(),
                startedAtElapsedMs = SystemClock.elapsedRealtime(),
            ),
        )

        // startForeground must happen before any expensive work: startForegroundService gives the
        // service five seconds to do it, and the cold-start cleanup join below can take a while.
        try {
            startForeground(NOTIFY_ID, buildNotification(getString(R.string.notification_running)))
            foregroundStarted = true
            lastNotificationText = getString(R.string.notification_running)
        } catch (e: Exception) {
            // SecurityException on Android 14+ when the location permission was revoked, and
            // ForegroundServiceStartNotAllowedException when the system refuses a background start.
            // Neither may take the process down.
            startGuard.requestStop()
            startGateJob?.cancel()
            Log.w(TAG, "startForeground failed", e)
            statusStore.emit(
                SessionState.Error(
                    sessionId = null,
                    reason = SessionErrorReason.ServiceStartFailed,
                    terminal = true,
                ),
            )
            postErrorNotification(SessionErrorReason.ServiceStartFailed)
            finishSession()
            return
        }

        observeSessionForNotification()
        acquireWakeLock()

        val source = try {
            spec.toMovementSource()
        } catch (e: IllegalArgumentException) {
            // Surfaced rather than substituted: every rejection here means the resulting position
            // would not be what the user asked for.
            startGuard.requestStop()
            startGateJob?.cancel()
            Log.w(TAG, "session spec rejected", e)
            statusStore.emit(
                SessionState.Error(
                    sessionId = null,
                    reason = SessionErrorReason.SourceInvalid,
                    terminal = true,
                ),
            )
            postErrorNotification(SessionErrorReason.SourceInvalid)
            finishSession()
            return
        }

        startGateJob = scope.launch {
            try {
                // Serialize with the cold-start provider cleanup: it may be removing stale
                // providers under the same global names. If it does not finish in time, refuse to
                // start rather than register providers while cleanup may still be manipulating
                // them.
                val gatePassed = withTimeoutOrNull(START_GATE_TIMEOUT_MS) { passStartupGate() } == true
                if (!gatePassed) {
                    startGuard.requestStop()
                    Log.w(TAG, "refusing to start: $START_GATE_DETAIL")
                    statusStore.emit(
                        SessionState.Error(
                            sessionId = null,
                            reason = SessionErrorReason.ServiceStartFailed,
                            terminal = true,
                        ),
                    )
                    finishSession()
                    return@launch
                }
                // A stop may have arrived while this coroutine was suspended on the gate.
                if (!startGuard.canStartRunner()) {
                    // Nothing reached the runner, so the Starting preview must not outlive this
                    // start request.
                    clearStartingPreview()
                    return@launch
                }

                runner.start(
                    scope = scope,
                    source = source,
                    config = SessionConfig(),
                    onFinished = { finishedSessionId -> finishSession(finishedSessionId) },
                )
            } finally {
                startGuard.finishStart()
            }
        }
    }

    /**
     * Marks the service start and joins the cold-start cleanup job.
     *
     * Only the cleanup gate is acquired here — never the lease store's `withLeaseLock`, which the
     * engine takes for the short lease write before each provider registration. Those two locks
     * must not nest: `Mutex` is not reentrant.
     */
    private suspend fun passStartupGate(): Boolean {
        val coordinator = (application as? SpoofingApp)?.startupCoordinator ?: return true
        coordinator.markServiceStarting()
        return coordinator.finishCleanup(CLEANUP_JOIN_TIMEOUT_MS)
    }

    private fun handleStop() {
        startGuard.requestStop()
        startGateJob?.cancel()
        // Always stop the service, even when no session was running: stopAndJoin cancels the
        // runner (if any) and waits for its teardown for a bounded time before finishing.
        scope.launch {
            logIfTeardownExceeded(runner.stopAndJoin())
            finishSession()
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // The user swiped the app away. The service does not want to keep running for a session
        // nobody can see, but teardown gets a bounded chance to remove the system-wide providers.
        // Runs on the service scope so the main thread is never blocked.
        startGuard.requestStop()
        startGateJob?.cancel()
        scope.launch {
            logIfTeardownExceeded(runner.stopAndJoin(MockSessionRunner.STOPPING_BUDGET_MS))
            finishSession()
        }
        super.onTaskRemoved(rootIntent)
    }

    /** Service-initiated stop paths: this service instance owns whatever it is tearing down. */
    private fun finishSession() {
        finishing = true
        finishSessionInternal()
    }

    /**
     * Runner completion callback. The runner checks `isCurrent` before calling, but that check is
     * not atomic with this callback: a new session can start in between and already own the
     * notification/service. In that case this callback must not cancel it.
     *
     * An open start window is part of the ownership decision: a start that has begun preflight but
     * has not published Starting yet still owns the service, even though the store reads Idle.
     */
    private fun finishSession(finishedSessionId: Long) {
        synchronized(startDecisionLock) {
            if (finishing || startGuard.isStartInFlight ||
                !finishStillOwnsSession(
                    finishedSessionId,
                    statusStore.state.value,
                    startInFlight = false,
                )
            ) {
                Log.w(TAG, "ignoring finish for session $finishedSessionId: another start owns the service")
                return
            }
            finishing = true
        }
        finishSessionInternal()
    }

    private fun finishSessionInternal() {
        // A start stopped while gated (or stopped before the runner took ownership) never began a
        // session; clear its Starting preview so the UI does not stay on "Starting" after the
        // service is gone. Terminal Errors and active Stopping phases are left untouched.
        clearStartingPreview()
        notificationJob?.cancel()
        notificationJob = null
        // No start window may outlive the service: a later intent on this instance must not be
        // accepted after the finish decision.
        startGuard.finishStart()
        requestTileRefresh()
        releaseWakeLock()
        foregroundStarted = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        // Known limitation (B-1 re-audit G8): when a stop ends in the non-terminal TeardownTimeout
        // window, removing the foreground notification leaves the reason visible only in the app
        // until the stuck cleanup completes. A persistent error notification is deferred.
    }

    override fun onDestroy() {
        startGuard.requestStop()
        startGateJob?.cancel()
        finishing = true
        // Best effort, bounded, and off the main thread. The runner's teardown is NonCancellable
        // and runs on its own dispatcher, so a runBlocking join here used to block the UI for up to
        // ON_DESTROY_JOIN_TIMEOUT_MS on a binder call that may never return. Firing the same
        // bounded join on a background scope keeps the "stop means cleanup" semantics without the
        // main-thread stall; if the process is killed before teardown finishes, the persisted lease
        // plus the next cold-start cleanup are the real protection.
        //
        // The scope is cancelled only when the join completes, not immediately: cancelling up front
        // would stop this coroutine before stop() runs and could cut off the NonCancellable
        // engine.stopMockMode() cleanup. Releasing on completion (normal or otherwise) lets the
        // cleanup finish and still collects the scope instead of leaving it dangling.
        teardownScope.launch {
            logIfTeardownExceeded(runner.stopAndJoin(ON_DESTROY_JOIN_TIMEOUT_MS))
        }.invokeOnCompletion {
            teardownScope.cancel()
        }
        clearStartingPreview()
        startGuard.finishStart()
        releaseWakeLock()
        foregroundStarted = false
        scope.cancel()
        super.onDestroy()
    }

    private fun logIfTeardownExceeded(joined: Boolean) {
        if (!joined) {
            // The runner already published a non-terminal TeardownTimeout error; Start stays
            // blocked until the stuck job completes, so a repeated stop must not restart the wait.
            Log.w(TAG, "teardown exceeded the stopping budget; Start is blocked until it completes")
        }
    }

    /** Drops a Starting state that no runner took over; never touches a terminal Error. */
    private fun clearStartingPreview() {
        if (statusStore.state.value.phase == SessionPhase.Starting) {
            statusStore.emit(SessionState.Idle)
        }
    }

    // ---------------------------------------------------------------- permissions & wake lock

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * A foreground service keeps the process alive but does not keep the CPU awake. Without this,
     * the injection loop stops while the screen is off and the device is in Doze.
     */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:mock-location")
                .apply {
                    setReferenceCounted(false)
                    acquire()
                }
        } catch (_: RuntimeException) {
            // A wake lock is an optimization, not a correctness requirement; the session can
            // still run (just less reliably while the device sleeps).
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (_: RuntimeException) {
            // Already released.
        } finally {
            wakeLock = null
        }
    }

    // ---------------------------------------------------------------- notification

    /**
     * Keeps the foreground notification in step with the phase. Only reposts when the text
     * changes: Running re-emits on every route tick, but Degraded/Stopping/Error are the states
     * that actually change the wording. Phase changes also refresh the Quick Settings tile, which
     * is the only reliable stop affordance when notifications are hidden by an OEM.
     */
    private fun observeSessionForNotification() {
        notificationJob?.cancel()
        notificationJob = scope.launch {
            statusStore.state.collect { session ->
                if (session.phase != lastTilePhase) {
                    lastTilePhase = session.phase
                    requestTileRefresh()
                }

                val text = when (session) {
                    SessionState.Idle -> return@collect
                    is SessionState.Starting, is SessionState.Running ->
                        getString(R.string.notification_running)
                    is SessionState.Degraded -> getString(
                        R.string.notification_degraded,
                        degradedReasonText(this@MockLocationService, session.reason),
                    )
                    is SessionState.Stopping -> getString(R.string.notification_stopping)
                    is SessionState.Error -> getString(
                        R.string.notification_error,
                        errorReasonText(this@MockLocationService, session.reason),
                    )
                }
                if (text == lastNotificationText) return@collect
                lastNotificationText = text
                try {
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIFY_ID, buildNotification(text))
                } catch (_: RuntimeException) {
                    // Notification permission can be revoked mid-session; the session itself is
                    // unaffected and the QS tile remains a stop affordance.
                }
            }
        }
    }

    private fun requestTileRefresh() {
        try {
            TileService.requestListeningState(
                this,
                ComponentName(this, MockLocationTileService::class.java),
            )
        } catch (_: RuntimeException) {
            // Best effort; the tile also reads the store whenever the shade opens.
        }
    }

    /**
     * Reports a preflight failure and stops.
     *
     * Called before `startForeground` on the paths that cannot start a session at all. Posting a
     * non-ongoing error notification first is the closest available mitigation for OEM builds that
     * treat a `startForegroundService` caller which never reaches `startForeground` as a crash.
     */
    private fun failEarly(reason: SessionErrorReason) {
        // G2: a preflight failure must not overwrite the non-terminal teardown window (or a live
        // session). The top-of-handleStart guard makes this rare, but a concurrent teardown
        // publication can land between that check and here.
        val current = statusStore.state.value
        if (!current.canStartNewSession) {
            refuseStart(current)
            return
        }
        // Cancel any gated start and make sure it can never reach runner.start after this stop
        // decision, even if a duplicate intent is delivered before the service is destroyed.
        startGuard.requestStop()
        startGateJob?.cancel()
        startGuard.finishStart()
        statusStore.emit(
            SessionState.Error(sessionId = null, reason = reason, terminal = true),
        )
        postErrorNotification(reason)
        // No collector has been attached on these paths, so refresh an open tile explicitly.
        requestTileRefresh()
        stopSelf()
    }

    /**
     * Ends a refused start without touching a live session, and without leaving a
     * startForegroundService request unsatisfied (G1).
     *
     * An instance that is already foreground owes nothing more for a duplicate intent; a freshly
     * created instance (the state changed between the caller's gate and onStartCommand) posts a
     * non-ongoing status and stops before the five-second deadline turns into a
     * RemoteServiceException/ANR. The published phase is never rewritten here.
     */
    private fun refuseStart(current: SessionState) {
        startGuard.requestStop()
        startGateJob?.cancel()
        startGuard.finishStart()
        Log.w(TAG, "ignoring start request: session phase is ${current.phase}")
        if (foregroundStarted) return

        val message = when (current) {
            is SessionState.Degraded -> degradedReasonText(this, current.reason)
            is SessionState.Error -> errorReasonText(this, current.reason)
            SessionState.Idle, is SessionState.Starting, is SessionState.Running,
            is SessionState.Stopping -> getString(R.string.notification_stopping)
        }
        postErrorNotification(message)
        requestTileRefresh()
        stopSelf()
    }

    /**
     * Posts a non-ongoing error notification before the service stops.
     *
     * Used on paths that have to stop without ever calling `startForeground`. On some OEM builds a
     * `startForegroundService` caller that never sees `startForeground` can crash the app; a
     * visible notification is the closest thing to a safe exit that is available without one.
     */
    private fun postErrorNotification(reason: SessionErrorReason) {
        postErrorNotification(errorReasonText(this, reason))
    }

    private fun postErrorNotification(message: String) {
        try {
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_location_pin)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(message)
                .setAutoCancel(true)
                .setContentIntent(openAppPendingIntent())
                .build()
            getSystemService(NotificationManager::class.java).notify(NOTIFY_ERROR_ID, notification)
        } catch (_: RuntimeException) {
            // Notification permission may be denied; the status store still carries the reason.
        }
    }

    private fun openAppPendingIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            REQUEST_OPEN_APP,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun buildNotification(content: String): Notification {
        // getService, not getForegroundService: the service is already running in the foreground,
        // so this is one of the documented background-start exemptions. Using the foreground
        // variant would oblige the service to call startForeground within five seconds on a path
        // whose entire purpose is to shut it down — which turns "stop" into "a new notification".
        //
        // A distinct request code matters: with the same one, FLAG_UPDATE_CURRENT would let the
        // two PendingIntents overwrite each other's extras and both would fire the same action.
        val stop = PendingIntent.getService(
            this,
            REQUEST_STOP,
            stopIntent(this),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            // Must be a single-colour silhouette: notification small icons are rendered as a mask,
            // so a full-colour drawable comes out as a solid blob.
            .setSmallIcon(R.drawable.ic_location_pin)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(content)
            .setContentIntent(openAppPendingIntent())
            // Stops the session without opening the app — the only stop affordance that works while
            // the user is inside whatever app they are testing.
            .addAction(0, getString(R.string.action_stop), stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            // Without this some OEM builds hold the foreground notification back for ~10 seconds,
            // during which the user has no visible way to stop the session.
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun ensureChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
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
        private const val TAG = "MockLocationService"
        private const val CHANNEL_ID = "mock_location_channel"
        private const val NOTIFY_ID = 1001
        private const val NOTIFY_ERROR_ID = 1002

        const val ACTION_START = "mock.start"
        const val ACTION_STOP = "mock.stop"

        private const val REQUEST_OPEN_APP = 1
        private const val REQUEST_STOP = 2

        /** Bounded wait for the cold-start provider cleanup. */
        private const val CLEANUP_JOIN_TIMEOUT_MS = 2_000L

        /** Outer bound for the whole start gate; the process stays foreground but uninjected. */
        private const val START_GATE_TIMEOUT_MS = 6_000L

        /** A bind attempt at the end of the start gate must not block the session forever. */
        private const val START_GATE_DETAIL = "startup cleanup did not finish"

        private const val ON_DESTROY_JOIN_TIMEOUT_MS = 1_000L

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
