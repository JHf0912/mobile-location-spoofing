package com.snake.mobilespoofing.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.snake.mobilespoofing.R
import com.snake.mobilespoofing.core.geo.EndBehavior
import com.snake.mobilespoofing.core.geo.TravelSpeeds

/** Presentation labels live in the UI layer; the domain enums stay free of localized text. */
@Composable
fun TravelSpeeds.label(): String = stringResource(
    when (this) {
        TravelSpeeds.WALKING -> R.string.speed_walking
        TravelSpeeds.RUNNING -> R.string.speed_running
        TravelSpeeds.CYCLING -> R.string.speed_cycling
        TravelSpeeds.DRIVING -> R.string.speed_driving
    },
)

@Composable
fun EndBehavior.label(): String = stringResource(
    when (this) {
        EndBehavior.STOP -> R.string.route_end_stop
        EndBehavior.LOOP -> R.string.route_end_loop
        EndBehavior.PING_PONG -> R.string.route_end_pingpong
    },
)
