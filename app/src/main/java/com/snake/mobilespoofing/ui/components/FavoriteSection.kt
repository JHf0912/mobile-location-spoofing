package com.snake.mobilespoofing.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.snake.mobilespoofing.R
import com.snake.mobilespoofing.core.model.FavoriteItem
import com.snake.mobilespoofing.ui.formatPoint
import com.snake.mobilespoofing.ui.theme.AppRadius
import com.snake.mobilespoofing.ui.theme.AppSize
import com.snake.mobilespoofing.ui.theme.AppSpacing
import com.snake.mobilespoofing.ui.theme.appColors

@Composable
fun FavoriteSection(
    favorites: List<FavoriteItem>,
    favoriteName: String,
    onFavoriteNameChange: (String) -> Unit,
    canSave: Boolean,
    onSave: (String) -> Unit,
    onPick: (FavoriteItem) -> Unit,
    onDelete: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.appColors
    val unnamed = stringResource(R.string.favorite_unnamed)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.md),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = favoriteName,
                onValueChange = onFavoriteNameChange,
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        text = stringResource(R.string.favorite_name_hint),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(AppRadius.chip),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = colors.surface,
                    unfocusedContainerColor = colors.surface,
                    focusedBorderColor = colors.accentCyan,
                    unfocusedBorderColor = colors.hairline,
                    focusedTextColor = colors.ink,
                    unfocusedTextColor = colors.ink,
                    focusedPlaceholderColor = colors.muted,
                    unfocusedPlaceholderColor = colors.muted,
                    cursorColor = colors.accentCyan,
                ),
            )
            Button(
                onClick = { onSave(favoriteName.ifBlank { unnamed }) },
                enabled = canSave,
                modifier = Modifier.height(AppSize.primaryButton),
                shape = RoundedCornerShape(AppRadius.chip),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.primary,
                    contentColor = colors.onPrimary,
                    disabledContainerColor = colors.surfaceMuted,
                    disabledContentColor = colors.muted,
                ),
            ) {
                Text(
                    text = stringResource(R.string.favorite_save),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }

        if (favorites.isEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(AppRadius.control),
                color = colors.surface,
                border = BorderStroke(1.dp, colors.hairline),
            ) {
                Text(
                    text = stringResource(R.string.favorites_empty),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AppSpacing.lg, vertical = AppSpacing.xl),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.muted,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(AppRadius.control),
                color = colors.surface,
                border = BorderStroke(1.dp, colors.hairline),
            ) {
                Column {
                    favorites.forEachIndexed { index, item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(role = Role.Button) { onPick(item) }
                                .padding(start = AppSpacing.lg, end = 4.dp, top = 10.dp, bottom = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Place,
                                contentDescription = null,
                                tint = colors.accentCyan,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(AppSpacing.md))
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    text = item.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = colors.ink,
                                )
                                Text(
                                    text = formatPoint(item.point.latitude, item.point.longitude),
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFeatureSettings = "tnum",
                                    ),
                                    color = colors.muted,
                                )
                            }
                            IconButton(onClick = { onDelete(item.id) }) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = stringResource(R.string.favorite_delete),
                                    tint = colors.muted,
                                )
                            }
                        }
                        if (index < favorites.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 48.dp),
                                color = colors.hairline,
                            )
                        }
                    }
                }
            }
        }
    }
}
