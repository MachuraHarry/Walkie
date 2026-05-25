package com.ronin.walkie.ui.channels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.ronin.walkie.R

@Composable
fun CreateChannelDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, description: String, color: String, password: String) -> Unit
) {
    var channelName by remember { mutableStateOf("") }
    var channelDescription by remember { mutableStateOf("") }
    var selectedColor by remember { mutableStateOf("#4CAF50") }
    var channelPassword by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var isError by remember { mutableStateOf(false) }

    val colors = listOf(
        "#4CAF50" to stringResource(R.string.color_green),
        "#2196F3" to stringResource(R.string.color_blue),
        "#FF5722" to stringResource(R.string.color_orange),
        "#9C27B0" to stringResource(R.string.color_purple),
        "#F44336" to stringResource(R.string.color_red),
        "#FFC107" to stringResource(R.string.color_yellow)
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = {
            Text(
                stringResource(R.string.create_channel_title),
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                OutlinedTextField(
                    value = channelName,
                    onValueChange = {
                        channelName = it
                        isError = false
                    },
                    label = { Text(stringResource(R.string.channel_name)) },
                    placeholder = { Text(stringResource(R.string.channel_name_placeholder)) },
                    singleLine = true,
                    isError = isError,
                    supportingText = if (isError) {
                        { Text(stringResource(R.string.name_min_length)) }
                    } else null,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = channelDescription,
                    onValueChange = { channelDescription = it },
                    label = { Text(stringResource(R.string.channel_description)) },
                    placeholder = { Text(stringResource(R.string.channel_description_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Passwort-Feld (optional)
                OutlinedTextField(
                    value = channelPassword,
                    onValueChange = { channelPassword = it },
                    label = { Text(stringResource(R.string.channel_password_optional)) },
                    placeholder = { Text(stringResource(R.string.channel_password_placeholder)) },
                    singleLine = true,
                    leadingIcon = {
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (showPassword) stringResource(R.string.hide_password) else stringResource(R.string.show_password),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    },
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    stringResource(R.string.color),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Farbauswahl
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    colors.forEach { (colorHex, _) ->
                        val isSelected = selectedColor == colorHex
                        val color = Color(android.graphics.Color.parseColor(colorHex))
                        FilterChip(
                            selected = isSelected,
                            onClick = { selectedColor = colorHex },
                            label = { Text("") },
                            leadingIcon = {
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .clip(CircleShape)
                                        .background(color)
                                )
                            },
                            modifier = Modifier.size(40.dp),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = color.copy(alpha = 0.2f)
                            )
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (channelName.length >= 2) {
                        onCreate(channelName, channelDescription, selectedColor, channelPassword)
                    } else {
                        isError = true
                    }
                },
                enabled = channelName.length >= 2,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(stringResource(R.string.create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
