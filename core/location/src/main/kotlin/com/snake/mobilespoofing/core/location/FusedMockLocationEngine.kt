package com.snake.mobilespoofing.core.location

import android.annotation.SuppressLint
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.tasks.Task
import com.snake.mobilespoofing.core.model.Fix
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Seam over the GMS fused-client Tasks.
 *
 * Completion callbacks are independent of the coroutine awaiting them: a GMS Task is not cancelled
 * when `await()` is cancelled, so a `setMockMode(true)` that times out can still complete later.
 * Exposing the Task through a callback lets that late completion be modelled deterministically in
 * JVM tests.
 */
interface FusedLocationGateway {
    /**
     * @param onComplete `null` on success, the failure otherwise. May be invoked on any thread,
     *   including synchronously.
     */
    fun setMockMode(enabled: Boolean, onComplete: (Throwable?) -> Unit)

    fun setMockLocation(fix: Fix, onComplete: (Throwable?) -> Unit)
}

@SuppressLint("MissingPermission")
@Singleton
class GmsFusedLocationGateway @Inject constructor(
    private val client: FusedLocationProviderClient,
) : FusedLocationGateway {

    override fun setMockMode(enabled: Boolean, onComplete: (Throwable?) -> Unit) {
        try {
            client.setMockMode(enabled).addOnCompleteListener { task: Task<Void> ->
                onComplete(if (task.isSuccessful) null else task.exception)
            }
        } catch (e: Exception) {
            onComplete(e)
        }
    }

    override fun setMockLocation(fix: Fix, onComplete: (Throwable?) -> Unit) {
        try {
            client.setMockLocation(fix.toLocation(PROVIDER)).addOnCompleteListener { task: Task<Void> ->
                onComplete(if (task.isSuccessful) null else task.exception)
            }
        } catch (e: Exception) {
            onComplete(e)
        }
    }

    private companion object {
        const val PROVIDER = "fused"
    }
}

/**
 * Injects through Google's FusedLocationProviderClient.
 *
 * `setMockMode` has no timeout of its own and a cancelled `await()` does **not** cancel the
 * underlying Task. A late completion could therefore re-enable mock mode after teardown. A
 * generation counter invalidates every start that is no longer current, and a late successful
 * completion (or a cancelled start) triggers a compensating `setMockMode(false)`.
 *
 * The location permission is checked by `MockLocationService` before any session starts; lint
 * cannot see that cross-module gate, so the platform permission requirement is suppressed on the
 * gateway that makes the calls.
 */
@Singleton
class FusedMockLocationEngine @Inject constructor(
    private val gateway: FusedLocationGateway,
) : MockLocationEngine {

    /**
     * What the engine wants the fused mock mode to be right now.
     *
     * A late `setMockMode(true)` completion may only be compensated with a `false` when no newer
     * session currently wants mock mode on. Generation inequality alone is not enough: a stop and
     * a newer start both change the generation, and disabling in the second case would turn off
     * the session that is running now.
     *
     * The state and the gateway calls that change it are guarded by one monitor, so a compensating
     * `false` and a newer `true` cannot be issued out of order. The gateway callback may run
     * synchronously on the issuing thread; the monitor is reentrant, so that is safe.
     */
    private val stateLock = Any()
    private var generation = 0L
    private var desiredMockMode = false

    override suspend fun startMockMode(sessionId: Long): EngineStartOutcome {
        val completion = CompletableDeferred<Throwable?>()
        val myGeneration = synchronized(stateLock) {
            generation++
            desiredMockMode = true
            val issuedFor = generation
            gateway.setMockMode(true) { error ->
                // The Task is not cancelled when the await below is cancelled. A successful late
                // completion may only be undone if the engine is still supposed to be off.
                if (error == null) compensateIfStillOff()
                completion.complete(error)
            }
            issuedFor
        }

        val error = try {
            completion.await()
        } catch (e: CancellationException) {
            // Invalidate this start and undo it if no newer session has taken over. If a newer
            // start wants mock mode, leave it alone: that session owns the mode now.
            cancelStart(myGeneration)
            throw e
        }

        return when {
            error != null -> EngineStartOutcome.Failed(error.message ?: error::class.simpleName)
            isActiveStart(myGeneration) -> EngineStartOutcome.Ok
            else -> {
                // A successful completion arrived after a stop or a newer start invalidated this
                // one. Disabling is only correct when no newer session wants the mode.
                compensateIfStillOff()
                EngineStartOutcome.Failed("mock mode start was superseded")
            }
        }
    }

    override suspend fun push(fix: Fix): EnginePushOutcome {
        val completion = CompletableDeferred<Throwable?>()
        gateway.setMockLocation(fix) { error -> completion.complete(error) }
        return try {
            if (completion.await() == null) EnginePushOutcome.Ok else EnginePushOutcome.AllRejected
        } catch (e: CancellationException) {
            // A per-push timeout is a rejected tick; only the caller's cancellation propagates.
            throw e
        }
    }

    override suspend fun stopMockMode(): List<ProviderStopResult> {
        synchronized(stateLock) {
            generation++
            desiredMockMode = false
        }

        // A failed or timed-out disable gets exactly one more chance. A failed Task is not a
        // successful stop: treating it as one would skip the retry and hide the failure.
        val first = disableOnce()
        val final = if (first is DisableAttempt.Success) first else disableOnce()
        when (final) {
            DisableAttempt.Success -> Unit
            is DisableAttempt.Failure -> throw IllegalStateException(
                "fused mock mode could not be disabled",
                final.error,
            )
            DisableAttempt.Timeout -> throw IllegalStateException("fused mock mode disable timed out")
        }

        // The fused engine has no system test provider. Its outcome is reported through
        // EngineHealthReporter by the composite, not through ProviderStopResult.
        return emptyList()
    }

    private suspend fun disableOnce(): DisableAttempt {
        val completion = CompletableDeferred<DisableAttempt>()
        synchronized(stateLock) {
            gateway.setMockMode(false) { error ->
                completion.complete(
                    if (error == null) DisableAttempt.Success else DisableAttempt.Failure(error),
                )
            }
        }
        return withTimeoutOrNull(STOP_ATTEMPT_TIMEOUT_MS) { completion.await() } ?: DisableAttempt.Timeout
    }

    /** Called with [stateLock] held (or reentrantly from a gateway callback). */
    private fun compensateIfStillOff() {
        synchronized(stateLock) {
            if (!desiredMockMode) {
                gateway.setMockMode(false) { }
            }
        }
    }

    private fun isActiveStart(myGeneration: Long): Boolean = synchronized(stateLock) {
        generation == myGeneration && desiredMockMode
    }

    private fun cancelStart(myGeneration: Long) {
        synchronized(stateLock) {
            if (generation == myGeneration && desiredMockMode) {
                generation++
                desiredMockMode = false
            }
            if (!desiredMockMode) {
                // No newer session wants mock mode on, so undo a possibly-enabled start.
                gateway.setMockMode(false) { }
            }
        }
    }

    private sealed interface DisableAttempt {
        data object Success : DisableAttempt

        data class Failure(val error: Throwable) : DisableAttempt

        data object Timeout : DisableAttempt
    }

    private companion object {
        const val STOP_ATTEMPT_TIMEOUT_MS = 1_000L
    }
}
