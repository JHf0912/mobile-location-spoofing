package com.snake.mobilespoofing.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.snake.mobilespoofing.R
import com.snake.mobilespoofing.ui.theme.AppRadius
import com.snake.mobilespoofing.ui.theme.AppSize
import com.snake.mobilespoofing.ui.theme.AppSpacing
import com.snake.mobilespoofing.ui.theme.appColors

/**
 * Typed/pasted coordinate entry, always visible below the map.
 *
 * The field is cleared only when [onSubmit] reports success, so a rejected value stays on screen
 * with the validation message and the user does not lose what they typed. The label states WGS84
 * because the map path is GCJ02 and mixing the two would shift the point.
 */
@Composable
fun CoordinateInputField(
    onSubmit: (String) -> Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.appColors
    var raw by rememberSaveable { mutableStateOf("") }

    val submit = {
        if (onSubmit(raw)) {
            raw = ""
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.sm),
    ) {
        OutlinedTextField(
            value = raw,
            onValueChange = { raw = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.coordinate_label)) },
            placeholder = { Text(stringResource(R.string.coordinate_hint)) },
            singleLine = true,
            shape = RoundedCornerShape(AppRadius.chip),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = colors.surface,
                unfocusedContainerColor = colors.surface,
                focusedBorderColor = colors.accentCyan,
                unfocusedBorderColor = colors.hairline,
                focusedTextColor = colors.ink,
                unfocusedTextColor = colors.ink,
                focusedLabelColor = colors.accentCyan,
                unfocusedLabelColor = colors.muted,
                focusedPlaceholderColor = colors.muted,
                unfocusedPlaceholderColor = colors.muted,
                cursorColor = colors.accentCyan,
            ),
        )
        Button(
            onClick = submit,
            enabled = raw.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .height(AppSize.primaryButton),
            shape = RoundedCornerShape(AppRadius.chip),
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.primary,
                contentColor = colors.onPrimary,
                disabledContainerColor = colors.surfaceMuted,
                disabledContentColor = colors.muted,
            ),
        ) {
            Text(
                text = stringResource(R.string.coordinate_apply),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}
