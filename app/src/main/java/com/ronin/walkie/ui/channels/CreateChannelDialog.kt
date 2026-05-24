package com.ronin.walkie.ui.channels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun CreateChannelDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, description: String, color: String) -> Unit
) {
    var channelName by remember { mutableStateOf("") }
    var channelDescription by remember { mutableStateOf("") }
    var selectedColor by remember { mutableStateOf("#4CAF50") }
    var isError by remember { mutableStateOf(false) }

    val colors = listOf(
        "#4CAF50" to "Grün",
        "#2196F3" to "Blau",
        "#FF5722" to "Orange",
        "#9C27B0" to "Lila",
        "#F44336" to "Rot",
        "#FFC107" to "Gelb"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = {
            Text(
                "Channel erstellen",
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
                    label = { Text("Channel-Name") },
                    placeholder = { Text("z.B. Allgemein") },
                    singleLine = true,
                    isError = isError,
                    supportingText = if (isError) {
                        { Text("Name muss mindestens 2 Zeichen haben") }
                    } else null,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = channelDescription,
                    onValueChange = { channelDescription = it },
                    label = { Text("Beschreibung (optional)") },
                    placeholder = { Text("Wofür ist dieser Channel?") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    "Farbe",
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
                        onCreate(channelName, channelDescription, selectedColor)
                    } else {
                        isError = true
                    }
                },
                enabled = channelName.length >= 2,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Erstellen")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Abbrechen")
            }
        }
    )
}
