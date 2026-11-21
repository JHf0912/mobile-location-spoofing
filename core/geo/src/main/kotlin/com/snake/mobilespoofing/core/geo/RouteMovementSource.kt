package com.snake.mobilespoofing.core.geo

import com.snake.mobilespoofing.core.model.Fix
import kotlin.math.roundToLong

/**
 * Moves along a [Route] at a constant ground speed.
 *
 * Position is a pure function of elapsed time, so a tick that fires late reports the correct
 * position for that moment instead of lagging behind by however long it was delayed.
 */
class RouteMovementSource(
    private val route: Route,
    private val speedMetersPerSecond: Double,
    private val endBehavior: EndBehavior,
) : MovementSource {

    init {
        require(speedMetersPerSecond > 0.0) {
            "speedMetersPerSecond must be positive, got $speedMetersPerSecond"
        }
        require(route.totalMeters > 0.0) { "route has zero length" }
        require(endBehavior != EndBehavior.LOOP || route.closedLoop) {
            "EndBehavior.LOOP walks a closed ring, so the route must be built with closedLoop = true; " +
                "an open route would teleport back to the start at the end of every cycle"
        }
    }

    /**
     * Length of one full cycle. For PING_PONG that is out and back, i.e. twice the route.
     */
    private val cycleMeters: Double = when (endBehavior) {
        EndBehavior.LOOP, EndBehavior.STOP -> route.totalMeters
        EndBehavior.PING_PONG -> route.totalMeters * 2.0
    }

    override val durationMs: Long? = when (endBehavior) {
        EndBehavior.STOP -> ((route.totalMeters / speedMetersPerSecond) * 1000.0).roundToLong()
        EndBehavior.LOOP, EndBehavior.PING_PONG -> null
    }

    override fun fixAt(elapsedMs: Long): Fix {
        val travelled = (elapsedMs / 1000.0) * speedMetersPerSecond

        // Double.mod, never the % operator. Kotlin's % on Double is truncated and takes the sign of
        // the dividend, so a negative distance would come back negative and positionAt would
        // silently clamp it to the start of the route.
        val distance: Double
        val reversed: Boolean
        when (endBehavior) {
            EndBehavior.LOOP -> {
                distance = travelled.mod(cycleMeters)
                reversed = false
            }
            EndBehavior.PING_PONG -> {
                val withinCycle = travelled.mod(cycleMeters)
                if (withinCycle <= route.totalMeters) {
                    distance = withinCycle
                    reversed = false
                } else {
                    distance = cycleMeters - withinCycle
                    reversed = true
                }
            }
            EndBehavior.STOP -> {
                distance = travelled.coerceAtMost(route.totalMeters)
                reversed = false
            }
        }

        val position = route.positionAt(distance)
        val moving = endBehavior != EndBehavior.STOP || travelled < route.totalMeters

        val bearing = if (reversed) {
            // positionAt returns the *forward* bearing of the leg. Travelling back along it means
            // the opposite direction — mirroring the distance alone would report a heading 180°
            // off for the entire return leg.
            GeoMath.normalizeBearing(position.bearingDeg + 180.0)
        } else {
            position.bearingDeg
        }

        return Fix(
            point = position.point,
            speedMetersPerSecond = if (moving) speedMetersPerSecond.toFloat() else 0f,
            bearingDegrees = if (moving) bearing.toFloat() else 0f,
            hasBearing = moving,
        )
    }
}
