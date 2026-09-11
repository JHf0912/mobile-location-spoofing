package com.snake.mobilespoofing.core.location

import com.snake.mobilespoofing.core.model.Fix
import kotlin.coroutines.cancellation.CancellationException

/**
 * Fans a fix out to several engines, so one session can cover apps that read through Google's
 * fused client *and* apps that read `LocationManager` directly.
 *
 * Not `@Inject`-annotated on purpose: a composite that both is a `MockLocationEngine` and consumes
 * a set of them is a Dagger dependency cycle. It is assembled explicitly in `LocationModule`.
 *
 * Partial start is expected and tolerated — Play services being unavailable, or the fused engine
 * failing, must not stop the `LocationManager` engine from working, which is the whole point of
 * having a second one.
 */
class CompositeMockLocationEngine(
    private val engines: List<MockLocationEngine>,
) : MockLocationEngine {

    private var started: List<MockLocationEngine> = emptyList()

    override suspend fun startMockMode() {
        val live = mutableListOf<MockLocationEngine>()
        val problems = mutableListOf<String>()

        engines.forEach { engine ->
            try {
                engine.startMockMode()
                live += engine
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                problems += "${engine::class.simpleName}: ${e.message}"
            }
        }

        if (live.isEmpty()) {
            error("No mock-location engine could start. ${problems.joinToString("; ")}")
        }
        started = live
    }

    override suspend fun push(fix: Fix) {
        started.forEach { engine ->
            try {
                engine.push(fix)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // A single engine failing a single tick is not a session failure.
            }
        }
    }

    override suspend fun stopMockMode() {
        // Every engine, not only the ones that reported a successful start. An engine that failed
        // halfway may still have registered a system-wide provider, and that is precisely the
        // failure that survives until the device is rebooted.
        engines.forEach { engine ->
            try {
                engine.stopMockMode()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Nothing useful to do; stopping the others matters more.
            }
        }
        started = emptyList()
    }
}
