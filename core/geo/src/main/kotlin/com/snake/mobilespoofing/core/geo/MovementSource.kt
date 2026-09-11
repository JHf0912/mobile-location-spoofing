package com.snake.mobilespoofing.core.geo

import com.snake.mobilespoofing.core.model.Fix

/** What a route does once it reaches its end. */
enum class EndBehavior {
    /** Stop at the final waypoint. */
    STOP,

    /** Continue around a closed ring, returning to the first waypoint and carrying on. */
    LOOP,

    /** Reverse at each end and retrace. */
    PING_PONG,
}

/**
 * A time-indexed source of positions.
 *
 * `fixAt` is the whole interface on purpose, and the alternatives were considered:
 *
 *  - A cold `Flow<Fix>` would push the tick cadence — a service-level policy — down into the
 *    movement logic. It would also force `runTest` and Turbine into tests that are pure arithmetic,
 *    and re-collection would restart a route.
 *  - `advance(deltaMs)` plus a `current` property makes position depend on *how often* it was
 *    called rather than on time, so the service and a UI preview could not share one source. It
 *    also loses distance permanently when a tick fires late, whereas `fixAt` simply returns the
 *    correct position for the time asked for.
 *
 * Contract:
 *  - pure in [elapsedMs]: the same input always yields the same [Fix]
 *  - [elapsedMs] is a monotonic mission clock (elapsedRealtime semantics), not wall-clock
 */
interface MovementSource {

    fun fixAt(elapsedMs: Long): Fix

    /** Total planned duration, or null when the source runs until told to stop. */
    val durationMs: Long? get() = null

    /** True once the source can produce no further movement. */
    fun isComplete(elapsedMs: Long): Boolean = durationMs?.let { elapsedMs >= it } ?: false
}
