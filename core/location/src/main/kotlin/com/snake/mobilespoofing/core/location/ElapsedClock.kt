package com.snake.mobilespoofing.core.location

import android.os.SystemClock

/**
 * A monotonic mission clock.
 *
 * `elapsedRealtime` semantics, not wall-clock: `System.currentTimeMillis()` jumps when NTP corrects
 * the time or the user changes it, and a movement engine driven by it would teleport kilometres in
 * a single tick. It also keeps counting through deep sleep, which is what a running session wants.
 */
fun interface ElapsedClock {
    fun nowMs(): Long
}

class SystemElapsedClock : ElapsedClock {
    override fun nowMs(): Long = SystemClock.elapsedRealtime()
}
