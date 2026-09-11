package com.snake.mobilespoofing.core.geo

/**
 * Speed presets, in metres per second.
 *
 * The values are ordinary real-world pacing rather than chosen to look impressive: a route walked
 * at 60 km/h is the kind of thing an app that sanity-checks its location stream will notice.
 */
enum class TravelSpeeds(val metersPerSecond: Double, val label: String) {
    WALKING(1.4, "步行"),
    RUNNING(3.3, "跑步"),
    CYCLING(5.5, "骑行"),
    DRIVING(13.9, "驾车"),
    ;

    val kilometersPerHour: Double get() = metersPerSecond * 3.6
}
