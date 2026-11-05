package com.snake.mobilespoofing.core.location

import com.snake.mobilespoofing.core.geo.EndBehavior
import com.snake.mobilespoofing.core.geo.GeoMath
import com.snake.mobilespoofing.core.geo.Route
import com.snake.mobilespoofing.core.geo.RouteMovementSource
import com.snake.mobilespoofing.core.geo.StaticMovementSource
import com.snake.mobilespoofing.core.model.CoordType
import com.snake.mobilespoofing.core.model.Fix
import com.snake.mobilespoofing.core.model.GeoPoint
import com.snake.mobilespoofing.core.model.SessionDegradedReason
import com.snake.mobilespoofing.core.model.SessionErrorReason
import com.snake.mobilespoofing.core.model.SessionPhase
import com.snake.mobilespoofing.core.model.SessionState
import com.snake.mobilespoofing.core.model.canStartNewSession
import com.snake.mobilespoofing.core.model.isSessionActive
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Virtual time throughout: `delay` is free, the clock is the test scheduler, and the whole session
 * loop runs deterministically without a device.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MockSessionRunnerTest {

    private val point = GeoPoint(39.9042, 116.4074, CoordType.WGS84)

    private companion object {
        /** Mirrors MockSessionRunner's private terminal threshold for fake engines. */
        const val TERMINAL_PUSH_FAILURES = 5
    }

    private class RecordingEngine(
        private val startOutcome: EngineStartOutcome = EngineStartOutcome.Ok,
        private val failingPushAttempts: Set<Int> = emptySet(),
        private val pushOutcomeAlways: EnginePushOutcome? = null,
        private val hangStart: Boolean = false,
        private val hangStop: Boolean = false,
    ) : MockLocationEngine {
        val pushed = mutableListOf<Fix>()
        val sessionIds = mutableListOf<Long>()
        var startCount = 0
        var stopCount = 0
        var pushAttempts = 0

        override suspend fun startMockMode(sessionId: Long): EngineStartOutcome {
            startCount++
            sessionIds += sessionId
            if (hangStart) awaitCancellation()
            return startOutcome
        }

        override suspend fun push(fix: Fix): EnginePushOutcome {
            pushAttempts++
            val outcome = pushOutcomeAlways
                ?: if (pushAttempts in failingPushAttempts) {
                    EnginePushOutcome.AllRejected
                } else {
                    EnginePushOutcome.Ok
                }
            if (outcome == EnginePushOutcome.Ok) pushed += fix
            return outcome
        }

        override suspend fun stopMockMode(): List<ProviderStopResult> {
            stopCount++
            if (hangStop) awaitCancellation()
            return emptyList()
        }
    }

    /** Suspends the teardown until the test releases it, to model a slow but eventually finishing stop. */
    private class ControllableStopEngine : MockLocationEngine {
        val stopEntered = CompletableDeferred<Unit>()
        val allowStop = CompletableDeferred<Unit>()
        var startCount = 0
        var stopCount = 0

        override suspend fun startMockMode(sessionId: Long): EngineStartOutcome {
            startCount++
            return EngineStartOutcome.Ok
        }

        override suspend fun push(fix: Fix): EnginePushOutcome = EnginePushOutcome.Ok

        override suspend fun stopMockMode(): List<ProviderStopResult> {
            stopCount++
            stopEntered.complete(Unit)
            allowStop.await()
            return emptyList()
        }
    }

    /** A misbehaving engine whose teardown throws instead of returning. */
    private class ThrowOnStopEngine : MockLocationEngine {
        var stopCount = 0

        override suspend fun startMockMode(sessionId: Long): EngineStartOutcome = EngineStartOutcome.Ok

        override suspend fun push(fix: Fix): EnginePushOutcome = EnginePushOutcome.Ok

        override suspend fun stopMockMode(): List<ProviderStopResult> {
            stopCount++
            throw CancellationException("engine stop was cancelled")
        }
    }

    /** A push outcome that can change between sessions, for same-runner epoch-reset coverage. */
    private class MutablePushEngine : MockLocationEngine {
        @Volatile
        var pushOutcome: EnginePushOutcome = EnginePushOutcome.Ok
        var startCount = 0
        var stopCount = 0

        override suspend fun startMockMode(sessionId: Long): EngineStartOutcome {
            startCount++
            return EngineStartOutcome.Ok
        }

        override suspend fun push(fix: Fix): EnginePushOutcome = pushOutcome

        override suspend fun stopMockMode(): List<ProviderStopResult> {
            stopCount++
            return emptyList()
        }
    }

    /**
     * Models a binder-style push that has already been dispatched: the call completes under
     * NonCancellable, so stop() cannot recall it and the runner really sees Ok afterwards.
     */
    private class CancellationIgnoringPushEngine : MockLocationEngine {
        val pushEntered = CompletableDeferred<Unit>()
        val releasePush = CompletableDeferred<Unit>()

        @Volatile
        var pushReturned = false
        var startCount = 0
        var stopCount = 0

        override suspend fun startMockMode(sessionId: Long): EngineStartOutcome {
            startCount++
            return EngineStartOutcome.Ok
        }

        override suspend fun push(fix: Fix): EnginePushOutcome {
            pushEntered.complete(Unit)
            withContext(NonCancellable) { releasePush.await() }
            pushReturned = true
            return EnginePushOutcome.Ok
        }

        override suspend fun stopMockMode(): List<ProviderStopResult> {
            stopCount++
            return emptyList()
        }
    }

    /**
     * Fails the first four pushes immediately, then blocks the fifth in a NonCancellable suspension
     * so the test can stop() while a terminal failure is in flight.
     */
    private class FailingPushAfterStopEngine : MockLocationEngine {
        val fifthPushEntered = CompletableDeferred<Unit>()
        val releaseFifthPush = CompletableDeferred<Unit>()

        @Volatile
        var fifthPushReturned = false
        var pushAttempts = 0
        var startCount = 0
        var stopCount = 0

        override suspend fun startMockMode(sessionId: Long): EngineStartOutcome {
            startCount++
            return EngineStartOutcome.Ok
        }

        override suspend fun push(fix: Fix): EnginePushOutcome {
            pushAttempts++
            if (pushAttempts < TERMINAL_PUSH_FAILURES) return EnginePushOutcome.AllRejected
            fifthPushEntered.complete(Unit)
            withContext(NonCancellable) { releaseFifthPush.await() }
            fifthPushReturned = true
            return EnginePushOutcome.AllRejected
        }

        override suspend fun stopMockMode(): List<ProviderStopResult> {
            stopCount++
            return emptyList()
        }
    }

    /** Always rejects, and hangs the teardown until the test releases it. */
    private class FailThenHangStopEngine : MockLocationEngine {
        val stopEntered = CompletableDeferred<Unit>()
        val allowStop = CompletableDeferred<Unit>()
        var startCount = 0

        override suspend fun startMockMode(sessionId: Long): EngineStartOutcome {
            startCount++
            return EngineStartOutcome.Ok
        }

        override suspend fun push(fix: Fix): EnginePushOutcome = EnginePushOutcome.AllRejected

        override suspend fun stopMockMode(): List<ProviderStopResult> {
            stopEntered.complete(Unit)
            allowStop.await()
            return emptyList()
        }
    }

    private fun runnerFor(
        engine: MockLocationEngine,
        status: MockStatusStore,
        scheduler: TestCoroutineScheduler,
        permissionChecker: MockPermissionChecker = MockPermissionChecker { true },
        startTimeoutMs: Long = MockSessionRunner.SESSION_START_TIMEOUT_MS,
        stopJoinTimeoutMs: Long = MockSessionRunner.STOPPING_BUDGET_MS,
    ): MockSessionRunner = MockSessionRunner(
        engine = engine,
        statusStore = status,
        clock = ElapsedClock { scheduler.currentTime },
        dispatcher = StandardTestDispatcher(scheduler),
        permissionChecker = permissionChecker,
        startTimeoutMs = startTimeoutMs,
        stopJoinTimeoutMs = stopJoinTimeoutMs,
    )

    /** Collects every distinct state the store publishes for the duration of the test. */
    private fun CoroutineScope.collectStates(
        status: MockStatusStore,
        into: MutableList<SessionState>,
    ) {
        launch {
            status.state.collect { into += it }
        }
    }

    @Test
    fun `pushes one fix per interval`() = runTest {
        val engine = RecordingEngine()
        val status = MockStatusStore()
        val runner = runnerFor(engine, status, testScheduler)

        runner.start(backgroundScope, StaticMovementSource(point), SessionConfig(intervalMs = 1_000L))
        advanceTimeBy(3_500L)

        assertEquals(1, engine.startCount)
        // Ticks land at t = 0, 1000, 2000, 3000.
        assertEquals(4, engine.pushed.size)
        assertTrue(status.state.value is SessionState.Running)
    }

    @Test
    fun `a static session emits Running exactly once`() = runTest {
        // Running carries no per-tick timestamp, so every tick after the first produces an equal
        // value and the StateFlow collapses it. This is the invariant that stops the 1 Hz loop
        // from waking Compose, the notification and the view-model.
        val engine = RecordingEngine()
        val status = MockStatusStore()
        val runner = runnerFor(engine, status, testScheduler)
        val emissions = mutableListOf<SessionState>()
        backgroundScope.collectStates(status, emissions)
        runCurrent()

        runner.start(backgroundScope, StaticMovementSource(point), SessionConfig(intervalMs = 1_000L))
        runCurrent()
        advanceTimeBy(3_500L)
        runCurrent()

        assertEquals(4, engine.pushed.size)
        assertEquals(1, emissions.count { it is SessionState.Running })

        val running = emissions.filterIsInstance<SessionState.Running>().single()
        assertEquals(1L, running.sessionId)
        assertEquals(0L, running.sessionEpochElapsedMs)
        assertEquals(point, running.lastPointWgs84)
    }

    @Test
    fun `phase transitions are Starting then Running then Stopping then Idle`() = runTest {
        val engine = RecordingEngine()
        val status = MockStatusStore()
        val runner = runnerFor(engine, status, testScheduler)
        val emissions = mutableListOf<SessionState>()
        backgroundScope.collectStates(status, emissions)
        runCurrent()

        runner.start(backgroundScope, StaticMovementSource(point), SessionConfig(intervalMs = 1_000L))
        runCurrent()
        advanceTimeBy(2_500L)
        runCurrent()

        runner.stopAndJoin()
        runCurrent()

        assertEquals(
            listOf(
                // The initial StateFlow value delivered to the new collector.
                SessionPhase.Idle,
                SessionPhase.Starting,
                SessionPhase.Running,
                SessionPhase.Stopping,
                SessionPhase.Idle,
            ),
            emissions.map { it.phase },
        )

        // The phase window must stay on one session and one epoch throughout.
        val starting = emissions.filterIsInstance<SessionState.Starting>().single()
        val running = emissions.filterIsInstance<SessionState.Running>().first()
        val stopping = emissions.filterIsInstance<SessionState.Stopping>().single()
        assertEquals(1L, starting.sessionId)
        assertEquals(starting.sessionId, running.sessionId)
        assertEquals(running.sessionId, stopping.sessionId)
        assertEquals(running.sessionEpochElapsedMs, stopping.sessionEpochElapsedMs)
    }

    @Test
    fun `nextSessionId matches the id published by the next start`() = runTest {
        val status = MockStatusStore()
        val runner = runnerFor(RecordingEngine(), status, testScheduler)

        assertEquals(1L, runner.nextSessionId())

        runner.start(backgroundScope, StaticMovementSource(point), SessionConfig(intervalMs = 1_000L))

        // The service previews this id before the gate; the runner must reuse it.
        assertEquals(1L, (status.state.value as SessionState.Starting).sessionId)
        assertEquals(2L, runner.nextSessionId())
    }

    @Test
    fun `a push that returns after stop does not publish Running`() = runTest {
        // Cancellation is cooperative; a dispatched push can return Ok after stop() published
        // Stopping. That late success must not flip the active phase back to Running.
        val engine = CancellationIgnoringPushEngine()
        val status = MockStatusStore()
        val runner = runnerFor(engine, status, testScheduler)
        val emissions = mutableListOf<SessionState>()
        backgroundScope.collectStates(status, emissions)
        runCurrent()

        runner.start(backgroundScope, StaticMovementSource(point), SessionConfig(intervalMs = 1_000L))
        runCurrent()
        assertTrue(engine.pushEntered.isCompleted)

        runner.stop()
        runCurrent()

        engine.releasePush.complete(Unit)
        runCurrent()
        advanceTimeBy(500L)
        runCurrent()

        // The engine really did return Ok after the Stopping state was published; only the
        // runner's stop suppression keeps Running out of the stream.
        assertTrue(engine.pushReturned)
        assertEquals(
            listOf(
                SessionPhase.Idle,
                SessionPhase.Starting,
                SessionPhase.Stopping,
                SessionPhase.Idle,
            ),
            emissions.map { it.phase },
        )
    }

    @Test
    fun `a stop whose teardown exceeds the budget recovers to Idle when it finally finishes`() = runTest {
        val engine = ControllableStopEngine()
        val status = MockStatusStore()
        val runner = runnerFor(engine, status, testScheduler)
        var finished = 0

        runner.start(
            backgroundScope,
            StaticMovementSource(point),
            SessionConfig(intervalMs = 1_000L),
            onFinished = { _ -> finished++ },
        )
        advanceTimeBy(500L)

        assertFalse(runner.stopAndJoin(timeoutMs = 1_000L))

        val error = status.state.value as SessionState.Error
        assertEquals(SessionErrorReason.TeardownTimeout, error.reason)
        assertEquals(1L, error.sessionId)
        // Non-terminal on purpose: Start stays blocked until the old teardown is really done,
        // while Stop has nothing left to offer.
        assertFalse(error.terminal)
        assertFalse(error.isSessionActive)
        assertFalse(error.canStartNewSession)

        // A repeated stop must not restart the timeout window or publish another error.
        assertFalse(runner.stopAndJoin(timeoutMs = 500L))
        assertTrue(status.state.value is SessionState.Error)

        engine.allowStop.complete(Unit)
        advanceTimeBy(500L)
        runCurrent()

        assertTrue(status.state.value is SessionState.Idle)
        assertTrue(status.state.value.canStartNewSession)
        assertEquals(1, finished)

        // The same runner accepts a new session once the stuck cleanup finished.
        runner.start(backgroundScope, StaticMovementSource(point), SessionConfig(intervalMs = 1_000L))
        advanceTimeBy(500L)
        assertTrue(status.state.value is SessionState.Running)
        assertEquals(2, engine.startCount)
    }

    @Test
    fun `a fifth push failure that returns after stop does not publish a terminal error`() = runTest {
        // G4: cancellation is cooperative, so the fifth (terminal) failure can be discovered after
        // Stopping was published. It is a push failure, not a teardown outcome, and must not
        // replace the stop with a terminal Error.
        val engine = FailingPushAfterStopEngine()
        val status = MockStatusStore()
        val runner = runnerFor(engine, status, testScheduler)
        val emissions = mutableListOf<SessionState>()
        backgroundScope.collectStates(status, emissions)
        runCurrent()

        runner.start(backgroundScope, StaticMovementSource(point), SessionConfig(intervalMs = 1_000L))
        // Ticks 0..3 fail; tick 4 (t = 4000) is the fifth failure and blocks in the fake.
        advanceTimeBy(4_500L)
        runCurrent()
        assertTrue(engine.fifthPushEntered.isCompleted)
        assertEquals(4, emissions.count { it is SessionState.Degraded })
        assertTrue(emissions.none { it is SessionState.Error })

        runner.stop()
        runCurrent()

        engine.releaseFifthPush.complete(Unit)
        runCurrent()
        advanceTimeBy(500L)
        runCurrent()

        // The terminal branch really ran after Stopping; only the stop suppression keeps it out.
        assertTrue(engine.fifthPushReturned)
        assertTrue(emissions.none { it is SessionState.Error })
        assertTrue(status.state.value is SessionState.Idle)
    }

    @Test
    fun `a terminal error with a stuck teardown becomes a non-startable timeout window`() = runTest {
        // R4: replacing the terminal error with the non-terminal window is deliberate. Keeping the
        // terminal Error would leave canStartNewSession=true while the runner refuses every start,
        // so Start would be enabled but always fail with another TeardownTimeout.
        val engine = FailThenHangStopEngine()
        val status = MockStatusStore()
        val runner = runnerFor(engine, status, testScheduler)

        runner.start(backgroundScope, StaticMovementSource(point), SessionConfig(intervalMs = 1_000L))
        // Five failures end the session; the teardown then hangs.
        advanceTimeBy(5_500L)
        val failed = status.state.value as SessionState.Error
        assertEquals(SessionErrorReason.TooManyConsecutiveFailures, failed.reason)
        assertTrue(failed.terminal)
        assertTrue(engine.stopEntered.isCompleted)

        assertFalse(runner.stopAndJoin(timeoutMs = 1_000L))

        val window = status.state.value as SessionState.Error
        assertEquals(SessionErrorReason.TeardownTimeout, window.reason)
        assertFalse(window.terminal)
        // Start disabled, Stop disabled: exactly the stuck-cleanup window.
        assertFalse(window.canStartNewSession)
        assertFalse(window.isSessionActive)

        engine.allowStop.complete(Unit)
        advanceTimeBy(500L)
        runCurrent()

        assertTrue(status.state.value is SessionState.Idle)

        // The stuck flag cleared: a new session is accepted (and immediately degrades on this
        // always-rejecting engine) instead of being refused with another TeardownTimeout.
        runner.start(backgroundScope, StaticMovementSource(point), SessionConfig(intervalMs = 1_000L))
        advanceTimeBy(500L)
        assertTrue(status.state.value is SessionState.Degraded)
        assertEquals(2, engine.startCount)
    }

    @Test
    fun `cancelling a start that waits for a stuck teardown does not orphan it`() = runTest {
        // B1-H1: A failed and its teardown hangs. B starts and is cancelled before the join budget
        // expires. B's cancellation must not emit Idle and forget A: the cleanup stays tracked, so
        // a later C cannot start its engine (and register/clear providers) until A is done.
        val engine = FailThenHangStopEngine()
        val status = MockStatusStore()
        val runner = runnerFor(engine, status, testScheduler)

        // A: five failures end the session, then its NonCancellable teardown hangs.
        runner.start(backgroundScope, StaticMovementSource(point), SessionConfig(intervalMs = 1_000L))
        advanceTimeBy(5_500L)
        val failed = status.state.value as SessionState.Error
        assertEquals(SessionErrorReason.TooManyConsecutiveFailures, failed.reason)
        assertTrue(engine.stopEntered.isCompleted)
        assertEquals(1, engine.startCount)

        // B starts and suspends joining A's teardown.
        runner.start(backgroundScope, StaticMovementSource(point), SessionConfig(intervalMs = 1_000L))
        runCurrent()

        // Cancel B before the stopping budget expires.
        runner.stop()
        runCurrent()

        val window = status.state.value as SessionState.Error
        assertEquals(SessionErrorReason.TeardownTimeout, window.reason)
        assertFalse(window.terminal)
        // The window names A, the session whose cleanup is actually stuck.
        assertEquals(1L, window.sessionId)
        assertFalse(window.canStartNewSession)

        // C is refused without touching the engine: no provider registration can precede A's
        // cleanup. The fake's startCount is the engine-start/provider-add proxy.
        runner.start(backgroundScope, StaticMovementSource(point), SessionConfig(intervalMs = 1_000L))
        runCurrent()
        assertEquals(1, engine.startCount)
        assertTrue(status.state.value is SessionState.Error)

        // A's cleanup finally completes; the window resolves to Idle and C may start.
        engine.allowStop.complete(Unit)
        advanceTimeBy(500L)
        runCurrent()
        assertTrue(status.state.value is SessionState.Idle)

        runner.start(backgroundScope, StaticMovementSource(point), SessionConfig(intervalMs = 1_000L))
        advanceTimeBy(500L)
        assertEquals(2, engine.startCount)
        assertTrue(status.state.value is SessionState.Degraded)
    }

    @Test
    fun `superseding a start that waits for a stuck teardown does not orphan it`() = runTest {
        // B1-H2: B waits for A; C supersedes B directly (no stop). B's wrapper is no longer current
        // and cannot record A, so the oldest teardown must be tracked independently of sessionId.
        val engine = FailThenHangStopEngine()
        val status = MockStatusStore()
        val runner = runnerFor(engine, status, testScheduler)

        // A: five failures end the session, then its NonCancellable teardown hangs.
        runner.start(backgroundScope, StaticMovementSource(point), SessionConfig(intervalMs = 1_000L))
        advanceTimeBy(5_500L)
        assertTrue(status.state.value is SessionState.Error)
        assertTrue(engine.stopEntered.isCompleted)
        assertEquals(1, engine.startCount)

        // B starts and waits for A's teardown.
        runner.start(backgroundScope, StaticMovementSource(point), SessionConfig(intervalMs = 1_000L))
        runCurrent()

        // C supersedes B while A is still hung.
        runner.start(backgroundScope, StaticMovementSource(point), SessionConfig(intervalMs = 1_000L))
        advanceTimeBy(MockSessionRunner.STOPPING_BUDGET_MS + 500L)
        runCurrent()

        // C must not have started its engine: A's cleanup still owns the provider lifecycle.
        assertEquals(1, engine.startCount)
        val window = status.state.value as SessionState.Error
        assertEquals(SessionErrorReason.TeardownTimeout, window.reason)
        assertFalse(window.terminal)
        // The window names A, the oldest unresolved teardown, not B or C.
        assertEquals(1L, window.sessionId)
        assertFalse(window.canStartNewSession)

        // A's cleanup finally completes; the window resolves and a later start may proceed.
        engine.allowStop.complete(Unit)
        advanceTimeBy(500L)
        runCurrent()
        assertTrue(status.state.value is SessionState.Idle)

        runner.start(backgroundScope, StaticMovementSource(point), SessionConfig(intervalMs = 1_000L))
        advanceTimeBy(500L)
        assertEquals(2, engine.startCount)
        assertTrue(status.state.value is SessionState.Degraded)
    }

    @Test
    fun `tick scheduling does not drift when a push is slow`() = runTest {
        // The pushes must stay anchored to the session epoch. Scheduling each tick as delay(interval)
        // from "now" would fold the push's own cost into every subsequent deadline.
        val engine = RecordingEngine()
        val runner = runnerFor(engine, MockStatusStore(), testScheduler)

        runner.start(backgroundScope, StaticMovementSource(point), SessionConfig(intervalMs = 1_000L))
        advanceTimeBy(10_500L)

        // 10 s of session at 1 Hz is 11 ticks, not 10.
        assertEquals(11, engine.pushed.size)
    }

    @Test
    fun `cancelling tears the engine down and resets the status`() = runTest {
        val engine = RecordingEngine()
        val status = MockStatusStore()
        val runner = runnerFor(engine, status, testScheduler)

        runner.start(backgroundScope, StaticMovementSource(point), SessionConfig(intervalMs = 1_000L))
        advanceTimeBy(2_500L)
        runner.stop()
        advanceTimeBy(5_000L)

        // Teardown runs on the cancellation path too. Skipping it would leave a system-wide test
        // provider registered, which outlives the process.
        assertEquals(1, engine.stopCount)
        assertTrue(status.state.value is SessionState.Idle)
        // And nothing is injected after the stop.
        assertEquals(3, engine.pushed.size)
    }

    @Test
    fun `a single failed push degrades the session but the session continues`() = runTest {
        // One flaky tick must not end a session; the next tick may well succeed.
        val engine = RecordingEngine(failingPushAttempts = setOf(2))
        val status = MockStatusStore()
        val runner = runnerFor(engine, status, testScheduler)
        val emissions = mutableListOf<SessionState>()
        backgroundScope.collectStates(status, emissions)
        runCurrent()

        runner.start(backgroundScope, StaticMovementSource(point), SessionConfig(intervalMs = 1_000L))
        // Tick 1 succeeds, tick 2 fails and must be observable as Degraded without ending the loop.
        advanceTimeBy(1_500L)
        runCurrent()

        val degraded = emissions.filterIsInstance<SessionState.Degraded>().single()
        assertEquals(SessionDegradedReason.PushRejected, degraded.reason)
        assertEquals(1, degraded.consecutiveFailures)
        assertEquals(point, degraded.lastPointWgs84)
        assertFalse(emissions.any { it is SessionState.Error })

        advanceTimeBy(2_000L)
        runCurrent()
        assertTrue(status.state.value is SessionState.Running)
        assertEquals(3, engine.pushed.size)
    }

    @Test
    fun `a degraded session preserves the session epoch`() = runTest {
        val engine = RecordingEngine(failingPushAttempts = setOf(2))
        val status = MockStatusStore()
        val runner = runnerFor(engine, status, testScheduler)

        advanceTimeBy(500L)
        runner.start(backgroundScope, StaticMovementSource(point), SessionConfig(intervalMs = 1_000L))
        advanceTimeBy(1_500L)

        val degraded = status.state.value as SessionState.Degraded
        // The epoch is the post-start time, constant across Running and Degraded.
        assertEquals(500L, degraded.sessionEpochElapsedMs)

        advanceTimeBy(1_000L)

        val running = status.state.value as SessionState.Running
        assertEquals(500L, running.sessionEpochElapsedMs)
    }

    @Test
    fun `a failed start reports a terminal error and never pushes`() = runTest {
        val engine = RecordingEngine(startOutcome = EngineStartOutcome.Failed("engine refused to start"))
        val status = MockStatusStore()
        val runner = runnerFor(engine, status, testScheduler)
        var finished = 0

        runner.start(
            backgroundScope,
            StaticMovementSource(point),
            SessionConfig(intervalMs = 1_000L),
            onFinished = { _ -> finished++ },
        )
        advanceTimeBy(5_000L)

        val error = status.state.value as SessionState.Error
        assertEquals(SessionErrorReason.EngineStartFailed, error.reason)
        assertEquals(1L, error.sessionId)
        assertTrue(error.terminal)
        assertEquals(0, engine.pushAttempts)
        assertEquals(1, engine.stopCount)
        assertEquals(1, finished)
    }

    @Test
    fun `a stop-behaviour route ends on its own and tears down`() = runTest {
        val route = Route.of(
            listOf(
                point,
                GeoMath.destination(point, bearingDeg = 90.0, distanceMeters = 100.0),
            ),
        )
        val source = RouteMovementSource(route, speedMetersPerSecond = 10.0, endBehavior = EndBehavior.STOP)
        val engine = RecordingEngine()
        val status = MockStatusStore()
        val runner = runnerFor(engine, status, testScheduler)

        runner.start(backgroundScope, source, SessionConfig(intervalMs = 1_000L))
        // 100 m at 10 m/s is 10 s, so it is done well before this.
        advanceTimeBy(20_000L)

        assertEquals(1, engine.stopCount)
        assertTrue(status.state.value is SessionState.Idle)
    }

    @Test
    fun `restarting supersedes the old session without stopping the new one`() = runTest {
        val engine = RecordingEngine()
        val status = MockStatusStore()
        val runner = runnerFor(engine, status, testScheduler)
        var finished = 0

        runner.start(
            backgroundScope,
            StaticMovementSource(point),
            SessionConfig(intervalMs = 1_000L),
            onFinished = { _ -> finished++ },
        )
        advanceTimeBy(2_500L)
        assertEquals(3, engine.pushed.size)

        runner.start(
            backgroundScope,
            StaticMovementSource(point),
            SessionConfig(intervalMs = 1_000L),
            onFinished = { _ -> finished++ },
        )
        advanceTimeBy(2_500L)

        // The old session tore its engine down exactly once, then the new session started and
        // injected its own fixes. The old session must not have called onFinished — that callback
        // is what stops the Android service, and the service now belongs to the new session.
        assertEquals(2, engine.startCount)
        assertEquals(1, engine.stopCount)
        assertEquals(0, finished)
        assertEquals(6, engine.pushed.size)
        assertTrue(status.state.value is SessionState.Running)
    }

    @Test
    fun `the session id is passed to the engine`() = runTest {
        val engine = RecordingEngine()
        val runner = runnerFor(engine, MockStatusStore(), testScheduler)

        runner.start(backgroundScope, StaticMovementSource(point), SessionConfig(intervalMs = 1_000L))
        advanceTimeBy(500L)
        runner.start(backgroundScope, StaticMovementSource(point), SessionConfig(intervalMs = 1_000L))
        advanceTimeBy(500L)

        assertEquals(listOf(1L, 2L), engine.sessionIds)
    }

    @Test
    fun `cancelling during start still tears the engine down`() = runTest {
        val engine = RecordingEngine(hangStart = true)
        val status = MockStatusStore()
        val runner = runnerFor(engine, status, testScheduler)

        runner.start(backgroundScope, StaticMovementSource(point), SessionConfig(intervalMs = 1_000L))
        // runCurrent, not advanceUntilIdle: background-scope coroutines are not part of the
        // "idle" definition (they may be infinite), but runCurrent does execute them.
        runCurrent()
        assertEquals(1, engine.startCount)

        runner.stop()
        runCurrent()

        // Cancellation used to escape before the try/finally that calls stopMockMode, leaving the
        // system-wide test provider registered until reboot.
        assertEquals(1, engine.stopCount)
        assertTrue(status.state.value is SessionState.Idle)
    }

    @Test
    fun `a hanging start times out as a failure and still tears down`() = runTest {
        // The timeout must surface as an Error and run teardown/onFinished — never escape as a
        // CancellationException that skips the finally.
        val engine = RecordingEngine(hangStart = true)
        val status = MockStatusStore()
        val runner = runnerFor(engine, status, testScheduler, startTimeoutMs = 1_000L)
        var finished = 0

        runner.start(
            backgroundScope,
            StaticMovementSource(point),
            SessionConfig(intervalMs = 1_000L),
            onFinished = { _ -> finished++ },
        )
        advanceTimeBy(1_500L)

        val error = status.state.value as SessionState.Error
        assertEquals(SessionErrorReason.EngineStartFailed, error.reason)
        assertEquals(1, engine.stopCount)
        assertEquals(1, finished)
        assertEquals(0, engine.pushAttempts)
    }

    @Test
    fun `a previous teardown that exceeds the stopping budget refuses the new start`() = runTest {
        val engine = RecordingEngine(hangStop = true)
        val status = MockStatusStore()
        val runner = runnerFor(engine, status, testScheduler)
        var finished = 0

        runner.start(
            backgroundScope,
            StaticMovementSource(point),
            SessionConfig(intervalMs = 1_000L),
            onFinished = { _ -> finished++ },
        )
        advanceTimeBy(500L)

        // The old job is cancelled and hangs in NonCancellable teardown. The new start must wait
        // for the stopping budget, then refuse to register providers rather than overlap the old
        // teardown.
        runner.start(
            backgroundScope,
            StaticMovementSource(point),
            SessionConfig(intervalMs = 1_000L),
            onFinished = { _ -> finished++ },
        )
        advanceTimeBy(MockSessionRunner.STOPPING_BUDGET_MS + 500L)

        val error = status.state.value as SessionState.Error
        assertEquals(SessionErrorReason.TeardownTimeout, error.reason)
        assertFalse(error.terminal)
        assertFalse(error.canStartNewSession)
        assertEquals(1, engine.startCount)
        assertEquals(1, engine.stopCount)
        assertEquals(1, finished)

        // While the stuck teardown never completes, later starts are refused immediately and the
        // refusal stays non-terminal, so it cannot re-open the Start gate.
        runner.start(
            backgroundScope,
            StaticMovementSource(point),
            SessionConfig(intervalMs = 1_000L),
            onFinished = { _ -> finished++ },
        )
        advanceTimeBy(1_000L)

        assertEquals(1, engine.startCount)
        assertEquals(2, finished)
        val refusedAgain = status.state.value as SessionState.Error
        assertEquals(SessionErrorReason.TeardownTimeout, refusedAgain.reason)
        assertFalse(refusedAgain.terminal)
        assertFalse(refusedAgain.canStartNewSession)
    }

    @Test
    fun `five consecutive push failures terminate the session and tear down`() = runTest {
        val engine = RecordingEngine(pushOutcomeAlways = EnginePushOutcome.AllRejected)
        val status = MockStatusStore()
        val runner = runnerFor(engine, status, testScheduler)
        var finished = 0

        runner.start(
            backgroundScope,
            StaticMovementSource(point),
            SessionConfig(intervalMs = 1_000L),
            onFinished = { _ -> finished++ },
        )
        // Ticks at t = 0, 1, 2, 3, 4 are the fifth failure.
        advanceTimeBy(5_500L)

        val error = status.state.value as SessionState.Error
        assertEquals(SessionErrorReason.TooManyConsecutiveFailures, error.reason)
        assertEquals(1L, error.sessionId)
        assertTrue(error.terminal)
        assertEquals(5, engine.pushAttempts)
        assertEquals(1, engine.stopCount)
        assertEquals(1, finished)

        // The loop is gone: no further pushes, no further ticks.
        advanceTimeBy(5_000L)
        assertEquals(5, engine.pushAttempts)
    }

    @Test
    fun `a terminal error clears the epoch and the same runner captures a new one`() = runTest {
        // One runner instance, like production: this proves currentSessionEpochMs is cleared after
        // a terminal failure rather than merely re-created on a fresh runner object.
        val engine = MutablePushEngine()
        engine.pushOutcome = EnginePushOutcome.AllRejected
        val status = MockStatusStore()
        val runner = runnerFor(engine, status, testScheduler)

        advanceTimeBy(1_000L)
        runner.start(backgroundScope, StaticMovementSource(point), SessionConfig(intervalMs = 1_000L))
        advanceTimeBy(5_500L)

        val error = status.state.value as SessionState.Error
        assertEquals(SessionErrorReason.TooManyConsecutiveFailures, error.reason)

        // Terminal Error carries no epoch by construction; a fresh session on the same runner must
        // start from a new capture rather than inheriting the failed session's timer.
        advanceTimeBy(1_000L)
        engine.pushOutcome = EnginePushOutcome.Ok
        val startAt = testScheduler.currentTime

        runner.start(backgroundScope, StaticMovementSource(point), SessionConfig(intervalMs = 1_000L))
        advanceTimeBy(500L)

        val running = status.state.value as SessionState.Running
        assertEquals(startAt, running.sessionEpochElapsedMs)
        assertEquals(2L, running.sessionId)
        assertEquals(2, engine.startCount)
    }

    @Test
    fun `a successful push resets the consecutive failure counter`() = runTest {
        // Four rejections, one success, then four rejections: without the reset this would already
        // be nine consecutive failures and the session would have been terminated.
        val engine = RecordingEngine(failingPushAttempts = setOf(1, 2, 3, 4, 6, 7, 8, 9))
        val status = MockStatusStore()
        val runner = runnerFor(engine, status, testScheduler)

        runner.start(backgroundScope, StaticMovementSource(point), SessionConfig(intervalMs = 1_000L))
        advanceTimeBy(9_500L)

        // Attempts 5 and 10 succeed, so the counter never reaches five.
        assertEquals(10, engine.pushAttempts)
        assertEquals(0, engine.stopCount)
        assertTrue(status.state.value is SessionState.Running)
    }

    @Test
    fun `a revoked mock AppOp terminates the session at the next ten-tick check`() = runTest {
        var allowed = true
        val engine = RecordingEngine()
        val status = MockStatusStore()
        val runner = runnerFor(
            engine,
            status,
            testScheduler,
            permissionChecker = MockPermissionChecker { allowed },
        )

        runner.start(backgroundScope, StaticMovementSource(point), SessionConfig(intervalMs = 1_000L))
        advanceTimeBy(5_000L)
        assertEquals(0, engine.stopCount)
        assertTrue(status.state.value is SessionState.Running)

        allowed = false
        // Ticks 5..9 pass; tick 10 checks the AppOp before pushing.
        advanceTimeBy(5_500L)

        val error = status.state.value as SessionState.Error
        assertEquals(SessionErrorReason.MockAppNotSelected, error.reason)
        assertEquals(1, engine.stopCount)
        assertEquals(10, engine.pushAttempts)
    }

    @Test
    fun `a teardown that throws cancellation still resets status and calls onFinished`() = runTest {
        // The teardown runs under NonCancellable, so there is no caller cancellation left to
        // preserve. An engine that throws must not leave the service foreground forever (F5).
        val engine = ThrowOnStopEngine()
        val status = MockStatusStore()
        val runner = runnerFor(engine, status, testScheduler)
        var finished = 0

        runner.start(
            backgroundScope,
            StaticMovementSource(point),
            SessionConfig(intervalMs = 1_000L),
            onFinished = { _ -> finished++ },
        )
        advanceTimeBy(500L)
        runner.stop()
        advanceTimeBy(500L)

        assertEquals(1, engine.stopCount)
        assertTrue(status.state.value is SessionState.Idle)
        assertEquals(1, finished)
    }

    @Test
    fun `a later start is allowed after a stuck teardown finally completes`() = runTest {
        val engine = ControllableStopEngine()
        val status = MockStatusStore()
        val runner = runnerFor(engine, status, testScheduler)
        var finished = 0

        runner.start(
            backgroundScope,
            StaticMovementSource(point),
            SessionConfig(intervalMs = 1_000L),
            onFinished = { _ -> finished++ },
        )
        advanceTimeBy(500L)
        assertFalse(engine.stopEntered.isCompleted)

        // Supersede while the teardown is not yet running, then let it hang past the budget.
        runner.start(
            backgroundScope,
            StaticMovementSource(point),
            SessionConfig(intervalMs = 1_000L),
            onFinished = { _ -> finished++ },
        )
        advanceTimeBy(MockSessionRunner.STOPPING_BUDGET_MS + 500L)
        assertEquals(1, engine.startCount)
        assertEquals(1, finished)

        // The stuck teardown completes; invokeOnCompletion must clear the refusal.
        engine.allowStop.complete(Unit)
        advanceTimeBy(500L)
        runCurrent()

        runner.start(
            backgroundScope,
            StaticMovementSource(point),
            SessionConfig(intervalMs = 1_000L),
            onFinished = { _ -> finished++ },
        )
        advanceTimeBy(500L)
        runCurrent()

        assertEquals(2, engine.startCount)
        assertTrue(status.state.value is SessionState.Running)
        assertEquals(1, finished)
    }

    @Test
    fun `stopAndJoin waits for the teardown to run`() = runTest {
        val engine = RecordingEngine()
        val status = MockStatusStore()
        val runner = runnerFor(engine, status, testScheduler)

        runner.start(backgroundScope, StaticMovementSource(point), SessionConfig(intervalMs = 1_000L))
        advanceTimeBy(500L)

        runner.stopAndJoin()

        assertEquals(1, engine.stopCount)
        assertTrue(status.state.value is SessionState.Idle)
    }
}
