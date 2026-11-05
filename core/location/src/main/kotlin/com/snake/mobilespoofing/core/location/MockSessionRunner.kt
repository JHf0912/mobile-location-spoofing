package com.snake.mobilespoofing.core.location

import com.snake.mobilespoofing.core.geo.MovementSource
import com.snake.mobilespoofing.core.model.GeoPoint
import com.snake.mobilespoofing.core.model.SessionDegradedReason
import com.snake.mobilespoofing.core.model.SessionErrorReason
import com.snake.mobilespoofing.core.model.SessionState
import com.snake.mobilespoofing.core.model.isSessionActive
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Drives a mock session: pulls fixes from a [MovementSource] on a fixed cadence and pushes them
 * through the engine.
 *
 * Deliberately not an Android component. Keeping the loop here rather than inside the service means
 * the whole thing is testable with a fake clock, a fake engine and virtual time — no device, no
 * Robolectric, no real delays.
 *
 * The runner is the single authority for [SessionState] transitions and for the session epoch:
 * Starting before the engine is touched, Running/Degraded while it injects, Stopping on an explicit
 * stop, and Idle after a clean teardown or a terminal Error when it fails. A teardown that exceeds
 * [STOPPING_BUDGET_MS] publishes a *non-terminal* TeardownTimeout error: the session is over, but
 * Start stays blocked until the stuck teardown completes, because registering new providers while
 * an old cleanup may still be running is exactly the overlap that used to tear down a fresh session.
 *
 * [clock] and [dispatcher] are constructor parameters with defaults so tests can substitute them
 * without Hilt qualifiers.
 */
class MockSessionRunner(
    private val engine: MockLocationEngine,
    private val statusStore: MockStatusStore,
    private val clock: ElapsedClock = SystemElapsedClock(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val permissionChecker: MockPermissionChecker = MockPermissionChecker { true },
    private val startTimeoutMs: Long = SESSION_START_TIMEOUT_MS,
    private val stopJoinTimeoutMs: Long = STOPPING_BUDGET_MS,
) {

    @Volatile
    private var job: Job? = null

    /**
     * Incremented by [start]. A job whose id is no longer current has been replaced by a newer
     * session and must not publish state or invoke its completion callback: doing so would reset
     * the new session to Idle and stop the service that is now running it.
     *
     * [stop] deliberately does not change this id, so the session being stopped still reports Idle
     * and runs its completion callback.
     */
    @Volatile
    private var sessionId: Long = 0L

    /** False as soon as [stop] is called, even while the cancelled job's teardown is still running. */
    @Volatile
    private var accepting: Boolean = false

    /**
     * Set by [stop] and cleared by [start]. Once a stop has been requested, an in-flight push that
     * returns afterwards must not flip the phase back from Stopping to Running/Degraded. Idle and
     * Error remain publishable: they are the outcomes of the teardown.
     */
    @Volatile
    private var stopping: Boolean = false

    /**
     * Set when a teardown did not finish inside [stopJoinTimeoutMs], and cleared when that job
     * actually completes.
     *
     * While set, every start is refused: registering providers while the old teardown may still be
     * unregistering them is exactly the overlap that used to tear down a fresh session. The state
     * stays a non-terminal TeardownTimeout error, which blocks Start but offers nothing to stop.
     */
    @Volatile
    private var teardownStuck: Boolean = false

    /**
     * Identifies the current stuck-teardown generation.
     *
     * Every timeout detection increments it; a completion handler captured for an older generation
     * must not clear the flag or resolve the state of a newer one. The id is deliberately not used
     * for this: a refused start rewrites the diagnostic id while the same teardown is still stuck.
     * AtomicLong, not a volatile `++`: a stop and a start can both detect a timeout concurrently.
     */
    private val stuckTeardownToken = AtomicLong(0L)

    /** Diagnostic id of the session whose teardown is stuck; null when no window is open. */
    @Volatile
    private var stuckTeardownSessionId: Long? = null

    /**
     * Oldest teardown that is still unresolved, independent of [sessionId].
     *
     * A start can be superseded while it waits for a previous teardown; its own wrapper then sees
     * `isCurrent == false` and cannot record anything. This reference makes the next start join
     * the same old teardown regardless of how many sessions were superseded in between (B1-H2),
     * so no later engine can register providers while it is still running.
     */
    @Volatile
    private var pendingTeardownJob: Job? = null

    @Volatile
    private var pendingTeardownSessionId: Long? = null

    /**
     * Serializes the two transitions that can race on the published state: a new start's Starting
     * emission and the stuck-teardown resolver's Idle. Without it, the resolver could read the
     * timeout Error, clear the flag, and then publish Idle after a direct start already published
     * Starting.
     */
    private val stateLock = Any()

    /** `elapsedRealtime` of the current start request; null while no session exists. */
    @Volatile
    private var currentSessionStartedAtMs: Long? = null

    /**
     * Captured once after `startMockMode` succeeds and constant until the session ends.
     *
     * [stop] and the status emissions read it from here rather than re-deriving it, which is what
     * keeps Degraded/Stopping on the same epoch and clears it for Idle/terminal Error.
     */
    @Volatile
    private var currentSessionEpochMs: Long? = null

    /**
     * Id that the next [start] will assign.
     *
     * The service uses it to publish Starting before the (possibly slow) startup gate; [start]
     * then publishes Starting again with the same id once it owns the session. Nothing in the app
     * may use this to predict transitions — it only labels the pre-run window.
     */
    fun nextSessionId(): Long = sessionId + 1

    /**
     * @param onFinished invoked with the finished session id once the session is over — whether it
     *   completed on its own, failed, or was cancelled. Lets the Android service tear down its
     *   notification without the runner needing to know anything about services. The id lets the
     *   service refuse to tear down a newer session that started while this callback was in
     *   flight. Not invoked for a session that was superseded by a newer [start], because in that
     *   case the service must keep running.
     */
    fun start(
        scope: CoroutineScope,
        source: MovementSource,
        config: SessionConfig,
        onFinished: (Long) -> Unit = {},
    ) {
        val previous = job
        val previousSessionId = sessionId
        val id = ++sessionId
        val startedAt = clock.nowMs()
        accepting = true
        stopping = false
        currentSessionStartedAtMs = startedAt
        currentSessionEpochMs = null

        val joinTargets: List<TeardownTarget>
        synchronized(stateLock) {
            // A completed tail no longer needs to be joined.
            if (pendingTeardownJob?.isCompleted == true && !teardownStuck) {
                pendingTeardownJob = null
                pendingTeardownSessionId = null
            }
            val pending = pendingTeardownJob?.takeIf { !it.isCompleted }
                ?.let { TeardownTarget(it, pendingTeardownSessionId ?: previousSessionId) }
            val immediate = previous?.takeIf { !it.isCompleted }
                ?.let { TeardownTarget(it, previousSessionId) }

            // Oldest first: the pending teardown predates the immediate previous session. Both are
            // joined, because superseding a start that was itself waiting for an older teardown
            // must not let this start run an engine before that older cleanup finishes (B1-H2).
            joinTargets = listOfNotNull(pending, immediate).distinctBy { it.job }
            if (pending == null && immediate != null) {
                // Track the newest unresolved teardown globally, so a start that supersedes this
                // one still joins it even though this session's wrapper becomes non-current.
                pendingTeardownJob = immediate.job
                pendingTeardownSessionId = immediate.sessionId
            }

            // Published synchronously so direct callers (tests, non-service hosts) see Starting
            // before the engine is touched, and so the service's earlier Starting preview is
            // replaced by the real session id. StateFlow collapses an identical repeat, not a new
            // id/timestamp.
            statusStore.emit(SessionState.Starting(sessionId = id, startedAtElapsedMs = startedAt))
        }
        previous?.cancel()

        job = scope.launch(dispatcher) {
            var ranSession = false
            var errorReported = false
            try {
                when (val join = awaitPrevious(joinTargets)) {
                    PreviousJoin.Joined -> {
                        ranSession = true
                        run(id, source, config, onFinished)
                    }

                    is PreviousJoin.TimedOut -> {
                        errorReported = true
                        // The session whose teardown is stuck may be older than the immediate
                        // previous one; this start never registered a provider either way.
                        publishStuckTeardown(id, join.sessionId, join.job, join.token)
                    }

                    PreviousJoin.AlreadyStuck -> {
                        errorReported = true
                        // Another start already detected the stuck teardown and registered the
                        // completion handler; just restate the refusal for this starting preview.
                        val token = stuckTeardownToken.get()
                        report(
                            id,
                            SessionState.Error(
                                sessionId = pendingTeardownSessionId ?: stuckTeardownSessionId,
                                reason = SessionErrorReason.TeardownTimeout,
                                terminal = false,
                            ),
                        )
                        // The stuck job can complete concurrently and clear the flag before this
                        // Error existed; resolve that race so the state cannot stay blocked.
                        if (!teardownStuck) onStuckTeardownFinished(token)
                    }
                }
            } finally {
                // Every exit path must release the service. `run` owns the callback once it is
                // entered; this covers cancellation while waiting for a previous teardown.
                if (!ranSession && isCurrent(id)) {
                    accepting = false
                    clearSessionTimestamps()
                    val pendingNow = pendingTeardownJob
                    val unresolved: TeardownTarget? = when {
                        pendingNow != null && !pendingNow.isCompleted ->
                            TeardownTarget(pendingNow, pendingTeardownSessionId ?: previousSessionId)

                        previous != null && !previous.isCompleted ->
                            TeardownTarget(previous, previousSessionId)

                        else -> null
                    }
                    if (!errorReported) {
                        when {
                            teardownStuck -> {
                                // Another teardown generation is already tracked and still
                                // running. Restore its non-terminal window instead of claiming
                                // Idle (a startable state the runner would immediately refuse).
                                report(
                                    id,
                                    SessionState.Error(
                                        sessionId = stuckTeardownSessionId,
                                        reason = SessionErrorReason.TeardownTimeout,
                                        terminal = false,
                                    ),
                                )
                            }

                            unresolved != null -> {
                                // B1-H1: cancelling a start that was waiting on a still-running
                                // previous teardown must not orphan that teardown. The job is
                                // already cancelled (isActive=false) but its NonCancellable
                                // cleanup is still running; only isCompleted says it is safe to
                                // start again. Keep it tracked (stuck flag + completion resolver)
                                // and publish the non-terminal window.
                                val token = markTeardownStuck(unresolved.job, unresolved.sessionId)
                                publishStuckTeardown(id, unresolved.sessionId, unresolved.job, token)
                            }

                            else -> {
                                // A start cancelled before the engine was touched has no session
                                // to tear down; keeping Starting/Stopping here would leave the UI
                                // stuck forever.
                                statusStore.emit(SessionState.Idle)
                            }
                        }
                    }
                    onFinished(id)
                }
            }
        }
    }

    /**
     * Cancels the loop and publishes Stopping for a session that is still alive.
     *
     * A session in Idle, terminal Error or the non-terminal teardown-timeout Error is not
     * active: it publishes no Stopping, so a repeated stop cannot overwrite the reason or re-enter
     * the timeout window.
     */
    fun stop() {
        val state = statusStore.state.value
        val wasActive = accepting && job?.isActive == true && state.isSessionActive
        // Set before the Stopping emission: a push that was already in flight must not publish
        // Running/Degraded after this point (see report()).
        stopping = true
        accepting = false
        if (wasActive) {
            // A session stopped before the engine accepted it has no epoch yet; the start-request
            // time is the only session timestamp available, and Stopping must still be visible.
            val epoch = currentSessionEpochMs ?: currentSessionStartedAtMs
            if (epoch != null) {
                statusStore.emit(SessionState.Stopping(sessionId = sessionId, sessionEpochElapsedMs = epoch))
            }
        }
        job?.cancel()
        // Keep `job` so a following start() can join the teardown.
    }

    /**
     * Cancels the loop and waits at most [timeoutMs] for its teardown.
     *
     * Bounded on purpose: a provider call that never returns must not turn a stop into an
     * unbounded wait on the main thread. Returns false when the budget expired, in which case the
     * state has been set to a non-terminal TeardownTimeout error and Start remains blocked until
     * the stuck job completes.
     */
    suspend fun stopAndJoin(timeoutMs: Long = STOPPING_BUDGET_MS): Boolean {
        stop()
        val current = job ?: return true
        val id = sessionId
        val joined = withTimeoutOrNull(timeoutMs) { current.join() }
        if (joined != null) return true

        if (isCurrent(id) && !isTeardownTimeoutReported()) {
            val token = markTeardownStuck(current, id)
            publishStuckTeardown(id, id, current, token)
        }
        return false
    }

    /**
     * Outcome of waiting for the previous session's teardown before a new start.
     *
     * The distinction matters: only a *newly* detected timeout may resolve itself via
     * [onStuckTeardownFinished] when the job completed concurrently; an already-stuck teardown is
     * owned by the handler registered when it was first detected.
     */
    private sealed interface PreviousJoin {
        data object Joined : PreviousJoin
        data object AlreadyStuck : PreviousJoin
        data class TimedOut(val job: Job, val token: Long, val sessionId: Long) : PreviousJoin
    }

    /** A job a new start must wait for before it may register providers. */
    private data class TeardownTarget(val job: Job, val sessionId: Long)

    /**
     * Waits for every unresolved teardown before allowing a new one to register providers.
     *
     * The list is oldest-first and includes both the global pending teardown and the immediate
     * previous session, so a superseding start cannot skip an older cleanup (B1-H2).
     *
     * An unbounded `join()` would let a hung teardown block every later start forever. Continuing
     * after a timeout would re-introduce the old bug where a late teardown unregisters the new
     * session's providers. So a timeout refuses the start and keeps refusing it until the oldest
     * unresolved job actually completes.
     */
    private suspend fun awaitPrevious(targets: List<TeardownTarget>): PreviousJoin {
        val unresolved = targets.filter { !it.job.isCompleted }
        if (unresolved.isEmpty()) {
            clearPendingTeardownIfCompleted()
            // Re-check after joining: a concurrent stop/new start may have established a window
            // whose job was not in this start's captured targets.
            return if (teardownStuck) PreviousJoin.AlreadyStuck else PreviousJoin.Joined
        }
        if (teardownStuck) return PreviousJoin.AlreadyStuck

        val joined = withTimeoutOrNull(stopJoinTimeoutMs) {
            unresolved.forEach { it.job.join() }
        }
        if (joined != null) {
            clearPendingTeardownIfCompleted()
            return if (teardownStuck) PreviousJoin.AlreadyStuck else PreviousJoin.Joined
        }

        // Oldest first: the pending teardown was captured before the immediate previous session.
        val stuck = unresolved.firstOrNull { !it.job.isCompleted }
        if (stuck == null) {
            clearPendingTeardownIfCompleted()
            return if (teardownStuck) PreviousJoin.AlreadyStuck else PreviousJoin.Joined
        }
        val token = markTeardownStuck(stuck.job, stuck.sessionId)
        return PreviousJoin.TimedOut(stuck.job, token, stuck.sessionId)
    }

    /** Drops a pending reference whose job has already completed and is no longer stuck. */
    private fun clearPendingTeardownIfCompleted() {
        synchronized(stateLock) {
            if (!teardownStuck && pendingTeardownJob?.isCompleted == true) {
                pendingTeardownJob = null
                pendingTeardownSessionId = null
            }
        }
    }

    /**
     * Starts a stuck-teardown generation: sets the refusal flag and registers a one-shot resolver.
     *
     * The whole transition is under [stateLock] together with [onStuckTeardownFinished], so an old
     * handler cannot clear the flag or pending reference written by a newer mark (B-1 re-audit
     * R3). The returned token additionally lets callers resolve the completion race.
     */
    private fun markTeardownStuck(stuck: Job, stuckSessionId: Long): Long {
        synchronized(stateLock) {
            val token = stuckTeardownToken.incrementAndGet()
            pendingTeardownJob = stuck
            pendingTeardownSessionId = stuckSessionId
            stuckTeardownSessionId = stuckSessionId
            teardownStuck = true
            // If the job already completed this runs synchronously and re-enters stateLock; the
            // JVM monitor is reentrant, and the token check keeps it correct.
            stuck.invokeOnCompletion { onStuckTeardownFinished(token) }
            return token
        }
    }

    /**
     * Publishes the non-terminal teardown-timeout window for a stuck cleanup.
     *
     * This is the only state that represents "session over, but providers may still be
     * unregistering": it blocks Start and offers no Stop. It deliberately replaces a previous
     * terminal Error when the cleanup is stuck, because a terminal Error would re-enable Start
     * while the runner still refuses it (B-1 re-audit R4).
     */
    private fun publishStuckTeardown(
        reportingSessionId: Long,
        stuckSessionId: Long,
        stuck: Job,
        token: Long,
    ) {
        report(
            reportingSessionId,
            SessionState.Error(
                sessionId = stuckSessionId,
                reason = SessionErrorReason.TeardownTimeout,
                terminal = false,
            ),
        )
        // The job may have finished between the timeout detection and this publication; the
        // completion handler may therefore have run before the Error existed.
        if (stuck.isCompleted) onStuckTeardownFinished(token)
    }

    /**
     * Resolves the non-terminal teardown-timeout window once the stuck job has actually finished.
     *
     * Only the generation that is still current may act; the token check and every flag write are
     * inside [stateLock], so a stale handler cannot clear a newer generation (B-1 re-audit R3).
     * The state read + Idle emission are serialized with a new start's Starting emission, so this
     * cannot clobber a newer Starting. A state that is not the timeout window (for example a
     * terminal Error published concurrently) is left untouched: the timeout window deliberately
     * supersedes a failed session's terminal Error while the cleanup is stuck (B-1 re-audit R4),
     * and resolving it must not invent an Idle over anything else.
     */
    private fun onStuckTeardownFinished(token: Long) {
        synchronized(stateLock) {
            if (token != stuckTeardownToken.get()) return
            val state = statusStore.state.value
            teardownStuck = false
            stuckTeardownSessionId = null
            if (pendingTeardownJob?.isCompleted == true) {
                pendingTeardownJob = null
                pendingTeardownSessionId = null
            }
            if (state is SessionState.Error &&
                !state.terminal &&
                state.reason == SessionErrorReason.TeardownTimeout
            ) {
                statusStore.emit(SessionState.Idle)
            }
        }
    }

    /** True while the published state is the non-terminal teardown-timeout window. */
    private fun isTeardownTimeoutReported(): Boolean {
        val state = statusStore.state.value
        return state is SessionState.Error &&
            !state.terminal &&
            state.reason == SessionErrorReason.TeardownTimeout
    }

    private suspend fun run(
        id: Long,
        source: MovementSource,
        config: SessionConfig,
        onFinished: (Long) -> Unit,
    ) {
        var failed = false
        // Whether the terminal failure actually reached the store. A failure that happens after
        // stop() is suppressed by report(); the stop outcome (Idle after teardown) must then win
        // instead of leaving the state on Stopping forever.
        var failurePublished = false

        try {
            // A hung start must not keep the service in a "starting" limbo forever. The composite
            // already bounds each engine; this is the outer guard for a composite that itself
            // never returns.
            val startOutcome = withTimeoutOrNull(startTimeoutMs) {
                try {
                    engine.startMockMode(id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    EngineStartOutcome.Failed(e.message)
                }
            }
            if (startOutcome != EngineStartOutcome.Ok) {
                failed = true
                failurePublished = report(
                    id,
                    SessionState.Error(
                        sessionId = id,
                        reason = SessionErrorReason.EngineStartFailed,
                        terminal = true,
                    ),
                )
                return
            }

            // Phase 1 decision (B-1 audit F5): the composite returns Ok when at least one engine
            // started, so a partial engine failure stays Running and is visible through
            // EngineHealthReporter. Degraded is reserved for push failures, not partial starts.
            //
            // Captured exactly once, after the engine accepted the session. Every Running and
            // Degraded state for this session carries this same value; only Idle and terminal
            // Error clear it.
            val epoch = clock.nowMs()
            currentSessionEpochMs = epoch
            var tick = 0
            var consecutivePushFailures = 0
            var lastPoint: GeoPoint? = null

            while (currentCoroutineContext().isActive) {
                // A revoked AppOp makes every later push fail; terminating here is what stops the
                // permanent Error-every-second loop. Checked every ten ticks, before the push, so
                // the session does not keep injecting after the user revoked it.
                if (tick > 0 && tick % PERMISSION_RECHECK_TICKS == 0 && !permissionChecker.canMockLocation()) {
                    failed = true
                    failurePublished = report(
                        id,
                        SessionState.Error(
                            sessionId = id,
                            reason = SessionErrorReason.MockAppNotSelected,
                            terminal = true,
                        ),
                    )
                    return
                }

                val elapsed = clock.nowMs() - epoch
                val fix = try {
                    source.fixAt(elapsed)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed = true
                    failurePublished = report(
                        id,
                        SessionState.Error(
                            sessionId = id,
                            reason = SessionErrorReason.SourceInvalid,
                            terminal = true,
                        ),
                    )
                    return
                }

                val pushOutcome = try {
                    engine.push(fix)
                } catch (e: CancellationException) {
                    // Must be rethrown: absorbing cancellation here would let a stop request be
                    // swallowed by an in-flight suspension and leave the loop injecting.
                    throw e
                } catch (e: Exception) {
                    EnginePushOutcome.AllRejected
                }

                when (pushOutcome) {
                    EnginePushOutcome.Ok -> {
                        consecutivePushFailures = 0
                        lastPoint = fix.point
                        // Identical values (static source, same epoch, same point) collapse in the
                        // StateFlow, so a static session publishes Running exactly once. A route
                        // publishes a new Running only when the injected point changes. A push
                        // that returned after stop() is suppressed by report().
                        report(id, SessionState.Running(id, epoch, lastPoint))
                    }

                    EnginePushOutcome.AllRejected -> {
                        consecutivePushFailures++
                        if (consecutivePushFailures >= MAX_CONSECUTIVE_PUSH_FAILURES) {
                            failed = true
                            failurePublished = report(
                                id,
                                SessionState.Error(
                                    sessionId = id,
                                    reason = SessionErrorReason.TooManyConsecutiveFailures,
                                    terminal = true,
                                ),
                            )
                            return
                        }
                        // One rejected tick is a warning, not a session failure — the next tick
                        // may succeed. Degraded (rather than Error) keeps the session alive and
                        // stop-able while the UI shows why no position is being injected.
                        report(
                            id,
                            SessionState.Degraded(
                                sessionId = id,
                                sessionEpochElapsedMs = epoch,
                                lastPointWgs84 = lastPoint,
                                reason = SessionDegradedReason.PushRejected,
                                consecutiveFailures = consecutivePushFailures,
                            ),
                        )
                    }
                }

                if (source.isComplete(elapsed)) break

                tick++
                // Anchor the next deadline to the session epoch, and jump over whole intervals if
                // the clock moved on (deep sleep). Advancing one tick at a time would replay every
                // missed interval in a tight burst the moment the device wakes up.
                val nextTickAt = epoch + ((elapsed / config.intervalMs) + 1) * config.intervalMs
                val wait = nextTickAt - clock.nowMs()
                if (wait > 0) delay(wait)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
            failurePublished = report(
                id,
                SessionState.Error(
                    sessionId = id,
                    reason = SessionErrorReason.Unknown,
                    terminal = true,
                ),
            )
        } finally {
            // Runs on every exit path, including cancellation and a failure during start. A
            // LocationManager test provider is registered with the system and survives this
            // process, so skipping teardown would leave the device reporting a mock position to
            // every app until it is rebooted.
            try {
                withContext(NonCancellable) {
                    try {
                        engine.stopMockMode()
                    } catch (_: Exception) {
                        // Includes CancellationException: the block is already NonCancellable, so
                        // there is no caller cancellation left to preserve, and an engine that
                        // throws here must not keep the service in the foreground.
                    }
                }
            } finally {
                // A teardown that rethrows must not skip the state reset or onFinished: without
                // onFinished the service can stay foreground with a stale notification forever.
                if (isCurrent(id)) {
                    accepting = false
                    clearSessionTimestamps()
                    // A failed session keeps the terminal Error it actually published; resetting to
                    // Idle would hide why it ended. A clean completion, or a failure that was
                    // suppressed because the user had already stopped, ends on Idle: the stop is
                    // the outcome, and leaving Stopping behind would strand the UI.
                    if (!failed || !failurePublished) statusStore.emit(SessionState.Idle)
                    onFinished(id)
                }
            }
        }
    }

    /**
     * Publishes [session] for the current id, subject to the stop-window rules.
     *
     * A push that was already in flight when stop() ran can return after Stopping was published.
     * It must not flip the phase back to Running/Degraded, and a *session failure* it discovers is
     * not a teardown outcome either (the matrix has Stopping -> Idle/TeardownTimeout, not a push
     * failure). Only the non-terminal teardown-timeout error, Idle and Stopping remain publishable
     * in that window.
     *
     * @return true when the state reached the store.
     */
    private fun report(id: Long, session: SessionState): Boolean {
        if (!isCurrent(id)) return false
        if (stopping) {
            when (session) {
                is SessionState.Running, is SessionState.Degraded -> return false
                is SessionState.Error ->
                    if (session.terminal || session.reason != SessionErrorReason.TeardownTimeout) {
                        return false
                    }
                SessionState.Idle, is SessionState.Starting, is SessionState.Stopping -> Unit
            }
        }
        statusStore.emit(session)
        return true
    }

    private fun clearSessionTimestamps() {
        currentSessionStartedAtMs = null
        currentSessionEpochMs = null
    }

    private fun isCurrent(id: Long): Boolean = id == sessionId

    companion object {
        /** How long a start waits for the previous session's teardown before refusing to start. */
        const val STOPPING_BUDGET_MS = 3_000L

        /**
         * The composite bounds each engine start at 5s, so two engines can legitimately take up to
         * 10s. This outer guard only catches a composite that never returns.
         */
        const val SESSION_START_TIMEOUT_MS = 12_000L

        private const val PERMISSION_RECHECK_TICKS = 10
        private const val MAX_CONSECUTIVE_PUSH_FAILURES = 5
    }
}
