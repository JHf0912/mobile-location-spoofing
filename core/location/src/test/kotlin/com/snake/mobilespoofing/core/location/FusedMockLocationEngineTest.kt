package com.snake.mobilespoofing.core.location

import com.snake.mobilespoofing.core.model.CoordType
import com.snake.mobilespoofing.core.model.Fix
import com.snake.mobilespoofing.core.model.GeoPoint
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The GMS Task is hidden behind [FusedLocationGateway], so the generation guard and the stop retry
 * are exercised deterministically. What the real client does with a late Task is `needs-device`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FusedMockLocationEngineTest {

    private val fix = Fix(GeoPoint(39.9042, 116.4074, CoordType.WGS84))

    private class FakeGateway : FusedLocationGateway {
        private class ModeCall(val enabled: Boolean, val onComplete: (Throwable?) -> Unit)

        val calls = mutableListOf<Boolean>()
        private val pendingMode = mutableListOf<ModeCall>()
        private val pendingLocation = mutableListOf<(Throwable?) -> Unit>()

        /** Mirrors the real client's quick acknowledgement for setMockMode(false). */
        var autoCompleteDisable = false

        /** When set, every setMockMode(false) fails synchronously with this error. */
        var failDisableWith: Throwable? = null

        override fun setMockMode(enabled: Boolean, onComplete: (Throwable?) -> Unit) {
            calls += enabled
            if (!enabled) {
                failDisableWith?.let { error ->
                    onComplete(error)
                    return
                }
                if (autoCompleteDisable) {
                    onComplete(null)
                    return
                }
            }
            pendingMode += ModeCall(enabled, onComplete)
        }

        override fun setMockLocation(fix: Fix, onComplete: (Throwable?) -> Unit) {
            pendingLocation += onComplete
        }

        fun completeMode(enabled: Boolean, error: Throwable? = null, occurrence: Int = -1) {
            val indices = pendingMode.indices.filter { pendingMode[it].enabled == enabled }
            check(indices.isNotEmpty()) { "no pending setMockMode($enabled) call" }
            val position = if (occurrence < 0) indices.last() else indices[occurrence]
            pendingMode.removeAt(position).onComplete(error)
        }

        fun completeLocation(error: Throwable? = null) {
            pendingLocation.removeAt(0)(error)
        }
    }

    @Test
    fun `start returns Ok after the task succeeds`() = runTest {
        val gateway = FakeGateway()
        val engine = FusedMockLocationEngine(gateway)

        val result = async { engine.startMockMode(1L) }
        runCurrent()
        gateway.completeMode(true)
        runCurrent()

        assertEquals(EngineStartOutcome.Ok, result.await())
    }

    @Test
    fun `start returns Failed when the task fails`() = runTest {
        val gateway = FakeGateway()
        val engine = FusedMockLocationEngine(gateway)

        val result = async { engine.startMockMode(1L) }
        runCurrent()
        gateway.completeMode(true, IllegalStateException("no mock mode for you"))

        val outcome = result.await()
        assertTrue(outcome is EngineStartOutcome.Failed)
        assertTrue((outcome as EngineStartOutcome.Failed).detail.orEmpty().contains("no mock mode"))
    }

    @Test
    fun `push reports Ok or AllRejected`() = runTest {
        val gateway = FakeGateway()
        val engine = FusedMockLocationEngine(gateway)

        val ok = async { engine.push(fix) }
        runCurrent()
        gateway.completeLocation()
        runCurrent()
        assertEquals(EnginePushOutcome.Ok, ok.await())

        val rejected = async { engine.push(fix) }
        runCurrent()
        gateway.completeLocation(IllegalArgumentException("rejected"))
        runCurrent()
        assertEquals(EnginePushOutcome.AllRejected, rejected.await())
    }

    @Test
    fun `stop reports no providers and disables mock mode`() = runTest {
        val gateway = FakeGateway().apply { autoCompleteDisable = true }
        val engine = FusedMockLocationEngine(gateway)

        assertEquals(emptyList<ProviderStopResult>(), engine.stopMockMode())
        assertEquals(listOf(false), gateway.calls)
    }

    @Test
    fun `stop retries exactly once when the first disable times out`() = runTest {
        val gateway = FakeGateway()
        val engine = FusedMockLocationEngine(gateway)

        val stop = async { engine.stopMockMode() }
        runCurrent()
        assertEquals(listOf(false), gateway.calls)

        advanceTimeBy(1_100L)
        runCurrent()
        assertEquals(listOf(false, false), gateway.calls)

        gateway.completeMode(false)
        runCurrent()
        assertEquals(emptyList<ProviderStopResult>(), stop.await())
        assertEquals(listOf(false, false), gateway.calls)
    }

    @Test
    fun `a late successful start completion after a stop disables mock mode again`() = runTest {
        // This is the GMS trap: cancelling await() does not cancel the Task, so setMockMode(true)
        // can complete after teardown. The generation guard must send a compensating false.
        val gateway = FakeGateway().apply { autoCompleteDisable = true }
        val engine = FusedMockLocationEngine(gateway)

        val start = async { engine.startMockMode(1L) }
        runCurrent()
        assertEquals(listOf(true), gateway.calls)

        start.cancel()
        runCurrent()
        // Cancelled start invalidates the generation and immediately tries to undo the mode.
        assertEquals(listOf(true, false), gateway.calls)

        engine.stopMockMode()
        assertEquals(listOf(true, false, false), gateway.calls)

        // The original Task now completes successfully, too late.
        gateway.completeMode(true)
        runCurrent()

        assertEquals(listOf(true, false, false, false), gateway.calls)
    }

    @Test
    fun `stop retries a failed disable and throws when the retry also fails`() = runTest {
        // A failed Task is not a successful stop: it must retry and then surface the failure so
        // the composite can report it through EngineHealthReporter.
        val gateway = FakeGateway().apply {
            failDisableWith = IllegalStateException("disable failed")
        }
        val engine = FusedMockLocationEngine(gateway)

        val result = async { runCatching { engine.stopMockMode() } }
        runCurrent()

        val error = result.await().exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertEquals(listOf(false, false), gateway.calls)
    }

    @Test
    fun `a fused stop failure is reported through health and does not block LocationManager cleanup`() = runTest {
        val gateway = FakeGateway().apply {
            failDisableWith = IllegalStateException("disable failed")
        }
        val fused = FusedMockLocationEngine(gateway)
        val locationManager = FixedStopEngine(listOf(ProviderStopResult("gps", removed = true, reason = null)))
        val reporter = RecordingHealthReporter()
        val composite = CompositeMockLocationEngine(
            engines = listOf(locationManager, fused),
            healthReporter = reporter,
        )

        val start = async { composite.startMockMode(1L) }
        runCurrent()
        gateway.completeMode(true)
        runCurrent()
        assertEquals(EngineStartOutcome.Ok, start.await())

        val results = composite.stopMockMode()

        // Only the LocationManager result is returned; the fused failure is an engine-level
        // failure, visible through health, never a fabricated provider name.
        assertEquals(listOf(ProviderStopResult("gps", removed = true, reason = null)), results)
        assertEquals(1, locationManager.stopCount)
        val failure = reporter.stopFailures["FusedMockLocationEngine"]
        assertTrue(failure != null && failure.contains("could not be disabled"))
        assertEquals(listOf(true, false, false), gateway.calls)
    }

    @Test
    fun `a late completion from an old start does not disable a newer session`() = runTest {
        // F2: session A times out, then session B starts and enables mock mode. A's late Task
        // completion must not switch B's mock mode off.
        val gateway = FakeGateway().apply { autoCompleteDisable = true }
        val engine = FusedMockLocationEngine(gateway)

        val sessionA = async { engine.startMockMode(1L) }
        runCurrent()
        sessionA.cancel()
        runCurrent()
        engine.stopMockMode()
        assertEquals(listOf(true, false, false), gateway.calls)

        val sessionB = async { engine.startMockMode(2L) }
        runCurrent()
        // The pending true calls are A's (index 0) and B's (index 1); complete B's first.
        gateway.completeMode(true, occurrence = 1)
        runCurrent()
        assertEquals(EngineStartOutcome.Ok, sessionB.await())
        assertEquals(listOf(true, false, false, true), gateway.calls)

        // A's Task completes successfully long after B owns the mode.
        gateway.completeMode(true, occurrence = 0)
        runCurrent()

        // Desired state is on for B, so no compensating false may be issued.
        assertEquals(listOf(true, false, false, true), gateway.calls)

        // Only stopping B disables mock mode.
        engine.stopMockMode()
        assertEquals(listOf(true, false, false, true, false), gateway.calls)
    }

    private class FixedStopEngine(
        private val results: List<ProviderStopResult>,
    ) : MockLocationEngine {
        var stopCount = 0

        override suspend fun startMockMode(sessionId: Long): EngineStartOutcome = EngineStartOutcome.Ok

        override suspend fun push(fix: Fix): EnginePushOutcome = EnginePushOutcome.Ok

        override suspend fun stopMockMode(): List<ProviderStopResult> {
            stopCount++
            return results
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
}
