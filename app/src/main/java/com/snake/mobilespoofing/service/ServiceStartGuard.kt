package com.snake.mobilespoofing.service

/**
 * Serializes the service's start decision against stop requests without holding a lock across the
 * startup gate or the runner start.
 *
 * A duplicate `ACTION_START` must be ignored before it reaches preflight: if it ran preflight while
 * the first start was still gated, a failure there would call `stopSelf()` and the first start
 * could still launch a session after the service had decided to stop.
 *
 * Deliberately a plain JVM class (no Android types) so the race contract is unit-testable without
 * Robolectric.
 */
internal class ServiceStartGuard {

    private val lock = Any()
    private var startInFlight = false
    private var stopping = false

    /**
     * Claims the start window. Returns false for a duplicate start, or for any start after a stop
     * was requested on this service instance.
     */
    fun beginStart(): Boolean = synchronized(lock) {
        if (startInFlight || stopping) {
            false
        } else {
            startInFlight = true
            true
        }
    }

    /** Ends the setup window; a later explicit start may begin again unless a stop was requested. */
    fun finishStart() {
        synchronized(lock) {
            startInFlight = false
        }
    }

    /** A stop, or a failure that ends this service instance's start window. */
    fun requestStop() {
        synchronized(lock) {
            stopping = true
        }
    }

    /** Checked by the gated start coroutine immediately before it may start the runner. */
    fun canStartRunner(): Boolean = synchronized(lock) {
        !stopping
    }

    /**
     * True while a start window is open, i.e. [beginStart] without [finishStart].
     *
     * The finish path uses this to avoid cancelling a new start that has begun preflight but has
     * not published Starting yet (the old completion would otherwise see Idle and stop the
     * service under it).
     */
    val isStartInFlight: Boolean
        get() = synchronized(lock) { startInFlight }

    /**
     * True once [requestStop] has been called on this instance.
     *
     * A refused start on a newly created instance that is already stopping must still end its
     * startForegroundService obligation (B-1 re-audit R1), while an in-flight start that has not
     * reached startForeground is allowed to do so itself.
     */
    val isStopping: Boolean
        get() = synchronized(lock) { stopping }
}
