package com.snake.mobilespoofing.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.snake.mobilespoofing.R
import com.snake.mobilespoofing.ui.theme.AppRadius
import com.snake.mobilespoofing.ui.theme.AppSpacing
import com.snake.mobilespoofing.ui.theme.appColors

/**
 * Boundary disclaimer: even a correctly configured session cannot defeat apps that actively detect
 * and filter simulated locations (for example AMap). Shown next to the diagnostics so the "pushing
 * normally" state is never read as a guarantee against strong detection.
 */
@Composable
fun BoundaryNote(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.appColors
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppRadius.control),
        color = colors.amberSoft,
        border = BorderStroke(1.dp, colors.hairline),
    ) {
        Text(
            text = stringResource(R.string.note_strong_detection),
            style = MaterialTheme.typography.bodySmall,
            color = colors.ink,
            modifier = Modifier.padding(horizontal = AppSpacing.lg, vertical = AppSpacing.md),
        )
    }
}
