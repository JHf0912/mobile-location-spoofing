package com.snake.mobilespoofing.core.location

import com.snake.mobilespoofing.core.geo.EndBehavior
import com.snake.mobilespoofing.core.geo.GeoMath
import com.snake.mobilespoofing.core.geo.Route
import com.snake.mobilespoofing.core.geo.RouteMovementSource
import com.snake.mobilespoofing.core.geo.StaticMovementSource
import com.snake.mobilespoofing.core.model.CoordType
import com.snake.mobilespoofing.core.model.Fix
import com.snake.mobilespoofing.core.model.GeoPoint
import com.snake.mobilespoofing.core.model.MockStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Virtual time throughout: `delay` is free, the clock is the test scheduler, and the whole session
 * loop runs deterministically without a device.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MockSessionRunnerTest {

    private val point = GeoPoint(39.9042, 116.4074, CoordType.WGS84)

    private class RecordingEngine(
        private val failPushNumber: Int? = null,
        private val failStart: Boolean = false,
    ) : MockLocationEngine {
        val pushed = mutableListOf<Fix>()
        var startCount = 0
        var stopCount = 0

        /** Counts attempts, not successes — a failed push must not be retried forever. */
        private var pushAttempts = 0

        override suspend fun startMockMode() {
            startCount++
            if (failStart) error("engine refused to start")
        }

        override suspend fun push(fix: Fix) {
            pushAttempts++
            if (failPushNumber != null && pushAttempts == failPushNumber) {
                error("push rejected")
            }
            pushed += fix
        }

        override suspend fun stopMockMode() {
            stopCount++
        }
    }

    @Test
    fun `pushes one fix per interval`() = runTest {
        val engine = RecordingEngine()
        val status = MockStatusStore()
        val runner = MockSessionRunner(
            engine,
            status,
            ElapsedClock { testScheduler.currentTime },
            StandardTestDispatcher(testScheduler),
        )

        runner.start(backgroundScope, StaticMovementSource(point), SessionConfig(intervalMs = 1_000L))
        advanceTimeBy(3_500L)

        assertEquals(1, engine.startCount)
        // Ticks land at t = 0, 1000, 2000, 3000.
        assertEquals(4, engine.pushed.size)
        assertTrue(status.status.value is MockStatus.Running)
    }

    @Test
    fun `tick scheduling does not drift when a push is slow`() = runTest {
        // The pushes must stay anchored to the session epoch. Scheduling each tick as delay(interval)
        // from "now" would fold the push's own cost into every subsequent deadline.
        val engine = RecordingEngine()
        val runner = MockSessionRunner(
            engine,
            MockStatusStore(),
            ElapsedClock { testScheduler.currentTime },
            StandardTestDispatcher(testScheduler),
        )

        runner.start(backgroundScope, StaticMovementSource(point), SessionConfig(intervalMs = 1_000L))
        advanceTimeBy(10_500L)

        // 10 s of session at 1 Hz is 11 ticks, not 10.
        assertEquals(11, engine.pushed.size)
    }

    @Test
    fun `cancelling tears the engine down and resets the status`() = runTest {
        val engine = RecordingEngine()
        val status = MockStatusStore()
        val runner = MockSessionRunner(
            engine,
            status,
            ElapsedClock { testScheduler.currentTime },
            StandardTestDispatcher(testScheduler),
        )

        runner.start(backgroundScope, StaticMovementSource(point), SessionConfig(intervalMs = 1_000L))
        advanceTimeBy(2_500L)
        runner.stop()
        advanceTimeBy(5_000L)

        // Teardown runs on the cancellation path too. Skipping it would leave a system-wide test
        // provider registered, which outlives the process.
        assertEquals(1, engine.stopCount)
        assertTrue(status.status.value is MockStatus.Idle)
        // And nothing is injected after the stop.
        assertEquals(3, engine.pushed.size)
    }

    @Test
    fun `a single failed push reports an error but the session continues`() = runTest {
        // One flaky tick must not end a session; the next tick may well succeed.
        val engine = RecordingEngine(failPushNumber = 2)
        val status = MockStatusStore()
        val runner = MockSessionRunner(
            engine,
            status,
            ElapsedClock { testScheduler.currentTime },
            StandardTestDispatcher(testScheduler),
        )

        runner.start(backgroundScope, StaticMovementSource(point), SessionConfig(intervalMs = 1_000L))
        advanceTimeBy(3_500L)

        assertTrue(status.status.value is MockStatus.Running)
        assertEquals(3, engine.pushed.size)
    }

    @Test
    fun `a failed start reports an error and never pushes`() = runTest {
        val engine = RecordingEngine(failStart = true)
        val status = MockStatusStore()
        val runner = MockSessionRunner(
            engine,
            status,
            ElapsedClock { testScheduler.currentTime },
            StandardTestDispatcher(testScheduler),
        )

        runner.start(backgroundScope, StaticMovementSource(point), SessionConfig(intervalMs = 1_000L))
        advanceTimeBy(5_000L)

        assertTrue(status.status.value is MockStatus.Error)
        assertEquals(0, engine.pushed.size)
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
        val runner = MockSessionRunner(
            engine,
            status,
            ElapsedClock { testScheduler.currentTime },
            StandardTestDispatcher(testScheduler),
        )

        runner.start(backgroundScope, source, SessionConfig(intervalMs = 1_000L))
        // 100 m at 10 m/s is 10 s, so it is done well before this.
        advanceTimeBy(20_000L)

        assertEquals(1, engine.stopCount)
        assertTrue(status.status.value is MockStatus.Idle)
    }
}
