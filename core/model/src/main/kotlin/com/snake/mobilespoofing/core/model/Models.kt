package com.snake.mobilespoofing.core.model

/** Which geodetic datum a coordinate pair is expressed in. */
enum class CoordType {
    WGS84,
    GCJ02,
    BD09,
}

/**
 * A geographic position together with the datum it is expressed in.
 *
 * Note that this is not necessarily WGS84 — UI surfaces hand back GCJ02 (that is what AMap
 * renders), while anything handed to the location injectors must already be WGS84. Keep track of
 * which is which; the two differ by hundreds of metres.
 */
data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
    val coordType: CoordType = CoordType.GCJ02,
)

/** Lifecycle phase of a mock-location session. */
enum class SessionPhase {
    Idle,
    Starting,
    Running,
    Degraded,
    Stopping,
    Error,
}

/**
 * Why a session that is still alive cannot inject reliably.
 *
 * Degraded is not a failure: at least one injection path is still available, the session keeps its
 * epoch, and the user can still stop it.
 */
enum class SessionDegradedReason {
    PushRejected,
    PermissionRevoked,
    ProviderConflict,
    EngineTimeout,
}

/**
 * Why a session could not start, or why it ended.
 *
 * Deliberately carries no detail string: the model is display-agnostic, and detail belongs in logs
 * and engine health reporting. The UI and the service localize these at their boundary.
 */
enum class SessionErrorReason {
    MissingSpec,
    PermissionDenied,
    MockAppNotSelected,
    EngineStartFailed,
    SourceInvalid,
    TooManyConsecutiveFailures,
    ServiceStartFailed,
    TeardownTimeout,
    Unknown,
}

/**
 * The single session state shared by the runner, the service, the Quick Settings tile and the UI.
 *
 * [Running] carries no per-tick timestamp on purpose: a static session publishes it exactly once
 * and a route session only when the injected point changes, so the 1 Hz loop does not wake the
 * whole Compose tree. The timer derives from [Running.sessionEpochElapsedMs], which the runner
 * captures once after the engine accepts the session and keeps constant until the session ends.
 */
sealed interface SessionState {
    val phase: SessionPhase

    data object Idle : SessionState {
        override val phase: SessionPhase = SessionPhase.Idle
    }

    data class Starting(
        val sessionId: Long,
        /** `elapsedRealtime` of the start request; used for diagnostics and start timeout only. */
        val startedAtElapsedMs: Long,
    ) : SessionState {
        override val phase: SessionPhase = SessionPhase.Starting
    }

    data class Running(
        val sessionId: Long,
        /** Constant for the whole session; never a per-tick timestamp. */
        val sessionEpochElapsedMs: Long,
        val lastPointWgs84: GeoPoint? = null,
    ) : SessionState {
        override val phase: SessionPhase = SessionPhase.Running
    }

    data class Degraded(
        val sessionId: Long,
        val sessionEpochElapsedMs: Long,
        val lastPointWgs84: GeoPoint?,
        val reason: SessionDegradedReason,
        val consecutiveFailures: Int,
    ) : SessionState {
        override val phase: SessionPhase = SessionPhase.Degraded
    }

    data class Stopping(
        val sessionId: Long,
        val sessionEpochElapsedMs: Long,
    ) : SessionState {
        override val phase: SessionPhase = SessionPhase.Stopping
    }

    /**
     * A session that could not start, or one that ended. An error carries no epoch: the timer is
     * cleared.
     *
     * [terminal] = true means the session is over and a new start is allowed. [terminal] = false
     * is used only for [SessionErrorReason.TeardownTimeout]: the session is over for the user and
     * Stop is disabled, but the engine may still be unregistering providers, so Start stays
     * blocked until that stuck teardown completes and the state resolves to [Idle].
     */
    data class Error(
        val sessionId: Long?,
        val reason: SessionErrorReason,
        val terminal: Boolean = true,
    ) : SessionState {
        override val phase: SessionPhase = SessionPhase.Error
    }
}

/**
 * True while a session exists, including its Starting and Stopping windows.
 *
 * Idle and [SessionState.Error] mean there is nothing to stop; a terminal error still allows a new
 * start, which is why this is not the inverse of "can start".
 */
val SessionState.isSessionActive: Boolean
    get() = phase == SessionPhase.Starting ||
        phase == SessionPhase.Running ||
        phase == SessionPhase.Degraded ||
        phase == SessionPhase.Stopping

/**
 * Whether a new session may be requested.
 *
 * Only Idle and a *terminal* [SessionState.Error] qualify. A non-terminal error is the
 * teardown-timeout window: the session is over for the user (nothing to stop), but the engine may
 * still be unregistering system providers, so starting a new session would overlap that teardown.
 * It resolves to Idle when the stuck teardown finally completes.
 */
val SessionState.canStartNewSession: Boolean
    get() = this is SessionState.Idle || (this is SessionState.Error && terminal)
