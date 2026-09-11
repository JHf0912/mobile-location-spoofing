package com.snake.mobilespoofing.core.location

import com.snake.mobilespoofing.core.geo.MovementSource
import com.snake.mobilespoofing.core.model.MockStatus
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

/**
 * Drives a mock session: pulls fixes from a [MovementSource] on a fixed cadence and pushes them
 * through the engine.
 *
 * Deliberately not an Android component. Keeping the loop here rather than inside the service means
 * the whole thing is testable with a fake clock, a fake engine and virtual time — no device, no
 * Robolectric, no real delays.
 *
 * [clock] and [dispatcher] are constructor parameters with defaults so tests can substitute them
 * without Hilt qualifiers.
 */
class MockSessionRunner(
    private val engine: MockLocationEngine,
    private val statusStore: MockStatusStore,
    private val clock: ElapsedClock = SystemElapsedClock(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {

    private var job: Job? = null

    val isRunning: Boolean get() = job?.isActive == true

    /**
     * @param onFinished invoked once the session is over — whether it completed on its own, failed,
     *   or was cancelled. Lets the Android service tear down its notification without the runner
     *   needing to know anything about services.
     */
    fun start(
        scope: CoroutineScope,
        source: MovementSource,
        config: SessionConfig,
        onFinished: () -> Unit = {},
    ) {
        stop()
        job = scope.launch(dispatcher) { run(source, config, onFinished) }
    }

    /** Cancels the loop. Teardown still runs — see the `finally` in [run]. */
    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun run(
        source: MovementSource,
        config: SessionConfig,
        onFinished: () -> Unit,
    ) {
        try {
            engine.startMockMode()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            statusStore.emit(MockStatus.Error("无法开启模拟模式：${e.message}"))
            onFinished()
            return
        }

        try {
            val startedAt = clock.nowMs()
            var tick = 0L

            while (currentCoroutineContext().isActive) {
                val elapsed = clock.nowMs() - startedAt
                val fix = source.fixAt(elapsed)

                try {
                    engine.push(fix)
                    statusStore.emit(MockStatus.Running(fix.point, clock.nowMs()))
                } catch (e: CancellationException) {
                    // Must be rethrown: absorbing cancellation here would let a stop request be
                    // swallowed by an in-flight suspension and leave the loop injecting.
                    throw e
                } catch (e: Exception) {
                    statusStore.emit(MockStatus.Error("注入失败：${e.message}"))
                }

                if (source.isComplete(elapsed)) break

                // Scheduled against the session epoch rather than against "now". A plain
                // delay(interval) adds each tick's own execution time to the next one's deadline,
                // so a long session quietly falls behind its own schedule.
                val nextTickAt = startedAt + (++tick) * config.intervalMs
                val wait = nextTickAt - clock.nowMs()
                if (wait > 0) delay(wait)
            }
        } finally {
            // Runs on every exit path, including cancellation. A LocationManager test provider is
            // registered with the system and survives this process, so skipping teardown would
            // leave the device reporting a mock position to every app until it is rebooted.
            // NonCancellable because a suspending call in a cancelled scope throws immediately.
            withContext(NonCancellable) {
                try {
                    engine.stopMockMode()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // The status reset below matters more than a failed teardown.
                }
            }
            statusStore.emit(MockStatus.Idle)
            onFinished()
        }
    }
}
