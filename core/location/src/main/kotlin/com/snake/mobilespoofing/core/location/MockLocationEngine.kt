package com.snake.mobilespoofing.core.location

import android.util.Log
import com.snake.mobilespoofing.core.model.Fix
import java.util.concurrent.ConcurrentHashMap

/**
 * Result of registering an engine's mock mode / providers.
 *
 * Engines report ordinary failures as values instead of throwing: the caller has to keep going
 * with the engines that did start, and an exception would make that control flow easy to get wrong.
 * `CancellationException` from the *caller's* coroutine must still be rethrown — only a real
 * cancellation may abort a coroutine.
 */
sealed interface EngineStartOutcome {
    data object Ok : EngineStartOutcome

    data class Failed(val detail: String?) : EngineStartOutcome

    /** The engine did not answer within its budget; it may still be starting. */
    data object TimedOut : EngineStartOutcome
}

/**
 * Result of one push to one engine.
 *
 * [AllRejected] means nothing was injected by that engine. Partial success (one of its providers
 * accepted the fix) is [Ok]; a per-tick failure is a warning, not a session failure.
 */
sealed interface EnginePushOutcome {
    data object Ok : EnginePushOutcome

    data object AllRejected : EnginePushOutcome
}

/**
 * Whether an individual LocationManager test provider was actually removed.
 *
 * `removed = true` is the only value that may clear the provider from the persisted lease:
 * [IllegalArgumentException] means it was already absent, while [SecurityException] means the app
 * can no longer manipulate it and the provider may still be registered system-wide.
 */
data class ProviderStopResult(
    val provider: String,
    val removed: Boolean,
    val reason: String?,
)

/**
 * Injects mock positions into the platform.
 *
 * Takes a [Fix] rather than an `android.location.Location`: `Location` is mutable, so handing one
 * instance to several engines lets them overwrite each other's `provider` field. Each engine builds
 * its own from the fix instead.
 *
 * Implementations must treat the two lifecycle calls as paired — a `LocationManager` test provider
 * is registered system-wide and outlives this process, so a missed stop leaves the device
 * reporting a fake position to every app until it is rebooted.
 *
 * Timeouts are owned by [CompositeMockLocationEngine], not by the engines themselves, so that one
 * slow engine cannot consume another engine's budget. Implementations must be cancellation-safe:
 * every suspend point may be cancelled (including by a timeout wrapper) and must rethrow
 * `CancellationException` instead of reporting it as a failure.
 */
interface MockLocationEngine {
    /**
     * @param sessionId the runner's session id, persisted with the provider lease so a later cold
     *   start can tell which session may have left providers behind.
     */
    suspend fun startMockMode(sessionId: Long): EngineStartOutcome

    suspend fun push(fix: Fix): EnginePushOutcome

    /**
     * Stops every engine and reports what happened to each system-level provider. Must not throw
     * for an individual provider's failure, and must rethrow `CancellationException`.
     *
     * Only real system provider names (`gps`, `network`) may appear in the returned list; this list
     * is the only input allowed to drive `ProviderLeaseStore.release`. Engines without system-level
     * providers (the fused client) return an empty list, and engine-level stop failures are
     * reported through [EngineHealthReporter] instead.
     */
    suspend fun stopMockMode(): List<ProviderStopResult>
}

/**
 * Observes per-engine outcomes without putting them into session state.
 *
 * Kept deliberately minimal: Phase 1 only needs a seam so a later diagnostic log can record which
 * engine failed and why. Per-tick health must not become part of `SessionState`.
 *
 * [onStop]'s [failure] carries an engine-level stop failure (a fused Task that never completed, an
 * engine that threw). It is kept separate from [ProviderStopResult] so an engine name can never be
 * mistaken for a system provider name by a caller that drives the lease.
 */
interface EngineHealthReporter {
    fun onStart(engine: String, outcome: EngineStartOutcome)

    fun onPush(engine: String, outcome: EnginePushOutcome)

    fun onStop(engine: String, results: List<ProviderStopResult>, failure: String?)
}

object NoOpEngineHealthReporter : EngineHealthReporter {
    override fun onStart(engine: String, outcome: EngineStartOutcome) = Unit

    override fun onPush(engine: String, outcome: EnginePushOutcome) = Unit

    override fun onStop(engine: String, results: List<ProviderStopResult>, failure: String?) = Unit
}

/**
 * Minimal production health sink: failures only, so a normal 1 Hz session does not flood logcat.
 *
 * This is intentionally not a metrics system; per-engine health is diagnostic and must never feed
 * `ProviderLeaseStore.release`.
 */
object LogEngineHealthReporter : EngineHealthReporter {
    private const val TAG = "MockEngineHealth"

    /** Last push state per engine, so a permanently failing 1 Hz session logs once, not every tick. */
    private val lastPushSucceeded = ConcurrentHashMap<String, Boolean>()

    override fun onStart(engine: String, outcome: EngineStartOutcome) {
        if (outcome != EngineStartOutcome.Ok) {
            Log.w(TAG, "engine $engine failed to start: $outcome")
        }
    }

    override fun onPush(engine: String, outcome: EnginePushOutcome) {
        val succeeded = outcome == EnginePushOutcome.Ok
        val previous = lastPushSucceeded.put(engine, succeeded)
        if (previous == succeeded) return
        when {
            !succeeded -> Log.w(TAG, "engine $engine rejected the fix")
            previous != null -> Log.i(TAG, "engine $engine recovered")
        }
    }

    override fun onStop(engine: String, results: List<ProviderStopResult>, failure: String?) {
        if (failure != null) {
            Log.w(TAG, "engine $engine failed to stop: $failure")
        }
        results.filterNot { it.removed }.forEach { result ->
            Log.w(TAG, "engine $engine left provider ${result.provider}: ${result.reason}")
        }
    }
}
