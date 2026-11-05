package com.snake.mobilespoofing.core.geo

import com.snake.mobilespoofing.core.model.CoordType
import com.snake.mobilespoofing.core.model.Fix
import com.snake.mobilespoofing.core.model.GeoPoint

/**
 * Stays at one point — the "teleport and hold" case.
 *
 * The output is deliberately constant: a caller that wants receiver-like jitter should add it
 * explicitly rather than relying on an engine to do so. Keeping this a pure function of time makes
 * it trivial to test.
 */
class StaticMovementSource(private val pointWgs84: GeoPoint) : MovementSource {

    init {
        // Route already enforces this; static used to accept any datum and inject it as WGS84,
        // which is silently hundreds of metres off for a GCJ02 point.
        require(pointWgs84.coordType == CoordType.WGS84) {
            "StaticMovementSource requires WGS84, got ${pointWgs84.coordType}"
        }
    }

    override fun fixAt(elapsedMs: Long): Fix = Fix(point = pointWgs84)
}
