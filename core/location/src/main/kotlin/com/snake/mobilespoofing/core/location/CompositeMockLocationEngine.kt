package com.snake.mobilespoofing.core.location

import com.snake.mobilespoofing.core.model.Fix
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Fans a fix out to several engines, so one session can cover apps that read through Google's
 * fused client *and* apps that read `LocationManager` directly.
 *
 * Not `@Inject`-annotated on purpose: a composite that both is a `MockLocationEngine` and consumes
 * a set of them is a Dagger dependency cycle. It is assembled explicitly in `LocationModule`.
 *
 * The engines are ordered most-reliable first: `LocationManager` must start before the fused client
 * can block it, and stop in reverse order. Every engine call gets its own timeout so one hung
 * engine can neither consume another engine's budget nor abort its teardown.
 *
 * A timeout is a failure outcome, never cancellation. `withTimeoutOrNull` returns `null` on its own
 * timeout; a true caller cancellation is rethrown by the inner `catch`.
 */
class CompositeMockLocationEngine(
    private val engines: List<MockLocationEngine>,
    private val startTimeoutMs: Long = DEFAULT_START_TIMEOUT_MS,
    private val pushTimeoutMs: Long = DEFAULT_PUSH_TIMEOUT_MS,
    private val stopTimeoutMs: Long = DEFAULT_STOP_TIMEOUT_MS,
    private val healthReporter: EngineHealthReporter = NoOpEngineHealthReporter,
) : MockLocationEngine {

    @Volatile
    private var started: List<MockLocationEngine> = emptyList()

    override suspend fun startMockMode(sessionId: Long): EngineStartOutcome {
        started = emptyList()
        val live = mutableListOf<MockLocationEngine>()
        val problems = mutableListOf<String>()
        var allTimedOut = engines.isNotEmpty()

        try {
            for (engine in engines) {
                val outcome = startOne(engine, sessionId)
                healthReporter.onStart(nameOf(engine), outcome)
                when (outcome) {
                    EngineStartOutcome.Ok -> live += engine
                    is EngineStartOutcome.Failed -> {
                        allTimedOut = false
                        problems += "${nameOf(engine)}: ${outcome.detail.orEmpty()}"
                    }
                    EngineStartOutcome.TimedOut -> problems += "${nameOf(engine)}: timed out"
                }
            }
        } catch (e: CancellationException) {
            // Cancellation must not leave the engines that did start running. Stop them even
            // though the caller's scope is already cancelled; the runner's finally is a second
            // safety net.
            stopQuietly(live)
            throw e
        }

        started = live
        return when {
            live.isNotEmpty() -> EngineStartOutcome.Ok
            allTimedOut -> EngineStartOutcome.TimedOut
            else -> EngineStartOutcome.Failed(problems.joinToString("; "))
        }
    }

    override suspend fun push(fix: Fix): EnginePushOutcome {
        val active = started
        if (active.isEmpty()) return EnginePushOutcome.AllRejected

        var anyAccepted = false
        for (engine in active) {
            val accepted = pushOne(engine, fix)
            healthReporter.onPush(
                nameOf(engine),
                if (accepted) EnginePushOutcome.Ok else EnginePushOutcome.AllRejected,
            )
            if (accepted) anyAccepted = true
        }
        return if (anyAccepted) EnginePushOutcome.Ok else EnginePushOutcome.AllRejected
    }

    override suspend fun stopMockMode(): List<ProviderStopResult> {
        val results = mutableListOf<ProviderStopResult>()
        try {
            // Reverse order: the engine that started last is stopped first, so a slow fused stop
            // cannot delay the LocationManager provider cleanup any more than necessary.
            for (engine in engines.asReversed()) {
                val outcome = stopOne(engine)
                healthReporter.onStop(nameOf(engine), outcome.results, outcome.failure)
                results += outcome.results
            }
        } finally {
            started = emptyList()
        }
        return results
    }

    // ---------------------------------------------------------------- per-engine helpers

    private suspend fun startOne(engine: MockLocationEngine, sessionId: Long): EngineStartOutcome {
        val outcome = timed(startTimeoutMs) { engine.startMockMode(sessionId) }
        return when (outcome) {
            is Timed.Value -> outcome.value
            is Timed.Failure -> EngineStartOutcome.Failed(outcome.error.message)
            Timed.Timeout -> EngineStartOutcome.TimedOut
        }
    }

    private suspend fun pushOne(engine: MockLocationEngine, fix: Fix): Boolean {
        val outcome = timed(pushTimeoutMs) { engine.push(fix) }
        return outcome is Timed.Value && outcome.value == EnginePushOutcome.Ok
    }

    /**
     * Stops one engine under its own timeout.
     *
     * An engine-level failure is reported as [StopOutcome.failure] instead of being fabricated into
     * a [ProviderStopResult]: `ProviderStopResult.provider` is a system provider name, and a
     * caller must never see an engine class name where the lease expects `gps`/`network`.
     */
    private suspend fun stopOne(engine: MockLocationEngine): StopOutcome {
        val outcome = timed(stopTimeoutMs) { engine.stopMockMode() }
        return when (outcome) {
            is Timed.Value -> StopOutcome(outcome.value, failure = null)
            is Timed.Failure -> StopOutcome(
                results = emptyList(),
                failure = outcome.error.message ?: outcome.error::class.simpleName,
            )
            Timed.Timeout -> StopOutcome(results = emptyList(), failure = "stop timed out")
        }
    }

    private suspend fun stopQuietly(enginesToStop: List<MockLocationEngine>) {
        withContext(NonCancellable) {
            enginesToStop.asReversed().forEach { engine ->
                withTimeoutOrNull(stopTimeoutMs) {
                    try {
                        engine.stopMockMode()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // The original start failure is the one worth reporting.
                    }
                }
            }
        }
    }

    private fun nameOf(engine: MockLocationEngine): String =
        engine::class.simpleName ?: engine.javaClass.name

    /**
     * Runs [block] under a timeout, classifying the outcome without ever letting the timeout leak
     * as a `CancellationException`.
     *
     * The inner `catch` rethrows cancellation so this function's own timeout reaches
     * `withTimeoutOrNull` and becomes `null`. A caller's cancellation also rethrows there and
     * propagates out of the composite.
     */
    private suspend inline fun <T : Any> timed(timeoutMs: Long, crossinline block: suspend () -> T): Timed<T> {
        val outcome = withTimeoutOrNull(timeoutMs) {
            try {
                Timed.Value(block())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timed.Failure(e)
            }
        }
        return outcome ?: Timed.Timeout
    }

    private sealed interface Timed<out T> {
        data class Value<T>(val value: T) : Timed<T>

        data class Failure(val error: Throwable) : Timed<Nothing>

        data object Timeout : Timed<Nothing>
    }

    private class StopOutcome(
        val results: List<ProviderStopResult>,
        val failure: String?,
    )

    companion object {
        const val DEFAULT_START_TIMEOUT_MS = 5_000L
        const val DEFAULT_PUSH_TIMEOUT_MS = 1_500L
        const val DEFAULT_STOP_TIMEOUT_MS = 2_000L
    }
}
