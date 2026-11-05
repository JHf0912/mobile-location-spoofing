package com.snake.mobilespoofing.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the F3 race contract: a duplicate ACTION_START is rejected before preflight, and a stop
 * or preflight failure makes the gated coroutine refuse to launch the runner.
 */
class ServiceStartGuardTest {

    @Test
    fun `a duplicate start is refused while the first is in flight`() {
        val guard = ServiceStartGuard()

        assertTrue(guard.beginStart())
        assertFalse(guard.beginStart())

        guard.finishStart()
        assertTrue(guard.beginStart())
    }

    @Test
    fun `a stop request prevents the gated start from launching the runner`() {
        val guard = ServiceStartGuard()
        assertTrue(guard.beginStart())

        // A stop (or a preflight failure) while the start is still gated.
        guard.requestStop()

        assertFalse(guard.canStartRunner())
        guard.finishStart()
        // This service instance is stopping; a late duplicate must not open a new window.
        assertFalse(guard.beginStart())
    }

    @Test
    fun `a start after a stop request is refused`() {
        val guard = ServiceStartGuard()
        assertTrue(guard.beginStart())
        guard.requestStop()
        guard.finishStart()
        assertFalse(guard.canStartRunner())
        assertFalse(guard.beginStart())
    }

    @Test
    fun `finishing the setup window alone allows the next start`() {
        val guard = ServiceStartGuard()
        assertTrue(guard.beginStart())
        guard.finishStart()

        assertTrue(guard.beginStart())
        assertTrue(guard.canStartRunner())
    }

    @Test
    fun `a start window is in flight until it is finished`() {
        val guard = ServiceStartGuard()

        assertFalse(guard.isStartInFlight)
        guard.beginStart()
        assertTrue(guard.isStartInFlight)
        guard.finishStart()
        assertFalse(guard.isStartInFlight)

        // A stop request closes the window for a later start but does not fabricate an in-flight
        // one; the finish ownership decision only needs the open-window signal.
        guard.requestStop()
        assertFalse(guard.isStartInFlight)
    }

    @Test
    fun `a stop request is visible as stopping`() {
        val guard = ServiceStartGuard()

        assertFalse(guard.isStopping)
        guard.requestStop()
        assertTrue(guard.isStopping)
    }
}
