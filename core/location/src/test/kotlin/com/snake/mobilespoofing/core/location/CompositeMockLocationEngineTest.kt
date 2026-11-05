package com.snake.mobilespoofing.core.location

import com.snake.mobilespoofing.core.model.CoordType
import com.snake.mobilespoofing.core.model.Fix
import com.snake.mobilespoofing.core.model.GeoPoint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CompositeMockLocationEngineTest {

    private val fix = Fix(GeoPoint(39.9042, 116.4074, CoordType.WGS84))

    private class FakeEngine(
        val name: String,
        private val startOutcome: EngineStartOutcome = EngineStartOutcome.Ok,
        private val pushOutcome: EnginePushOutcome = EnginePushOutcome.Ok,
        private val stopResults: List<ProviderStopResult> = emptyList(),
        private val hangStart: Boolean = false,
        private val hangPush: Boolean = false,
        private val hangStop: Boolean = false,
        private val log: MutableList<String>? = null,
    ) : MockLocationEngine {
        val pushed = mutableListOf<Fix>()
        var startCount = 0
        var stopCount = 0

        override suspend fun startMockMode(sessionId: Long): EngineStartOutcome {
            startCount++
            log?.add("start:$name")
            if (hangStart) awaitCancellation()
            return startOutcome
        }

        override suspend fun push(fix: Fix): EnginePushOutcome {
            log?.add("push:$name")
            if (hangPush) awaitCancellation()
            if (pushOutcome == EnginePushOutcome.Ok) pushed += fix
            return pushOutcome
        }

        override suspend fun stopMockMode(): List<ProviderStopResult> {
            stopCount++
            log?.add("stop:$name")
            if (hangStop) awaitCancellation()
            return stopResults
        }
    }

    private class HangingStartEngine : MockLocationEngine {
        val enteredStart = CompletableDeferred<Unit>()
        var stopCount = 0

        override suspend fun startMockMode(sessionId: Long): EngineStartOutcome {
            enteredStart.complete(Unit)
            awaitCancellation()
        }

        override suspend fun push(fix: Fix): EnginePushOutcome = EnginePushOutcome.Ok

        override suspend fun stopMockMode(): List<ProviderStopResult> {
            stopCount++
            return emptyList()
        }
    }

    private class HangingStopEngine : MockLocationEngine {
        var stopCount = 0

        override suspend fun startMockMode(sessionId: Long): EngineStartOutcome = EngineStartOutcome.Ok

        override suspend fun push(fix: Fix): EnginePushOutcome = EnginePushOutcome.Ok

        override suspend fun stopMockMode(): List<ProviderStopResult> {
            stopCount++
            awaitCancellation()
        }
    }

    private class RecordingHealthReporter : EngineHealthReporter {
        val stopFailures = mutableMapOf<String, String?>()

        override fun onStart(engine: String, outcome: EngineStartOutcome) = Unit

        override fun onPush(engine: String, outcome: EnginePushOutcome) = Unit

        override fun onStop(engine: String, results: List<ProviderStopResult>, failure: String?) {
            if (failure != null) stopFailures[engine] = failure
        }
    }

    @Test
    fun `one engine failing to start does not stop the other`() = runTest {
        val working = FakeEngine("working")
        val broken = FakeEngine("broken", startOutcome = EngineStartOutcome.Failed("nope"))
        val composite = CompositeMockLocationEngine(listOf(working, broken))

        assertEquals(EngineStartOutcome.Ok, composite.startMockMode(1))
        assertEquals(EnginePushOutcome.Ok, composite.push(fix))

        assertEquals(1, working.startCount)
        assertEquals(1, working.pushed.size)
    }

    @Test
    fun `a single failing engine does not fail the push`() = runTest {
        val working = FakeEngine("working")
        val flaky = FakeEngine("flaky", pushOutcome = EnginePushOutcome.AllRejected)
        val composite = CompositeMockLocationEngine(listOf(working, flaky))

        composite.startMockMode(1)
        assertEquals(EnginePushOutcome.Ok, composite.push(fix))

        assertEquals(1, working.pushed.size)
    }

    @Test
    fun `push fails when every engine rejects the fix`() = runTest {
        val first = FakeEngine("first", pushOutcome = EnginePushOutcome.AllRejected)
        val second = FakeEngine("second", pushOutcome = EnginePushOutcome.AllRejected)
        val composite = CompositeMockLocationEngine(listOf(first, second))

        composite.startMockMode(1)
        // Swallowing this used to leave the UI and notification reporting Running while nothing
        // was injected into the device.
        assertEquals(EnginePushOutcome.AllRejected, composite.push(fix))
    }

    @Test
    fun `engines start in the configured order`() = runTest {
        val log = mutableListOf<String>()
        val locationManager = FakeEngine("locationManager", log = log)
        val fused = FakeEngine("fused", log = log)
        val composite = CompositeMockLocationEngine(listOf(locationManager, fused))

        composite.startMockMode(1)

        assertEquals(listOf("start:locationManager", "start:fused"), log)
    }

    @Test
    fun `a hanging fused start does not block the LocationManager engine`() = runTest {
        // The fused client is the one that hangs when Play services is cold or updating. The
        // reliable fallback must be started, and the composite must still report Ok.
        val locationManager = FakeEngine("locationManager")
        val fused = FakeEngine("fused", hangStart = true)
        val composite = CompositeMockLocationEngine(listOf(locationManager, fused))

        val deferred = async { composite.startMockMode(1) }
        advanceUntilIdle()

        assertEquals(EngineStartOutcome.Ok, deferred.await())
        assertEquals(1, locationManager.startCount)
    }

    @Test
    fun `all engines timing out reports TimedOut`() = runTest {
        val first = FakeEngine("first", hangStart = true)
        val second = FakeEngine("second", hangStart = true)
        val composite = CompositeMockLocationEngine(listOf(first, second))

        val deferred = async { composite.startMockMode(1) }
        advanceUntilIdle()

        assertEquals(EngineStartOutcome.TimedOut, deferred.await())
    }

    @Test
    fun `all engines failing reports Failed with the details`() = runTest {
        val first = FakeEngine("first", startOutcome = EngineStartOutcome.Failed("first failed"))
        val second = FakeEngine("second", startOutcome = EngineStartOutcome.Failed("second failed"))
        val composite = CompositeMockLocationEngine(listOf(first, second))

        val outcome = composite.startMockMode(1)

        assertTrue(outcome is EngineStartOutcome.Failed)
        val detail = (outcome as EngineStartOutcome.Failed).detail.orEmpty()
        assertTrue(detail.contains("first failed"))
        assertTrue(detail.contains("second failed"))
    }

    @Test
    fun `a hanging push times out per engine without blocking the other`() = runTest {
        val locationManager = FakeEngine("locationManager")
        val fused = FakeEngine("fused", hangPush = true)
        val composite = CompositeMockLocationEngine(listOf(locationManager, fused))

        composite.startMockMode(1)
        val deferred = async { composite.push(fix) }
        advanceUntilIdle()

        // The timed-out engine counts as a rejection, not as cancellation of the tick.
        assertEquals(EnginePushOutcome.Ok, deferred.await())
        assertEquals(1, locationManager.pushed.size)
    }

    @Test
    fun `stop runs in reverse start order`() = runTest {
        val log = mutableListOf<String>()
        val locationManager = FakeEngine("locationManager", log = log)
        val fused = FakeEngine("fused", log = log)
        val composite = CompositeMockLocationEngine(listOf(locationManager, fused))

        composite.startMockMode(1)
        composite.stopMockMode()

        assertEquals(listOf("start:locationManager", "start:fused", "stop:fused", "stop:locationManager"), log)
    }

    @Test
    fun `one engine timing out during stop does not abort the others`() = runTest {
        val locationManager = FakeEngine(
            "locationManager",
            stopResults = listOf(ProviderStopResult("gps", removed = true, reason = null)),
        )
        val fused = HangingStopEngine()
        val reporter = RecordingHealthReporter()
        val composite = CompositeMockLocationEngine(
            engines = listOf(locationManager, fused),
            healthReporter = reporter,
        )

        composite.startMockMode(1)
        val deferred = async { composite.stopMockMode() }
        advanceUntilIdle()
        val results = deferred.await()

        // Fused is stopped first and times out; the LocationManager engine must still be stopped.
        assertEquals(1, locationManager.stopCount)
        assertEquals(1, fused.stopCount)
        // F8: an engine failure is reported through health, never fabricated as a provider name.
        assertEquals(listOf(ProviderStopResult("gps", removed = true, reason = null)), results)
        assertEquals(mapOf("HangingStopEngine" to "stop timed out"), reporter.stopFailures)
    }

    @Test
    fun `cancelling a push is rethrown instead of reported as AllRejected`() = runTest {
        val hanging = FakeEngine("hanging", hangPush = true)
        val composite = CompositeMockLocationEngine(listOf(hanging))
        composite.startMockMode(1)

        var outcome: EnginePushOutcome? = null
        val job = launch { outcome = composite.push(fix) }
        runCurrent()
        job.cancelAndJoin()

        // A per-engine timeout becomes AllRejected; only a real caller cancellation propagates.
        assertNull(outcome)
        assertTrue(job.isCancelled)
    }

    @Test
    fun `cancelling a stop is rethrown`() = runTest {
        val hanging = FakeEngine("hanging", hangStop = true)
        val composite = CompositeMockLocationEngine(listOf(hanging))
        composite.startMockMode(1)

        var results: List<ProviderStopResult>? = null
        val job = launch { results = composite.stopMockMode() }
        runCurrent()
        job.cancelAndJoin()

        assertNull(results)
        assertTrue(job.isCancelled)
    }

    @Test
    fun `cancelling during start stops the engine that already started`() = runTest {
        val working = FakeEngine("working")
        val hanging = HangingStartEngine()
        val composite = CompositeMockLocationEngine(listOf(working, hanging))

        val job = launch { composite.startMockMode(1) }
        runCurrent()
        assertTrue(hanging.enteredStart.isCompleted)

        job.cancelAndJoin()

        assertEquals(1, working.stopCount)
    }
}
