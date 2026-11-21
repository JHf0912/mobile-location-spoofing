package com.snake.mobilespoofing.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.snake.mobilespoofing.R
import com.snake.mobilespoofing.ui.DiagnosticTone
import com.snake.mobilespoofing.ui.MainUiState
import com.snake.mobilespoofing.ui.deriveLiveDiagnostics
import com.snake.mobilespoofing.ui.theme.AppRadius
import com.snake.mobilespoofing.ui.theme.AppSpacing
import com.snake.mobilespoofing.ui.theme.appColors

/**
 * Read-only "live diagnostics" card: whether the system accepts this app as the mock-location app,
 * the current session's provider registration/push outcome, and the most recent failure reason.
 * Everything is derived from [MainUiState] (MockPermissionChecker + MockStatusStore); no root.
 */
@Composable
fun LiveDiagnosticsCard(state: MainUiState, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.appColors
    val diagnostics = deriveLiveDiagnostics(state.canMock, state.session)

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppRadius.control),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.hairline),
    ) {
        Column {
            DiagnosticRow(
                label = stringResource(R.string.diag_mock_app),
                value = stringResource(
                    if (diagnostics.mockAppSelected) {
                        R.string.diag_mock_app_selected
                    } else {
                        R.string.diag_mock_app_not_selected
                    },
                ),
                tone = if (diagnostics.mockAppSelected) DiagnosticTone.Good else DiagnosticTone.Bad,
            )
            DiagnosticDivider()
            DiagnosticRow(
                label = stringResource(R.string.diag_session_label),
                value = stringResource(diagnostics.sessionStatusRes),
                tone = diagnostics.sessionTone,
            )
            DiagnosticDivider()
            DiagnosticRow(
                label = stringResource(R.string.diag_last_failure_label),
                value = diagnostics.lastFailureRes
                    ?.let { stringResource(it) }
                    ?: stringResource(R.string.diag_last_failure_none),
                tone = if (diagnostics.lastFailureRes != null) DiagnosticTone.Bad else DiagnosticTone.Neutral,
            )
        }
    }
}

@Composable
private fun DiagnosticDivider() {
    val colors = MaterialTheme.appColors
    HorizontalDivider(
        modifier = Modifier.padding(start = 44.dp),
        color = colors.hairline,
    )
}

@Composable
private fun DiagnosticRow(
    label: String,
    value: String,
    tone: DiagnosticTone,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.appColors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(horizontal = AppSpacing.lg, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(color = toneColor(tone), pulsing = false)
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = colors.muted,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.ink,
        )
    }
}

@Composable
private fun toneColor(tone: DiagnosticTone): Color {
    val colors = MaterialTheme.appColors
    return when (tone) {
        DiagnosticTone.Good -> colors.onAccentMint
        DiagnosticTone.Warning -> colors.accentAmber
        DiagnosticTone.Bad -> colors.danger
        DiagnosticTone.Neutral -> colors.muted
    }
}
