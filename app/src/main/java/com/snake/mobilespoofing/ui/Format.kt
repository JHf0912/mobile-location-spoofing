package com.snake.mobilespoofing.ui

import java.util.Locale

/** WGS84 display with a fixed number of decimals and a locale-independent separator. */
fun formatPoint(latitude: Double, longitude: Double): String =
    String.format(Locale.ROOT, "%.5f, %.5f", latitude, longitude)
