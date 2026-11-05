package com.snake.mobilespoofing.ui

import android.os.SystemClock
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * Compose's finite animations already honour the system animator scale, but the infinite status
 * pulse does not reliably stop when animations are disabled. Read the scale once per screen so the
 * breathing dot and the entrance stagger can be turned off together.
 */
@Composable
fun rememberMotionEnabled(): Boolean {
    if (LocalInspectionMode.current) return false
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) != 0f
    }
}

/** Fades and lifts a section into place, with a small stagger per section index. */
@Composable
fun StaggeredEntrance(
    index: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val motionEnabled = rememberMotionEnabled()
    val progress = remember { Animatable(if (motionEnabled) 0f else 1f) }

    LaunchedEffect(motionEnabled) {
        if (motionEnabled) {
            delay(index * 50L)
            progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
            )
        }
    }

    Box(
        modifier = modifier.graphicsLayer {
            alpha = progress.value
            translationY = (1f - progress.value) * 16.dp.toPx()
        },
    ) {
        content()
    }
}

/** Fixed label rendered during inspection, so previews never depend on the wall clock. */
private const val PREVIEW_ELAPSED_LABEL = "00:12"

/**
 * Session timer derived from the runner's constant session epoch, never inferred from the first
 * tick the UI happens to observe. Returns null while there is no session, so the caller can omit
 * the metric entirely instead of showing 00:00 for an idle screen.
 */
@Composable
fun rememberElapsedLabel(sessionEpochElapsedMs: Long?): String? {
    if (sessionEpochElapsedMs == null) return null
    // Previews have no running session timer; a fixed value keeps screenshots deterministic.
    if (LocalInspectionMode.current) return PREVIEW_ELAPSED_LABEL

    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }

    LaunchedEffect(sessionEpochElapsedMs) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(1_000L)
        }
    }

    val totalSeconds = ((now - sessionEpochElapsedMs) / 1_000L).coerceAtLeast(0L)
    return String.format(Locale.ROOT, "%02d:%02d", totalSeconds / 60, totalSeconds % 60)
}
